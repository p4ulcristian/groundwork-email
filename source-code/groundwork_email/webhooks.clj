(ns groundwork-email.webhooks
  "The two routes the provider posts to (Postmark's webhooks), as reitit
   data:

     POST <delivery-path>?token=…   a bounce or a spam complaint on an email the app sent
     POST <inbound-path>?token=…    an email sent to the app

   The token, a secret of groundwork-settings's, is compared in constant time
   (settings/equal?: both sides hashed first); none
   or another is 401, written to the log at :warn, and nothing is read. A
   body that isn't a JSON object is 400. Otherwise the post is answered
   200, whatever the app's function does: one that throws is written to
   the log at :error and the event is not posted again, so a mistake in
   the app can't turn the provider's retries into a storm. The provider
   may post an event twice: the app's function must be safe to run twice
   (by the event's :message-id).

   A delivery event, handed to the function of its email's tag:

     {:kind        :bounce or :spam-complaint
      :tag         :orders/confirmation     ; the settings' tag the email was sent with; nil for another
      :message-id  \"…\"                       ; the provider's
      :email-key   \"order-confirmation/…\"   ; groundwork-email's key of the email, nil when groundwork-email didn't send it
      :address     \"anna@shop.example\"      ; the address that bounced or complained
      :bounce-type \"HardBounce\"             ; Postmark's Type, nil for a complaint
      :inactive?   true                     ; the provider sends no more to the address
      :metadata    {\"order-id\" \"7\"}}        ; as the email was queued with

   Any other record (a delivery, an open, a click) is ignored. An inbound
   email, every one handed to the app's one inbound function, which routes
   it by its :mailbox:

     {:message-id  \"…\"
      :mailbox     \"quotes\"                 ; before @ of the recipient on :inbound-domain, lower case;
                                            ; else Postmark's MailboxHash (inbound+quotes@…); else nil
      :from        {:address \"bob@example.com\" :name \"Bob\"}
      :to          [\"quotes@in.shop.example\"]
      :subject     \"…\" :text \"…\" :html \"…\" :stripped-reply \"…\"   ; each nil when missing
      :attachments [{:name \"door.jpg\" :content-type \"image/jpeg\" :bytes bytes}]}

   A delivery event of a tag with no function is written to the log at
   :info. Each function runs in one transaction the
   app's :run-in-transaction makes (the app says as whom: groundwork's runs
   it as groundwork-access's system actor :email, which the gates allow what the
   app's sentences give it), as (f event {:transaction transaction :log
   log}); the email's key is looked up in that transaction first."
  (:require
   [clojure.string :as str]
   [groundwork-settings.api :as settings]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (java.net URLDecoder)
   (java.nio.charset StandardCharsets)
   (java.util Base64)))

(defn- read-token [{:keys [query-string]}]
  (some-> (when query-string (re-find #"(?:^|&)token=([^&]*)" query-string))
          second
          (URLDecoder/decode StandardCharsets/UTF_8)))

(defn has-token?
  "Whether the request's ?token= is the token (a secret), compared in
   constant time by groundwork-settings."
  [request token]
  (settings/equal? token (read-token request)))

(defn- read-body [shaper {:keys [body]}]
  (try
    (let [data (shaped-json/turn-json-text-into-data shaper (if (string? body) body (slurp body :encoding "UTF-8")))]
      (when (map? data) data))
    (catch Exception _ nil)))

(defn- turn-text-into-blank-nil [text]
  (when (and (string? text) (not (str/blank? text))) text))

(defn- turn-metadata-into-text-map
  "The JSON's metadata, its names read as keywords by the shaper, back as text."
  [metadata]
  (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) (str k)) (str v)])) metadata))

(defn- find-tag [tags tag-text]
  (some #(when (= tag-text (subs (str %) 1)) %) tags))

(defn normalize-delivery-event
  "A Postmark Bounce or SpamComplaint post as a delivery event (without
   :email-key), or nil for any other record."
  [tags payload]
  (when-let [kind ({"Bounce" :bounce "SpamComplaint" :spam-complaint} (:RecordType payload))]
    {:kind        kind
     :tag         (find-tag tags (:Tag payload))
     :message-id  (turn-text-into-blank-nil (:MessageID payload))
     :address     (turn-text-into-blank-nil (:Email payload))
     :bounce-type (when (= kind :bounce) (turn-text-into-blank-nil (:Type payload)))
     :inactive?   (true? (:Inactive payload))
     :metadata    (turn-metadata-into-text-map (:Metadata payload))}))

(defn- find-local-part-on-domain [domain address]
  (when (string? address)
    (let [address (str/lower-case (str/trim address))
          [local at-domain] (str/split address #"@" 2)]
      (when (and (seq local) (= (str/lower-case domain) at-domain))
        local))))

(defn- decode-base64 [text]
  (try (.decode (Base64/getMimeDecoder) ^String text)
       (catch IllegalArgumentException _ nil)))

(defn normalize-inbound-email
  "A Postmark inbound post as an inbound email."
  [inbound-domain payload]
  (let [recipients (concat [(:OriginalRecipient payload)] (map :Email (:ToFull payload)) (map :Email (:CcFull payload)))]
    {:message-id     (turn-text-into-blank-nil (:MessageID payload))
     :mailbox        (or (some #(find-local-part-on-domain inbound-domain %) recipients)
                         (some-> (turn-text-into-blank-nil (:MailboxHash payload)) str/lower-case))
     :from           {:address (some-> (or (get-in payload [:FromFull :Email]) (:From payload)) str/trim str/lower-case)
                      :name    (turn-text-into-blank-nil (get-in payload [:FromFull :Name]))}
     :to             (vec (keep #(some-> % turn-text-into-blank-nil str/lower-case) (map :Email (:ToFull payload))))
     :subject        (turn-text-into-blank-nil (:Subject payload))
     :text           (turn-text-into-blank-nil (:TextBody payload))
     :html           (turn-text-into-blank-nil (:HtmlBody payload))
     :stripped-reply (turn-text-into-blank-nil (:StrippedTextReply payload))
     :attachments    (vec (keep (fn [{:keys [Name ContentType Content]}]
                                  (when-let [bs (and (string? Content) (decode-base64 Content))]
                                    {:name Name :content-type ContentType :bytes bs}))
                                (:Attachments payload)))}))

(def ^:private ok
  {:status 200 :headers {"content-type" "text/plain" "cache-control" "no-store"} :body "ok"})

(def ^:private not-allowed
  {:status 401 :headers {"content-type" "text/plain" "cache-control" "no-store"} :body "This address needs its token."})

(def ^:private bad-body
  {:status 400 :headers {"content-type" "text/plain" "cache-control" "no-store"} :body "Send a JSON object."})

(defn- run-function!
  "Runs (f (add-to-event transaction event) context) in one transaction
   the app's :run-in-transaction makes; :handled, or :failed (written to
   the log)."
  [{:keys [run-in-transaction log]} f add-to-event event webhook what]
  (try
    (run-in-transaction (fn [transaction] (f (add-to-event transaction event) {:transaction transaction :log log})))
    :handled
    (catch Exception e
      (log :error :groundwork-email/webhook-function-failed (assoc what :groundwork-email/webhook webhook :error e))
      :failed)))

(defn- handle-post
  "The post's checks, then (answer-payload payload) → outcome; counted."
  [{:keys [shaper log token count-event!]} webhook request answer-payload]
  (let [outcome (if-not (has-token? request token)
                  (do (log :warn :groundwork-email/webhook-refused {:groundwork-email/webhook webhook})
                      :refused)
                  (if-let [payload (read-body shaper request)]
                    (answer-payload payload)
                    :bad-body))]
    (count-event! webhook outcome)
    (case outcome
      :refused  not-allowed
      :bad-body bad-body
      ok)))

(defn- answer-delivery-event [{:keys [tags on-delivery-event log find-email-key] :as webhooks} payload]
  (if-let [event (normalize-delivery-event tags payload)]
    (let [what  {:groundwork-email/kind (:kind event) :groundwork-email/tag (:tag event) :groundwork-email/message-id (:message-id event)}]
      (if-let [f (get on-delivery-event (:tag event))]
        (run-function! webhooks f #(assoc %2 :email-key (find-email-key %1 (:message-id %2))) event :delivery what)
        (do (log :info :groundwork-email/no-function what)
            :no-function)))
    :ignored))

(defn- answer-inbound-email [{:keys [inbound-domain on-inbound-email] :as webhooks} payload]
  (let [email (normalize-inbound-email inbound-domain payload)
        what  {:groundwork-email/mailbox (:mailbox email) :groundwork-email/message-id (:message-id email)}]
    (run-function! webhooks on-inbound-email (fn [_ email] email) email :inbound what)))

(defn make-webhook-routes
  "The two routes as reitit data (see the namespace)."
  [{:keys [delivery-path inbound-path] :as webhooks}]
  [[delivery-path {:post {:handler #(handle-post webhooks :delivery % (partial answer-delivery-event webhooks))}}]
   [inbound-path {:post {:handler #(handle-post webhooks :inbound % (partial answer-inbound-email webhooks))}}]])

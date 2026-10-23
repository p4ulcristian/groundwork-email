(ns postbox.api
  "Email for a Clojure app on Postgres. postbox's one public namespace.

     (def postbox (postbox/make-postbox {:provider       {:name :postmark :server-token token
                                                         :api-url \"https://api.postmarkapp.com\"
                                                         :connect-timeout-ms 10000 :request-timeout-ms 30000}
                                         :tags           #{:orders/confirmation :orders/shop-notification}
                                         :background-job {:timeout-ms 60000 :attempts 8}}
                                        {:log log :meter meter :wire wire}))

     ;; backroom runs the sending: its kind among the app's
     (backroom/start-workers! pool (assoc backroom-settings :kinds [(postbox/make-background-job-kind postbox) …] …))

     ;; in the transaction that marks the order paid: the email exists only if that commits
     (postbox/queue-email! postbox transaction
                           {:key         (str \"order-confirmation/\" order-id)
                            :tag         :orders/confirmation
                            :to          [\"Anna <anna@shop.example>\"]
                            :from        \"Shop <orders@shop.example>\"
                            :reply-to    nil
                            :subject     \"Your order 7\"
                            :html        html            ; the app renders
                            :text        text
                            :metadata    {\"order-id\" (str order-id)}
                            :attachments []
                            :stream      \"outbound\"})
     ;=> :queued   (:queued-before when its key was queued before: nothing queued)

   An email is a row of postbox.emails (emails-table, a map for cellar):
   its key, tag, state (queued, sent), the provider's message id, when it
   was queued and sent; never its addresses or bodies. Its content waits in
   its background job (backroom.background_jobs), queued in the same
   transaction; the job, run by backroom, holds the row (FOR UPDATE),
   sends it unless it is sent already, and marks it sent with the
   provider's message id, in its one transaction. A failure runs again
   later (backroom's retries); an email the provider refuses for itself
   (Postmark: an invalid email, an inactive recipient) gives up at once.

   The provider (the settings' :provider): :postmark, its HTTP API; :log,
   the email written to the app's log, answered as sent.

   postbox writes to the app's log only, :log, and counts on its meter,
   :meter (postbox.metrics)."
  (:require
   [backroom.api :as backroom]
   [cellar.api :as cellar]
   [logbook.api :as logbook]
   [postbox.metrics :as metrics]
   [postbox.providers :as providers]
   [postbox.settings :as settings]
   [postbox.webhooks :as webhooks])
  (:import
   (java.util Base64)))

(defn- make-query [file-name]
  (cellar/make-query-from-sql-file (str "postbox/" file-name)))

(def ^:private insert-email (make-query "insert_email.sql"))
(def ^:private lock-email (make-query "lock_email.sql"))
(def ^:private update-email-to-sent (make-query "update_email_to_sent.sql"))
(def ^:private find-email-row (make-query "find_email.sql"))
(def ^:private find-email-of-message (make-query "find_email_of_message.sql"))

;; -----------------------------------------------------------------------------
;; Settings and the table

(def settings-shape
  "make-postbox's settings, every key required. See postbox.settings/settings-shape."
  settings/settings-shape)

(def email-shape
  "An email as queue-email! takes it, every key required. See
   postbox.settings/email-shape."
  settings/email-shape)

(def webhook-settings-shape
  "make-webhook-routes' settings, every key required. See
   postbox.settings/webhook-settings-shape."
  settings/webhook-settings-shape)

(def emails-table
  "Every email queued: postbox.emails, a map for cellar, to pass with the
   app's tables. Its key is the email's own: a key is queued once, ever."
  {:table     :postbox/emails
   :columns   [[:email/key        :string                   {:primary-key? true}]
               [:email/tag        :string]
               [:email/state      [:enum "queued" "sent"]   {:default "queued"}]
               [:email/message-id :string                   {:optional? true}]
               [:email/queued-at  :time/instant             {:default :now}]
               [:email/sent-at    :time/instant             {:optional? true}]]
   :indexes   [[:email/message-id]]
   :gates     nil
   :on-change nil})

(def ^:private background-job-kind-name :postbox/send-email)

(def ^:private params-shape
  "The email as its background job keeps it: the tag as text, the
   metadata as name-value pairs, the attachments' bytes as base64."
  [:map {:closed true}
   [:email/key :string]
   [:email/tag :string]
   [:email/to [:vector :string]]
   [:email/from :string]
   [:email/reply-to [:maybe :string]]
   [:email/subject :string]
   [:email/html [:maybe :string]]
   [:email/text [:maybe :string]]
   [:email/metadata [:vector [:map {:closed true} [:name :string] [:value :string]]]]
   [:email/attachments [:vector [:map {:closed true} [:name :string] [:content-type :string] [:content-base64 :string]]]]
   [:email/stream :string]])

;; -----------------------------------------------------------------------------
;; The postbox

(defn make-postbox
  "A postbox from its settings and options ({:log :meter :wire}, the
   app's), every key required, checked first (:postbox/bad-settings,
   :postbox/bad-options); declares postbox's metrics on the meter, the
   settings' :tags as their set. Returns the postbox, for every other
   function."
  [postbox-settings options]
  (settings/check-data-for-shape "The settings" :postbox/bad-settings settings/settings-shape postbox-settings)
  (settings/check-data-for-shape "The options" :postbox/bad-options settings/options-shape options)
  (let [{:keys [log meter wire]} options]
    ;; a log logbook didn't make is refused now, not at the first send
    (logbook/with-context log {})
    (metrics/declare-metrics! meter (:tags postbox-settings))
    {:settings      postbox-settings
     :send-function (providers/make-send-function (:provider postbox-settings))
     :log           log
     :meter         meter
     :wire          wire}))

;; -----------------------------------------------------------------------------
;; Queueing and sending

(defn- encode-base64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn- turn-email-into-params [{:keys [key tag to from reply-to subject html text metadata attachments stream]}]
  {:email/key         key
   :email/tag         (subs (str tag) 1)
   :email/to          to
   :email/from        from
   :email/reply-to    reply-to
   :email/subject     subject
   :email/html        html
   :email/text        text
   :email/metadata    (mapv (fn [[n v]] {:name n :value v}) (sort metadata))
   :email/attachments (mapv (fn [{:keys [name content-type bytes]}]
                              {:name name :content-type content-type :content-base64 (encode-base64 bytes)})
                            attachments)
   :email/stream      stream})

(defn- find-tag [tags tag-text]
  (some #(when (= tag-text (subs (str %) 1)) %) tags))

(defn- turn-params-into-email [tags params]
  (let [{:email/keys [key tag to from reply-to subject html text metadata attachments stream]} params]
    {:key key :tag (find-tag tags tag) :to to :from from :reply-to reply-to :subject subject :html html :text text
     :metadata (into {} (map (juxt :name :value)) metadata) :attachments attachments :stream stream}))

(defn- send-email-of-job!
  "The background job's run: holds the email's row, sends it unless it is
   sent, marks it sent."
  [{:keys [settings send-function meter wire]} {:keys [transaction log]} params]
  (let [{:keys [key tag] :as email} (turn-params-into-email (:tags settings) params)
        row (cellar/find-one-row transaction lock-email {:email/key key})]
    (cond
      (nil? row)
      (throw (backroom/make-give-up-exception "The email's row is gone" {:email/key key}))

      (= "sent" (:email/state row))
      (log :info :postbox/sent-before {:email/key key :email/message-id (:email/message-id row)})

      :else
      (let [{:keys [message-id]} (try (send-function email {:log log :wire wire})
                                      (catch Exception e
                                        (metrics/count-failed! meter tag)
                                        (throw e)))]
        (cellar/run-statement! transaction update-email-to-sent {:email/key key :email/message-id message-id})
        (metrics/count-sent! meter tag)))))

(defn make-background-job-kind
  "The background job kind that sends an email, :postbox/send-email, its
   time limit and attempts the settings' :background-job: for backroom's
   start-workers! :kinds, with the app's own."
  [{:keys [settings] :as postbox}]
  {:name         background-job-kind-name
   :params-shape params-shape
   :run          (fn [context params] (send-email-of-job! postbox context params))
   :timeout-ms   (get-in settings [:background-job :timeout-ms])
   :attempts     (get-in settings [:background-job :attempts])})

(defn- check-email [{:keys [settings]} email]
  (settings/check-data-for-shape "The email's keys" :postbox/bad-email settings/email-shape email)
  (when-not (contains? (:tags settings) (:tag email))
    (throw (ex-info (str "The email's tag " (:tag email) " isn't one of the settings' :tags")
                    {:type :postbox/bad-email :errors {:tag ["should be one of the settings' :tags"]}}))))

(defn queue-email!
  "Queues the email (email-shape, every key required, checked first,
   :postbox/bad-email) in the caller's transaction: it is sent only if
   that commits, by a background job of make-background-job-kind's.
   Returns :queued, or :queued-before when an email of its key was queued
   before, ever: then nothing is queued. Counted by its tag."
  [{:keys [wire meter] :as postbox} transaction email]
  (check-email postbox email)
  (let [{:keys [key tag]} email
        {:keys [added]} (cellar/find-one-row transaction insert-email {:email/key key :email/tag (subs (str tag) 1)})]
    (if-not added
      :queued-before
      (do (backroom/add-background-job-to-queue! transaction (make-background-job-kind postbox) (turn-email-into-params email)
                                                 {:wire wire :meter meter :run-in-ms 0 :key nil})
          (metrics/count-queued! meter tag)
          :queued))))

(defn find-email
  "The email's row, #:email{:key :tag :state :message-id :queued-at
   :sent-at}, or nil."
  [pool-or-transaction email-key]
  (cellar/find-one-row pool-or-transaction find-email-row {:email/key email-key}))

;; -----------------------------------------------------------------------------
;; The webhooks

(defn make-webhook-routes
  "The provider's two webhooks as reitit data, at the settings' paths
   (postbox.webhooks says what they answer and hand on). Settings
   (webhook-settings-shape: :delivery-path :inbound-path :token, a secret
   of safe's of at least 16 characters, :inbound-domain) and options (:pool, :on-delivery-event {tag f},
   :on-inbound-email {mailbox f}; f is (fn [event {:keys [transaction
   log]}]), safe to run twice), every key required, checked first; a tag
   that isn't one of the settings' :tags is refused."
  [{:keys [settings log meter wire]} webhook-settings options]
  (settings/check-data-for-shape "The settings" :postbox/bad-settings settings/webhook-settings-shape webhook-settings)
  (settings/check-token-length (:token webhook-settings))
  (settings/check-data-for-shape "The options" :postbox/bad-options settings/webhook-options-shape options)
  (let [{:keys [pool on-delivery-event on-inbound-email]} options
        strangers (remove (:tags settings) (keys on-delivery-event))]
    (when (seq strangers)
      (throw (ex-info (str "The delivery functions name tags the settings' :tags don't have: " (pr-str (vec strangers)))
                      {:type :postbox/bad-options :errors {:on-delivery-event ["should name only the settings' :tags"]}})))
    (webhooks/make-webhook-routes
     (assoc webhook-settings
            :tags              (:tags settings)
            :wire              wire
            :log               log
            :pool              pool
            :on-delivery-event on-delivery-event
            :on-inbound-email  on-inbound-email
            :find-email-key    #(when % (:email/key (cellar/find-one-row pool find-email-of-message {:email/message-id %})))
            :count-event!      #(metrics/count-webhook-event! meter %1 %2)))))

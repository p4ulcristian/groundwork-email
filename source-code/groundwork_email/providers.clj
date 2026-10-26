(ns groundwork-email.providers
  "Who sends: one function per provider, by the provider settings' :name.
   Each takes the provider's settings, the email as its background job
   keeps it ({:key :tag :to :from :reply-to :subject :html :text :metadata
   :attachments [{:name :content-type :content-base64}] :stream
   :unsubscribe-url}) and {:log :shaper}, and returns what became of it:
   {:message-id \"…\" :gave-up-reason nil :provider-message nil}, sent, or
   {:message-id nil :gave-up-reason \"Inactive recipient (406)\"
   :provider-message \"…\"}, the email itself refused, which no later
   attempt changes (the reason is kept on the email's row; the provider's
   own words, which may name the address, only go to the log). A failure a
   later attempt may get past throws. A new provider is one more entry in send-function-makers and one
   more branch of settings/provider-shape."
  (:require
   [clojure.string :as str]
   [groundwork-settings.api :as settings]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.time Duration)))

(defn- turn-tag-into-text [tag]
  (subs (str tag) 1))

;; -----------------------------------------------------------------------------
;; Postmark

(def ^:private refusals-of-the-email
  "Postmark's error codes that say the email itself is refused, which no
   later attempt changes, each by Postmark's name for it: 300, an invalid
   email (a bad address, a missing body); 406, an inactive recipient
   (bounced or marked spam before). The name, not Postmark's message, is
   the email's reason: the message names the address."
  {300 "Invalid email request"
   406 "Inactive recipient"})

(def ^:private one-click-unsubscribe
  "RFC 8058's second header: the unsubscribe address takes a POST of
   List-Unsubscribe=One-Click."
  "List-Unsubscribe=One-Click")

(defn- make-postmark-body [{:keys [to from reply-to subject html text tag metadata attachments stream unsubscribe-url]}]
  (cond-> {"From"          from
           "To"            (str/join ", " to)
           "Subject"       subject
           "Tag"           (turn-tag-into-text tag)
           "Metadata"      metadata
           "MessageStream" stream}
    html               (assoc "HtmlBody" html)
    text               (assoc "TextBody" text)
    reply-to           (assoc "ReplyTo" reply-to)
    (seq attachments)  (assoc "Attachments" (mapv (fn [{:keys [name content-type content-base64]}]
                                                    {"Name" name "ContentType" content-type "Content" content-base64})
                                                  attachments))
    unsubscribe-url    (assoc "Headers" [{"Name" "List-Unsubscribe" "Value" (str "<" unsubscribe-url ">")}
                                         {"Name" "List-Unsubscribe-Post" "Value" one-click-unsubscribe}])))

(defn- post-to-postmark
  "Postmark's answer: [status data-or-nil]."
  [^HttpClient client {:keys [server-token api-url request-timeout-ms]} shaper body]
  (let [request (-> (HttpRequest/newBuilder (URI. (str api-url "/email")))
                    (.timeout (Duration/ofMillis request-timeout-ms))
                    (.header "Accept" "application/json")
                    (.header "Content-Type" "application/json")
                    (.header "X-Postmark-Server-Token" (settings/reveal server-token))
                    (.POST (HttpRequest$BodyPublishers/ofString (shaped-json/turn-data-into-json-text shaper body)))
                    (.build))
        answer  (.send client request (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode answer)
     (try (shaped-json/turn-json-text-into-data shaper (.body answer)) (catch Exception _ nil))]))

(defn- send-by-postmark [client provider email {:keys [shaper]}]
  (let [[status {:keys [ErrorCode MessageID Message]}] (post-to-postmark client provider shaper (make-postmark-body email))]
    (cond
      (and (= 200 status) (= 0 ErrorCode) (string? MessageID))
      {:message-id MessageID :gave-up-reason nil :provider-message nil}

      (and (= 422 status) (contains? refusals-of-the-email ErrorCode))
      {:message-id       nil
       :gave-up-reason   (str (get refusals-of-the-email ErrorCode) " (" ErrorCode ")")
       :provider-message Message}

      :else
      (throw (ex-info (str "Postmark didn't send the email: " status " " ErrorCode " " Message)
                      {:type :groundwork-email/not-sent :groundwork-email/status status :groundwork-email/error-code ErrorCode})))))

;; -----------------------------------------------------------------------------
;; The log

(defn- send-to-log [{:keys [level]} {:keys [attachments unsubscribe-url] :as email} {:keys [log]}]
  (let [message-id (str "logged-" (random-uuid))]
    (log level :groundwork-email/logged-not-sent
         (-> email
             (dissoc :attachments :unsubscribe-url)
             (assoc :message-id message-id
                    :unsubscribe-url? (some? unsubscribe-url)
                    :attachments (mapv (fn [{:keys [name content-type content-base64]}]
                                         {:name name :content-type content-type :base64-length (count content-base64)})
                                       attachments))))
    {:message-id message-id :gave-up-reason nil :provider-message nil}))

(defn- make-postmark-send-function
  "One http client for every send, its connect timeout the settings'."
  [{:keys [connect-timeout-ms] :as provider}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))]
    (fn [email options] (send-by-postmark client provider email options))))

(def ^:private send-function-makers
  {:postmark make-postmark-send-function
   :log      (fn [provider] (fn [email options] (send-to-log provider email options)))})

(defn make-send-function
  "(fn [email {:keys [log shaper]}]) → {:message-id :gave-up-reason :provider-message}, sending
   by the provider the settings name."
  [provider]
  ((get send-function-makers (:name provider)) provider))

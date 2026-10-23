(ns postbox.providers
  "Who sends: one function per provider, by the provider settings' :name.
   Each takes the provider's settings, the email as its background job
   keeps it ({:key :tag :to :from :reply-to :subject :html :text :metadata
   :attachments [{:name :content-type :content-base64}] :stream}) and
   {:log :wire}, and returns {:message-id …}; a failure a later attempt
   may get past throws, one no attempt can (the email itself is refused)
   throws backroom's give-up exception. A new provider is one more entry
   in send-function-makers and one more branch of settings/provider-shape."
  (:require
   [backroom.api :as backroom]
   [clojure.string :as str]
   [safe.api :as safe]
   [wire.api :as wire])
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
   later attempt changes: 300, an invalid email (a bad address, a missing
   body); 406, an inactive recipient (bounced or marked spam before)."
  #{300 406})

(defn- make-postmark-body [{:keys [to from reply-to subject html text tag metadata attachments stream]}]
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
                                                  attachments))))

(defn- post-to-postmark
  "Postmark's answer: [status data-or-nil]."
  [^HttpClient client {:keys [server-token api-url request-timeout-ms]} wire body]
  (let [request (-> (HttpRequest/newBuilder (URI. (str api-url "/email")))
                    (.timeout (Duration/ofMillis request-timeout-ms))
                    (.header "Accept" "application/json")
                    (.header "Content-Type" "application/json")
                    (.header "X-Postmark-Server-Token" (safe/reveal server-token))
                    (.POST (HttpRequest$BodyPublishers/ofString (wire/turn-data-into-json-text wire body)))
                    (.build))
        answer  (.send client request (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode answer)
     (try (wire/turn-json-text-into-data wire (.body answer)) (catch Exception _ nil))]))

(defn- send-by-postmark [client provider email {:keys [wire]}]
  (let [[status {:keys [ErrorCode MessageID Message]}] (post-to-postmark client provider wire (make-postmark-body email))]
    (cond
      (and (= 200 status) (= 0 ErrorCode) (string? MessageID))
      {:message-id MessageID}

      (and (= 422 status) (contains? refusals-of-the-email ErrorCode))
      (throw (backroom/make-give-up-exception (str "Postmark refused the email: " ErrorCode " " Message)
                                              {:postbox/status status :postbox/error-code ErrorCode}))

      :else
      (throw (ex-info (str "Postmark didn't send the email: " status " " ErrorCode " " Message)
                      {:type :postbox/not-sent :postbox/status status :postbox/error-code ErrorCode})))))

;; -----------------------------------------------------------------------------
;; The log

(defn- send-to-log [{:keys [level]} {:keys [attachments] :as email} {:keys [log]}]
  (let [message-id (str "logged-" (random-uuid))]
    (log level :postbox/logged-not-sent
         (-> email
             (dissoc :attachments)
             (assoc :message-id message-id
                    :attachments (mapv (fn [{:keys [name content-type content-base64]}]
                                         {:name name :content-type content-type :base64-length (count content-base64)})
                                       attachments))))
    {:message-id message-id}))

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
  "(fn [email {:keys [log wire]}]) → {:message-id …}, sending by the
   provider the settings name."
  [provider]
  ((get send-function-makers (:name provider)) provider))

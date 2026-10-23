(ns groundwork-email.api
  "Email for a Clojure app on Postgres. groundwork-email's one public namespace.

     (def mailer (email/make-mailer {:provider       {:name :postmark :server-token token
                                                         :api-url \"https://api.postmarkapp.com\"
                                                         :connect-timeout-ms 10000 :request-timeout-ms 30000}
                                         :tags           #{:orders/confirmation :orders/shop-notification}
                                         :background-job {:timeout-ms 60000 :attempts 8}}
                                        {:log log :meter meter :shaper groundwork-shaped-json}))

     ;; groundwork-background-jobs runs the sending: its kind among the app's
     (background-jobs/start-workers! pool (assoc background-jobs-settings :kinds [(email/make-background-job-kind mailer) …] …))

     ;; in the transaction that marks the order paid: the email exists only if that commits
     (email/queue-email! mailer transaction
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

   An email is a row of groundwork_email.emails (emails-table, a map for groundwork-postgres):
   its key, tag, state (queued, sent), the provider's message id, when it
   was queued and sent; never its addresses or bodies. Its content waits in
   its background job (groundwork_background_jobs.background_jobs), queued in the same
   transaction; the job, run by groundwork-background-jobs, holds the row (FOR UPDATE),
   sends it unless it is sent already, and marks it sent with the
   provider's message id, in its one transaction. A failure runs again
   later (groundwork-background-jobs's retries); an email the provider refuses for itself
   (Postmark: an invalid email, an inactive recipient) gives up at once.

   The provider (the settings' :provider): :postmark, its HTTP API; :log,
   the email written to the app's log, answered as sent.

   groundwork-email writes to the app's log only, :log, and counts on its meter,
   :meter (groundwork_email.metrics)."
  (:require
   [groundwork-background-jobs.api :as background-jobs]
   [groundwork-postgres.api :as postgres]
   [groundwork-logs.api :as logs]
   [groundwork-email.metrics :as metrics]
   [groundwork-email.providers :as providers]
   [groundwork-email.settings :as settings]
   [groundwork-email.webhooks :as webhooks])
  (:import
   (java.util Base64)))

(defn- make-query [file-name]
  (postgres/make-query-from-sql-file (str "groundwork_email/" file-name)))

(def ^:private insert-email (make-query "insert_email.sql"))
(def ^:private lock-email (make-query "lock_email.sql"))
(def ^:private update-email-to-sent (make-query "update_email_to_sent.sql"))
(def ^:private find-email-row (make-query "find_email.sql"))
(def ^:private find-email-of-message (make-query "find_email_of_message.sql"))

;; -----------------------------------------------------------------------------
;; Settings and the table

(def settings-shape
  "make-mailer's settings, every key required. See groundwork-email.settings/settings-shape."
  settings/settings-shape)

(def email-shape
  "An email as queue-email! takes it, every key required. See
   groundwork-email.settings/email-shape."
  settings/email-shape)

(def webhook-settings-shape
  "make-webhook-routes' settings, every key required. See
   groundwork-email.settings/webhook-settings-shape."
  settings/webhook-settings-shape)

(def emails-table
  "Every email queued: groundwork_email.emails, a map for groundwork-postgres, to pass with the
   app's tables. Its key is the email's own: a key is queued once, ever."
  {:table     :groundwork-email/emails
   :columns   [[:email/key        :string                   {:primary-key? true}]
               [:email/tag        :string]
               [:email/state      [:enum "queued" "sent"]   {:default "queued"}]
               [:email/message-id :string                   {:optional? true}]
               [:email/queued-at  :time/instant             {:default :now}]
               [:email/sent-at    :time/instant             {:optional? true}]]
   :indexes   [{:columns [[:email/message-id :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def ^:private background-job-kind-name :groundwork-email/send-email)

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
;; The mailer

(defn make-mailer
  "A mailer from its settings and options ({:log :meter :shaper}, the
   app's, and :system-actor, whom the sending job runs as), every key
   required, checked first (:groundwork-email/bad-settings,
   :groundwork-email/bad-options); declares groundwork-email's metrics on the meter, the
   settings' :tags as their set. Returns the mailer, for every other
   function."
  [email-settings options]
  (settings/check-data-for-shape "The settings" :groundwork-email/bad-settings settings/settings-shape email-settings)
  (settings/check-data-for-shape "The options" :groundwork-email/bad-options settings/options-shape options)
  (let [{:keys [log meter shaper system-actor]} options]
    ;; a log groundwork-logs didn't make is refused now, not at the first send
    (logs/with-context log {})
    (metrics/declare-metrics! meter (:tags email-settings))
    {:settings      email-settings
     :send-function (providers/make-send-function (:provider email-settings))
     :log           log
     :meter         meter
     :shaper          shaper
     :system-actor  system-actor}))

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
  [{:keys [settings send-function meter shaper]} {:keys [transaction log]} params]
  (let [{:keys [key tag] :as email} (turn-params-into-email (:tags settings) params)
        row (postgres/find-one-row transaction lock-email {:email/key key})]
    (cond
      (nil? row)
      (throw (background-jobs/make-give-up-exception "The email's row is gone" {:email/key key}))

      (= "sent" (:email/state row))
      (log :info :groundwork-email/sent-before {:email/key key :email/message-id (:email/message-id row)})

      :else
      (let [{:keys [message-id]} (try (send-function email {:log log :shaper shaper})
                                      (catch Exception e
                                        (metrics/count-failed! meter tag)
                                        (throw e)))]
        (postgres/run-statement! transaction update-email-to-sent {:email/key key :email/message-id message-id})
        (metrics/count-sent! meter tag)))))

(defn make-background-job-kind
  "The background job kind that sends an email, :groundwork-email/send-email, its
   time limit and attempts the settings' :background-job, run as the
   options' :system-actor: for groundwork-background-jobs's start-workers! :kinds, with the
   app's own."
  [{:keys [settings system-actor] :as mailer}]
  {:name         background-job-kind-name
   :system-actor system-actor
   :params-shape params-shape
   :run          (fn [context params] (send-email-of-job! mailer context params))
   :timeout-ms   (get-in settings [:background-job :timeout-ms])
   :attempts     (get-in settings [:background-job :attempts])})

(defn- check-email [{:keys [settings]} email]
  (settings/check-data-for-shape "The email's keys" :groundwork-email/bad-email settings/email-shape email)
  (when-not (contains? (:tags settings) (:tag email))
    (throw (ex-info (str "The email's tag " (:tag email) " isn't one of the settings' :tags")
                    {:type :groundwork-email/bad-email :errors {:tag ["should be one of the settings' :tags"]}}))))

(defn queue-email!
  "Queues the email (email-shape, every key required, checked first,
   :groundwork-email/bad-email) in the caller's transaction: it is sent only if
   that commits, by a background job of make-background-job-kind's.
   Returns :queued, or :queued-before when an email of its key was queued
   before, ever: then nothing is queued. Counted by its tag."
  [{:keys [shaper meter] :as mailer} transaction email]
  (check-email mailer email)
  (let [{:keys [key tag]} email
        {:keys [added]} (postgres/find-one-row transaction insert-email {:email/key key :email/tag (subs (str tag) 1)})]
    (if-not added
      :queued-before
      (do (background-jobs/add-background-job-to-queue! transaction (make-background-job-kind mailer) (turn-email-into-params email)
                                                 {:shaper shaper :meter meter :run-in-ms 0 :key nil})
          (metrics/count-queued! meter tag)
          :queued))))

(defn find-email
  "The email's row, #:email{:key :tag :state :message-id :queued-at
   :sent-at}, or nil."
  [pool-or-transaction email-key]
  (postgres/find-one-row pool-or-transaction find-email-row {:email/key email-key}))

;; -----------------------------------------------------------------------------
;; The webhooks

(defn make-webhook-routes
  "The provider's two webhooks as reitit data, at the settings' paths
   (groundwork-email.webhooks says what they answer and hand on). Settings
   (webhook-settings-shape: :delivery-path :inbound-path :token, a secret
   of groundwork-settings's of at least 16 characters, :inbound-domain) and options
   (:run-in-transaction, (fn [f]) running (f transaction) in one
   transaction of the app's, as whom it chooses (groundwork's: groundwork-access's
   system actor :email); :on-delivery-event {tag f}; :on-inbound-email
   {mailbox f}; f is (fn [event {:keys [transaction log]}]), safe to run
   twice), every key required, checked first; a tag that isn't one of the
   settings' :tags is refused."
  [{:keys [settings log meter shaper]} webhook-settings options]
  (settings/check-data-for-shape "The settings" :groundwork-email/bad-settings settings/webhook-settings-shape webhook-settings)
  (settings/check-token-length (:token webhook-settings))
  (settings/check-data-for-shape "The options" :groundwork-email/bad-options settings/webhook-options-shape options)
  (let [{:keys [run-in-transaction on-delivery-event on-inbound-email]} options
        strangers (remove (:tags settings) (keys on-delivery-event))]
    (when (seq strangers)
      (throw (ex-info (str "The delivery functions name tags the settings' :tags don't have: " (pr-str (vec strangers)))
                      {:type :groundwork-email/bad-options :errors {:on-delivery-event ["should name only the settings' :tags"]}})))
    (webhooks/make-webhook-routes
     (assoc webhook-settings
            :tags              (:tags settings)
            :shaper              shaper
            :log               log
            :run-in-transaction run-in-transaction
            :on-delivery-event on-delivery-event
            :on-inbound-email  on-inbound-email
            :find-email-key    #(when %2 (:email/key (postgres/find-one-row %1 find-email-of-message {:email/message-id %2})))
            :count-event!      #(metrics/count-webhook-event! meter %1 %2)))))

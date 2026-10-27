(ns groundwork-email.api-test
  "The settings and the webhooks' token run everywhere. The rest runs
   against a real Postgres when EMAIL_TEST_PORT is set (on localhost,
   user from EMAIL_TEST_USER, no password, no TLS), with groundwork-background-jobs's
   workers sending, by the :log provider and by Postmark's API served by a
   fake Postmark in the test (an HTTP server answering as Postmark does);
   says it was skipped otherwise."
  (:require
   [groundwork-background-jobs.api :as background-jobs]
   [groundwork-postgres.api :as postgres]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]
   [groundwork-email.api :as email]
   [groundwork-settings.api :as settings]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.io ByteArrayInputStream)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)
   (java.util Base64)))

(def ^:private port (some-> (System/getenv "EMAIL_TEST_PORT") parse-long))

(def ^:private lines (atom []))

(def ^:private log
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 100
                                               :write-to     #(swap! lines conj (json/read-value %))})))

(defn- find-lines [event]
  (filterv #(= event (get % "event")) @lines))

(defn- make-meter []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 1000} {:log log}))

(def ^:private shaper
  (shaped-json/make-shaper-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                       :max-name-length 50000 :max-document-length nil :max-token-count nil
                       :max-writing-nesting-depth 1000}}))

(def ^:private tags #{:orders/confirmation :orders/shop-notification})

(defn- make-settings [provider]
  {:provider provider :tags tags :background-job {:timeout-ms 10000 :attempts 3}})

(def ^:private log-provider {:name :log :level :info})

(def ^:private webhook-settings
  {:delivery-path "/email/delivery" :inbound-path "/email/inbound"
   :token (settings/make-secret :groundwork-email/webhook-token "the-webhook-token-0123") :inbound-domain "in.shop.example"})

(defn- make-email [order-id]
  {:key         (str "order-confirmation/" order-id)
   :tag         :orders/confirmation
   :to          ["Anna <anna@shop.example>" "bob@shop.example"]
   :from        "Shop <orders@shop.example>"
   :reply-to    "help@shop.example"
   :subject     (str "Your order " order-id)
   :html        "<p>Thank you.</p>"
   :text        "Thank you."
   :metadata    {"order-id" (str order-id)}
   :attachments [{:name "invoice.pdf" :content-type "application/pdf" :bytes (.getBytes "%PDF-1.7")}]
   :stream      "outbound"
   :unsubscribe-url nil})

(def ^:private unsubscribe-url "https://shop.example/unsubscribe/7f3a?list=orders")

(def ^:private ignore-inbound (fn [_ _]))

(defn- read-counters [m metric-name]
  (->> (str/split-lines (metrics/write-metrics-text m))
       (filter #(str/starts-with? % metric-name))
       (map #(let [[series value] (str/split % #" ")] [series (parse-double value)]))
       (into {})))

(defn- post [routes path query body]
  (let [handler (some (fn [[p {{:keys [handler]} :post}]] (when (= p path) handler)) routes)]
    (handler {:request-method :post :uri path :query-string query
              :headers {"content-type" "application/json"}
              :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))})))

;; -----------------------------------------------------------------------------
;; Everywhere

(deftest settings-and-emails-are-every-key-written-out
  (is (= {:background-job ["missing required key"] :provider {:level ["missing required key"]}}
         (:errors (ex-data (try (email/make-mailer {:provider {:name :log} :tags tags}
                                                      {:log log :meter (make-meter) :shaper shaper :system-actor :email})
                                (catch Exception e e))))))
  (let [box (email/make-mailer (make-settings log-provider) {:log log :meter (make-meter) :shaper shaper :system-actor :email})]
    (is (= {:system-actor ["missing required key"]}
           (:errors (ex-data (try (email/make-mailer (make-settings log-provider) {:log log :meter (make-meter) :shaper shaper})
                                  (catch Exception e e)))))
        "the app names whom the sending runs as")
    (is (= :email (:system-actor (email/make-background-job-kind box))) "the kind runs as it")
    (is (= {:reply-to ["missing required key"]}
           (:errors (ex-data (try (email/queue-email! box nil (dissoc (make-email 7) :reply-to))
                                  (catch Exception e e))))))
    (is (= {:unsubscribe-url ["missing required key"]}
           (:errors (ex-data (try (email/queue-email! box nil (dissoc (make-email 7) :unsubscribe-url))
                                  (catch Exception e e)))))
        "an email says it has no unsubscribe address")
    (is (= {:unsubscribe-url ["should be an https:// address without spaces, commas, quotes or angle brackets, or nil"]
            :headers ["disallowed key"]}
           (:errors (ex-data (try (email/queue-email! box nil (assoc (make-email 7) :unsubscribe-url "http://shop.example/u"
                                                                       :headers {"X-Anything" "1"}))
                                  (catch Exception e e)))))
        "only https, and no other header can be set")
    (is (= {:unsubscribe-url ["should be an https:// address without spaces, commas, quotes or angle brackets, or nil"]}
           (:errors (ex-data (try (email/queue-email! box nil (assoc (make-email 7) :unsubscribe-url "https://shop.example/u>, <mailto:x@y"))
                                  (catch Exception e e)))))
        "an address that would break the header out of its angle brackets")
    (is (= ["should have :html or :text, or both"]
           (:errors (ex-data (try (email/queue-email! box nil (assoc (make-email 7) :html nil :text nil))
                                  (catch Exception e e)))))
        "an email has a body")
    (is (= {:tag ["should be one of the settings' :tags"]}
           (:errors (ex-data (try (email/queue-email! box nil (assoc (make-email 7) :tag :orders/unknown))
                                  (catch Exception e e))))))
    (is (= :groundwork-email/bad-options
           (:type (ex-data (try (email/make-webhook-routes box webhook-settings
                                                             {:run-in-transaction (fn [_]) :on-delivery-event {:orders/unknown (fn [_ _])}
                                                              :on-inbound-email ignore-inbound})
                                (catch Exception e e)))))
        "a delivery function for a tag the settings don't have")
    (is (= {:run-in-transaction ["missing required key"] :pool ["disallowed key"]}
           (:errors (ex-data (try (email/make-webhook-routes box webhook-settings {:pool :a-pool :on-delivery-event {} :on-inbound-email ignore-inbound})
                                  (catch Exception e e)))))
        "the app hands the transaction it runs them in, not a pool")
    (is (= {:on-inbound-email ["should be the one function every inbound email is handed to, (fn [email context])"]}
           (:errors (ex-data (try (email/make-webhook-routes box webhook-settings {:run-in-transaction (fn [_]) :on-delivery-event {}
                                                                                   :on-inbound-email {"quotes" ignore-inbound}})
                                  (catch Exception e e)))))
        "every inbound email goes to one function, not a map of mailboxes")))

(deftest the-tokens-are-secrets
  (let [refusal (fn [f] (try (f) :nothing (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :errors]))))
        box     (email/make-mailer (make-settings log-provider) {:log log :meter (make-meter) :shaper shaper :system-actor :email})
        options {:run-in-transaction (fn [_] (throw (ex-info "not reached" {}))) :on-delivery-event {} :on-inbound-email ignore-inbound}]
    (testing "Postmark's server token written out as text is refused, and the text is never said"
      (let [refused (refusal #(email/make-mailer (make-settings {:name :postmark :server-token "written-out-server-token"
                                                                    :api-url "https://api.postmarkapp.com"
                                                                    :connect-timeout-ms 2000 :request-timeout-ms 5000})
                                                    {:log log :meter (make-meter) :shaper shaper :system-actor :email}))]
        (is (= {:type :groundwork-email/bad-settings :errors {:provider {:server-token ["should be a #secret, read from the settings' secrets source"]}}}
               refused))
        (is (not (str/includes? (pr-str refused) "written-out-server-token")))))
    (testing "so is the webhooks' token"
      (is (= {:type :groundwork-email/bad-settings :errors {:token ["should be a #secret, read from the settings' secrets source"]}}
             (refusal #(email/make-webhook-routes box (assoc webhook-settings :token "the-webhook-token-0123") options)))))
    (testing "a webhooks' token shorter than 16 characters is refused, named, its text never said"
      (let [refused (refusal #(email/make-webhook-routes box (assoc webhook-settings :token (settings/make-secret :groundwork-email/webhook-token "short-token-15c")) options))]
        (is (= {:type :groundwork-email/bad-settings :errors {:token ["should be at least 16 characters"]}} refused))
        (is (not (str/includes? (pr-str refused) "short-token-15c")))))
    (testing "16 characters are enough"
      (is (vector? (email/make-webhook-routes box (assoc webhook-settings :token (settings/make-secret :groundwork-email/webhook-token "sixteen-chars-16")) options))))))

(deftest the-webhooks-refuse-without-their-token
  (let [m      (make-meter)
        box    (email/make-mailer (make-settings log-provider) {:log log :meter m :shaper shaper :system-actor :email})
        called (atom 0)
        routes (email/make-webhook-routes box webhook-settings
                                            {:run-in-transaction (fn [_] (throw (ex-info "not reached" {})))
                                             :on-delivery-event {:orders/confirmation (fn [_ _] (swap! called inc))}
                                             :on-inbound-email  (fn [_ _] (swap! called inc))})
        bounce "{\"RecordType\":\"Bounce\",\"Tag\":\"orders/confirmation\",\"MessageID\":\"m-1\"}"]
    (is (= 401 (:status (post routes "/email/delivery" nil bounce))))
    (is (= 401 (:status (post routes "/email/delivery" "token=the-webhook-token-0124" bounce))))
    (is (= 401 (:status (post routes "/email/inbound" "token=" "{}"))))
    (is (= 400 (:status (post routes "/email/delivery" "token=the-webhook-token-0123" "[1,2]"))))
    (is (= 0 @called))
    (is (= {"groundwork_email_webhook_events_total{outcome=\"refused\",webhook=\"delivery\"}"  2.0
            "groundwork_email_webhook_events_total{outcome=\"refused\",webhook=\"inbound\"}"   1.0
            "groundwork_email_webhook_events_total{outcome=\"bad-body\",webhook=\"delivery\"}" 1.0}
           (into {} (remove (comp zero? val)) (read-counters m "groundwork_email_webhook_events_total"))))))

;; the token taken (the body, not an object, is then 400) or refused (401)
(deftest the-token-is-read-from-the-query-as-form-text
  (let [box    (email/make-mailer (make-settings log-provider) {:log log :meter (make-meter) :shaper shaper :system-actor :email})
        routes (email/make-webhook-routes box webhook-settings
                                            {:run-in-transaction (fn [_] (throw (ex-info "not reached" {})))
                                             :on-delivery-event {:orders/confirmation (fn [_ _] nil)}
                                             :on-inbound-email  (fn [_ _] nil)})
        status #(:status (post routes "/email/delivery" % "[1,2]"))]
    (is (= 400 (status "token=the-webhook-token-0123")))
    (is (= 400 (status "a=1&token=the-webhook-token-0123&b")) "among other params")
    (is (= 400 (status "token=the%2Dwebhook%2dtoken-0123")) "%-escaped")
    (is (= 400 (status "tok%65n=the-webhook-token-0123")) "its name %-escaped too, as form text")
    (is (= 400 (status "x=%zz&token=the-webhook-token-0123")) "a pair with a bad escape is left out, the rest read")
    (is (= 401 (status "token=%zz")) "a token with a bad escape is none")
    (is (= 401 (status "token=the-webhook-token-0123&token=the-webhook-token-0123")) "twice: none")
    (is (= 401 (status "xtoken=the-webhook-token-0123")) "another name")
    (is (= 401 (status "token")) "no value")
    (is (= 401 (status "")) "an empty query")))

;; -----------------------------------------------------------------------------
;; Against Postgres

(defn- make-pool []
  (postgres/start-pool! {:host "localhost" :port port :database "postgres" :user (System/getenv "EMAIL_TEST_USER")
                       :password nil :tls :off :tls-root-cert nil :pool-size 4
                       :connection-wait-ms 30000 :connection-check-wait-ms 5000
                       :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                       :leak-warning-after-ms :none
                       :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                       :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
                       :time-zone "UTC"
                       :metric-buckets {:query-seconds [0.01 0.1 1] :connection-acquire-seconds [0.01 0.1 1]
                                        :connection-usage-seconds [0.01 0.1 1]}}
                      {:log log :meter (make-meter)}))

(def ^:private migrate-options
  {:instant-part {:statement-timeout-ms 5000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
   :log          (fn [_ _ _])})

(defn- declare-tables [emails-table]
  {:tables (conj background-jobs/background-jobs-tables emails-table) :extensions [] :functions [] :views []})

(defn- drop-schemas! [pool]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "drop" "DROP SCHEMA IF EXISTS groundwork_email CASCADE; DROP SCHEMA IF EXISTS groundwork_background_jobs CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE") {}))

(defn- make-tables-afresh! [pool]
  (drop-schemas! pool)
  @(:slow-part (postgres/migrate! pool (declare-tables email/emails-table) [] migrate-options)))

(def ^:private emails-table-of-v0-1
  "groundwork_email.emails as v0.1.9 declared it."
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

(defn- start-workers! [pool box m]
  (background-jobs/start-workers! pool {:shaper shaper :log log :meter m
                                 ;; as an app opens the job's transaction (groundwork-access's run-as-system!, in groundwork)
                                 :run-in-transaction-as (fn [pool _system-actor log f] (postgres/run-in-transaction! pool {:read-only? false :log log} f))
                                 :given-to-every-run {}
                                 :kinds [(email/make-background-job-kind box)] :schedules []
                                 :workers 2 :poll-ms 50
                                 :retry-first-wait-ms 100 :retry-wait-factor 1 :retry-max-wait-ms 100
                                 :stuck-after-time-limit-ms 60000
                                 :forget-done-after-ms 604800000 :forget-done-every-ms 3600000
                                 :stop-wait-ms 5000
                                 :metric-buckets {:job-run-seconds [0.1 1 10]}}))

(defn- wait-until [done?]
  (loop [n 0]
    (when (and (not (done?)) (< n 100))
      (Thread/sleep 50)
      (recur (inc n)))))

(defn- queue-in-transaction! [pool box email keep?]
  (try
    (postgres/run-in-transaction! pool {:read-only? false :log log}
                                (fn [transaction]
                                  (let [queued (email/queue-email! box transaction email)]
                                    (when-not keep? (throw (ex-info "the caller's work failed" {})))
                                    queued)))
    (catch clojure.lang.ExceptionInfo e
      (if (= "the caller's work failed" (ex-message e)) :rolled-back (throw e)))))

(defn- start-fake-postmark!
  "An HTTP server answering POST /email as Postmark does; answers is an
   atom of [status body-text] to give, one per request, the last kept."
  [answers]
  (let [received (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/email"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [^HttpExchange exchange exchange
                              body (slurp (.getRequestBody exchange) :encoding "UTF-8")
                              [status answer] (first @answers)]
                          (swap! received conj {:token (.getFirst (.getRequestHeaders exchange) "X-Postmark-Server-Token")
                                                :body  (json/read-value body)})
                          (when (next @answers) (swap! answers rest))
                          (let [bs (.getBytes ^String answer StandardCharsets/UTF_8)]
                            (.sendResponseHeaders exchange status (alength bs))
                            (with-open [out (.getResponseBody exchange)] (.write out bs)))))))
    (.start server)
    {:server server :received received :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn- postmark-provider [url]
  {:name :postmark :server-token (settings/make-secret :groundwork-email/postmark-token "the-server-token") :api-url url :connect-timeout-ms 2000 :request-timeout-ms 5000})

(defn- run-sql! [pool what sql]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text what sql) {}))

(deftest the-table-of-v0-1-moves-by-its-map
  (if-not port
    (println "groundwork-email.api-test: the Postgres tests skipped, EMAIL_TEST_PORT is not set")
    (let [pool (make-pool)]
      (try
        (drop-schemas! pool)
        @(:slow-part (postgres/migrate! pool (declare-tables emails-table-of-v0-1) [] migrate-options))
        (run-sql! pool "v0.1's rows" "INSERT INTO groundwork_email.emails (email__key, email__tag) VALUES ('order-confirmation/1', 'orders/confirmation');
                                      INSERT INTO groundwork_email.emails (email__key, email__tag, email__state, email__message_id, email__sent_at) VALUES ('order-confirmation/2', 'orders/confirmation', 'sent', 'pm-2', now())")
        (let [{:keys [stops instant changes-to-mark]} (postgres/make-plan-from-tables pool (declare-tables email/emails-table) [])]
          (is (= [] stops) "no stop: the new column is optional, the state's check and the reason's are added NOT VALID")
          (is (seq instant))
          (is (= [] changes-to-mark) "no change file: the map says it all"))
        @(:slow-part (postgres/migrate! pool (declare-tables email/emails-table) [] migrate-options))
        (is (postgres/does-database-match-tables? pool (declare-tables email/emails-table) []))
        (is (= [#:email{:key "order-confirmation/1" :state "queued" :gave-up-reason nil}
                #:email{:key "order-confirmation/2" :state "sent" :gave-up-reason nil}]
               (mapv #(select-keys (email/find-email pool %) [:email/key :email/state :email/gave-up-reason])
                     ["order-confirmation/1" "order-confirmation/2"]))
            "v0.1's rows are read as they were, without a reason")
        (is (thrown? Exception (run-sql! pool "gave up without a reason" "UPDATE groundwork_email.emails SET email__state = 'gave-up' WHERE email__key = 'order-confirmation/1'"))
            "a gave-up email has its reason")
        (is (thrown? Exception (run-sql! pool "a reason on a sent one" "UPDATE groundwork_email.emails SET email__gave_up_reason = 'Inactive recipient (406)' WHERE email__key = 'order-confirmation/2'"))
            "and only a gave-up one")
        (finally (postgres/stop-pool! pool))))))

(deftest emails-against-postgres
  (if-not port
    (println "groundwork-email.api-test: the Postgres tests skipped, EMAIL_TEST_PORT is not set")
    (let [pool (make-pool)]
      (try
        (make-tables-afresh! pool)

        (testing "the :log provider: rolled back, nothing is sent; committed, sent once; its key never again"
          (let [m       (make-meter)
                box     (email/make-mailer (make-settings log-provider) {:log log :meter m :shaper shaper :system-actor :email})
                workers (start-workers! pool box m)]
            (try
              (reset! lines [])
              (is (= :rolled-back (queue-in-transaction! pool box (make-email 1) false)))
              (is (nil? (email/find-email pool "order-confirmation/1")))
              (is (= :queued (queue-in-transaction! pool box (make-email 2) true)))
              (wait-until #(= "sent" (:email/state (email/find-email pool "order-confirmation/2"))))
              (is (= :queued-before (queue-in-transaction! pool box (make-email 2) true)))
              (Thread/sleep 300)
              (let [logged (find-lines "groundwork-email/logged-not-sent")
                    row    (email/find-email pool "order-confirmation/2")]
                (is (= 1 (count logged)) "sent once; the rolled-back one never")
                (is (= {"key" "order-confirmation/2" "subject" "Your order 2" "to" ["Anna <anna@shop.example>" "bob@shop.example"]
                        "tag" "orders/confirmation" "metadata" {"order-id" "2"}
                        "attachments" [{"name" "invoice.pdf" "content-type" "application/pdf" "base64-length" 12}]
                        "unsubscribe-url?" false}
                       (select-keys (get (first logged) "data") ["key" "subject" "to" "tag" "metadata" "attachments" "unsubscribe-url?"])))
                (is (= "sent" (:email/state row)))
                (is (= (get-in (first logged) ["data" "message-id"]) (:email/message-id row)))
                (is (some? (:email/sent-at row))))
              (is (= 2.0 (get (read-counters m "groundwork_email_emails_queued_total") "groundwork_email_emails_queued_total{tag=\"orders/confirmation\"}"))
                  "the rolled-back one is counted too, as groundwork-background-jobs counts a job added: the counter can't know")
              (is (= 1.0 (get (read-counters m "groundwork_email_emails_sent_total") "groundwork_email_emails_sent_total{tag=\"orders/confirmation\"}")))

              (testing "the log says an email has an unsubscribe address, not the address"
                (is (= :queued (queue-in-transaction! pool box (assoc (make-email 5) :unsubscribe-url unsubscribe-url) true)))
                (wait-until #(= "sent" (:email/state (email/find-email pool "order-confirmation/5"))))
                (let [data (get (last (find-lines "groundwork-email/logged-not-sent")) "data")]
                  (is (= ["order-confirmation/5" true] [(get data "key") (get data "unsubscribe-url?")]))
                  (is (not (str/includes? (pr-str data) unsubscribe-url)))))

              (testing "a job queued by v0.1, its params without an unsubscribe address, is sent"
                (postgres/run-in-transaction! pool {:read-only? false :log log}
                                              (fn [transaction]
                                                (postgres/run-statement! transaction (postgres/make-query-from-sql-text "a v0.1 email's row" "INSERT INTO groundwork_email.emails (email__key, email__tag) VALUES ('order-confirmation/6', 'orders/confirmation')") {})
                                                (background-jobs/add-background-job-to-queue!
                                                 transaction (email/make-background-job-kind box)
                                                 #:email{:key "order-confirmation/6" :tag "orders/confirmation" :to ["anna@shop.example"]
                                                         :from "orders@shop.example" :reply-to nil :subject "Your order 6" :html nil
                                                         :text "Thank you." :metadata [] :attachments [] :stream "outbound"}
                                                 {:shaper shaper :meter m :run-in-ms 0 :key nil})))
                (wait-until #(= "sent" (:email/state (email/find-email pool "order-confirmation/6"))))
                (is (= ["order-confirmation/6" false]
                       ((juxt #(get % "key") #(get % "unsubscribe-url?")) (get (last (find-lines "groundwork-email/logged-not-sent")) "data")))))
              (finally (background-jobs/stop-workers! workers)))))

        (testing "Postmark: the email as Postmark takes it, retried after a failure, sent once"
          (let [answers (atom [[503 "{\"ErrorCode\":0,\"Message\":\"Service unavailable\"}"]
                               [200 "{\"ErrorCode\":0,\"Message\":\"OK\",\"MessageID\":\"pm-3\",\"To\":\"anna@shop.example\"}"]])
                fake    (start-fake-postmark! answers)
                m       (make-meter)
                box     (email/make-mailer (make-settings (postmark-provider (:url fake))) {:log log :meter m :shaper shaper :system-actor :email})
                workers (start-workers! pool box m)]
            (try
              (is (= :queued (queue-in-transaction! pool box (assoc (make-email 3) :unsubscribe-url unsubscribe-url) true)))
              (wait-until #(= "sent" (:email/state (email/find-email pool "order-confirmation/3"))))
              (Thread/sleep 300)
              (let [received @(:received fake)
                    body     (:body (last received))]
                (is (= 2 (count received)) "the 503, then the send")
                (is (= #{"the-server-token"} (set (map :token received))))
                (is (= {"From" "Shop <orders@shop.example>" "To" "Anna <anna@shop.example>, bob@shop.example"
                        "ReplyTo" "help@shop.example" "Subject" "Your order 3" "HtmlBody" "<p>Thank you.</p>"
                        "TextBody" "Thank you." "Tag" "orders/confirmation" "Metadata" {"order-id" "3"}
                        "MessageStream" "outbound"
                        "Attachments" [{"Name" "invoice.pdf" "ContentType" "application/pdf"
                                        "Content" (.encodeToString (Base64/getEncoder) (.getBytes "%PDF-1.7"))}]
                        "Headers" [{"Name" "List-Unsubscribe" "Value" (str "<" unsubscribe-url ">")}
                                   {"Name" "List-Unsubscribe-Post" "Value" "List-Unsubscribe=One-Click"}]}
                       body)
                    "the unsubscribe address as RFC 8058's two headers, the only headers sent"))
              (is (= "pm-3" (:email/message-id (email/find-email pool "order-confirmation/3"))))
              (is (= 1.0 (get (read-counters m "groundwork_email_email_sends_failed_total") "groundwork_email_email_sends_failed_total{tag=\"orders/confirmation\"}")))

              (testing "an email Postmark refuses for itself gives up at once, its row gave-up with the reason, committed"
                (reset! lines [])
                (reset! answers [[422 "{\"ErrorCode\":300,\"Message\":\"Invalid 'To' address: 'not an address'\"}"]])
                (is (= :queued (queue-in-transaction! pool box (assoc (make-email 4) :to ["not an address"]) true)))
                (wait-until #(= "gave-up" (:email/state (email/find-email pool "order-confirmation/4"))))
                (is (= #:email{:state "gave-up" :gave-up-reason "Invalid email request (300)" :message-id nil :sent-at nil}
                       (select-keys (email/find-email pool "order-confirmation/4") [:email/state :email/gave-up-reason :email/message-id :email/sent-at]))
                    "Postmark's name of the code, never its message, which names the address")
                (is (not (contains? (:body (nth @(:received fake) 2)) "Headers")) "no unsubscribe address, no header")
                (reset! answers [[422 "{\"ErrorCode\":406,\"Message\":\"You tried to send to recipient(s) that have been marked as inactive. Found inactive addresses: anna@shop.example.\"}"]])
                (is (= :queued (queue-in-transaction! pool box (make-email 8) true)))
                (wait-until #(= "gave-up" (:email/state (email/find-email pool "order-confirmation/8"))))
                (Thread/sleep 300)
                (is (= "Inactive recipient (406)" (:email/gave-up-reason (email/find-email pool "order-confirmation/8"))))
                (is (= 4 (count @(:received fake))) "one attempt each, not three")
                (is (= [[] []] [(background-jobs/list-dead-background-jobs pool 10) (background-jobs/list-queued-background-jobs pool 10)])
                    "the jobs are done, neither dead nor waiting: their transactions committed the gave-up")
                (is (= [["order-confirmation/4" "Invalid email request (300)" "Invalid 'To' address: 'not an address'"]
                        ["order-confirmation/8" "Inactive recipient (406)" "You tried to send to recipient(s) that have been marked as inactive. Found inactive addresses: anna@shop.example."]]
                       (mapv (comp (juxt #(get % "email/key") #(get % "email/gave-up-reason") #(get % "groundwork-email/provider-message")) #(get % "data"))
                             (find-lines "groundwork-email/gave-up")))
                    "Postmark's words go to the log only")
                (is (= {"groundwork_email_emails_gave_up_total{tag=\"orders/confirmation\"}" 2.0}
                       (into {} (remove (comp zero? val)) (read-counters m "groundwork_email_emails_gave_up_total"))))
                (is (= 3.0 (get (read-counters m "groundwork_email_email_sends_failed_total") "groundwork_email_email_sends_failed_total{tag=\"orders/confirmation\"}"))
                    "the 503 and the two refusals")
                (is (= :queued-before (queue-in-transaction! pool box (make-email 8) true)) "a key given up is still queued once, ever"))

              (testing "a bounce is handed to its tag's function, with groundwork-email's key and the metadata"
                (let [events  (atom [])
                      runs    (atom 0)
                      routes  (email/make-webhook-routes box webhook-settings
                                                           {:run-in-transaction (fn [f]
                                                                                  (swap! runs inc)
                                                                                  (postgres/run-in-transaction! pool {:read-only? false :log log} f))
                                                            :on-delivery-event {:orders/confirmation (fn [event {:keys [transaction]}]
                                                                                                       (is (some? transaction))
                                                                                                       (swap! events conj event))
                                                                                :orders/shop-notification (fn [_ _] (throw (ex-info "the app's function broke" {})))}
                                                            :on-inbound-email  (fn [email {:keys [transaction]}]
                                                                                 (is (some? transaction))
                                                                                 (swap! events conj email))})
                      token   "token=the-webhook-token-0123"
                      bounce  (json/write-value-as-string {"RecordType" "Bounce" "Type" "HardBounce" "Tag" "orders/confirmation"
                                                           "MessageID" "pm-3" "Email" "anna@shop.example" "Inactive" true
                                                           "Metadata" {"order-id" "3"}})]
                  (is (= 200 (:status (post routes "/email/delivery" token bounce))))
                  (is (= 1 @runs) "in the transaction the app's function made, groundwork-email's key looked up in it too")
                  (is (= [{:kind :bounce :tag :orders/confirmation :message-id "pm-3" :email-key "order-confirmation/3"
                           :address "anna@shop.example" :bounce-type "HardBounce" :inactive? true :metadata {"order-id" "3"}}]
                         @events))
                  (reset! lines [])
                  (is (= 200 (:status (post routes "/email/delivery" token
                                            (json/write-value-as-string {"RecordType" "SpamComplaint" "Tag" "orders/shop-notification"
                                                                         "MessageID" "pm-9" "Email" "x@shop.example"})))))
                  (is (= 1 (count (find-lines "groundwork-email/webhook-function-failed"))) "the app's function threw: logged, answered 200")
                  (is (= 200 (:status (post routes "/email/delivery" token
                                            (json/write-value-as-string {"RecordType" "Bounce" "Tag" "someone/else" "MessageID" "pm-10"})))))
                  (is (= 1 (count (find-lines "groundwork-email/no-function"))))
                  (is (= 200 (:status (post routes "/email/delivery" token (json/write-value-as-string {"RecordType" "Delivery"})))))

                  (testing "every inbound email is handed to the one function, its :mailbox to route by"
                    (reset! events [])
                    (is (= 200 (:status (post routes "/email/inbound" token
                                              (json/write-value-as-string
                                               {"MessageID" "in-1" "From" "bob@example.com" "FromFull" {"Email" "Bob@Example.com" "Name" "Bob"}
                                                "OriginalRecipient" "Quotes@in.shop.example" "ToFull" [{"Email" "quotes@in.shop.example"}]
                                                "Subject" "A gate" "TextBody" "Please quote." "HtmlBody" ""
                                                "Attachments" [{"Name" "gate.jpg" "ContentType" "image/jpeg"
                                                                "Content" (.encodeToString (Base64/getEncoder) (.getBytes "jpeg"))}]})))))
                    (let [[email] @events]
                      (is (= {:message-id "in-1" :mailbox "quotes" :from {:address "bob@example.com" :name "Bob"}
                              :to ["quotes@in.shop.example"] :subject "A gate" :text "Please quote." :html nil :stripped-reply nil}
                             (dissoc email :attachments)))
                      (is (= [["gate.jpg" "image/jpeg" "jpeg"]]
                             (mapv (juxt :name :content-type #(String. ^bytes (:bytes %))) (:attachments email)))))
                    (is (= 200 (:status (post routes "/email/inbound" token
                                              (json/write-value-as-string
                                               {"MessageID" "in-2" "From" "cy@example.com" "OriginalRecipient" "shop-7-quotes@in.shop.example"
                                                "ToFull" [{"Email" "shop-7-quotes@in.shop.example"}] "Subject" "Wheels" "TextBody" "Four."})))))
                    (is (= 200 (:status (post routes "/email/inbound" token
                                              (json/write-value-as-string
                                               {"MessageID" "in-3" "From" "dee@example.com" "OriginalRecipient" "quotes@elsewhere.example"
                                                "ToFull" [{"Email" "quotes@elsewhere.example"}] "Subject" "Lost" "TextBody" "Hi."})))))
                    (is (= [["in-1" "quotes"] ["in-2" "shop-7-quotes"] ["in-3" nil]] (mapv (juxt :message-id :mailbox) @events))
                        "a mailbox no map names, and one off the domain, nil: each handed on"))

                  (is (= {"groundwork_email_webhook_events_total{outcome=\"handled\",webhook=\"delivery\"}"     1.0
                          "groundwork_email_webhook_events_total{outcome=\"failed\",webhook=\"delivery\"}"      1.0
                          "groundwork_email_webhook_events_total{outcome=\"no-function\",webhook=\"delivery\"}" 1.0
                          "groundwork_email_webhook_events_total{outcome=\"ignored\",webhook=\"delivery\"}"     1.0
                          "groundwork_email_webhook_events_total{outcome=\"handled\",webhook=\"inbound\"}"      3.0}
                         (into {} (remove (comp zero? val)) (read-counters m "groundwork_email_webhook_events_total"))))))
              (finally
                (background-jobs/stop-workers! workers)
                (.stop ^HttpServer (:server fake) 0)))))
        (finally (postgres/stop-pool! pool))))))

(ns postbox.api-test
  "The settings and the webhooks' token run everywhere. The rest runs
   against a real Postgres when POSTBOX_TEST_PORT is set (on localhost,
   user from POSTBOX_TEST_USER, no password, no TLS), with backroom's
   workers sending, by the :log provider and by Postmark's API served by a
   fake Postmark in the test (an HTTP server answering as Postmark does);
   says it was skipped otherwise."
  (:require
   [backroom.api :as backroom]
   [cellar.api :as cellar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [logbook.api :as logbook]
   [meter.api :as meter]
   [postbox.api :as postbox]
   [safe.api :as safe]
   [wire.api :as wire])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.io ByteArrayInputStream)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)
   (java.util Base64)))

(def ^:private port (some-> (System/getenv "POSTBOX_TEST_PORT") parse-long))

(def ^:private lines (atom []))

(def ^:private log
  (logbook/log-function (logbook/make-logbook {:lowest-level :debug :format :json :errors-kept 100
                                               :write-to     #(swap! lines conj (json/read-value %))})))

(defn- find-lines [event]
  (filterv #(= event (get % "event")) @lines))

(defn- make-meter []
  (meter/make-meter {:jvm-metrics false :most-series-per-metric 1000} {:log log}))

(def ^:private wire
  (wire/make-wire-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                       :max-name-length 50000 :max-document-length nil :max-token-count nil
                       :max-writing-nesting-depth 1000}}))

(def ^:private tags #{:orders/confirmation :orders/shop-notification})

(defn- make-settings [provider]
  {:provider provider :tags tags :background-job {:timeout-ms 10000 :attempts 3}})

(def ^:private log-provider {:name :log :level :info})

(def ^:private webhook-settings
  {:delivery-path "/postbox/delivery" :inbound-path "/postbox/inbound"
   :token (safe/make-secret :postbox/webhook-token "the-webhook-token-0123") :inbound-domain "in.shop.example"})

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
   :stream      "outbound"})

(defn- read-counters [m metric-name]
  (->> (str/split-lines (meter/write-metrics-text m))
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
         (:errors (ex-data (try (postbox/make-postbox {:provider {:name :log} :tags tags}
                                                      {:log log :meter (make-meter) :wire wire})
                                (catch Exception e e))))))
  (let [box (postbox/make-postbox (make-settings log-provider) {:log log :meter (make-meter) :wire wire})]
    (is (= {:reply-to ["missing required key"]}
           (:errors (ex-data (try (postbox/queue-email! box nil (dissoc (make-email 7) :reply-to))
                                  (catch Exception e e))))))
    (is (= ["should have :html or :text, or both"]
           (:errors (ex-data (try (postbox/queue-email! box nil (assoc (make-email 7) :html nil :text nil))
                                  (catch Exception e e)))))
        "an email has a body")
    (is (= {:tag ["should be one of the settings' :tags"]}
           (:errors (ex-data (try (postbox/queue-email! box nil (assoc (make-email 7) :tag :orders/unknown))
                                  (catch Exception e e))))))
    (is (= :postbox/bad-options
           (:type (ex-data (try (postbox/make-webhook-routes box webhook-settings
                                                             {:run-in-transaction (fn [_]) :on-delivery-event {:orders/unknown (fn [_ _])}
                                                              :on-inbound-email {}})
                                (catch Exception e e)))))
        "a delivery function for a tag the settings don't have")
    (is (= {:run-in-transaction ["missing required key"] :pool ["disallowed key"]}
           (:errors (ex-data (try (postbox/make-webhook-routes box webhook-settings {:pool :a-pool :on-delivery-event {} :on-inbound-email {}})
                                  (catch Exception e e)))))
        "the app hands the transaction it runs them in, not a pool")))

(deftest the-tokens-are-secrets
  (let [refusal (fn [f] (try (f) :nothing (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :errors]))))
        box     (postbox/make-postbox (make-settings log-provider) {:log log :meter (make-meter) :wire wire})
        options {:run-in-transaction (fn [_] (throw (ex-info "not reached" {}))) :on-delivery-event {} :on-inbound-email {}}]
    (testing "Postmark's server token written out as text is refused, and the text is never said"
      (let [refused (refusal #(postbox/make-postbox (make-settings {:name :postmark :server-token "written-out-server-token"
                                                                    :api-url "https://api.postmarkapp.com"
                                                                    :connect-timeout-ms 2000 :request-timeout-ms 5000})
                                                    {:log log :meter (make-meter) :wire wire}))]
        (is (= {:type :postbox/bad-settings :errors {:provider {:server-token ["should be a #secret, read from the settings' secrets source"]}}}
               refused))
        (is (not (str/includes? (pr-str refused) "written-out-server-token")))))
    (testing "so is the webhooks' token"
      (is (= {:type :postbox/bad-settings :errors {:token ["should be a #secret, read from the settings' secrets source"]}}
             (refusal #(postbox/make-webhook-routes box (assoc webhook-settings :token "the-webhook-token-0123") options)))))
    (testing "a webhooks' token shorter than 16 characters is refused, named, its text never said"
      (let [refused (refusal #(postbox/make-webhook-routes box (assoc webhook-settings :token (safe/make-secret :postbox/webhook-token "short-token-15c")) options))]
        (is (= {:type :postbox/bad-settings :errors {:token ["should be at least 16 characters"]}} refused))
        (is (not (str/includes? (pr-str refused) "short-token-15c")))))
    (testing "16 characters are enough"
      (is (vector? (postbox/make-webhook-routes box (assoc webhook-settings :token (safe/make-secret :postbox/webhook-token "sixteen-chars-16")) options))))))

(deftest the-webhooks-refuse-without-their-token
  (let [m      (make-meter)
        box    (postbox/make-postbox (make-settings log-provider) {:log log :meter m :wire wire})
        called (atom 0)
        routes (postbox/make-webhook-routes box webhook-settings
                                            {:run-in-transaction (fn [_] (throw (ex-info "not reached" {})))
                                             :on-delivery-event {:orders/confirmation (fn [_ _] (swap! called inc))}
                                             :on-inbound-email  {"quotes" (fn [_ _] (swap! called inc))}})
        bounce "{\"RecordType\":\"Bounce\",\"Tag\":\"orders/confirmation\",\"MessageID\":\"m-1\"}"]
    (is (= 401 (:status (post routes "/postbox/delivery" nil bounce))))
    (is (= 401 (:status (post routes "/postbox/delivery" "token=the-webhook-token-0124" bounce))))
    (is (= 401 (:status (post routes "/postbox/inbound" "token=" "{}"))))
    (is (= 400 (:status (post routes "/postbox/delivery" "token=the-webhook-token-0123" "[1,2]"))))
    (is (= 0 @called))
    (is (= {"postbox_webhook_events_total{outcome=\"refused\",webhook=\"delivery\"}"  2.0
            "postbox_webhook_events_total{outcome=\"refused\",webhook=\"inbound\"}"   1.0
            "postbox_webhook_events_total{outcome=\"bad-body\",webhook=\"delivery\"}" 1.0}
           (into {} (remove (comp zero? val)) (read-counters m "postbox_webhook_events_total"))))))

;; -----------------------------------------------------------------------------
;; Against Postgres

(defn- make-pool []
  (cellar/start-pool! {:host "localhost" :port port :database "postgres" :user (System/getenv "POSTBOX_TEST_USER")
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

(defn- make-tables-afresh! [pool]
  (cellar/run-statement! pool (cellar/make-query-from-sql-text "drop" "DROP SCHEMA IF EXISTS postbox CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE") {})
  @(:slow-part (cellar/migrate! pool (conj backroom/background-jobs-tables postbox/emails-table) []
                                {:instant-part {:statement-timeout-ms 5000 :lock-timeout-ms 5000}
                                 :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                                 :log          (fn [_ _ _])})))

(defn- start-workers! [pool box m]
  (backroom/start-workers! pool {:wire wire :log log :meter m
                                 :kinds [(postbox/make-background-job-kind box)] :schedules []
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
    (cellar/run-in-transaction! pool {:read-only? false}
                                (fn [transaction]
                                  (let [queued (postbox/queue-email! box transaction email)]
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
  {:name :postmark :server-token (safe/make-secret :postbox/postmark-token "the-server-token") :api-url url :connect-timeout-ms 2000 :request-timeout-ms 5000})

(deftest emails-against-postgres
  (if-not port
    (println "postbox.api-test: the Postgres tests skipped, POSTBOX_TEST_PORT is not set")
    (let [pool (make-pool)]
      (try
        (make-tables-afresh! pool)

        (testing "the :log provider: rolled back, nothing is sent; committed, sent once; its key never again"
          (let [m       (make-meter)
                box     (postbox/make-postbox (make-settings log-provider) {:log log :meter m :wire wire})
                workers (start-workers! pool box m)]
            (try
              (reset! lines [])
              (is (= :rolled-back (queue-in-transaction! pool box (make-email 1) false)))
              (is (nil? (postbox/find-email pool "order-confirmation/1")))
              (is (= :queued (queue-in-transaction! pool box (make-email 2) true)))
              (wait-until #(= "sent" (:email/state (postbox/find-email pool "order-confirmation/2"))))
              (is (= :queued-before (queue-in-transaction! pool box (make-email 2) true)))
              (Thread/sleep 300)
              (let [logged (find-lines "postbox/logged-not-sent")
                    row    (postbox/find-email pool "order-confirmation/2")]
                (is (= 1 (count logged)) "sent once; the rolled-back one never")
                (is (= {"key" "order-confirmation/2" "subject" "Your order 2" "to" ["Anna <anna@shop.example>" "bob@shop.example"]
                        "tag" "orders/confirmation" "metadata" {"order-id" "2"}
                        "attachments" [{"name" "invoice.pdf" "content-type" "application/pdf" "base64-length" 12}]}
                       (select-keys (get (first logged) "data") ["key" "subject" "to" "tag" "metadata" "attachments"])))
                (is (= "sent" (:email/state row)))
                (is (= (get-in (first logged) ["data" "message-id"]) (:email/message-id row)))
                (is (some? (:email/sent-at row))))
              (is (= 2.0 (get (read-counters m "postbox_emails_queued_total") "postbox_emails_queued_total{tag=\"orders/confirmation\"}"))
                  "the rolled-back one is counted too, as backroom counts a job added: the counter can't know")
              (is (= 1.0 (get (read-counters m "postbox_emails_sent_total") "postbox_emails_sent_total{tag=\"orders/confirmation\"}")))
              (finally (backroom/stop-workers! workers)))))

        (testing "Postmark: the email as Postmark takes it, retried after a failure, sent once"
          (let [answers (atom [[503 "{\"ErrorCode\":0,\"Message\":\"Service unavailable\"}"]
                               [200 "{\"ErrorCode\":0,\"Message\":\"OK\",\"MessageID\":\"pm-3\",\"To\":\"anna@shop.example\"}"]])
                fake    (start-fake-postmark! answers)
                m       (make-meter)
                box     (postbox/make-postbox (make-settings (postmark-provider (:url fake))) {:log log :meter m :wire wire})
                workers (start-workers! pool box m)]
            (try
              (is (= :queued (queue-in-transaction! pool box (make-email 3) true)))
              (wait-until #(= "sent" (:email/state (postbox/find-email pool "order-confirmation/3"))))
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
                                        "Content" (.encodeToString (Base64/getEncoder) (.getBytes "%PDF-1.7"))}]}
                       body)))
              (is (= "pm-3" (:email/message-id (postbox/find-email pool "order-confirmation/3"))))
              (is (= 1.0 (get (read-counters m "postbox_email_sends_failed_total") "postbox_email_sends_failed_total{tag=\"orders/confirmation\"}")))

              (testing "an email Postmark refuses for itself gives up at once"
                (reset! answers [[422 "{\"ErrorCode\":300,\"Message\":\"Invalid 'To' address\"}"]])
                (is (= :queued (queue-in-transaction! pool box (assoc (make-email 4) :to ["not an address"]) true)))
                (wait-until #(seq (backroom/list-dead-background-jobs pool 10)))
                (is (= 1 (count (backroom/list-dead-background-jobs pool 10))))
                (is (= 3 (count @(:received fake))) "one attempt, not three")
                (is (= "queued" (:email/state (postbox/find-email pool "order-confirmation/4")))))

              (testing "a bounce is handed to its tag's function, with postbox's key and the metadata"
                (let [events  (atom [])
                      runs    (atom 0)
                      routes  (postbox/make-webhook-routes box webhook-settings
                                                           {:run-in-transaction (fn [f]
                                                                                  (swap! runs inc)
                                                                                  (cellar/run-in-transaction! pool {:read-only? false} f))
                                                            :on-delivery-event {:orders/confirmation (fn [event {:keys [transaction]}]
                                                                                                       (is (some? transaction))
                                                                                                       (swap! events conj event))
                                                                                :orders/shop-notification (fn [_ _] (throw (ex-info "the app's function broke" {})))}
                                                            :on-inbound-email  {"quotes" (fn [email _] (swap! events conj email))}})
                      token   "token=the-webhook-token-0123"
                      bounce  (json/write-value-as-string {"RecordType" "Bounce" "Type" "HardBounce" "Tag" "orders/confirmation"
                                                           "MessageID" "pm-3" "Email" "anna@shop.example" "Inactive" true
                                                           "Metadata" {"order-id" "3"}})]
                  (is (= 200 (:status (post routes "/postbox/delivery" token bounce))))
                  (is (= 1 @runs) "in the transaction the app's function made, postbox's key looked up in it too")
                  (is (= [{:kind :bounce :tag :orders/confirmation :message-id "pm-3" :email-key "order-confirmation/3"
                           :address "anna@shop.example" :bounce-type "HardBounce" :inactive? true :metadata {"order-id" "3"}}]
                         @events))
                  (reset! lines [])
                  (is (= 200 (:status (post routes "/postbox/delivery" token
                                            (json/write-value-as-string {"RecordType" "SpamComplaint" "Tag" "orders/shop-notification"
                                                                         "MessageID" "pm-9" "Email" "x@shop.example"})))))
                  (is (= 1 (count (find-lines "postbox/webhook-function-failed"))) "the app's function threw: logged, answered 200")
                  (is (= 200 (:status (post routes "/postbox/delivery" token
                                            (json/write-value-as-string {"RecordType" "Bounce" "Tag" "someone/else" "MessageID" "pm-10"})))))
                  (is (= 1 (count (find-lines "postbox/no-function"))))
                  (is (= 200 (:status (post routes "/postbox/delivery" token (json/write-value-as-string {"RecordType" "Delivery"})))))

                  (testing "an inbound email is handed to its mailbox's function"
                    (reset! events [])
                    (is (= 200 (:status (post routes "/postbox/inbound" token
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
                             (mapv (juxt :name :content-type #(String. ^bytes (:bytes %))) (:attachments email))))))

                  (is (= {"postbox_webhook_events_total{outcome=\"handled\",webhook=\"delivery\"}"     1.0
                          "postbox_webhook_events_total{outcome=\"failed\",webhook=\"delivery\"}"      1.0
                          "postbox_webhook_events_total{outcome=\"no-function\",webhook=\"delivery\"}" 1.0
                          "postbox_webhook_events_total{outcome=\"ignored\",webhook=\"delivery\"}"     1.0
                          "postbox_webhook_events_total{outcome=\"handled\",webhook=\"inbound\"}"      1.0}
                         (into {} (remove (comp zero? val)) (read-counters m "postbox_webhook_events_total"))))))
              (finally
                (backroom/stop-workers! workers)
                (.stop ^HttpServer (:server fake) 0)))))
        (finally (cellar/stop-pool! pool))))))

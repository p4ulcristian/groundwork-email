(ns postbox.settings
  "postbox's settings, options and the email it takes, as malli shapes,
   checked before anything is queued. Nothing has a default: every key is
   written out (nil where it is a choice), and a missing or unknown one
   refuses, named, with every other problem at once. Checked by malli
   itself: values the app hands in, not data crossing an edge."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]
   [safe.api :as safe]))

(def ^:private address-shape
  [:re {:error/message "should be an address starting with http:// or https://, no / at the end"} #"^https?://.*[^/]$"])

(def provider-shape
  "Who sends, by :name; each provider its own keys, every one required.
   {:name :postmark ...}: Postmark's HTTP API. :server-token, the server's
   API token, a secret of safe's (#secret :postbox/postmark-token in the
   app's settings file; text written out is refused); :api-url, \"https://api.postmarkapp.com\";
   :connect-timeout-ms, how long a send waits for the connection;
   :request-timeout-ms, how long it waits for Postmark's answer after.
   {:name :log :level :info}: nothing is sent; each email is written to
   the app's log at :level, :postbox/logged-not-sent, and answered as a
   sent one, its message id \"logged-<uuid>\". For development and tests."
  [:multi {:dispatch :name}
   [:postmark [:map {:closed true}
               [:name [:= :postmark]]
               [:server-token safe/secret]
               [:api-url address-shape]
               [:connect-timeout-ms [:int {:min 1}]]
               [:request-timeout-ms [:int {:min 1}]]]]
   [:log [:map {:closed true}
          [:name [:= :log]]
          [:level [:enum :debug :info :warn :error]]]]])

(def settings-shape
  "make-postbox's settings, every key required.
   :provider: who sends (provider-shape).
   :tags: every email's tag, a closed set of namespaced keywords
   (#{:orders/confirmation :orders/shop-notification}): an email names
   one; the metrics count by them and the delivery webhook hands events
   to the app's function by them.
   :background-job: the sending background job's :timeout-ms (one send)
   and :attempts (how many before it is dead; backroom retries a failed
   one, waiting by its own settings)."
  [:map {:closed true}
   [:provider provider-shape]
   [:tags [:set {:min 1} :qualified-keyword]]
   [:background-job [:map {:closed true}
                     [:timeout-ms [:int {:min 1}]]
                     [:attempts [:int {:min 1}]]]]])

(def options-shape
  "make-postbox's options: the app's log, meter and wire, and
   :system-actor, the named system actor the sending background job runs
   as (its kind's :system-actor, backroom's; a keyword without a
   namespace): the app's to name, as it says whom its webhooks run as
   (groundwork's is doorman's :postbox, for both)."
  [:map {:closed true}
   [:log [fn? {:error/message "should be the app's log function, made by logbook"}]]
   [:meter [some? {:error/message "should be the app's meter, made by meter.api/make-meter"}]]
   [:wire [some? {:error/message "should be the app's wire, made by wire.api/make-wire-from-settings"}]]
   [:system-actor [simple-keyword? {:error/message "should be a keyword without a namespace, the named system actor the sending runs as"}]]])

(defn- has-html-or-text? [{:keys [html text]}]
  (or (some? html) (some? text)))

(def email-shape
  "An email the app has written, every key required, nil or empty where
   it has none.
   :key: the email's own key, never queued twice: \"order-confirmation/<order id>\".
   :tag: one of the settings' :tags.
   :to: the addresses, 1 to 50 (\"Anna <anna@shop.example>\" too).
   :from, :reply-to (or nil), :subject.
   :html, :text: the bodies; nil for one, never both.
   :metadata: text to text, back on the email's bounces: at most 10, a
   name at most 20 characters, a value at most 80 (Postmark's limits).
   :attachments: [{:name \"invoice.pdf\" :content-type \"application/pdf\" :bytes bytes}], [] for none.
   :stream: the provider's message stream, \"outbound\" or \"broadcast\"."
  [:and
   [:map {:closed true}
    [:key [:string {:min 1 :max 300}]]
    [:tag :qualified-keyword]
    [:to [:vector {:min 1 :max 50} [:string {:min 3}]]]
    [:from [:string {:min 3}]]
    [:reply-to [:maybe [:string {:min 3}]]]
    [:subject [:string {:min 1}]]
    [:html [:maybe :string]]
    [:text [:maybe :string]]
    [:metadata [:map-of {:max 10} [:string {:min 1 :max 20}] [:string {:max 80}]]]
    [:attachments [:vector [:map {:closed true}
                            [:name [:string {:min 1}]]
                            [:content-type [:string {:min 1}]]
                            [:bytes bytes?]]]]
    [:stream [:string {:min 1}]]]
   [:fn {:error/message "should have :html or :text, or both"} has-html-or-text?]])

(def webhook-settings-shape
  "make-webhook-routes' settings, every key required.
   :delivery-path: where the provider posts bounces and spam complaints,
   \"/postbox/delivery\".
   :inbound-path: where it posts email sent to the app, \"/postbox/inbound\".
   :token: the secret both URLs carry as ?token=…, a secret of safe's
   (#secret :postbox/webhook-token; text written out is refused), at least
   16 characters (make-webhook-routes checks); the provider's webhook URL
   is <app>/<path>?token=<token>.
   :inbound-domain: the domain the app takes email on, \"in.shop.example\":
   an inbound email's mailbox is the part before @ of its recipient there."
  [:map {:closed true}
   [:delivery-path [:re #"^/"]]
   [:inbound-path [:re #"^/"]]
   [:token safe/secret]
   [:inbound-domain [:string {:min 1}]]])

(def webhook-options-shape
  "make-webhook-routes' options, every key required.
   :run-in-transaction: (fn [f]), running (f transaction) in one
   transaction of the app's and returning what f returns; each event is
   handled in one. The app says as whom it runs: rows behind the app's
   gates are reached only as someone they allow (groundwork's runs it as
   doorman's system actor :postbox).
   :on-delivery-event: {tag (fn [event context])}, a bounce or spam
   complaint handed to the function of the email's tag; {} for none.
   :on-inbound-email: {mailbox (fn [email context])}, an inbound email
   handed to the function of its mailbox (\"quotes\"); {} for none.
   context is {:transaction :log}. A function must be safe to run twice:
   the provider may post an event again."
  [:map {:closed true}
   [:run-in-transaction fn?]
   [:on-delivery-event [:map-of :qualified-keyword fn?]]
   [:on-inbound-email [:map-of [:string {:min 1}] fn?]]])

(def ^:private shortest-token-length 16)

(defn check-token-length
  "Throws {:type :postbox/bad-settings :errors {:token […]}} when the
   webhooks' token is shorter than 16 characters: a URL's token is
   guessed by trying. The text is revealed here, for its length only, and
   never said."
  [token]
  (when (< (count (safe/reveal token)) shortest-token-length)
    (throw (ex-info (str "The settings have problems: :token should be at least " shortest-token-length " characters")
                    {:type :postbox/bad-settings :errors {:token [(str "should be at least " shortest-token-length " characters")]}}))))

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

# postbox

Email for a Clojure app on Postgres. The app writes an email; postbox
queues it in the caller's transaction as a background job
([backroom](https://github.com/p4ulcristian/backroom)), so it exists only
if the caller's work commits, and sends it once, by Postmark or to the
log, retrying a failure. An email's key is queued once, ever. Bounces,
spam complaints and inbound email come back on two webhooks and are
handed to the app's functions, by the email's tag and by the mailbox.

```clojure
io.github.p4ulcristian/postbox {:git/tag "v0.1.2" :git/sha "b407967"}
```

Needs [backroom](https://github.com/p4ulcristian/backroom),
[cellar](https://github.com/p4ulcristian/cellar),
[wire](https://github.com/p4ulcristian/wire),
[logbook](https://github.com/p4ulcristian/logbook) and
[meter](https://github.com/p4ulcristian/meter) (the last three handed in),
[safe](https://github.com/p4ulcristian/safe) (the two tokens are its
secrets) and malli; Postmark is called with the JDK's own HTTP client. One public
namespace, `postbox.api`.

## The api

| name | does |
|---|---|
| `make-postbox` `settings-shape` | a postbox from its settings and `{:log :meter :wire}`, every key required, checked first; declares postbox's metrics |
| `emails-table` | `postbox.emails` as a map for cellar, to migrate with the app's tables |
| `make-background-job-kind` | the kind that sends, `:postbox/send-email`, for backroom's workers |
| `queue-email!` `email-shape` | queues a written email in the caller's transaction; `:queued`, or `:queued-before` for a key queued before |
| `find-email` | an email's row: key, tag, state, message id, queued and sent at |
| `make-webhook-routes` `webhook-settings-shape` | the delivery and inbound webhooks, as reitit data |

```clojure
(require '[postbox.api :as postbox])

(def postbox
  (postbox/make-postbox {:provider       {:name               :postmark
                                          :server-token       postmark-token   ; safe's secret, #secret :postbox/postmark-token
                                          :api-url            "https://api.postmarkapp.com"
                                          :connect-timeout-ms 10000
                                          :request-timeout-ms 30000}
                         ;; or, sending nothing: {:name :log :level :info}
                         :tags           #{:orders/confirmation :orders/shop-notification}
                         :background-job {:timeout-ms 60000 :attempts 8}}
                        {:log log :meter meter :wire wire}))

(cellar/migrate! pool (into app-tables (conj backroom/background-jobs-tables postbox/emails-table)) changes migrate-options)
(backroom/start-workers! pool (assoc backroom-settings :kinds [(postbox/make-background-job-kind postbox)] …))

;; in the transaction that marks the order paid
(postbox/queue-email! postbox transaction
                      {:key         (str "order-confirmation/" order-id)   ; never queued twice
                       :tag         :orders/confirmation
                       :to          ["Anna <anna@shop.example>"]
                       :from        "Shop <orders@shop.example>"
                       :reply-to    nil
                       :subject     "Your order 7"
                       :html        html                                   ; the app renders
                       :text        text
                       :metadata    {"order-id" (str order-id)}            ; back on its bounces
                       :attachments [{:name "invoice.pdf" :content-type "application/pdf" :bytes invoice-bytes}]
                       :stream      "outbound"})
;=> :queued

;; the provider's webhooks: <app>/postbox/delivery?token=… and <app>/postbox/inbound?token=…
(postbox/make-webhook-routes postbox
                             {:delivery-path  "/postbox/delivery"
                              :inbound-path   "/postbox/inbound"
                              :token          webhook-token        ; safe's secret, #secret :postbox/webhook-token, 16 characters or more
                              :inbound-domain "in.shop.example"}
                             {:pool              pool
                              :on-delivery-event {:orders/confirmation mark-address-undeliverable!}   ; (fn [event {:keys [transaction log]}])
                              :on-inbound-email  {"quotes" add-quote-request!}})
```

The job holds the email's row while it sends: a second run of the same
job waits, then sees it sent. A send is at least once: a provider that
took the email and a transaction that then fails sends it again on the
retry ([why](decisions/2026-10-23-sending-is-a-background-job-queued-in-the-callers-transaction.md)).
`postbox.emails` keeps the key, tag, state and message id, never an
address or a body: those wait in the background job, and go when backroom
forgets it ([why](decisions/2026-10-23-postbox-keeps-the-envelope-not-the-content.md)).

Postmark: 200 is sent; 422 of an invalid email (300) or an inactive
recipient (406) gives up at once; anything else runs again by backroom's
retries ([why](decisions/2026-10-23-the-provider-is-a-setting.md)).

The two tokens are safe's secrets: written out as text, they are refused,
and each is revealed only where it is used, Postmark's in its header, the
webhooks' once for its length. The webhooks: the token is compared in
constant time (`safe/equal?`), 401 without it; a
body that isn't a JSON object is 400; every other post is 200, whatever the
app's function does (one that throws is logged,
`:postbox/webhook-function-failed`). A bounce or spam complaint goes to the
function of its email's tag, `{:kind :tag :message-id :email-key :address
:bounce-type :inactive? :metadata}`; an inbound email to the function of
its mailbox (before `@` on `:inbound-domain`), `{:message-id :mailbox :from
:to :subject :text :html :stripped-reply :attachments}`. Each runs in one
transaction. **A function must be safe to run twice:** the provider may
post an event again
([why](decisions/2026-10-23-webhooks-hand-events-to-the-apps-functions.md)).
The server's body limit must take an inbound email with its attachments.

`make-postbox` declares, on the app's meter:
`postbox_emails_queued_total{tag}`, `postbox_emails_sent_total{tag}`,
`postbox_email_sends_failed_total{tag}`,
`postbox_webhook_events_total{webhook,outcome}`.

Every setting, option and email key is required: a missing or unknown one
is refused, named, with every other problem at once. What stays fixed, and
why, is in
[the decision](decisions/2026-10-23-every-value-that-sets-behaviour-is-a-setting.md).

## Tests

```sh
clojure -X:test     # the Postgres tests are skipped without a database

# with a throwaway Postgres:
initdb -D /tmp/postbox-pg -A trust -U "$USER"
pg_ctl -D /tmp/postbox-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/postbox-pg.log start
POSTBOX_TEST_PORT=5499 POSTBOX_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/postbox-pg stop
```

Postmark is a fake in the test, an HTTP server answering as Postmark's API
does ([why](decisions/2026-10-23-the-tests-send-to-the-log-and-a-fake-postmark.md)).
Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.

# groundwork-email

Email for a Clojure app on Postgres. The app writes an email; groundwork-email
queues it in the caller's transaction as a background job
([groundwork-background-jobs](https://github.com/p4ulcristian/groundwork-background-jobs)), so it exists only
if the caller's work commits, and sends it once, by Postmark or to the
log, retrying a failure, or gives up on one the provider refuses for
itself; its row says queued, sent or gave-up. An email's key is queued
once, ever. Bounces, spam complaints and inbound email come back on two
webhooks and are handed to the app's functions: a bounce by the email's
tag, every inbound email to one function, which routes by the mailbox.

```clojure
io.github.p4ulcristian/groundwork-email {:git/tag "v0.2.1" :git/sha "e9cf94b"}
```

Needs [groundwork-background-jobs](https://github.com/p4ulcristian/groundwork-background-jobs),
[groundwork-postgres](https://github.com/p4ulcristian/groundwork-postgres),
[groundwork-shaped-json](https://github.com/p4ulcristian/groundwork-shaped-json),
[groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) and
[groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) (the last three handed in),
[groundwork-settings](https://github.com/p4ulcristian/groundwork-settings) (the two tokens are its
secrets) and malli; Postmark is called with the JDK's own HTTP client. One public
namespace, `groundwork-email.api`.

## The api

| name | does |
|---|---|
| `make-mailer` `settings-shape` | a mailer from its settings and `{:log :meter :shaper :system-actor}`, every key required, checked first; declares groundwork-email's metrics |
| `emails-table` | `groundwork_email.emails` as a map for groundwork-postgres, to migrate with the app's tables |
| `make-background-job-kind` | the kind that sends, `:groundwork-email/send-email`, for groundwork-background-jobs's workers, run as the options' `:system-actor` |
| `queue-email!` `email-shape` | queues a written email in the caller's transaction; `:queued`, or `:queued-before` for a key queued before |
| `find-email` | an email's row: key, tag, state (`queued`, `sent`, `gave-up`), message id, why it gave up, queued and sent at |
| `make-webhook-routes` `webhook-settings-shape` | the delivery and inbound webhooks, as reitit data |

```clojure
(require '[groundwork-email.api :as email])

(def mailer
  (email/make-mailer {:provider       {:name               :postmark
                                          :server-token       postmark-token   ; groundwork-settings's secret, #secret :groundwork-email/postmark-token
                                          :api-url            "https://api.postmarkapp.com"
                                          :connect-timeout-ms 10000
                                          :request-timeout-ms 30000}
                         ;; or, sending nothing: {:name :log :level :info}
                         :tags           #{:orders/confirmation :orders/shop-notification}
                         :background-job {:timeout-ms 60000 :attempts 8}}
                        {:log log :meter meter :shaper shaper
                         :system-actor :email}))   ; whom the sending job runs as (groundwork-background-jobs's kind :system-actor)

(postgres/migrate! pool {:tables     (into app-tables (conj background-jobs/background-jobs-tables email/emails-table))
                       :extensions [] :functions [] :views []}
                 changes migrate-options)
(background-jobs/start-workers! pool (assoc background-jobs-settings :kinds [(email/make-background-job-kind mailer)] …))

;; in the transaction that marks the order paid
(email/queue-email! mailer transaction
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
                       :stream      "outbound"
                       :unsubscribe-url nil})                              ; or "https://…": RFC 8058's two headers
;=> :queued

(email/find-email pool (str "order-confirmation/" order-id))
;=> #:email{:key "order-confirmation/7" :tag "orders/confirmation" :state "gave-up"
;           :message-id nil :gave-up-reason "Inactive recipient (406)" :queued-at … :sent-at nil}

;; the provider's webhooks: <app>/email/delivery?token=… and <app>/email/inbound?token=…
(email/make-webhook-routes mailer
                             {:delivery-path  "/email/delivery"
                              :inbound-path   "/email/inbound"
                              :token          webhook-token        ; groundwork-settings's secret, #secret :groundwork-email/webhook-token, 16 characters or more
                              :inbound-domain "in.shop.example"}
                             {:run-in-transaction #(postgres/run-in-transaction! pool {:read-only? false :log log} %)   ; the app's, as whom it chooses
                              :on-delivery-event {:orders/confirmation mark-address-undeliverable!}   ; (fn [event {:keys [transaction log]}])
                              :on-inbound-email  route-inbound-email!})   ; every inbound email, (fn [email {:keys [transaction log]}]), routed by its :mailbox
```

The job holds the email's row while it sends: a second run of the same
job waits, then sees it sent or given up. A send is at least once: a provider that
took the email and a transaction that then fails sends it again on the
retry ([why](decisions/2026-10-23-sending-is-a-background-job-queued-in-the-callers-transaction.md)).
`groundwork_email.emails` keeps the key, tag, state, message id and why it
gave up, never an address or a body: those wait in the background job, and go when groundwork-background-jobs
forgets it ([why](decisions/2026-10-23-postbox-keeps-the-envelope-not-the-content.md)).
An app reads its emails' states by their keys (`find-email`), keeping no copy.

Postmark: 200 is sent; 422 of an invalid email (300) or an inactive
recipient (406) gives up at once; anything else runs again by groundwork-background-jobs's
retries ([why](decisions/2026-10-23-the-provider-is-a-setting.md)). A give-up
marks the row `gave-up`, its reason Postmark's name of the code and the
code (`"Invalid email request (300)"`, `"Inactive recipient (406)"`, never
Postmark's message, which names the address), in the job's own
transaction, which then commits with the job done; written to the job's
log, `:groundwork-email/gave-up` (error, Postmark's message with it).
A job that runs out of attempts on failures that may pass (a 5xx, a wrong
token) is dead in groundwork-background-jobs, its row still `queued`: run
again, it sends ([why](decisions/2026-10-26-a-send-that-gives-up-has-its-own-state.md)).

An email's `:unsubscribe-url`, an `https://` address or nil, is sent as
RFC 8058's two headers, `List-Unsubscribe: <url>` and
`List-Unsubscribe-Post: List-Unsubscribe=One-Click` (Postmark's `Headers`);
the log provider writes `:unsubscribe-url? true` in its place. No other
header can be set
([why](decisions/2026-10-26-an-email-has-an-unsubscribe-url.md)).

The two tokens are groundwork-settings's secrets: written out as text, they are refused,
and each is revealed only where it is used, Postmark's in its header, the
webhooks' once for its length. The webhooks: the token, `?token=` read as
form text by the JDK's URLDecoder (given twice, none;
[why](decisions/2026-10-27-the-webhooks-token-is-read-from-the-query-as-form-text.md)), is compared in
constant time (`settings/equal?`), 401 without it; a
body that isn't a JSON object is 400; every other post is 200, whatever the
app's function does (one that throws is logged,
`:groundwork-email/webhook-function-failed`). A bounce or spam complaint goes to the
function of its email's tag, `{:kind :tag :message-id :email-key :address
:bounce-type :inactive? :metadata}`; every inbound email to the one
`:on-inbound-email` function, `{:message-id :mailbox :from :to :subject
:text :html :stripped-reply :attachments}`, which routes it by its
`:mailbox`: before `@` of its recipient on `:inbound-domain`, lower case,
else Postmark's MailboxHash, else nil
([why](decisions/2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md)). Each runs in one
transaction the app's `:run-in-transaction` makes: the app says as whom
it runs, so rows behind its gates are reached only as someone they allow
(groundwork runs it as groundwork-access's system actor `:email`;
[why](decisions/2026-10-23-the-app-makes-the-webhooks-transaction.md)).
**A function must be safe to run twice:** the provider may
post an event again
([why](decisions/2026-10-23-webhooks-hand-events-to-the-apps-functions.md)).
The server's body limit must take an inbound email with its attachments.

`make-mailer` declares, on the app's meter:
`groundwork_email_emails_queued_total{tag}`, `groundwork_email_emails_sent_total{tag}`,
`groundwork_email_email_sends_failed_total{tag}` (every failed attempt, a give-up too),
`groundwork_email_emails_gave_up_total{tag}`,
`groundwork_email_webhook_events_total{webhook,outcome}`.

Every setting, option and email key is required: a missing or unknown one
is refused, named, with every other problem at once. What stays fixed, and
why, is in
[the decision](decisions/2026-10-23-every-value-that-sets-behaviour-is-a-setting.md).

## Tests

```sh
clojure -X:test     # the Postgres tests are skipped without a database

# with a throwaway Postgres:
initdb -D /tmp/email-pg -A trust -U "$USER"
pg_ctl -D /tmp/email-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/email-pg.log start
EMAIL_TEST_PORT=5499 EMAIL_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/email-pg stop
```

Postmark is a fake in the test, an HTTP server answering as Postmark's API
does ([why](decisions/2026-10-23-the-tests-send-to-the-log-and-a-fake-postmark.md)).
Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.

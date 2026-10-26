# Inbound mail goes to one function of the app

> The user's decision, 2026-10-26, from Iron Rainbow's port onto groundwork-3. Since v0.2.0. Changes in part [The webhooks hand events to the app's functions; always 200](2026-10-23-webhooks-hand-events-to-the-apps-functions.md): an inbound email went to the function of its mailbox in `:on-inbound-email {mailbox f}`.

**What was wrong:** `make-webhook-routes` took `:on-inbound-email` as a map, `{"quotes" add-quote-request!}`, and an inbound email whose mailbox the map didn't name was logged, `:groundwork-email/no-function`, and dropped. Iron Rainbow's inbound mail is addressed to a different mailbox for every shop, each shop's quote alias: the mailboxes are rows of the app, made while it runs, and a map fixed at the start can't name them.

**Decision:** `:on-inbound-email` is one function, `(fn [email context])`, and every inbound email is handed to it, in one transaction the app's `:run-in-transaction` makes, as before. The email carries its `:mailbox`, as before: the part before `@` of its recipient on `:inbound-domain` (the original recipient, then To, then Cc), lower case; else Postmark's MailboxHash; else nil. The app routes by it.

```clojure
(email/make-webhook-routes mailer webhook-settings
                           {:run-in-transaction #(postgres/run-in-transaction! pool {:read-only? false :log log} %)
                            :on-delivery-event  {:orders/confirmation mark-address-undeliverable!}
                            :on-inbound-email   (fn [{:keys [mailbox] :as email} {:keys [transaction]}]
                                                  (if-let [shop (find-shop-by-quote-alias transaction mailbox)]
                                                    (add-quote-request! transaction shop email)
                                                    (keep-unrouted-email! transaction email)))})
```

A map in its place is refused, `:groundwork-email/bad-options`, `{:on-inbound-email ["should be the one function every inbound email is handed to, (fn [email context])"]}`. An app that takes no inbound email hands one that ignores it. The inbound webhook's outcomes are `handled` or `failed` (and `refused`, `bad-body`); `no-function` is a delivery event's only.

**Why:** which mailbox is whose is the app's data, read in the transaction the email is handled in; the library can't know it at the start. One function is one way to route: the app's own code, with the app's rows. An email to no mailbox the app knows reaches the app too, which decides what to do with it, rather than a log line.

**The price:** every caller of v0.1 changes `:on-inbound-email`. An app with fixed mailboxes writes its own `case` over `:mailbox`.

**Instead of:**
- **B, the map kept and a function for every other mailbox:** two ways to route in the library, and a mailbox in the map hides a shop alias of the same name
- **C, Iron Rainbow moving to one fixed mailbox with a plus tag** (`quotes+<shop>@…`): every address and forwarding rule shops already have would break

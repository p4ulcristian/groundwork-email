# The webhooks hand events to the app's functions; always 200

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** `make-webhook-routes` makes two POST routes as reitit data, at the settings' `:delivery-path` and `:inbound-path`; the provider's webhook URL carries `?token=<the settings' :token>`.

- **The token** is compared in constant time (both sides hashed, then `MessageDigest/isEqual`); none or another is 401, written at `:warn`, the body never read. A body that isn't a JSON object is 400.
- **A delivery event**, a Bounce or SpamComplaint, is normalized (`:kind :tag :message-id :email-key :address :bounce-type :inactive? :metadata`; `:email-key` postbox's, found by the message id) and handed to the app's function for its tag: `:on-delivery-event {:orders/confirmation f}`, a map, the tags the settings' (checked). Any other record (a delivery, an open) is ignored.
- **An inbound email** is normalized (`:message-id :mailbox :from :to :subject :text :html :stripped-reply :attachments` with their bytes) and handed to the function of its mailbox: `:on-inbound-email {"quotes" f}`. The mailbox is the part before `@` of its recipient on the settings' `:inbound-domain` (the original recipient, then To, then Cc), lower case; else Postmark's MailboxHash (`inbound+quotes@…`), which works before the domain's MX does.
- **Each function runs in one transaction** on the app's pool, as `(f event {:transaction :log})`: what it writes is kept whole or not at all.
- **Always 200** once the token and body pass: a function that throws is written at `:error`, `:postbox/webhook-function-failed`; no function for the tag or mailbox is `:info`, `:postbox/no-function`.
- **A function must be safe to run twice**: the provider may post an event again; the event's `:message-id` tells it.

Since v0.1.0.

**Why:** Iron Rainbow's rules: answering an error for the app's own mistake makes Postmark post the event again and again; a token in the URL is what Postmark's webhooks carry. A map of functions, not multimethods: the app's whole dispatch is one value, checked at the start, and house passes it in from the app map. The inbound mailbox by domain: a mail to `quotes@gmail.com` and the app's address must not land in the app's `quotes`.

**The price:** an event the app's function failed on is not posted again: the log is its only trace. The provider's body limit and the server's must match (an inbound email with attachments can be tens of megabytes).

**Instead of:**
- multimethods (Iron Rainbow's): open dispatch anywhere in the code, seen only by reading it all
- answering 500 when the function throws: retries until the provider gives up, the same failure each time
- HTTP Basic auth: Postmark allows it; one token in the query is what both of Iron Rainbow's webhooks use, and one way to check

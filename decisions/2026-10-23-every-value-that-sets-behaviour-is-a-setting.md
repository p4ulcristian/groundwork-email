# Every value that sets behaviour is a setting; no hidden defaults

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> **Changed in part** by [Inbound mail goes to one function of the app](2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md) (2026-10-26, v0.2.0): `:on-inbound-email` is one function, not a map; `:no-function` is a delivery event's only. By [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md) (2026-10-26, v0.2.0): the line events `:groundwork-email/gave-up` and `:groundwork-email/gave-up-before`, and the reasons' texts, fixed. By [An email has an unsubscribe URL](2026-10-26-an-email-has-an-unsubscribe-url.md) (2026-10-26, v0.2.0): an email's `:unsubscribe-url`; RFC 8058's two headers, fixed.

**Decision:** as in every manor library, every value that sets how postbox behaves is written out by the app, checked by malli before anything is queued or served, the maps closed, a missing or unknown key refused, named, with every other problem at once; no function fills in an argument.

- **`make-postbox`'s settings**, `settings-shape`: `:provider` (each provider's keys), `:tags`, `:background-job {:timeout-ms :attempts}` (`:postbox/bad-settings`); its options `{:log :meter :wire}` (`:postbox/bad-options`).
- **An email**, `email-shape`: every key, nil or empty for none (`:postbox/bad-email`).
- **`make-webhook-routes`' settings**, `webhook-settings-shape`: `:delivery-path :inbound-path :token :inbound-domain`; its options `:pool :on-delivery-event :on-inbound-email`, `{}` for none.

**What stays fixed, and why:**

- **The background job kind's name, `:postbox/send-email`, its params' shape**: postbox's own, read by its own run.
- **Postmark's request** (`/email`, its headers, its JSON) and **the two error codes that give up** (300, 406): what Postmark says; [the provider decision](2026-10-23-the-provider-is-a-setting.md).
- **The webhooks' answers** (200 `ok`, 401, 400, their texts, `no-store`) and **the token in `?token=`**: what the provider reads.
- **The line events** (`:postbox/logged-not-sent`, `:postbox/sent-before`, `:postbox/webhook-refused`, `:postbox/webhook-function-failed`, `:postbox/no-function`) and their levels, but the log provider's own `:level`: what postbox says.

**Why:** a value left to a default is a value the app doesn't show ("Every value that sets behaviour is a setting", mailroom).

**Instead of:**
- Postmark's URL fixed in the code: the tests point it at a fake, and a region or a proxy is the app's
- the stream as a setting: one app sends transactional and broadcast mail; it is the email's

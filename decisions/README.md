# postbox decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

postbox is groundwork's email (groundwork: the libraries and the composer of the same name that puts them together). Those of 2026-10-23 were decided as v0.1.0 was built, from Iron Rainbow's email code and what it taught; those of 2026-10-26 by the user, as Iron Rainbow moved onto groundwork-3 (v0.2.0).

- [postbox is its own library](2026-10-23-postbox-is-its-own-library.md)
- [Sending is a background job, queued in the caller's transaction; a key is queued once](2026-10-23-sending-is-a-background-job-queued-in-the-callers-transaction.md), changed in part by [The sending job runs as the system actor the app names](2026-10-23-the-sending-job-runs-as-the-system-actor-the-app-names.md) and [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md)
- [postbox keeps an email's envelope, never its content](2026-10-23-postbox-keeps-the-envelope-not-the-content.md), changed in part by [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md)
- [The provider is a setting: Postmark or the log](2026-10-23-the-provider-is-a-setting.md), changed in part by [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md) and [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md)
- [The app writes the email; postbox takes it whole](2026-10-23-the-app-writes-the-email.md), changed in part by [An email has an unsubscribe URL](2026-10-26-an-email-has-an-unsubscribe-url.md)
- [The webhooks hand events to the app's functions; always 200](2026-10-23-webhooks-hand-events-to-the-apps-functions.md), changed in part by [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md), [The app makes the transaction a webhook's function runs in](2026-10-23-the-app-makes-the-webhooks-transaction.md) and [Inbound mail goes to one function of the app](2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md), changed in part by [Inbound mail goes to one function of the app](2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md), [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md) and [An email has an unsubscribe URL](2026-10-26-an-email-has-an-unsubscribe-url.md)
- [The app passes its log, its meter and its wire](2026-10-23-the-app-passes-its-log-meter-and-wire.md), changed in part by [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md)
- [The tests send to the log and to a fake Postmark, on a real Postgres and real workers](2026-10-23-the-tests-send-to-the-log-and-a-fake-postmark.md), changed in part by [Inbound mail goes to one function of the app](2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md)
- [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md), changed in part by [The webhooks' token is read from the query as form text](2026-10-27-the-webhooks-token-is-read-from-the-query-as-form-text.md)
- [The app makes the transaction a webhook's function runs in](2026-10-23-the-app-makes-the-webhooks-transaction.md)
- [The sending job runs as the system actor the app names](2026-10-23-the-sending-job-runs-as-the-system-actor-the-app-names.md)
- [Inbound mail goes to one function of the app](2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md)
- [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md)
- [An email has an unsubscribe URL](2026-10-26-an-email-has-an-unsubscribe-url.md)
- [The webhooks' token is read from the query as form text, by the JDK](2026-10-27-the-webhooks-token-is-read-from-the-query-as-form-text.md) (v0.2.1)

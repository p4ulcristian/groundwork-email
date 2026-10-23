# postbox decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

postbox is manor's email (manor: house and the libraries it puts together). All were decided as v0.1.0 was built, from Iron Rainbow's email code and what it taught.

- [postbox is its own library](2026-10-23-postbox-is-its-own-library.md)
- [Sending is a background job, queued in the caller's transaction; a key is queued once](2026-10-23-sending-is-a-background-job-queued-in-the-callers-transaction.md)
- [postbox keeps an email's envelope, never its content](2026-10-23-postbox-keeps-the-envelope-not-the-content.md)
- [The provider is a setting: Postmark or the log](2026-10-23-the-provider-is-a-setting.md)
- [The app writes the email; postbox takes it whole](2026-10-23-the-app-writes-the-email.md)
- [The webhooks hand events to the app's functions; always 200](2026-10-23-webhooks-hand-events-to-the-apps-functions.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md)
- [The app passes its log, its meter and its wire](2026-10-23-the-app-passes-its-log-meter-and-wire.md)
- [The tests send to the log and to a fake Postmark, on a real Postgres and real workers](2026-10-23-the-tests-send-to-the-log-and-a-fake-postmark.md)

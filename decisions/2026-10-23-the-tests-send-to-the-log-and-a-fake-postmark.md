# The tests send to the log and to a fake Postmark, on a real Postgres and real workers

> **Changed in part** by [Inbound mail goes to one function of the app](2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md) (2026-10-26, v0.2.0): every inbound email is handed to the one function. Since v0.2.0 they also prove: a refused email's row gave-up with its reason, committed; the unsubscribe headers in Postmark's request; v0.1.9's table moved by its map.

**Decision:** postbox's tests queue emails on a throwaway Postgres (`POSTBOX_TEST_PORT`, `POSTBOX_TEST_USER`; without them those tests say they were skipped) and send them by backroom's real workers: by the `:log` provider, and by the `:postmark` provider pointed (`:api-url`) at an HTTP server in the test that answers `POST /email` as Postmark's API does and keeps what it was sent. They prove: queued in a transaction rolled back, nothing is sent; committed, sent once; a key queued again, nothing; Postmark's request (the token, every field, the attachment's base64); a 503 retried and sent once; a 422 of an invalid email given up after one attempt; a bounce handed to its tag's function with postbox's key and the metadata; a function that throws, answered 200 and logged; an inbound email handed to its mailbox's function; the token refused. Since v0.1.0.

**Why:** the real Postmark needs a server token and sends real email; its test mode (`POSTMARK_API_TEST`) checks the request but sends nothing back to test the rest. What postbox must get right is its request and how it takes each answer; a fake that answers as Postmark's documentation says shows both, and fails a request of the wrong shape.

**The price:** the fake knows Postmark only as its documentation says; a change in Postmark's API isn't seen until production.

**Instead of:**
- Postmark's test token against the real API: a network and a secret for every test run
- only the `:log` provider: Postmark's request and answers never tested

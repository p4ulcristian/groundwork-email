# Sending is a background job, queued in the caller's transaction; a key is queued once

> **Changed in part** by [The sending job runs as the system actor the app names](2026-10-23-the-sending-job-runs-as-the-system-actor-the-app-names.md) (2026-10-23, built on tidy-up, not released): the kind carries the `:system-actor` of `make-postbox`'s options.

> **Changed in part** by [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md) (2026-10-26, v0.2.0): the job marks the row `gave-up` with the reason when the provider refuses the email itself, in its one transaction, as it marks `sent`; a second run sees it gave up and sends nothing.

**Decision:** `(queue-email! postbox transaction email)` writes the email's row in `postbox.emails` and queues a background job of postbox's kind, `:postbox/send-email`, both in the caller's transaction: the email exists if and only if the caller's work commits. The email's `:key` is its own, the app's (`"order-confirmation/<order id>"`): the row's primary key, so a key is queued once, ever; a second `queue-email!` of it queues nothing and answers `:queued-before`. backroom runs the job: in its one transaction it holds the row (`FOR UPDATE`), sends the email unless the row says sent, and marks it sent with the provider's message id. A failure runs again later, by backroom's retries; the kind's time limit and attempts are the settings' `:background-job`. The app adds the kind (`make-background-job-kind`) to its workers' kinds. Since v0.1.0.

**Why:** Iron Rainbow's lesson (above). The key is postbox's row and not backroom's `:key`: backroom forgets done jobs after `:forget-done-after-ms`, and an order's confirmation must not go out again a week later. Holding the row makes a second run of the same job (a server that stopped after sending, its job queued again once stuck) wait, then see it sent.

**The price:** a send is at least once, not exactly once: a provider that took the email, then a transaction that fails to commit (the database gone that moment), sends it again on the retry. Postmark has no idempotency key that would stop it. The counter of emails queued counts one whose transaction is rolled back later, as backroom's does.

**Instead of:**
- sending in the request: a slow provider holds the request, a failed one fails the order, a deploy loses the email
- backroom's `:key` as the email's key: forgotten with the done job
- a key made by postbox (a hash of the email): the same email to the same person twice on purpose (a second reminder) would be refused; the app knows what "the same email" means

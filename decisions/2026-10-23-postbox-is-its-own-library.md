# postbox is its own library

**Decision:** email is manor's library postbox: an email the app has written is queued in the caller's transaction as a background job (backroom), sent once by a provider (Postmark, or the log), its envelope kept in a table of postbox's; bounces, spam complaints and inbound email come back on two webhooks, handed to the app's functions. It is its own repository, `io.github.p4ulcristian/postbox`, public, MIT, topic `manor`; house takes it from git and puts its settings, table, background job kind and webhook routes in. One public namespace, `postbox.api`. It needs backroom, cellar, wire, logbook and meter, and malli. Since v0.1.0.

It is Iron Rainbow's email code made general: its Postmark client and log-only mode, its delivery and inbound webhooks, and its order emails sent as jobs in the order's transaction. Nothing of Iron Rainbow's is in it: no template, address, domain or token.

**Why:** Iron Rainbow sent its order emails from a `future` after the order was written; a deploy in between lost them without a trace. As jobs in the order's transaction they exist if and only if the order does, and are retried. Every app that sends email needs the same, and the same webhooks; a library writes them once.

**Instead of:**
- email in house: house puts libraries together; email is a question of its own
- a Postmark client library: one POST, and the rest (the queue, the once, the webhooks) is what matters
- the templates in postbox: what an email says is the app's, in its language (dictionary's texts)

# The app makes the transaction a webhook's function runs in

> Since v0.1.3. Changes in part [The webhooks hand events to the app's functions; always 200](2026-10-23-webhooks-hand-events-to-the-apps-functions.md): the functions ran in a transaction postbox opened on the app's pool.

**Decision:** `make-webhook-routes` takes `:run-in-transaction`, `(fn [f])` running `(f transaction)` in one transaction of the app's and returning what `f` returns, in place of `:pool` (now refused, named). Each event the app has a function for is handled in one such transaction: postbox looks up the email's key in it (`postbox.emails`, a table without gates), then calls the function with it. postbox doesn't know who the transaction runs as. groundwork hands `#(doorman/run-as-system! pool :postbox {:read-only? false} %)`: the function runs as doorman's system actor `:postbox`, which the gates allow exactly what the app's sentences give `[:system :postbox]` (doorman's [A named system actor is allowed what its sentences give it](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md)).

**Why:** on the app's own role with nobody asking, every row behind doorman's gates was invisible to a webhook's function: a bounce couldn't mark the shop's address row it was about. Who the function runs as is the app's to say (postbox knows nothing of doorman); handing a function that makes the transaction says it without postbox taking a library it doesn't need.

**Instead of:**
- the pool, with the app's function opening its own transaction as whom it needs: every function would repeat it, and postbox's own lookup would run outside it
- postbox taking doorman: a library knows nothing of who uses it, and an app without doorman would have to bring it

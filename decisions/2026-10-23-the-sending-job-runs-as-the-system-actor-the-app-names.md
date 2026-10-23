# The sending job runs as the system actor the app names

> Follows backroom's [A job kind names its system actor, and its one transaction runs as it](https://github.com/p4ulcristian/backroom/blob/tidy-up/decisions/2026-10-23-a-job-kind-names-its-system-actor.md) (the user's decision, 2026-10-23), which makes `:system-actor` a required key of every job kind, postbox's too. Changes in part [Sending is a background job, queued in the caller's transaction](2026-10-23-sending-is-a-background-job-queued-in-the-callers-transaction.md). Built on tidy-up, not released.

**Decision:** `make-postbox` takes `:system-actor`, a required option beside `:log`, `:meter` and `:wire`: the named system actor the sending job runs as. `make-background-job-kind` writes it as the kind's `:system-actor`, so backroom opens the job's one transaction as it (groundwork: doorman's `run-as-system!`).

```clojure
(postbox/make-postbox settings {:log log :meter meter :wire wire :system-actor :postbox})
```

**Why:** who a transaction runs as is the app's to say, as it already is for the webhooks (`:run-in-transaction`); postbox knows no doorman. The job reaches `postbox.emails`, a table without gates, so the actor needs no sentence for it.

**Instead of:** postbox naming `:postbox` itself: a name the app's sentences must match, fixed in a library that doesn't know them.

## Settled while building

Choices made while building, not asked:

1. **An option, not a setting:** the actor is a name in the app's code (its sentences), not a value of an environment, as `:run-in-transaction` is an option of the webhooks.
2. **groundwork names `:postbox` for both,** the sending job and the webhooks.
3. **postbox takes backroom at its tidy-up commit** (`6b720e7…`); its tests open the job's transaction on the pool, as `:run-in-transaction-as`.

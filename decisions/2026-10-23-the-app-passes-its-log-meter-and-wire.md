# The app passes its log, its meter and its wire

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> **Changed in part** by [A send that gives up has its own state](2026-10-26-a-send-that-gives-up-has-its-own-state.md) (2026-10-26, v0.2.0): one more counter, `groundwork_email_emails_gave_up_total{tag}`.

**Decision:** `make-postbox` takes the app's log (logbook's), meter (meter's) and wire (wire's) under `:log`, `:meter` and `:wire`, required. It declares postbox's metrics there, the settings' `:tags` as the tag label's set: `postbox_emails_queued_total{tag}`, `postbox_emails_sent_total{tag}`, `postbox_email_sends_failed_total{tag}` (every failed attempt, a give-up too) and `postbox_webhook_events_total{webhook,outcome}` (`delivery`, `inbound`; `handled`, `no-function`, `ignored`, `failed`, `refused`, `bad-body`). The wire keeps the email in its job's params, writes Postmark's JSON and reads the webhooks'. The job's run writes to the job's log, backroom's, which knows the job; the webhooks to the app's. A log logbook didn't make, or a meter meter didn't make, is refused at `make-postbox`. Since v0.1.0.

**Why:** as every manor library: the app decides how lines are written, serves the metrics, and makes one wire ([meter's decision](https://github.com/p4ulcristian/meter/blob/main/decisions/2026-10-23-a-library-takes-the-meter-as-meter.md)). Sent against failed by tag shows which email is in trouble; refused webhooks, someone guessing the token.

**Instead of:**
- a histogram of the provider's time: backroom's `backroom_job_run_seconds{kind="postbox/send-email"}` already is one
- jsonista directly: a second JSON reader with limits of its own, beside the app's wire

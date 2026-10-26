# A send that gives up has its own state

> The user's decision, 2026-10-26, from Iron Rainbow's port onto groundwork-3. Since v0.2.0. Changes in part [postbox keeps an email's envelope, never its content](2026-10-23-postbox-keeps-the-envelope-not-the-content.md) (a third state and the reason), [The provider is a setting](2026-10-23-the-provider-is-a-setting.md) (a refusal is no longer groundwork-background-jobs's give-up), [Sending is a background job, queued in the caller's transaction](2026-10-23-sending-is-a-background-job-queued-in-the-callers-transaction.md) (the job marks it gave-up too) and [The app passes its log, its meter and its wire](2026-10-23-the-app-passes-its-log-meter-and-wire.md) (one more counter).

**What was wrong:** `groundwork_email.emails.email__state` was `queued` or `sent`. A send the provider refused for itself (Postmark's 422 with `ErrorCode` 300, an invalid email, or 406, an inactive recipient) threw groundwork-background-jobs's give-up exception: the job was dead, its transaction rolled back, and the row stayed `queued` for good. An app reading the row (Iron Rainbow's order page) saw an email waiting that would never go.

**Decision:** the state is `queued`, `sent` or `gave-up`, and the row has `email__gave_up_reason`, text, set exactly on a gave-up one (a check: `(email__state = 'gave-up') = (email__gave_up_reason IS NOT NULL)`). The reason is Postmark's name for its error code and the code: `"Invalid email request (300)"`, `"Inactive recipient (406)"`. `find-email` returns both:

```clojure
(email/find-email pool "order-confirmation/7")
;=> #:email{:key "order-confirmation/7" :tag "orders/confirmation" :state "gave-up" :message-id nil
;           :gave-up-reason "Inactive recipient (406)" :queued-at #inst "…" :sent-at nil}
```

**How it commits:** groundwork-background-jobs runs a job and marks it done in one transaction; a run that throws, the give-up exception too, rolls that transaction back, and the failure mark (dead, for a give-up) is written afterwards on the pool, outside it. A gave-up written in the job's transaction before a throw would be rolled back; one written on the pool from inside the run would wait for the row the run itself holds (`FOR UPDATE`). So a refusal is an answer, not a throw: the provider returns `{:message-id nil :gave-up-reason "Inactive recipient (406)" :provider-message "…"}`, the run writes `gave-up` and the reason, exactly as it writes `sent`, and returns; the job's transaction commits the row with the job marked done. Written to the job's log, `:error :groundwork-email/gave-up`, with Postmark's own message; counted, `groundwork_email_emails_gave_up_total{tag}`, and in `groundwork_email_email_sends_failed_total{tag}` as before. A second run of the same job waits for the row, then sees it gave up (`:info :groundwork-email/gave-up-before`) and sends nothing.

The app reads the row by its email's key, so its own records (an order's "confirmation: sent", "gave up: Inactive recipient") show the state without keeping a copy.

**Why:** the state is the email's, and the row is where it is kept; a give-up that leaves the row `queued` makes the row false. The job's own transaction is the one place where the row is held and where the write commits with the job's end, the same as `sent`.

**The price:**
- a gave-up email's job is done, not dead: groundwork-background-jobs counts it `done` and `list-dead-background-jobs` doesn't show it; groundwork-email's line, counter and row do. Its content goes when the done job is forgotten (`:forget-done-after-ms`), as a sent one's does, where a dead job kept it until it was deleted. Sending it again is the app's: queued again under a new key.
- a job that runs out of attempts on failures a later attempt may pass (a 5xx, a 429, a wrong token) is still dead in groundwork-background-jobs and its row still `queued`: nothing was refused, and run again (`run-background-job-again-now!`) once the cause is fixed, it sends.
- the email's row whose job finds it gone is still groundwork-background-jobs's give-up: there is no row to mark.

**Instead of:**
- **B, calling the app back on sent or gave-up, so it keeps its own status:** two places for one state, one of them wrong whenever a callback fails
- **C, the app sending directly where a person waits for the result:** the request waits on the provider, and the email is no longer queued in the caller's transaction

## Settled while building

Choices made while building, not asked:

1. **The reason is Postmark's name for the code, never its message.** Postmark's `Message` names the address ("Found inactive addresses: anna@…"); the row keeps no address. The message goes to the job's log only, `:groundwork-email/provider-message`.
2. **A counter of its own,** `groundwork_email_emails_gave_up_total{tag}`: with the job done, groundwork-background-jobs's `job_runs_total{outcome="gave-up"}` no longer counts it.
3. **One column, the reason;** no time of the give-up beside it.
4. **The check** that a reason is on a gave-up row and on no other.
5. **The table moves by its map,** no change file: the reason column is optional, so it is added at once; the state's enum check and the new check are added `NOT VALID` and proven in the slow part (a test migrates v0.1.9's table with rows).

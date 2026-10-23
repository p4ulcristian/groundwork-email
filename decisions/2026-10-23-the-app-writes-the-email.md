# The app writes the email; postbox takes it whole

**Decision:** the app renders (its templates, its language) and hands postbox a written email, every key required, nil or empty where it has none (`email-shape`):

| key | | |
|---|---|---|
| `:key` | text | the email's own, never queued twice |
| `:tag` | one of the settings' `:tags` | counted by, and its bounces handed on by |
| `:to` | 1 to 50 addresses | `"Anna <anna@shop.example>"` too |
| `:from` `:subject` | text | |
| `:reply-to` | text or nil | |
| `:html` `:text` | text or nil | not both nil |
| `:metadata` | text to text, `{}` for none | back on its bounces; at most 10, names at most 20 characters, values at most 80 (Postmark's) |
| `:attachments` | `[{:name :content-type :bytes}]`, `[]` for none | |
| `:stream` | text | the provider's message stream, `"outbound"` |

The tags are a closed set in the settings, namespaced keywords; an email with another is refused when queued (`:postbox/bad-email`), every problem at once. postbox checks no address: the provider does, and refuses the email (giving up). Since v0.1.0.

**Why:** what an email says is the app's: postbox rendering would mean postbox's templates. Rendered when queued, the email is what the app meant at that moment (the order as it was paid), not what the rows say when the job runs. Every key written out says, for each email, that it has no reply address or no attachment, rather than forgetting one. The tags closed: a metric's label set, and the webhooks' dispatch, both checked when the app starts.

**The price:** the rendered email, attachments too, is the job's params: a large attachment is a large row until the job is forgotten. An app that wants the email rendered at send time (the latest status) queues its own background job that renders and calls `queue-email!` in its transaction.

**Instead of:**
- the email's id and a template name, rendered in the job (Iron Rainbow's): postbox would need the app's templates and rows
- open tags: any text as a label, and a bounce's function never checked against what is sent

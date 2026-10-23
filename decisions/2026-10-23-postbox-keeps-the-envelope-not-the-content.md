# postbox keeps an email's envelope, never its content

**Decision:** `postbox.emails` (`emails-table`, a map for cellar, in postbox's schema) keeps an email's key, tag, state (`queued`, `sent`), the provider's message id (indexed: a bounce finds its email by it), when it was queued and when sent. Its addresses, subject, bodies, metadata and attachments wait only in its background job's params, kept by the app's wire (the attachments as base64), and go when backroom forgets the done job. `find-email` reads a row. Since v0.1.0.

**Why:** addresses and bodies are personal data; kept forever in a table of postbox's, every email would be a copy the app must find and delete for a person who asks. What postbox needs after the send is the key (never twice), the tag (a bounce's function) and the message id (which email bounced): none of it personal. The app keeps what it sent in its own rows if it must.

**The price:** an email can't be sent again from postbox once its job is forgotten: the app queues it again under a new key. A dead job keeps its content until it is run again or deleted.

**Instead of:**
- the whole email in postbox's table: a resend button, and every address kept forever
- nothing of postbox's, only backroom's job: no key that lasts, no message id for a bounce

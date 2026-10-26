# An email has an unsubscribe URL

> The user's decision, 2026-10-26, from Iron Rainbow's port onto groundwork-3. Since v0.2.0. Changes in part [The app writes the email; postbox takes it whole](2026-10-23-the-app-writes-the-email.md): one more key of `email-shape`.

**What was wrong:** `email-shape` is a closed map without headers. An email a reader can stop (Iron Rainbow's reminders and shop notifications) needs RFC 8058's one-click unsubscribe, which Gmail and Yahoo require of a sender of more than 5,000 emails a day, and the library had no way to send it.

**Decision:** an email has `:unsubscribe-url`, required, an `https://` address or nil. For a non-nil one, groundwork-email sends RFC 8058's two headers itself, through Postmark's `Headers`:

```
List-Unsubscribe: <https://shop.example/unsubscribe/7f3a?list=orders>
List-Unsubscribe-Post: List-Unsubscribe=One-Click
```

```clojure
(email/queue-email! mailer transaction
                    {:key             (str "pickup-reminder/" order-id)
                     …
                     :stream          "outbound"
                     :unsubscribe-url (str "https://shop.example/unsubscribe/" token)})   ; nil for none
```

An address that isn't `https://`, or that has a space, a comma, a quote or an angle bracket (which would break the header out of its `<…>`), is refused, `:groundwork-email/bad-email`. The log provider writes `:unsubscribe-url? true` (or false) in its place, not the address. No other header can be set: `:headers` is refused as an unknown key. The address must take RFC 8058's POST of `List-Unsubscribe=One-Click`: that is the app's route.

**Why:** the two headers are a pair with fixed words; written once in the library, every app sends them right. The address is the app's (its route, its token per reader), the headers' spelling the RFC's.

**The price:** an app that needs another header asks for it as a decision of its own.

**Instead of:**
- **A, a free `:headers` map:** an app could send a header that breaks the mail (a second `From`, a `Content-Type`), and each app spells the pair itself
- **C, no header:** a mail program shows no unsubscribe button, and a sender past Gmail's and Yahoo's 5,000 a day breaks their rules

## Settled while building

Choices made while building, not asked:

1. **The log provider writes whether there is one, not the address:** the address usually carries a reader's token.
2. **A job queued by v0.1 has no `:email/unsubscribe-url` in its params:** the params' shape takes it as optional (the email's shape doesn't), so such a job is sent without the headers rather than failing on its shape.

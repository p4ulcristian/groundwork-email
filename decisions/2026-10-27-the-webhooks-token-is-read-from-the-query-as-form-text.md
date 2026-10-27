# The webhooks' token is read from the query as form text, by the JDK

> The user's decision, 2026-10-27 (keep groundwork minimal; replace query strings read by regex with the JDK's URLDecoder, as UTF-8, or ring.util.codec where ring is on the library's path). Since v0.2.1. Changes in part [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md): how the request's `?token=` is read before it is compared.

**Decision:** the webhooks read `?token=` from the query string as `application/x-www-form-urlencoded` text: the query split by `&`, each pair at its first `=`, its name and its value each decoded by the JDK's `URLDecoder` as UTF-8 (`%2D` a `-`, `+` a space). The token is the value of the one pair named `token`:

- a pair with a bad %-escape (`%zz`) is left out, the others read;
- `token` given twice is no token: 401, as none;
- `tok%65n=…` is the token, as a name is decoded too.

It is then compared with the setting in constant time, as before.

**Why:** the regex found the first `token=…` and decoded its value; a bad escape in it threw out of the handler instead of answering 401, and a second token was never seen. A query string's format is form text, which the JDK decodes; ring.util.codec is not on groundwork-email's path (it needs no Ring), and `java.net.URI` adds nothing to a query string the server has already split from its address.

**The price:** a query naming `token` twice is refused, where the first was compared; a token with a bad escape is a 401, where the handler threw.

**Instead of:**
- **the regex**: the first match, a throw on a bad escape
- **the first of two tokens**: a guess at which one the sender meant; Postmark sends one

# Secrets are safe's wrapped secrets

> **Changed in part** by [The webhooks' token is read from the query as form text, by the JDK](2026-10-27-the-webhooks-token-is-read-from-the-query-as-form-text.md) (2026-10-27, v0.2.1): the request's `?token=` is read by URLDecoder, a bad escape left out, a token given twice none.

> Changes in part [The provider is a setting](2026-10-23-the-provider-is-a-setting.md) (Postmark's `:server-token` is `safe/secret`) and [The webhooks hand events to the app's functions](2026-10-23-webhooks-hand-events-to-the-apps-functions.md) (the webhooks' `:token` is `safe/secret`, compared by `safe/equal?`). Since v0.1.1, on [safe](https://github.com/p4ulcristian/safe) v0.1.0.

**Decision:** postbox's two tokens are secrets of safe's:

- **Postmark's `:server-token`** is `safe/secret` (`#secret :postbox/postmark-token` in the app's settings file). It is revealed in one place, the `X-Postmark-Server-Token` header of each send (`postbox.providers`).
- **The webhooks' `:token`** is `safe/secret` (`#secret :postbox/webhook-token`). A request's `?token=` is compared with it by `safe/equal?`, in constant time. It must still be at least 16 characters: `make-webhook-routes` reveals it once, for its length only, and refuses a shorter one, `:postbox/bad-settings` with `{:token ["should be at least 16 characters"]}`, never saying the text.

Either written out as text is refused (`"should be a #secret, read from the settings' secrets source"`), and no refusal holds the text. The tests make theirs with `safe/make-secret`.

**Why:** the settings are printed, logged and put in ex-data; a token in them as a string leaks by every one of those. Wrapped, each prints as its name everywhere but the call that uses it, which can be searched for. safe's [A secret stays wrapped until it is used](https://github.com/p4ulcristian/safe/blob/main/decisions/2026-10-23-a-secret-stays-wrapped-until-it-is-used.md) says how. The length check stays, since a URL's token is guessed by trying; malli can't read a secret's length without the shape revealing it, so it moved from the shape to the one reveal at the start.

**Instead of:**
- strings, as before: every log of the settings is a leak
- the length in the shape, a `:fn` that reveals: the shape would hand the text to whatever checks it, and safe's `secret` is one shape for every library
- no length check: a short token in a URL is found by trying
- postbox's own hashing for the comparison: safe's `equal?` is the one every library uses

# The provider is a setting: Postmark or the log

**Decision:** the settings' `:provider` names who sends, each with its own keys, every one required:

- `{:name :postmark :server-token … :api-url "https://api.postmarkapp.com" :connect-timeout-ms … :request-timeout-ms …}`: Postmark's HTTP API, a POST to `<api-url>/email` with the server token, through one java.net.http client made with the postbox. An answer of 200 with `ErrorCode` 0 is sent. 422 with `ErrorCode` 300 (an invalid email: a bad address, no body) or 406 (an inactive recipient: bounced or complained before) gives up at once, backroom's give-up: no attempt changes the email. Anything else (a 5xx, a 429, a 401 from a wrong token, no connection, a timeout) throws, and runs again by backroom's retries; a wrong token fixed in time loses nothing.
- `{:name :log :level :info}`: nothing is sent; the email is written to the app's log at `:level`, `:postbox/logged-not-sent` (every key but the attachments' bytes, which are given as their names, types and lengths), and answered as sent, its message id `logged-<uuid>`. Development, tests, a stage that must send nobody anything (Iron Rainbow's log-only mode).

A provider is a function maker in `postbox.providers` and a branch of the settings' `[:multi {:dispatch :name}]`: another (SES, SMTP) is one of each. Since v0.1.0.

**Why:** which provider, and its token and timeouts, set behaviour, and differ by place: the log in development, Postmark in production. A tagged map keeps each provider's keys with it, required only when it is the one named.

**Instead of:**
- a protocol the app implements: the app would write Postmark's client
- a `:log?` flag beside Postmark's keys: Postmark's token required where nothing is sent
- giving up on every 4xx: a wrong token would lose every email queued before it is fixed

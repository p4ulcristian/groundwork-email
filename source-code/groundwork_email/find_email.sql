-- One email, by its key.
SELECT email__key, email__tag, email__state, email__message_id, email__queued_at, email__sent_at
FROM groundwork_email.emails
WHERE email__key = :email/key

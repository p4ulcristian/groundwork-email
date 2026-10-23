-- The email the provider sent under a message id.
SELECT email__key, email__tag, email__state, email__message_id, email__queued_at, email__sent_at
FROM postbox.emails
WHERE email__message_id = :email/message-id

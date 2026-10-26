-- The email the provider sent under a message id.
SELECT email__key, email__tag, email__state, email__message_id, email__queued_at, email__sent_at
FROM groundwork_email.emails
WHERE email__message_id = param('email/message-id')

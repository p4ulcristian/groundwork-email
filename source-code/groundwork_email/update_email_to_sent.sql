-- The provider took the email.
UPDATE groundwork_email.emails
SET email__state = 'sent', email__message_id = :email/message-id, email__sent_at = now()
WHERE email__key = :email/key

-- The email its background job sends, held until the job's transaction ends: a second run of
-- the same job waits, then sees it sent or given up.
SELECT email__key, email__tag, email__state, email__message_id, email__gave_up_reason
FROM groundwork_email.emails
WHERE email__key = param('email/key')
FOR UPDATE

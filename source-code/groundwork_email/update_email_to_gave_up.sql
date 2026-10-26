-- The provider refused the email itself: no attempt sends it. The reason is the provider's
-- name of its refusal, never its words, which may name the address.
UPDATE groundwork_email.emails
SET email__state = 'gave-up', email__gave_up_reason = param('email/gave-up-reason')
WHERE email__key = param('email/key')

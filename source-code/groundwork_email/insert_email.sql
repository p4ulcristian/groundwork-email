-- An email queued, by its key: one queued before under the key is left as it is. added says
-- whether this call wrote it.
WITH added AS (
  INSERT INTO groundwork_email.emails (email__key, email__tag)
  VALUES (param('email/key'), param('email/tag'))
  ON CONFLICT (email__key) DO NOTHING
  RETURNING email__key
)
SELECT email__key, true AS added FROM added
UNION ALL
SELECT email__key, false AS added FROM groundwork_email.emails
WHERE email__key = param('email/key') AND NOT EXISTS (SELECT 1 FROM added)

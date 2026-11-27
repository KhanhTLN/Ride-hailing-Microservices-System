BEGIN;
CREATE TABLE IF NOT EXISTS registration_request (
 key VARCHAR(128) PRIMARY KEY,
 request_hash VARCHAR(64) NOT NULL,
 password_hash VARCHAR(255),
 response_body TEXT,
 expires_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE registration_request ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
-- Backfill only the local records written before password fingerprints were
-- separated. Preserve their cached response, key and original BCrypt verifier.
DO $migration$
BEGIN
 IF to_regclass('public."user"') IS NOT NULL THEN
   UPDATE registration_request r SET password_hash=u.password,request_hash=''
   FROM public."user" u
   WHERE r.password_hash IS NULL AND r.response_body IS NOT NULL
     AND r.response_body::jsonb->>'id'=u.id::text AND u.password LIKE '$2%';
 END IF;
END
$migration$;
-- Orphan records must not retain a fast digest that included a password.
UPDATE registration_request SET request_hash='' WHERE password_hash IS NULL;
COMMIT;

-- Email opt-in is separate from web notifications. Existing notifications are not backfilled.
BEGIN;
CREATE TABLE IF NOT EXISTS notification_email_preferences (
    user_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT false,
    verified_email varchar(255),
    verified_at timestamp(6),
    token_hash varchar(64),
    token_expires_at timestamp(6),
    verification_last_sent_at timestamp(6)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_email_token ON notification_email_preferences(token_hash)
    WHERE token_hash IS NOT NULL;
CREATE TABLE IF NOT EXISTS notification_email_outbox (
    notification_id bigint PRIMARY KEY REFERENCES product_change_notifications(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    queued_at timestamp(6) NOT NULL DEFAULT localtimestamp,
    available_at timestamp(6) NOT NULL DEFAULT localtimestamp + interval '1 minute',
    state varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','PROCESSING','SENT','SKIPPED','FAILED')),
    attempts integer NOT NULL DEFAULT 0,
    lease_until timestamp(6),
    batch_id uuid,
    sent_at timestamp(6),
    last_error varchar(80)
);
CREATE INDEX IF NOT EXISTS ix_notification_email_ready ON notification_email_outbox(state,available_at,user_id);
COMMIT;

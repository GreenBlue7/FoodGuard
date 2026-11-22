-- Count accepted new jobs using DB time. Duplicates/rejections never create an event.
-- Independent of inspection history deletion; deleting a user removes their admission log.
CREATE TABLE inspection_submission_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    accepted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_inspection_submission_user_time ON inspection_submission_log(user_id, accepted_at);

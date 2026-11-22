-- Completed history is preserved. Older active jobs are fingerprinted lazily on the next submission.
ALTER TABLE inspection_records ADD COLUMN request_fingerprint varchar(64);
CREATE UNIQUE INDEX uq_inspection_active_request
    ON inspection_records(user_id, request_fingerprint)
    WHERE request_fingerprint IS NOT NULL AND status IN ('PENDING','RUNNING');

-- Existing completed records are unchanged. Pending inputs commit with their inspection record.
CREATE TABLE inspection_job_queue (
    job_id bigint PRIMARY KEY REFERENCES inspection_records(id) ON DELETE CASCADE,
    judge_request_json text NOT NULL,
    recommendation_json text,
    queued_at timestamptz NOT NULL DEFAULT current_timestamp,
    claim_token uuid,
    lease_until timestamptz,
    CONSTRAINT ck_inspection_claim CHECK ((claim_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ix_inspection_queue_pending ON inspection_job_queue(queued_at, job_id) WHERE claim_token IS NULL;
CREATE INDEX ix_inspection_queue_lease ON inspection_job_queue(lease_until) WHERE claim_token IS NOT NULL;

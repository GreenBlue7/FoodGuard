-- Operational metadata only: never store API URLs, keys, source text or exception messages.
CREATE TABLE product_sync_runs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id varchar(20) NOT NULL,
    mode varchar(20) NOT NULL CHECK (mode IN ('FULL','INCREMENTAL')),
    status varchar(20) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    error_code varchar(40),
    CHECK ((status='RUNNING' AND finished_at IS NULL AND error_code IS NULL)
        OR (status='SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL)
        OR (status='FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL AND error_code IN
            ('IMPORT_NOT_COMPLETED','IMPORT_EXCEPTION','CHECKPOINT_FAILED')))
);
CREATE INDEX ix_product_sync_runs_service_id ON product_sync_runs(service_id,id DESC);

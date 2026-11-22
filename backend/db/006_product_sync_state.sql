-- Apply after 005_product_changes_reinspections.sql. Existing data is preserved.
CREATE TABLE IF NOT EXISTS product_sync_state (
    service_id varchar(20) PRIMARY KEY,
    covered_through date NOT NULL
);

-- For the existing capstone_db, where Hibernate already created products and
-- inspection_records. Do not run 001_core.sql against that database.
-- This adds the approved-functions table, inspection FK, and history index
-- without changing existing product or inspection rows.
CREATE TABLE IF NOT EXISTS product_approved_functions (
    prdlst_report_no varchar(255) NOT NULL,
    position integer NOT NULL,
    function_text text NOT NULL,
    PRIMARY KEY (prdlst_report_no, position),
    CONSTRAINT fk_approved_function_product
        FOREIGN KEY (prdlst_report_no) REFERENCES products (prdlst_report_no)
);

-- Match the inspection-history constraints and index in 001_core.sql.
-- Existing non-null report numbers must refer to products before adding this FK.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'inspection_records'::regclass
          AND conname = 'fk_inspection_product'
    ) THEN
        ALTER TABLE inspection_records
            ADD CONSTRAINT fk_inspection_product
            FOREIGN KEY (prdlst_report_no) REFERENCES products (prdlst_report_no);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_inspection_records_created_at
    ON inspection_records (created_at DESC);

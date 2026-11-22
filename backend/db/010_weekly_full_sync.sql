-- Unknown historical full-sync dates intentionally remain NULL: next refresh performs a full import.
ALTER TABLE product_sync_state ADD COLUMN IF NOT EXISTS last_full_sync_on date;

-- Prevent concurrent imports from overwriting newer product data. Existing records are preserved.
ALTER TABLE products ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

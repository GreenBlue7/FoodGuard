-- Additive migration. Existing products need a full I0030 import to fill these fields.
BEGIN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS ingredients_source text;
ALTER TABLE products ADD COLUMN IF NOT EXISTS cautions_source text;
ALTER TABLE products ADD COLUMN IF NOT EXISTS intake text;
ALTER TABLE products ADD COLUMN IF NOT EXISTS individual_ingredients_source text;
ALTER TABLE products ADD COLUMN IF NOT EXISTS etc_ingredients_source text;
ALTER TABLE products ADD COLUMN IF NOT EXISTS capsule_ingredients_source text;
ALTER TABLE products ADD COLUMN IF NOT EXISTS standards text;
COMMIT;

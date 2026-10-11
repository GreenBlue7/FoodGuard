-- Run in the database shared with the judge when vector search is needed.
-- The judge owns the cases table and its embedding schema.
CREATE EXTENSION IF NOT EXISTS vector;

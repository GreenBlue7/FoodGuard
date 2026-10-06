CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS cases (
    id             text PRIMARY KEY,
    phrase         text NOT NULL,
    violation_type text NOT NULL,
    article        text NOT NULL,
    product_type   text NOT NULL,
    note           text NOT NULL DEFAULT '',
    case_date      date NOT NULL,
    source_title   text NOT NULL,
    source_url     text NOT NULL,
    embedding      vector(1024) NOT NULL
);
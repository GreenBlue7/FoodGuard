CREATE TABLE inspection_job_images (
    job_id bigint NOT NULL REFERENCES inspection_job_queue(job_id) ON DELETE CASCADE,
    position integer NOT NULL,
    content_type text NOT NULL,
    content bytea NOT NULL,
    PRIMARY KEY (job_id, position)
);

CREATE TABLE inspection_image_results (
    job_id bigint PRIMARY KEY REFERENCES inspection_records(id) ON DELETE CASCADE,
    result_json text NOT NULL
);

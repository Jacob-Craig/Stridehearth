TRUNCATE TABLE run;

ALTER TABLE run
    ADD COLUMN source VARCHAR(255) NOT NULL,
    ADD COLUMN external_id VARCHAR(255); -- nullable: manual runs have no external identity

-- NULLs are distinct in a UNIQUE constraint, so any number of manual runs can coexist

ALTER TABLE run
    ADD CONSTRAINT uq_run_user_source_external_id UNIQUE (user_id, source, external_id);

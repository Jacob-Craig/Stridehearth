TRUNCATE TABLE run;

CREATE TABLE app_user (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL UNIQUE,
    first_name VARCHAR(255),
    last_name VARCHAR(255)
);

ALTER TABLE run
    ADD COLUMN user_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_run_app_user FOREIGN KEY (user_id) REFERENCES app_user (id);

CREATE INDEX idx_run_user_id ON run (user_id);
-- A migration only a newer One-way Release knows about. NewerSchemaIntegrationTest applies it alongside the real
-- migrations to stand in for a Server that was upgraded, then starts the current code against that database.
CREATE TABLE from_a_newer_release (id BIGINT PRIMARY KEY);

-- Runs only once, when the pgdata volume is initialized for the first time.
-- Tables are created by Flyway migrations so the schema is versioned with the application code.
CREATE EXTENSION IF NOT EXISTS vector;

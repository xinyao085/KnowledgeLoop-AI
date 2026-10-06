ALTER TABLE upload_sessions
    ADD COLUMN total_size BIGINT NULL,
    ADD COLUMN chunk_size INT NULL,
    ADD COLUMN fingerprint CHAR(64) NULL,
    ADD COLUMN document_id BIGINT NULL;

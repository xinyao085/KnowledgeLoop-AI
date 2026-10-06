CREATE TABLE IF NOT EXISTS documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    mime_type VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    parser_version INT NOT NULL DEFAULT 1,
    title VARCHAR(500) NULL,
    outline_json LONGTEXT NULL,
    page_count INT NULL,
    warning_json LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_documents_user_time (user_id, created_at),
    KEY idx_documents_hash_version (user_id, content_hash, parser_version),
    KEY idx_documents_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    chunk_key VARCHAR(160) NOT NULL,
    heading_path VARCHAR(1000) NULL,
    page_number INT NULL,
    slide_number INT NULL,
    paragraph_index INT NULL,
    line_start INT NULL,
    line_end INT NULL,
    content LONGTEXT NOT NULL,
    summary TEXT NULL,
    keywords_json TEXT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding_version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_chunk_key (document_id, chunk_key),
    KEY idx_document_chunks_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    goal VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_knowledge_point VARCHAR(500) NULL,
    next_action VARCHAR(32) NULL,
    state_json LONGTEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_learning_sessions_user (user_id, updated_at),
    KEY idx_learning_sessions_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_mastery (
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    knowledge_key VARCHAR(255) NOT NULL,
    mastery_score DECIMAL(5,4) NOT NULL DEFAULT 0,
    weakness_json LONGTEXT NULL,
    next_review_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, document_id, knowledge_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS quiz_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    knowledge_key VARCHAR(255) NOT NULL,
    question_json LONGTEXT NOT NULL,
    user_answer LONGTEXT NOT NULL,
    evaluation_json LONGTEXT NULL,
    score DECIMAL(5,4) NULL,
    evidence_json LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_quiz_attempt_idempotency (session_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

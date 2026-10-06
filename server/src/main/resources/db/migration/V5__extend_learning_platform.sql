ALTER TABLE knowledge_mastery
    ADD COLUMN review_interval_days INT NOT NULL DEFAULT 1 AFTER next_review_at;

CREATE TABLE IF NOT EXISTS document_processing_checkpoints (
    document_id BIGINT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    detail VARCHAR(500) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS upload_sessions (
    upload_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(128) NULL,
    total_chunks INT NOT NULL,
    received_chunks INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (upload_id),
    KEY idx_upload_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS courses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_course_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS course_documents (
    course_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (course_id, document_id),
    KEY idx_course_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS document_questions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    document_id BIGINT NULL,
    course_id BIGINT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    question LONGTEXT NOT NULL,
    answer LONGTEXT NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_question_conversation (user_id, conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_mastery_review ON knowledge_mastery(user_id, next_review_at);
CREATE INDEX idx_quiz_session_time ON quiz_attempts(session_id, created_at);

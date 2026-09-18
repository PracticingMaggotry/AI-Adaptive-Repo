-- Create system_configuration table
CREATE TABLE IF NOT EXISTS system_configuration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT,
    description TEXT,
    data_type VARCHAR(50) NOT NULL
);

-- Seed default system configurations
-- ── Quiz & Learning Limits ───────────────────────────────────────────

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MAX_UPLOADS_PER_DAY', '4', 'INTEGER', 'Maximum number of material uploads allowed per student per day. Adjust based on Claude API costs and desired student engagement.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MAX_MIXED_QUESTIONS', '30', 'INTEGER', 'Maximum number of mixed-type quiz questions to generate per upload. Controls AI output length and cost.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MAX_TOPIC_LENGTH', '100', 'INTEGER', 'Maximum length in characters for a topic/subject name entered by students.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MAX_UPLOAD_BYTES', '10485760', 'LONG', 'Maximum file upload size in bytes. Default: 10 MB. Adjust for bandwidth/storage constraints.');

-- ── Difficulty Score Thresholds ──────────────────────────────────────

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('HARD_DIFFICULTY_THRESHOLD', '80', 'INTEGER', 'Student score percentage (0-100) that triggers Hard difficulty quiz generation. Adjust based on your student population.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MEDIUM_DIFFICULTY_THRESHOLD', '50', 'INTEGER', 'Student score percentage (0-100) that triggers Medium difficulty. Scores below this get Easy quizzes.');

-- ── File Upload Configuration ───────────────────────────────────────

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('ALLOWED_FILE_EXTENSIONS', 'pdf,txt,csv,doc,docx', 'STRING', 'Comma-separated list of allowed file extensions (case-insensitive). Add more as needed.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('ALLOWED_CONTENT_TYPES', 'pdf,text,csv,msword,wordprocessingml,octet-stream', 'STRING', 'Comma-separated list of allowed MIME type fragments for validation.');

-- ── Text Extraction & Preview ──────────────────────────────────────

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MAX_PREVIEW_LENGTH', '1800', 'INTEGER', 'Maximum character length of extracted text preview stored in Material records.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MIN_EXTRACTED_TEXT_LENGTH', '100', 'INTEGER', 'Minimum extracted text length required from a file to be considered valid. Prevents scanned image-only PDFs.');

-- ── Password & Security ────────────────────────────────────────────

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('MIN_PASSWORD_LENGTH', '8', 'INTEGER', 'Minimum password length. Adjust to match your school security policy.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('REQUIRE_PASSWORD_UPPERCASE', 'true', 'BOOLEAN', 'Require at least one uppercase letter in passwords.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('REQUIRE_PASSWORD_NUMBER', 'true', 'BOOLEAN', 'Require at least one numeric digit in passwords.');

INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('REQUIRE_PASSWORD_SPECIAL_CHAR', 'true', 'BOOLEAN', 'Require at least one special character (!@#$%^&*) in passwords.');


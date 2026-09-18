-- Create material_categories table
CREATE TABLE IF NOT EXISTS material_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed initial categories from the original hardcoded list
INSERT INTO material_categories (name, description, active) VALUES
('Mathematics & Quantitative Reasoning', 'Mathematics, statistics, quantitative analysis, and numerical methods.', true),
('Computer Science & Programming', 'Software development, algorithms, data structures, and computer systems.', true),
('Engineering & Applied Sciences', 'Civil, mechanical, electrical, chemical engineering and applications.', true),
('Natural Sciences', 'Physics, chemistry, biology, geology, and earth sciences.', true),
('Business, Economics & Management', 'Economics, business strategy, finance, management, and organizational studies.', true),
('Social Sciences', 'Psychology, sociology, anthropology, political science, and related fields.', true),
('Humanities & Languages', 'Literature, history, philosophy, languages, and cultural studies.', true),
('Health & Medical Sciences', 'Medicine, nursing, public health, biology, and health sciences.', true),
('Law & Legal Studies', 'Constitutional law, civil law, criminal law, and legal systems.', true),
('General / Other', 'Content that does not fit into any specific category or spans multiple fields.', true);


-- Selects how difficulty is adapted: 'ability' (continuous Elo/Rasch ability model, default) or
-- 'threshold' (original three-tier score rule). The application falls back to 'ability' when the row is absent.
INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('ADAPTATION_MODEL', 'ability', 'STRING', 'Difficulty adaptation model: ability (continuous per-question difficulty from an Elo/Rasch ability estimate) or threshold (original Easy/Medium/Hard score thresholds). Use threshold as the baseline when comparing the two.');

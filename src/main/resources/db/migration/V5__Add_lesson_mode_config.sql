-- Selects the Learning Hub lesson: 'units' (default; taught part by part from handout passages, with check questions)
-- or 'classic' (the original single-summary lesson). The application falls back to 'units' when the row is absent.
INSERT INTO system_configuration (config_key, config_value, data_type, description) VALUES
('LESSON_MODE', 'units', 'STRING', 'Learning Hub lesson: units (part-by-part lesson written from handout passages, weakest concepts first, with check questions) or classic (original single summary lesson). Use classic as the baseline when comparing the two.');

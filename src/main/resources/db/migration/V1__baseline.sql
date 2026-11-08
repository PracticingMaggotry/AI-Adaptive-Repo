-- Baseline. The schema before Flyway was created and kept in sync by Hibernate (spring.jpa.hibernate.ddl-auto=update).
-- On an existing database Flyway records version 1 as already applied and does NOT run this file.
-- On an empty database this runs once as a harmless no-op; Hibernate then creates the tables.
-- Real schema changes start at V2. Never edit a migration after it has been applied anywhere.
SELECT 1;

# Database migrations (Flyway)

## What changed

Flyway now versions the schema. The only thing it does to an existing database on first start is create one table,
`flyway_schema_history`, and record "baseline = version 1". It runs **no SQL against your data**.

`spring.jpa.hibernate.ddl-auto` is still `update`, so Hibernate keeps creating and extending tables exactly as before.
Flyway is the place for changes Hibernate cannot or should not make (data fixes, renames, dropping columns, seed rows).

## Why the old V2–V5 scripts were deleted

They were never executed (Flyway was not in the pom), and running them would have changed behaviour:

- `V2` seeded 10 categories with different names from the 11 that `MaterialCategorySeeder` creates, so the AI
  classifier's category list would have grown with near-duplicates.
- `V3–V5` inserted config rows with plain `INSERT`s. They would fail on a database that already has the rows, and on one
  that lacks them they would add values that may differ from the defaults `ConfigurationService` falls back to.

They are still in git history if ever needed, e.g.
`git show 5688c0d:src/main/resources/db/migration/V3__Create_system_configuration_and_seed.sql`.

## Rules from now on

1. New change = a new file `src/main/resources/db/migration/V2__short_description.sql`, then V3, V4, ...
2. Never edit or rename a migration after it has run anywhere (Flyway checks a checksum and refuses to start).
3. Write MySQL-compatible SQL. Tests do not run migrations (they use H2 with Hibernate `create-drop`), so try each new
   migration on a copy of the real database first.
4. Prefer additive changes (new nullable column, new table). Flyway cannot undo a migration.

## Rollout checklist (first deploy)

1. Take a database backup/snapshot first.
2. Deploy. Expected log lines: `Creating Schema History table ... with baseline`, then
   `Successfully baselined schema with version: 1`, then `Schema ... is up to date` (or similar).
   An existing database is NOT empty, so the baseline path is the one you should see.
3. Check the app works as before (login, upload, quiz). Check the table exists:
   `SELECT * FROM flyway_schema_history;` returns one row (version 1, type BASELINE).

## If it fails to start

Set the environment variable `FLYWAY_ENABLED=false` and redeploy (no code change). The app then behaves exactly as it did
before Flyway. `flyway_schema_history` can stay; it is harmless.

## Later (not part of this change)

Once Flyway has been running cleanly for a while, consider `spring.jpa.hibernate.ddl-auto=validate` so Hibernate stops
altering tables and every schema change must come through a migration. Do that as its own change, after checking
`validate` passes against a copy of the production database.

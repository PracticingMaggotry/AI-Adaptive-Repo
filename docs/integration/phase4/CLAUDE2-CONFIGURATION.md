# System > Configuration page (`#/system/configuration`)

Paths below are relative to the repository root (`AI-Adaptive-Repo-main/`). Source of truth: `AI-Adaptive-Repo-phase3-integrated-2026-10-24.zip`.
No backend Java, schema, seed, security rule, shell, `admin.js`, `admin.html`, shared UI, People, Overview or Study file was touched.

## Files

| File | Change |
|---|---|
| `src/main/resources/static/admin/views/configuration.js` | new: the view, `default export { mount(el, ctx), unmount(ctx) }` |
| `src/main/resources/static/admin/views/configuration-model.js` | new: pure key registry, parsing, validation, view model (Node-testable) |
| `src/main/resources/static/admin/routes.js` | changed: placeholder removed; `/system/configuration` now loads `./views/configuration.js`. Nothing else in the file changed |
| `scripts/test-system-configuration.mjs` | new: 350 assertions |
| `scripts/smoke-admin-shell.mjs` | changed: stubs the two read endpoints and asserts the real page instead of the placeholder text (see "Not verified") |

The view reuses `ctx.api.request`, `ctx.ui.confirm/toast/empty`, and the existing helpers in `views/study-common.js` (imported, not modified).
Route preservation: all 17 routes, the 10 aliases, Study mappings, the dedicated Platform report and Reliability mappings, and `after`/`legacyTab`/`shellLoad` fields are unchanged (asserted in the new test).

## Endpoints used

| Endpoint | Use |
|---|---|
| `GET /api/admin/configuration` | stored rows; the source for editing and for the read-only "other records" list |
| `GET /api/admin/configuration/current/all` | effective snapshot; reloaded after every save |
| `GET /api/admin/configuration/{key}` | freshness check immediately before a save (and for the paired difficulty threshold) |
| `PUT /api/admin/configuration/{key}` | the only write. Body is exactly `{ "configValue": "<text>", "dataType": "<registry type>" }`; `description` is never sent, so the controller leaves it alone |

`POST` and `DELETE` are never called (asserted).

## Settings

All 19 keys read by `ConfigurationService` are in the registry (a test parses the Java source, so a new key there fails the test until reviewed).

Editable (validated, confirmed before save):

| Group | Key | Type | Accepted values (source of the rule) |
|---|---|---|---|
| Daily limits | `MAX_UPLOADS_PER_DAY` | INTEGER | 0 to 2147483647 (`AdminController` accepts 0, rejects negatives) |
| Daily limits | `MAX_QUIZ_SUBMISSIONS_PER_DAY`, `MAX_ADAPTED_QUIZZES_PER_DAY`, `MAX_TARGETED_QUIZZES_PER_DAY`, `MAX_REPORTS_PER_DAY`, `MAX_LESSON_REGENERATIONS_PER_DAY` | INTEGER | same, but only if a row exists (see limitations) |
| Materials and uploads | `MAX_UPLOAD_BYTES` | LONG | 1 to Long.MAX (0 would reject every file) |
| Materials and uploads | `MAX_TOPIC_LENGTH` | INTEGER | 1 to int max (0 would reject every topic) |
| Materials and uploads | `ALLOWED_FILE_EXTENSIONS` | STRING | comma list from `pdf,txt,csv,doc,docx` (the types `DocumentTextExtractor` reads); lower-case; no empty entries |
| Materials and uploads | `ALLOWED_CONTENT_TYPES` | STRING | comma list of lower-case fragments `[a-z0-9][a-z0-9.+_-]*`; no empty entries (an empty fragment matches every type in `String.contains`) |
| Difficulty thresholds | `HARD_DIFFICULTY_THRESHOLD`, `MEDIUM_DIFFICULTY_THRESHOLD` | INTEGER | 0 to 100 (score is a percentage); Medium must not exceed Hard, re-checked against the freshly read counterpart |
| Password policy | `MIN_PASSWORD_LENGTH` | INTEGER | 1 to 128 (`PasswordPolicy.MAX_LENGTH`; a higher minimum would make every new password invalid) |
| Password policy | `REQUIRE_PASSWORD_UPPERCASE`, `_NUMBER`, `_SPECIAL_CHAR` | BOOLEAN | exactly `true` or `false` (the backend reads any other text as false) |

Numbers must be plain decimal digits (no `+`, spaces, decimals, exponent).

Read-only:
- `MAX_MIXED_QUESTIONS`, `MAX_PREVIEW_LENGTH`, `MIN_EXTRACTED_TEXT_LENGTH`: their getters exist and appear in the snapshot, but no code outside `ConfigurationService` calls them, so a change has no verified effect. Flip `editable` in the registry if they get consumers.
- Any key with no stored row (cannot be created here), or whose row `dataType` differs from the registry type (editing would rewrite the type).
- Every unrecognised record, listed under "Other records (read-only)", rendered as text only.

## Save behaviour

1. Validate by real type; nothing is sent if invalid, and the draft stays visible.
2. `ctx.ui.confirm` with the old and new value, the setting's impact text and any warnings (value 0, above the 15 MB servlet ceiling, below the seeded password minimum, equal difficulty thresholds, unknown content-type fragments).
3. Re-read the row; refuse if its value or type changed since the page loaded (reloads and says so). One save at a time; other Edit buttons are disabled meanwhile.
4. `PUT`, then reload rows and snapshot. A value is shown as saved only from that reload.
5. Failure: the displayed value stays the last one loaded; the draft is kept and labelled "Not saved". A 4xx is treated as definite. A network error or 5xx is ambiguous, so the page reloads and reports what the server actually holds. If the reload after a successful save fails, values are flagged "May be outdated".
6. Snapshot failure disables editing until it loads (Retry offered). Loading, empty, error and Retry states are kept.

## Limitations

- Five of six daily-limit rows are not seeded by `V3`. The backend creates them when saved from Usage limits (`AdminController` → `setConfig`); this page cannot, so they show their code default read-only. Use `#/system/usage-limits` for those.
- `PUT /api/admin/configuration/{key}` writes no Activity Log entry; `AdminController`'s daily-limit endpoint does. The page says so. Changing this needs backend work, which was out of scope.
- `MAX_UPLOAD_BYTES` above 15 728 640 has no effect while `spring.servlet.multipart.max-file-size=15MB`. `MaterialController` still says "10 MB" in its error text whatever the value.
- No upper bound beyond the integer range could be established for `MAX_TOPIC_LENGTH` (the `Material.topic` column length is not declared) or for daily limits.
- `ALLOWED_FILE_EXTENSIONS` / `ALLOWED_CONTENT_TYPES` are not in the `/current/all` snapshot, so their "current" value is the stored row. Content types are not applied when assigning handouts.
- The backend has no optimistic locking; the freshness check narrows but cannot close the window between the re-read and the PUT.
- Values apply immediately to new requests (`ConfigurationService` reads the row on each call; no cache was found). Existing passwords are not re-validated.

## Needs the shell owner (files this task could not touch)

- `admin.html` line 314 still shows the **"Not built"** sidebar tag for Configuration.
- `routes.js` still has `status: "not-implemented"` on that route, deliberately: `test-admin-shell.mjs` ties that tag in `admin.html` to the status, so flipping only one breaks it. Together: remove the tag, set `status: "ready"`, and update the assertions in `test-admin-shell.mjs` (line 43), `test-report-reliability.mjs` (lines 58-59) and the "Not built" check in `smoke-admin-shell.mjs`. The new test accepts either status.

## Tests

`JSDOM_FROM=/tmp/jsd/x.js node scripts/test-system-configuration.mjs` (jsdom installed outside the repo, as for the Study tests). Without jsdom the model and route checks run, then the script exits 1.
Run in this environment: the new suite passes (350); `test-admin-shell`, `test-report-reliability`, `test-admin-contracts`, `test-admin-overview`, `test-study-views`, `test-admin-ui-hardening` and both AI-testing suites still pass. `test-people-users` and `test-self-deletion-queue` printed SKIPPED (jsdom not resolvable from their lookup path) and were not verified.

## Not verified

- **No live backend test.** Persistence in MySQL, the real response shapes beyond what the Java source implies, CSRF/session behaviour of the PUT, and the 404/403 bodies were not exercised. Tests use a fake backend modelled on the controller code.
- **No real browser.** Layout and the `ui.confirm` dialog (jsdom has no `<dialog>.showModal`; the test replaces `confirm`) were not seen. Playwright had no browsers installed, so the edited `smoke-admin-shell.mjs` was syntax-checked only, not run.
- Whether any other component or deployment sets these keys by other means.

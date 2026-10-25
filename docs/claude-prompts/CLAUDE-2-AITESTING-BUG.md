# Claude 2 — Fix AI Testing preview error (Phase 1)

Read `docs/ADMIN-REDESIGN-BRIEF.md` and the current AI Testing code first. This is a focused bug fix, not an AI Testing redesign.

## Own only these files
- `src/main/resources/static/admin/legacy/aitesting.js`
- one small focused regression check under `scripts/` (add one only if it can genuinely test the regression without adding dependencies)

Do not edit the shell, admin.js, any route/UI contract, backend, upload-progress code, or other views.

## Bug
`loadAitPreviewFrame(topic, difficulty)` creates a local `body` element, injects the preview iframe, and then calls `renderMathOnly(el)` even though `el` is not defined in that function. This can throw after a successful AI test run and make the sandbox report failure.

Fix the undefined reference with the smallest correct change. The preview is an iframe; do not invent a parent `el` or change the generated quiz behavior.

## Non-negotiable safety checks
Preserve upload lifecycle behavior and do not remove or weaken `aitUploadInProgress`, `aitActiveUploadId`, the cancel endpoint call, `pagehide`, `beforeunload`, or upload-progress handling. Do not make unrelated refactors.

## Verify
Run JavaScript syntax checks and your focused regression check if added. If full browser verification is unavailable, say so. Return only changed/new files in a ZIP with original relative paths, plus a concise change/test report.

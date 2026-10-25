# Claude 1 — First bounded task: admin summary endpoint

You are implementing one small backend task in my existing Spring Boot project. I am a beginner; please make the code change, not just explain it.

Read `docs/ADMIN-ARCHITECTURE-AUDIT.md` and the existing `AdminController.java`, `QuestionReportRepository.java`, `AccountDeletionRequestRepository.java`, `AccountDeletionApprovalRepository.java`, `UserRepository.java`, `AssignedHandout.java`, `AssignedHandoutRepository.java`, and `User.java`.

**Task:** implement only `GET /api/admin/summary` in the existing backend. Do not edit any frontend files or other endpoint contracts.

Return these JSON fields exactly as documented (additional unrelated UI work is out of scope):
- `pendingReports`: `QuestionReportRepository.countPending()`.
- `deletionVotesNeeded`: number of admin-initiated deletion requests with status `PENDING` that have fewer valid approvals than the number of current administrators. This is the count of requests needing votes, not the sum of missing votes. Reuse the existing valid-admin approval logic in `AdminController`; do not include self-deletion requests here.
- `selfDeletionRequests`: number of users whose `deletionRequested` flag is true.
- `flaggedUsers`: number of users whose `flagged` flag is true.
- `pendingHandouts`: number of `AssignedHandout` rows whose status is `PENDING`.

Requirements:
1. Enforce the same `isAdmin(session)` check as the other admin endpoints; return the existing 403 response for non-admins.
2. Reuse existing repositories and current-admin approval semantics. Add only the smallest repository method/dependency required.
3. Do not change existing endpoint responses, remove legacy fields, rename `archived`, alter deletion flows, or touch any frontend/docs except a focused test if useful.
4. Avoid expensive duplicated `findAll()` calls when one list can serve multiple counts.
5. Run the narrowest relevant test/compile checks that are actually available. If Maven cannot run, state that honestly and provide the exact reason; do not claim tests passed without running them.
6. Return only the changed files (prefer a ZIP preserving original relative paths if file export is supported) and a short report. Do not paste the whole `AdminController.java` into chat unless absolutely necessary.

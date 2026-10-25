/**
 * Canonical admin account status and severity ordering.
 *
 *   ACTIVE < FLAGGED < SUSPENDED < BANNED < DELETION_PENDING
 *
 * Every rule below is tied to a field or endpoint the backend really exposes:
 *
 *   FLAGGED           user.flagged            GET /api/admin/users
 *   SUSPENDED         user.archived           GET /api/admin/users
 *   BANNED            user.email is in        GET /api/admin/banned-emails
 *                     AccountAccessService.checkEmail() blocks login and ends sessions for a
 *                     banned email, so a match on the account's CURRENT email is an established
 *                     relationship. A banned entry is never matched on originalEmail.
 *   DELETION_PENDING  (a) user.id is in       GET /api/admin/self-deletion-requests  (item.id IS the user id)
 *                     (b) user.email is the   GET /api/admin/deletion-requests  targetEmail of a
 *                         PENDING or APPROVED admin-initiated request
 *
 * "Deleted" is an audit/log event (Activity log), not a live status, and is never produced here.
 *
 * Source arrays: pass an array when the endpoint was loaded (an empty array means "none"),
 * and leave the property undefined/null when it could not be loaded. Unloaded sources are
 * reported in `uncertain` instead of being treated as "none".
 */
export const ACCOUNT_STATUS = Object.freeze({
  ACTIVE: "ACTIVE",
  FLAGGED: "FLAGGED",
  SUSPENDED: "SUSPENDED",
  BANNED: "BANNED",
  DELETION_PENDING: "DELETION_PENDING"
});

export const ACCOUNT_STATUS_SEVERITY = Object.freeze({
  ACTIVE: 0,
  FLAGGED: 1,
  SUSPENDED: 2,
  BANNED: 3,
  DELETION_PENDING: 4
});

const OPEN_DELETION_REQUEST_STATUSES = new Set(["PENDING", "APPROVED"]);
const emailKey = value => String(value ?? "").trim().toLowerCase();
const loaded = source => Array.isArray(source);

export function compareAccountStatus(a, b) {
  return (ACCOUNT_STATUS_SEVERITY[a] ?? -1) - (ACCOUNT_STATUS_SEVERITY[b] ?? -1);
}

/**
 * @returns {{ status: string, reasons: {status: string, text: string}[], uncertain: {status: string, text: string}[] } | null}
 *   status     highest applicable severity among the conditions that could be established
 *   reasons    every established condition, most severe first
 *   uncertain  higher-severity conditions that could not be ruled out because a source was not loaded
 */
export function deriveAccountStatusDetail(user, { bannedEmails, selfDeletionRequests, deletionRequests } = {}) {
  if (!user) return null;
  const email = emailKey(user.email);
  const reasons = [];
  const uncertain = [];

  if (loaded(selfDeletionRequests)) {
    if (selfDeletionRequests.some(item => item && item.id != null && String(item.id) === String(user.id))) {
      reasons.push({ status: ACCOUNT_STATUS.DELETION_PENDING, text: "The user requested deletion of their own account." });
    }
  } else if (user.archived) {
    // Self-requested deletion always suspends the account, so it can only apply to archived rows.
    uncertain.push({ status: ACCOUNT_STATUS.DELETION_PENDING, text: "Self-requested deletions could not be loaded, so a pending self-deletion cannot be ruled out." });
  }

  if (loaded(deletionRequests)) {
    const open = deletionRequests.find(item => {
      if (!item) return false;
      const state = item.status == null ? null : String(item.status).toUpperCase();
      if (state !== null && !OPEN_DELETION_REQUEST_STATUSES.has(state)) return false;
      return email !== "" && emailKey(item.targetEmail ?? item.email) === email;
    });
    if (open) reasons.push({ status: ACCOUNT_STATUS.DELETION_PENDING, text: "An admin deletion request for this account is awaiting approval or execution." });
  } else {
    uncertain.push({ status: ACCOUNT_STATUS.DELETION_PENDING, text: "Admin deletion requests could not be loaded, so a pending admin deletion cannot be ruled out." });
  }

  if (loaded(bannedEmails)) {
    if (email !== "" && bannedEmails.some(item => emailKey(typeof item === "string" ? item : item?.email) === email)) {
      reasons.push({ status: ACCOUNT_STATUS.BANNED, text: "This account's email is on the banned email list." });
    }
  } else {
    uncertain.push({ status: ACCOUNT_STATUS.BANNED, text: "Banned emails could not be loaded, so a ban cannot be ruled out." });
  }

  if (user.archived === true) reasons.push({ status: ACCOUNT_STATUS.SUSPENDED, text: "The account is suspended." });
  if (user.flagged === true) reasons.push({ status: ACCOUNT_STATUS.FLAGGED, text: "The account is flagged for review." });

  reasons.sort((a, b) => compareAccountStatus(b.status, a.status));
  const status = reasons.length ? reasons[0].status : ACCOUNT_STATUS.ACTIVE;
  return {
    status,
    reasons,
    uncertain: uncertain.filter(item => compareAccountStatus(item.status, status) > 0)
  };
}

export function deriveAccountStatus(user, sources) {
  const detail = deriveAccountStatusDetail(user, sources);
  return detail ? detail.status : null;
}

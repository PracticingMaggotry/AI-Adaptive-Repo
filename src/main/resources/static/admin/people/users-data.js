/**
 * Data access for the People > Users views. Reuses existing endpoints through ctx.api.get only;
 * nothing here invents an endpoint or a response field.
 *
 *   GET /api/admin/users                       -> { users: [...] }
 *   GET /api/admin/banned-emails               -> { bannedEmails: [...] }
 *   GET /api/admin/self-deletion-requests      -> { requests: [...] }   (item.id is the user id)
 *   GET /api/admin/deletion-requests           -> { requests: [...] }   (item.id is the REQUEST id)
 *   GET /api/admin/users/{id}/daily-usage      -> { email, usage: [...] }
 */

/** "12" -> 12. Anything else (0, 007, "abc", 1.5, huge numbers) -> null. */
export function parseUserId(raw) {
  const text = String(raw ?? "");
  if (!/^[1-9]\d*$/.test(text)) return null;
  const n = Number(text);
  return Number.isSafeInteger(n) ? n : null;
}

export function findUser(users, id) {
  return (Array.isArray(users) ? users : []).find(u => u && String(u.id) === String(id)) || null;
}

function listFrom(response, key) {
  if (Array.isArray(response)) return response;
  return response && Array.isArray(response[key]) ? response[key] : null;
}

export async function loadUsers(api) {
  const users = listFrom(await api.get("/api/admin/users"), "users");
  if (!users) throw new Error("The server returned an unexpected response for the user list.");
  return users;
}

/**
 * Loads the three account-status sources independently. A failed source is left undefined
 * (so deriveAccountStatusDetail reports it as uncertain) and named in `failed`.
 */
export async function loadStatusSources(api) {
  const jobs = {
    bannedEmails: ["/api/admin/banned-emails", "bannedEmails"],
    selfDeletionRequests: ["/api/admin/self-deletion-requests", "requests"],
    deletionRequests: ["/api/admin/deletion-requests", "requests"]
  };
  const sources = {};
  const failed = [];
  await Promise.all(Object.entries(jobs).map(async ([name, [path, key]]) => {
    try {
      const list = listFrom(await api.get(path), key);
      if (list) sources[name] = list; else failed.push(name);
    } catch {
      failed.push(name);
    }
  }));
  return { sources, failed };
}

export async function loadUsage(api, id) {
  const usage = listFrom(await api.get(`/api/admin/users/${encodeURIComponent(id)}/daily-usage`), "usage");
  if (!usage) throw new Error("The server returned an unexpected response for daily usage.");
  return usage;
}

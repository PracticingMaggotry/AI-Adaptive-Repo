/** Observable shared count store: ctx.badges.set("people.deletions", 3). */
export function createBadgeStore(onChange = () => {}) {
  const counts = new Map();
  const listeners = new Set([onChange]);
  return {
    set(key, value) {
      const count = Math.max(0, Number(value) || 0);
      if (counts.get(key) === count) return;
      counts.set(key, count);
      for (const listener of listeners) listener(key, count, Object.fromEntries(counts));
    },
    get(key) { return counts.get(key) || 0; },
    snapshot() { return Object.fromEntries(counts); },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    destroy() { counts.clear(); listeners.clear(); }
  };
}

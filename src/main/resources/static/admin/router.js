/**
 * Hash router for the admin shell (Phase 2).
 *
 * - Routes live in routes.js as paths WITHOUT "#": "/people/users", "/people/users/:id".
 * - The URL is "admin.html#/people/users". Old hashes ("#users") are aliases that are
 *   rewritten in place with history.replaceState (no extra history entry).
 * - Nothing here touches window/document at import time, and every browser dependency
 *   is injectable (`env`), so the router can be unit-tested in Node.
 * - Navigation is serialized: a view's mount() always settles before the next navigation
 *   unmounts it, the previous view is unmounted before the next one is mounted, and when
 *   several navigations queue up only the latest one is rendered.
 * - Routing is navigation only. It is NOT authorization; backend controllers stay
 *   authoritative for every admin API.
 */

export const DEFAULT_PATH = "/overview";
const PARAM_VALUE = /^[A-Za-z0-9_.~-]+$/;
const hasOwn = (obj, key) => Object.prototype.hasOwnProperty.call(obj, key);

function safeDecode(segment) {
  try { return decodeURIComponent(segment); } catch { return null; }
}

/** "#/a/b?x=1" -> { rawPath: "/a/b", queryString: "x=1" } */
export function splitHash(hash) {
  let h = String(hash ?? "").trim();
  if (h.startsWith("#")) h = h.slice(1);
  const q = h.indexOf("?");
  return {
    rawPath: q === -1 ? h : h.slice(0, q),
    queryString: q === -1 ? "" : h.slice(q + 1)
  };
}

/**
 * Collapse slashes, drop trailing slash, decode segments. Returns "/a/b",
 * "" for an empty path, or null for malformed input (bad %-escape, "." / "..",
 * or an encoded slash inside a segment).
 */
export function normalizePath(rawPath) {
  const parts = String(rawPath ?? "").split("/").filter(Boolean);
  const decoded = [];
  for (const part of parts) {
    const value = safeDecode(part);
    if (value === null || value === "." || value === ".." || value.includes("/") || value.includes("\\")) return null;
    decoded.push(value);
  }
  return decoded.length ? "/" + decoded.join("/") : "";
}

/** Match a normalized path against route records. Static routes beat ":param" routes. */
export function matchRoute(routes, path) {
  const segments = String(path).split("/").filter(Boolean);
  let paramMatch = null;
  for (const route of routes) {
    const pattern = route.path.split("/").filter(Boolean);
    if (pattern.length !== segments.length) continue;
    const params = {};
    const canonical = [];
    let ok = true;
    let hasParam = false;
    for (let i = 0; i < pattern.length; i += 1) {
      if (pattern[i].startsWith(":")) {
        if (!PARAM_VALUE.test(segments[i])) { ok = false; break; }
        params[pattern[i].slice(1)] = segments[i];
        canonical.push(segments[i]);
        hasParam = true;
      } else if (pattern[i] === segments[i].toLowerCase()) {
        canonical.push(pattern[i]);
      } else { ok = false; break; }
    }
    if (!ok) continue;
    const result = { route, params, path: "/" + canonical.join("/") };
    if (!hasParam) return result;
    paramMatch = paramMatch || result;
  }
  return paramMatch;
}

/**
 * Resolve any incoming hash to a canonical target.
 * kind: "ok" | "notfound"; empty hash resolves to the default route.
 */
export function resolveHash(hash, { routes, aliases = {}, defaultPath = DEFAULT_PATH } = {}) {
  const { rawPath, queryString } = splitHash(hash);
  const inputHash = "#" + splitHashInput(hash);
  const query = new URLSearchParams(queryString);
  const withQuery = (path) => "#" + path + (queryString ? "?" + queryString : "");
  const finish = (kind, path, extra = {}) => {
    const canonicalHash = kind === "ok" ? withQuery(path) : inputHash;
    return { kind, path, query, canonicalHash, rewritten: kind === "ok" && canonicalHash !== inputHash, viaAlias: false, route: null, params: {}, ...extra };
  };

  const trimmed = rawPath.replace(/\/+$/, "");
  if (trimmed === "" || trimmed === "/") {
    const match = matchRoute(routes, defaultPath);
    return finish("ok", match.path, { route: match.route, params: match.params });
  }

  if (!trimmed.startsWith("/")) {
    const key = trimmed.toLowerCase();
    if (hasOwn(aliases, key)) {
      const match = matchRoute(routes, aliases[key]);
      if (match) return finish("ok", match.path, { route: match.route, params: match.params, viaAlias: true });
    }
    return finish("notfound", trimmed);
  }

  const path = normalizePath(trimmed);
  if (path === null) return finish("notfound", trimmed);
  const match = matchRoute(routes, path);
  if (!match) return finish("notfound", path);
  return finish("ok", match.path, { route: match.route, params: match.params });
}

function splitHashInput(hash) {
  let h = String(hash ?? "").trim();
  if (h.startsWith("#")) h = h.slice(1);
  return h;
}

/** Breadcrumb trail: [{ label, path? }]. Last item is the current page (no path). */
export function buildBreadcrumb(match, { routes, sections }) {
  if (!match?.route) return [];
  const { route, params = {} } = match;
  const section = sections.find((s) => s.id === route.section);
  const navRoutes = routes.filter((r) => r.nav !== false && r.section === route.section);
  const trail = [];
  if (route.section === "overview") return [{ label: route.title }];
  if (section) trail.push({ label: section.label, path: navRoutes[0]?.path });
  const parent = route.navPath && route.navPath !== route.path ? routes.find((r) => r.path === route.navPath) : null;
  if (parent) trail.push({ label: parent.label || parent.title, path: parent.path });
  const leaf = route.path.includes(":") ? `${route.title} ${Object.values(params).join(" ")}`.trim() : route.title;
  trail.push({ label: leaf });
  return trail;
}

function defaultEnv() {
  const w = globalThis.window;
  if (!w) throw new Error("createRouter needs a browser window or an injected env");
  return {
    get hash() { return w.location.hash; },
    setHash(value) { w.location.hash = value; },
    replaceHash(value) { w.history.replaceState(null, "", value); },
    onHashChange(handler) { w.addEventListener("hashchange", handler); return () => w.removeEventListener("hashchange", handler); }
  };
}

/**
 * createRouter({ routes, aliases, mount, env, hooks })
 *   mount  element passed to view.mount(el, ctx)
 *   env    { hash, setHash(v), replaceHash(v), onHashChange(handler) -> unsubscribe }
 *   hooks  all optional:
 *     getContext(match)               -> { api, ui, session, badges }
 *     onRouteChange(match|null, info) before the view loads; info = { initial, silent, resolved }
 *     beforeLeave(match)              sync, before the previous view is unmounted
 *     afterMount(match, ctx, info)    after view.mount() resolved and the route is still current
 *     onNotFound(el, resolved)        render a not-found state
 *     onError(error, match, phase)    phase: load | mount | unmount | afterMount | beforeLeave | router
 */
export function createRouter({ routes, aliases = {}, mount, env, hooks = {}, defaultPath = DEFAULT_PATH }) {
  if (!Array.isArray(routes) || routes.length === 0) throw new TypeError("createRouter requires a non-empty routes array");
  if (!mount) throw new TypeError("createRouter requires a mount element");
  const environment = env || defaultEnv();
  const resolve = (hash) => resolveHash(hash, { routes, aliases, defaultPath });

  let latest = 0;
  let chain = Promise.resolve();
  let active = null;      // { match, view } - the mounted (or mounting) view
  let current = null;     // { route, params, query, path }
  let currentHash = null;
  let started = false;
  let destroyed = false;
  let stopListening = null;

  const report = (error, match, phase) => {
    try {
      if (hooks.onError) hooks.onError(error, match, phase);
      else console.error(`[admin router] ${phase} failed`, error);
    } catch (hookError) { console.error("[admin router] onError hook failed", hookError); }
  };

  async function leave() {
    if (!active) return;
    const previous = active;
    active = null;
    try { hooks.beforeLeave?.(previous.match); } catch (error) { report(error, previous.match, "beforeLeave"); }
    try { await previous.view?.unmount?.(); } catch (error) { report(error, previous.match, "unmount"); }
  }

  function buildContext(match) {
    const extra = (hooks.getContext && hooks.getContext(match)) || {};
    return { params: match.params, query: match.query, api: extra.api, ui: extra.ui, router: publicApi, session: extra.session, badges: extra.badges };
  }

  async function run(ticket, resolved, info) {
    if (destroyed || ticket !== latest) return;          // superseded while queued
    await leave();
    if (destroyed || ticket !== latest) return;          // superseded while unmounting
    mount.replaceChildren();

    if (resolved.kind !== "ok") {
      current = null;
      currentHash = resolved.canonicalHash;
      try { hooks.onRouteChange?.(null, { ...info, resolved }); } catch (error) { report(error, null, "router"); }
      try { hooks.onNotFound?.(mount, resolved); } catch (error) { report(error, null, "router"); }
      return;
    }

    const match = { route: resolved.route, params: resolved.params, query: resolved.query, path: resolved.path };
    current = match;
    currentHash = resolved.canonicalHash;
    try { hooks.onRouteChange?.(match, { ...info, resolved }); } catch (error) { report(error, match, "router"); }

    let loaded;
    try { loaded = await (match.route.shellLoad || match.route.load)(); } catch (error) { report(error, match, "load"); return; }
    if (destroyed || ticket !== latest) return;          // nothing mounted yet; nothing to undo

    const view = loaded && loaded.default;
    if (!view || typeof view.mount !== "function" || typeof view.unmount !== "function") {
      report(new TypeError(`View for ${match.route.path} must export default { mount, unmount }`), match, "load");
      return;
    }
    active = { match, view };                            // set BEFORE mount so a failed mount is still unmounted
    const ctx = buildContext(match);
    try { await view.mount(mount, ctx); } catch (error) { report(error, match, "mount"); return; }
    if (destroyed || ticket !== latest) return;          // the next run unmounts this view
    try { await hooks.afterMount?.(match, ctx, info); } catch (error) { report(error, match, "afterMount"); }
  }

  function enqueue(resolved, info = {}) {
    const ticket = ++latest;
    chain = chain.then(() => run(ticket, resolved, info)).catch((error) => report(error, null, "router"));
    return chain;
  }

  function handle(hash, info = {}) {
    const resolved = resolve(hash);
    if (resolved.rewritten) environment.replaceHash(resolved.canonicalHash);   // alias/case/slash cleanup, no new history entry
    if (!info.force && active && resolved.kind === "ok" && resolved.canonicalHash === currentHash) return chain;
    return enqueue(resolved, info);
  }

  function onHashChange() { if (!destroyed) handle(environment.hash, { initial: false }); }

  const publicApi = {
    /** Begin listening and render the route for the current hash. */
    start() {
      if (started || destroyed) return chain;
      started = true;
      stopListening = environment.onHashChange(onHashChange);
      return handle(environment.hash, { initial: true });
    },
    /** target: canonical path ("/people/users"), old alias ("users") or a "#..." hash. */
    navigate(target, { replace = false } = {}) {
      const resolved = resolve(String(target ?? ""));
      const nextHash = resolved.kind === "ok" ? resolved.canonicalHash : "#" + splitHashInput(target);
      if (replace) {
        environment.replaceHash(nextHash);
        return handle(nextHash, { initial: false });
      }
      if (environment.hash === nextHash) return chain;          // same URL: no event, no re-render
      environment.setHash(nextHash);                            // hashchange drives the render
      return chain;
    },
    /** Update URL/title/nav to another route that shares the already-mounted screen. No remount. */
    sync(target) {
      const resolved = resolve(String(target ?? ""));
      if (resolved.kind !== "ok" || !active) return false;
      environment.replaceHash(resolved.canonicalHash);
      current = { route: resolved.route, params: resolved.params, query: resolved.query, path: resolved.path };
      currentHash = resolved.canonicalHash;
      active.match = current;
      try { hooks.onRouteChange?.(current, { initial: false, silent: true, resolved }); } catch (error) { report(error, current, "router"); }
      return true;
    },
    /** Re-render the current route. */
    reload() { return handle(environment.hash, { initial: false, force: true }); },
    current() { return current; },
    resolve,
    /** Stop listening and unmount the active view. */
    async destroy() {
      if (destroyed) return;
      destroyed = true;
      if (stopListening) stopListening();
      await chain.catch(() => {});
      await leave();
    }
  };
  return publicApi;
}

export default createRouter;

/**
 * Route module for /people/users/:id (the `load` of the route record).
 *
 * The production shell mounts views/users.js for this route (routes.js `shellLoad`), and that adapter
 * hosts the detail drawer. This module exists so `load` stays a real, mountable view for the dev
 * harness and the contract test: it delegates to the same implementation, so there is a single
 * code path. It does not call any endpoint that the backend lacks (the old GET /api/admin/users/{id}).
 */
import usersView from "./users.js";

export default {
  mount: (el, ctx) => usersView.mount(el, ctx),
  unmount: () => usersView.unmount()
};

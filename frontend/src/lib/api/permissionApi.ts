import { apiClient } from "./client";
import type { ApiResponse, PermissionDescriptor } from "../types";

/**
 * Permission catalog wrapper.
 *
 * The backend exposes the static {@code PermissionCatalog} (76 codes) under
 * {@code GET /v1/profiles/permissions}. This module gives the catalog its own
 * api file because more than one feature (Profile editor, role detail page,
 * future per-user permission inspector) needs it — keeping it grouped under
 * {@code profileApi} would push those features to import a peer's concern.
 *
 * The endpoint requires {@code ADMIN_PROFILE.READ}. Errors propagate to
 * react-query the same way every other call does.
 */
export const permissionApi = {
  list: () =>
    apiClient
      .get<ApiResponse<PermissionDescriptor[]>>("/v1/profiles/permissions")
      .then((r) => r.data),
};

import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Read-only role catalog.
 *
 * The backend has no `GET /v1/roles` endpoint as of Phase 5 — the canonical 22
 * role names live in {@code RolePermissionMatrix.DEFAULTS} (server-side) and
 * are mirrored here as a typed constant. Each role maps 1:1 to a
 * system-default Profile (see {@link profileApi.listProfiles}); the matching
 * profile's {@code legacyRoleName} is what links the two.
 *
 * If/when a `GET /v1/roles` endpoint is added, swap {@link roleApi.list} to a
 * real network call without changing call sites.
 */
export interface RoleDescriptor {
  name: string;
  description: string;
}

export const CANONICAL_ROLES: RoleDescriptor[] = [
  { name: "ADMIN", description: "Full system administrator with every permission." },
  { name: "EXECUTIVE", description: "Portfolio oversight and cross-project reporting." },
  { name: "PMO", description: "Cross-portfolio governance and master-data stewardship." },
  { name: "FINANCE", description: "Cost control, EVM, and contract finance." },
  { name: "PROJECT_MANAGER", description: "Owns a project end-to-end — plan, schedule, cost, risk." },
  { name: "SCHEDULER", description: "Activity and baseline maintenance, schedule recalculation." },
  { name: "PLANNING_ENGINEER", description: "Planning and schedule support with broader read access." },
  { name: "RESOURCE_MANAGER", description: "Resource pool and rate master administration." },
  { name: "STORE_MANAGER", description: "Inventory and material store operations." },
  { name: "PROCUREMENT_OFFICER", description: "Purchasing and contract intake." },
  { name: "SITE_MANAGER", description: "On-site delivery — DPR approval, NCR, safety, permits." },
  { name: "SITE_ENGINEER", description: "Field execution — DPR writing, NCR, safety logging." },
  { name: "PROJECT_ENGINEER", description: "Technical project support with DPR and yield-variance access." },
  { name: "SUPERVISOR", description: "Field supervision and DPR write." },
  { name: "FOREMAN", description: "Crew-level field role — DPR submission and incident log." },
  { name: "QA_QC_ENGINEER", description: "Quality control — NCR ownership and DPR QC annotation." },
  { name: "SAFETY_OFFICER", description: "HSE — safety records, permits, NCR." },
  { name: "BIM_DATA_COORDINATOR", description: "Document control and data-quality auditing." },
  { name: "TEAM_MEMBER", description: "Default team member — document control and read access." },
  { name: "CONTRACTOR", description: "External contractor — narrow project and DPR read/write." },
  { name: "CLIENT", description: "External client — read-only project and report access." },
  { name: "VIEWER", description: "Generic read-only role — every *.READ permission plus EVM/report export." },
];

const envelope = <T>(data: T): ApiResponse<T> => ({
  data,
  error: null,
  meta: { timestamp: new Date().toISOString(), version: "static" },
});

export const roleApi = {
  /**
   * Returns the canonical 22-role catalog. Today this is a constant — see the
   * top-of-file note for why. Returns an `ApiResponse` envelope to match
   * sibling apis so call sites don't have to special-case it.
   */
  list: async (): Promise<ApiResponse<RoleDescriptor[]>> => envelope(CANONICAL_ROLES),

  /**
   * Reserved for the future server-backed endpoint. Today it filters the
   * static catalog locally.
   */
  get: async (roleName: string): Promise<ApiResponse<RoleDescriptor | null>> => {
    const match = CANONICAL_ROLES.find((r) => r.name === roleName) ?? null;
    return envelope(match);
  },
};

// Re-export apiClient so future server-backed swap is a one-line change.
export { apiClient };

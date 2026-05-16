import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types";

export interface MissingWorkActivityRow {
  activityId: string;
  code: string;
  name: string;
  dprCount: number;
}

export type ConstraintType =
  | "START_ON"
  | "START_ON_OR_AFTER"
  | "START_ON_OR_BEFORE"
  | "FINISH_ON"
  | "FINISH_ON_OR_AFTER"
  | "FINISH_ON_OR_BEFORE"
  | "AS_LATE_AS_POSSIBLE";

export interface ActivityResponse {
  id: string;
  code: string;
  name: string;
  description?: string;
  projectId: string;
  wbsNodeId: string;
  status: string;
  /**
   * Two-stage edit lifecycle. {@code DRAFT} = inputs editable, DPRs blocked;
   * {@code LOCKED} = inputs read-only, DPRs flow. New activities default to DRAFT;
   * seeded activities are LOCKED. Toggled by the lock / unlock endpoints below.
   */
  editStatus: "DRAFT" | "LOCKED";
  plannedStartDate: string | null;
  plannedFinishDate: string | null;
  actualStartDate: string | null;
  actualFinishDate: string | null;
  earlyStartDate?: string | null;
  earlyFinishDate?: string | null;
  lateStartDate?: string | null;
  lateFinishDate?: string | null;
  duration: number;
  originalDuration?: number | null;
  atCompletionDuration?: number | null;
  percentComplete: number;
  slack: number;
  totalFloat: number;
  freeFloat?: number;
  remainingDuration: number;
  isCritical?: boolean;
  notes?: string;
  /** Soft FK to resource.work_activities.id — links this activity to its master library entry. */
  workActivityId?: string | null;
  /** Soft FK to cost.cost_accounts.id — the cost account directly assigned to this activity. */
  costAccountId?: string | null;
  calendarId?: string | null;
  primaryConstraintType?: ConstraintType | null;
  primaryConstraintDate?: string | null;
  secondaryConstraintType?: ConstraintType | null;
  secondaryConstraintDate?: string | null;
  percentCompleteType?: string | null;
  activityType?: string | null;
  durationType?: string | null;
  /** Cached supervisor Resource id (mirror of ResourceAssignment with isSupervisor=true). */
  responsibleResourceId?: string | null;
  /** Snapshot of the supervisor Resource name at the time the flag was set. */
  responsibleResourceName?: string | null;
  /**
   * Legacy single-supervisor cache. Kept in sync with {@code supervisors[0]} for back-compat;
   * new UIs should read {@code supervisors} instead. Null when no supervisors are assigned.
   */
  supervisorUserId?: string | null;
  /** Display-snapshot of the first supervisor's name. Mirror of {@code supervisorUserId}. */
  supervisorUserName?: string | null;
  /**
   * All supervisors assigned to this activity. The list is "all equal" — there is no primary.
   * Empty when no supervisor is assigned.
   */
  supervisors?: SupervisorEntry[];
  /** Default unit from the linked WorkActivity. Lets the DPR form auto-fill the unit field
   *  when the user picks an activity. Null when no work-activity link exists. */
  workActivityDefaultUnit?: string | null;
  createdAt: string;
  updatedAt: string;
}

export type PercentCompleteType = "DURATION" | "UNITS" | "PHYSICAL";

export interface CreateActivityRequest {
  code: string;
  name: string;
  projectId: string;
  wbsNodeId: string;
  originalDuration?: number;
  activityType?: string;
  durationType?: string;
  percentCompleteType?: PercentCompleteType;
  plannedStartDate?: string;
  plannedFinishDate?: string;
  calendarId?: string;
  /** Optional link to the WorkActivity (master library) this activity is an instance of. */
  workActivityId?: string | null;
  /** Optional cost account assignment; overrides WBS-level inheritance. */
  costAccountId?: string | null;
  primaryConstraintType?: ConstraintType;
  primaryConstraintDate?: string;
  secondaryConstraintType?: ConstraintType;
  secondaryConstraintDate?: string;
  /**
   * Supervisor: a User UUID (carrying SUPERVISOR / FOREMAN / SITE_ENGINEER /
   * SITE_MANAGER role) accountable for this activity. Phase 4.4 rename — the
   * legacy {@code supervisorResourceId} (Resource UUID) is gone on the backend.
   * Source the value from {@code userApi.listByRoles([...])}.
   */
  supervisorUserId?: string | null;
  /** Supervisor display-snapshot — frontend passes the picker option name. */
  supervisorUserName?: string | null;
}

export interface UpdateActivityRequest {
  name?: string;
  duration?: number;
  originalDuration?: number;
  percentComplete?: number;
  percentCompleteType?: PercentCompleteType;
  actualStartDate?: string;
  actualFinishDate?: string;
  notes?: string;
  calendarId?: string;
  activityType?: string;
  durationType?: string;
  plannedStartDate?: string;
  plannedFinishDate?: string;
  /** Pass to attach/change the WorkActivity link; omit (or null) to leave unchanged. */
  workActivityId?: string | null;
  /** Pass to set or clear the cost account assignment; omit to leave unchanged. */
  costAccountId?: string | null;
  primaryConstraintType?: ConstraintType | null;
  primaryConstraintDate?: string | null;
  secondaryConstraintType?: ConstraintType | null;
  secondaryConstraintDate?: string | null;
  /**
   * Pass to set/change supervisor; omit (null) to leave unchanged. User UUID
   * sourced from {@code userApi.listByRoles([...])}. Phase 4.4 rename of the
   * legacy {@code supervisorResourceId} Resource UUID.
   */
  supervisorUserId?: string | null;
  supervisorUserName?: string | null;
}

/**
 * Single-supervisor write — legacy. New UIs should use {@link SetSupervisorsRequest}
 * via {@code activityApi.setSupervisors}. This shape is kept so older clients don't break;
 * the backend translates it into a one-element list.
 */
export interface SetSupervisorRequest {
  /** User UUID (NOT a Resource UUID). Null clears the supervisor. */
  supervisorUserId: string | null;
  /** Display snapshot of the supervisor's name; persisted alongside the FK so list views
   * don't need a second hop. Null is acceptable when clearing. */
  supervisorName: string | null;
}

/** One supervisor on an activity. All entries are equal — there is no primary. */
export interface SupervisorEntry {
  userId: string;
  userName: string | null;
}

/**
 * Replace the supervisor set on an activity. Pass an empty list (or omit
 * {@code supervisors}) to clear all supervisors. Duplicates are deduplicated server-side.
 */
export interface SetSupervisorsRequest {
  supervisors: SupervisorEntry[];
}

export const activityApi = {
  listActivities: (projectId: string, page = 0, size = 20) =>
    apiClient
      .get<ApiResponse<PagedResponse<ActivityResponse>>>(`/v1/projects/${projectId}/activities`, {
        params: { page, size },
      })
      .then((r) => r.data),

  getActivity: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<ActivityResponse>>(`/v1/projects/${projectId}/activities/${activityId}`)
      .then((r) => r.data),

  createActivity: (projectId: string, data: CreateActivityRequest) =>
    apiClient
      .post<ApiResponse<ActivityResponse>>(`/v1/projects/${projectId}/activities`, data)
      .then((r) => r.data),

  updateActivity: (projectId: string, activityId: string, data: UpdateActivityRequest) =>
    apiClient
      .put<ApiResponse<ActivityResponse>>(
        `/v1/projects/${projectId}/activities/${activityId}`,
        data
      )
      .then((r) => r.data),

  deleteActivity: (projectId: string, activityId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/activities/${activityId}`),

  /** Activities under this project with no Work Activity linked, optionally filtered by window. */
  listMissingWorkActivity: (projectId: string, from?: string, to?: string) =>
    apiClient
      .get<ApiResponse<MissingWorkActivityRow[]>>(
        `/v1/projects/${projectId}/activities/missing-work-activity`,
        { params: { from, to } }
      )
      .then((r) => r.data),

  triggerSchedule: (projectId: string, option: string) =>
    apiClient
      // The backend returns a full ScheduleResultResponse including warnings + status breakdown;
      // typing it as a generic object had been hiding fields the schedule-log panel needs.
      .post<ApiResponse<import("./scheduleApi").ScheduleResultResponse>>(
        `/v1/projects/${projectId}/schedule`,
        { projectId, option }
      )
      .then((r) => r.data),

  getCriticalPath: (projectId: string) =>
    apiClient
      .get<ApiResponse<ActivityResponse[]>>(`/v1/projects/${projectId}/schedule/critical-path`)
      .then((r) => r.data),

  getRelationships: (projectId: string) =>
    apiClient
      .get<ApiResponse<RelationshipResponse[]>>(`/v1/projects/${projectId}/relationships`)
      .then((r) => r.data),

  getPredecessors: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<RelationshipResponse[]>>(
        `/v1/projects/${projectId}/relationships/predecessors/${activityId}`
      )
      .then((r) => r.data),

  getSuccessors: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<RelationshipResponse[]>>(
        `/v1/projects/${projectId}/relationships/successors/${activityId}`
      )
      .then((r) => r.data),

  createRelationship: (projectId: string, data: CreateRelationshipRequest) =>
    apiClient
      .post<ApiResponse<RelationshipResponse>>(
        `/v1/projects/${projectId}/relationships`,
        data
      )
      .then((r) => r.data),

  updateRelationship: (projectId: string, relationshipId: string, data: UpdateRelationshipRequest) =>
    apiClient
      .put<ApiResponse<RelationshipResponse>>(
        `/v1/projects/${projectId}/relationships/${relationshipId}`,
        data
      )
      .then((r) => r.data),

  deleteRelationship: (projectId: string, relationshipId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/relationships/${relationshipId}`),

  applyActuals: (projectId: string, dataDate: string) =>
    apiClient
      .put<ApiResponse<void>>(
        `/v1/projects/${projectId}/activities/apply-actuals`,
        { dataDate }
      )
      .then((r) => r.data),

  applyGlobalChange: (projectId: string, request: GlobalChangeRequest) =>
    apiClient
      .post<ApiResponse<{ updatedCount: number }>>(
        `/v1/projects/${projectId}/activities/global-change`,
        request
      )
      .then((r) => r.data),

  /**
   * Set (or clear) the supervisor for a single activity. Writes
   * {@code Activity.supervisorUserId} on the backend; the picker source is now
   * {@code /v1/users?roles=SUPERVISOR,...} (Phase 4.4 RBAC) instead of the legacy
   * project resource pool.
   *
   * Pass {@code supervisorUserId: null} to clear. The {@code supervisorName} snapshot
   * is denormalised onto the row so the UI doesn't need a second hop to render the
   * supervisor's name on activity / DPR lists.
   */
  setSupervisor: (
    projectId: string,
    activityId: string,
    body: SetSupervisorRequest
  ) =>
    apiClient
      .put<ApiResponse<ActivityResponse>>(
        `/v1/projects/${projectId}/activities/${activityId}/supervisor`,
        body
      )
      .then((r) => r.data),

  /**
   * Replace the supervisor set on an activity. All supervisors are equal — there is no
   * primary. Empty {@code supervisors} array clears everyone; the backend treats null
   * supervisor and zero supervisors as the same state.
   */
  setSupervisors: (
    projectId: string,
    activityId: string,
    body: SetSupervisorsRequest
  ) =>
    apiClient
      .put<ApiResponse<ActivityResponse>>(
        `/v1/projects/${projectId}/activities/${activityId}/supervisors`,
        body
      )
      .then((r) => r.data),

  /**
   * Bulk-assign one supervisor (a User with SUPERVISOR / FOREMAN / SITE_ENGINEER /
   * SITE_MANAGER role) across many activities. Powers the Resources → Supervisor sub-tab.
   * Returns count updated.
   *
   * Phase 4.4 rename: the request body field is now {@code supervisorUserId} (User UUID),
   * not {@code supervisorResourceId} (Resource UUID). Backend's legacy
   * {@code BulkSupervisorRequest} is {@code @Deprecated}; the new contract carries a
   * User id.
   */
  bulkSetSupervisor: (
    projectId: string,
    body: {
      supervisorUserId: string;
      supervisorName: string | null;
      activityIds: string[];
    }
  ) =>
    apiClient
      .post<ApiResponse<{ updated: number }>>(
        `/v1/projects/${projectId}/activities/supervisor-bulk`,
        body
      )
      .then((r) => r.data),

  updateProgress: (projectId: string, activityId: string, percentComplete: number, actualStartDate?: string, actualFinishDate?: string) =>
    apiClient
      .put<ApiResponse<ActivityResponse>>(
        `/v1/projects/${projectId}/activities/${activityId}/progress`,
        null,
        { params: { percentComplete, actualStartDate, actualFinishDate } }
      )
      .then((r) => r.data),

  /**
   * Flip an activity from DRAFT to LOCKED. Requires the {@code ACTIVITY.LOCK} permission.
   * Locking disables manual edits on the inputs but enables DPR submission against this activity.
   */
  lock: (projectId: string, activityId: string) =>
    apiClient
      .post<ApiResponse<ActivityResponse>>(
        `/v1/projects/${projectId}/activities/${activityId}/lock`
      )
      .then((r) => r.data),

  /**
   * Flip an activity from LOCKED back to DRAFT. Requires {@code ACTIVITY.UNLOCK}. Re-opens inputs
   * for editing; DPRs against the activity are rejected until it is locked again.
   */
  unlock: (projectId: string, activityId: string) =>
    apiClient
      .post<ApiResponse<ActivityResponse>>(
        `/v1/projects/${projectId}/activities/${activityId}/unlock`
      )
      .then((r) => r.data),
};

export interface GlobalChangeRequest {
  filterField: string;
  filterValue: string;
  updateField: string;
  updateValue: string;
  operation: "SET" | "ADD" | "SUBTRACT";
}

// === Relationships ===

export type RelationshipType = "FINISH_TO_START" | "FINISH_TO_FINISH" | "START_TO_START" | "START_TO_FINISH";

export interface RelationshipResponse {
  id: string;
  predecessorActivityId: string;
  successorActivityId: string;
  relationshipType: RelationshipType;
  lag: number;
  isExternal: boolean;
}

export interface CreateRelationshipRequest {
  predecessorActivityId: string;
  successorActivityId: string;
  relationshipType?: RelationshipType;
  lag?: number;
}

export interface UpdateRelationshipRequest {
  relationshipType: string;
  lag?: number;
}

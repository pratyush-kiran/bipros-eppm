// Shared DPR domain types — mirror the backend records in
// backend/bipros-project/.../dto. Kept in /lib/types so non-API callers (forms,
// charts, AI insights) can import without dragging the axios client along.

export type Side = "LHS" | "RHS" | "CENTER";
export type Shift = "DAY" | "NIGHT";
export type DprApprovalStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED";
export type SafetyIncidentType = "NONE" | "NEAR_MISS" | "INCIDENT";
export type ManpowerCategory = "SKILLED" | "SEMI_SKILLED" | "UNSKILLED";
export type EquipmentOwnership = "OWNED" | "HIRED" | "SUBCONTRACTOR";
export type EquipmentAvailability = "AVAILABLE" | "UTILIZED" | "IDLE" | "BREAKDOWN";

export type Unit = "Cum" | "MT" | "Rm" | "Each" | "Sqm" | "R/mtr" | "Nr";

export type RateBasis = "HOUR" | "DAY" | "EACH";

export interface DprManpowerRow {
  id?: string | null;
  /** Optional in the role-only flow; legacy callers may still send it. */
  resourceAssignmentId?: string | null;
  resourceId?: string | null;
  trade: string;
  category?: ManpowerCategory | null;
  nos?: number | null;
  workingHours?: number | null;
  otHours?: number | null;
  /** Idle hours (per crew member). Feeds KPI 1.2 Idle Time Ratio. */
  idleHours?: number | null;
  unitRate?: number | null;
  unitRateBasis?: RateBasis | null;
  lineCost?: number | null;
  contractorName?: string | null;
  remarks?: string | null;
  /** Role-only model: the manpower-rate variant consumed. */
  manpowerRoleRateId?: string | null;
  roleId?: string | null;
}

export interface DprEquipmentRow {
  id?: string | null;
  resourceAssignmentId?: string | null;
  resourceId?: string | null;
  equipmentType: string;
  fleetNo?: string | null;
  ownership?: EquipmentOwnership | null;
  nos?: number | null;
  workingHours?: number | null;
  idleHours?: number | null;
  breakdownHours?: number | null;
  fuelLitres?: number | null;
  unitRate?: number | null;
  unitRateBasis?: RateBasis | null;
  lineCost?: number | null;
  operatorName?: string | null;
  availabilityStatus?: EquipmentAvailability | null;
  remarks?: string | null;
  /** Role-only model: which equipment variant. */
  equipmentRoleVariantId?: string | null;
  roleId?: string | null;
}

export interface DprMaterialRow {
  id?: string | null;
  resourceAssignmentId?: string | null;
  materialId?: string | null;
  resourceId?: string | null;
  materialName: string;
  quantity?: number | null;
  unit?: string | null;
  source?: string | null;
  batchNo?: string | null;
  vendorName?: string | null;
  unitRate?: number | null;
  lineCost?: number | null;
  remarks?: string | null;
  /** Role-only model: which material variant. */
  materialRoleVariantId?: string | null;
  roleId?: string | null;
}

export type IssueCategory =
  | "SAFETY"
  | "QUALITY"
  | "MATERIAL_SHORTAGE"
  | "EQUIPMENT_BREAKDOWN"
  | "MANPOWER_SHORTAGE"
  | "WEATHER"
  | "DESIGN_CHANGE"
  | "LAND_ACCESS"
  | "UTILITY_CLASH"
  | "PERMIT_DELAY"
  | "SUBCONTRACTOR"
  | "ENVIRONMENTAL"
  | "OTHER";

export type IssueSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type IssueStatus =
  | "OPEN"
  | "IN_PROGRESS"
  | "BLOCKED"
  | "RESOLVED"
  | "CLOSED"
  | "CANCELLED";

/**
 * Field-issue row attached to a DPR. {@code id} is null on insert, non-null on update; the
 * backend uses merge-by-id semantics (rows absent from a re-save are deleted). Status
 * transitions to RESOLVED/CLOSED auto-stamp {@code resolvedAt} server-side; the client must
 * not set it directly.
 */
export interface DprIssueRow {
  id?: string | null;
  dprId?: string | null;
  activityId?: string | null;
  activityName?: string | null;
  reportDate?: string | null;
  title: string;
  description?: string | null;
  category: IssueCategory;
  severity: IssueSeverity;
  status: IssueStatus;
  supervisorUserId?: string | null;
  supervisorName?: string | null;
  assignedToUserId?: string | null;
  assignedToName?: string | null;
  openedAt?: string | null;
  resolvedAt?: string | null;
  resolutionNotes?: string | null;
}

/** Request body for creating a standalone DprIssue not tied to a parent DPR. */
export interface CreateDprIssueRequest {
  title: string;
  description?: string | null;
  category: IssueCategory;
  severity: IssueSeverity;
  status?: IssueStatus;
  supervisorResourceId?: string | null;
  supervisorName?: string | null;
  assignedToResourceId?: string | null;
  assignedToName?: string | null;
  activityId?: string | null;
  activityName?: string | null;
  reportDate?: string | null;
}

/** Picker-mode option returned by GET .../resource-assignments/activity/{id}/picker?kind=… */
export interface AssignedResourceOption {
  assignmentId: string;
  resourceId: string;
  resourceName: string;
  resourceCode?: string | null;
  unit?: string | null;
  unitRateBasis?: RateBasis | null;
  unitRate?: number | null;
  rateType?: string | null;
  plannedUnits?: number | null;
  actualUnits?: number | null;
  plannedCost?: number | null;
  actualCost?: number | null;
  kind?: "MANPOWER" | "EQUIPMENT" | "MATERIAL" | null;
}

export interface DprBaseFields {
  reportDate: string;
  supervisorUserId?: string | null;
  supervisorName: string;
  chainageFromM?: number | null;
  chainageToM?: number | null;
  /** Soft FK to activity.activities.id. Required to load assigned resources for the picker. */
  activityId?: string | null;
  activityName: string;
  wbsNodeId?: string | null;
  boqItemNo?: string | null;
  unit: string;
  qtyExecuted: number;
  weatherCondition?: string | null;
  remarks?: string | null;

  side?: Side | null;
  landmark?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  shift?: Shift | null;
  approvalStatus?: DprApprovalStatus | null;
  contractorName?: string | null;
  delayReason?: string | null;
  safetyObservation?: string | null;
  safetyIncidentType?: SafetyIncidentType | null;

  manpower?: DprManpowerRow[];
  equipment?: DprEquipmentRow[];
  materials?: DprMaterialRow[];
  issues?: DprIssueRow[];
}

/**
 * Photo attached to a DPR row. Mirrors the backend {@code DprAttachmentResponse} record. The
 * binary itself is fetched from {@code GET /v1/projects/{projectId}/dpr/{dprId}/photos/{id}},
 * which is JWT-protected — see {@code dprApi.fetchPhotoBlobUrl} for the auth-aware blob loader
 * used to feed {@code <img src>}.
 */
export interface DprAttachment {
  id: string;
  dprId: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  caption: string | null;
  capturedAt: string | null;
  createdAt: string;
}

export interface DailyProgressReportResponse extends DprBaseFields {
  id: string;
  projectId: string;
  cumulativeQty: number | null;
  manpower: DprManpowerRow[];
  equipment: DprEquipmentRow[];
  materials: DprMaterialRow[];
  attachments?: DprAttachment[];
  issues?: DprIssueRow[];
  /** Server-side warnings (e.g. rate-missing:trade-name, assignment-not-found:uuid). */
  warnings?: string[];
}

export type CreateDailyProgressReportRequest = DprBaseFields;
export type UpdateDailyProgressReportRequest = DprBaseFields;

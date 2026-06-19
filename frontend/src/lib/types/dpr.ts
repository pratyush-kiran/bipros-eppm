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
  /** Per-row shift (DAY / NIGHT). Mixed-shift days use separate rows. Defaults DAY server-side. */
  shift?: Shift | null;
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
  /** Per-row shift (DAY / NIGHT). Mixed-shift days use separate rows. Defaults DAY server-side. */
  shift?: Shift | null;
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

export interface DprSubContractorRow {
  id?: string | null;
  /** Required FK to a planned ActivitySubContractorAssignment for this activity. */
  activitySubContractorAssignmentId: string;
  // Snapshots — populated by server on read.
  subContractorMasterId?: string | null;
  subContractorName?: string | null;
  subContractorCode?: string | null;
  workActivityName?: string | null;
  unit?: string | null;
  ratePerUnit?: number | null;
  /** Required — units delivered by this sub-contractor on this DPR's date. */
  quantity: number;
  /** Computed = quantity × ratePerUnit. Response-only. */
  lineCost?: number | null;
  remarks?: string | null;
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
  /** Workstream B1: new canonical FK to BoqItem.id. Prefer over {@link boqItemNo}. */
  boqItemId?: string | null;
  /** Legacy back-link by item-number string. New clients should send {@link boqItemId}. */
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
  subContractors?: DprSubContractorRow[];
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

/**
 * Voice note attached to a DPR row. Mirrors the backend {@code DprVoiceNoteResponse} record.
 * Distinct from the "Voice fill" feature: voice notes are persisted audio attachments (like
 * photos), never transcribed. The audio itself is streamed from
 * {@code GET /v1/projects/{projectId}/dpr/{dprId}/voice-notes/{id}/stream}, which is
 * JWT-protected — see {@code dprApi.fetchVoiceNoteBlobUrl} for the auth-aware blob loader used
 * to feed {@code <audio src>}.
 */
export interface DprVoiceNote {
  id: string;
  dprId: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  durationSeconds: number | null;
  caption: string | null;
  createdAt: string;
}

export interface DailyProgressReportResponse extends DprBaseFields {
  id: string;
  projectId: string;
  cumulativeQty: number | null;
  manpower: DprManpowerRow[];
  equipment: DprEquipmentRow[];
  materials: DprMaterialRow[];
  subContractors: DprSubContractorRow[];
  attachments?: DprAttachment[];
  voiceNotes?: DprVoiceNote[];
  issues?: DprIssueRow[];
  /** Server-side warnings (e.g. rate-missing:trade-name, assignment-not-found:uuid). */
  warnings?: string[];
}

export type CreateDailyProgressReportRequest = DprBaseFields;
export type UpdateDailyProgressReportRequest = DprBaseFields;

/**
 * Slim DPR list row returned by the paginated list endpoint. Carries parent fields used by the
 * Day → Activity → Work-front grouping + collapsed row, plus precomputed child aggregates. Full
 * child detail (manpower/equipment/material/sub-contractor/issue rows, cumulativeQty, landmark,
 * remarks) comes from GET /dpr/{id} on row expand — see DailyProgressReportResponse.
 */
export interface DprSummaryRow {
  id: string;
  projectId: string;
  reportDate: string;
  supervisorUserId?: string | null;
  supervisorName: string;
  chainageFromM?: number | null;
  chainageToM?: number | null;
  activityId?: string | null;
  activityName: string;
  boqItemNo?: string | null;
  unit: string;
  qtyExecuted?: number | null;
  side?: Side | null;
  approvalStatus?: DprApprovalStatus | null;
  weatherCondition?: string | null;
  manpowerNos: number;
  equipmentNos: number;
  materialCount: number;
  photoCount: number;
  issueCount: number;
  openIssueCount: number;
  hasCriticalOpen: boolean;
}

/** One page of the day-cursored DPR list. */
export interface DprPage {
  items: DprSummaryRow[];
  /** Oldest report date in this batch; pass as `before` for the next page. Null when no more. */
  nextCursor: string | null;
  hasMore: boolean;
}

// === API Response Envelope ===

export interface ApiResponse<T> {
  data: T | null;
  error: ApiError | null;
  meta: {
    timestamp: string;
    version: string;
  };
}

export interface ApiError {
  code: string;
  message: string;
  details?: FieldError[];
}

export interface FieldError {
  field: string;
  reason: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
  pagination: {
    totalElements: number;
    totalPages: number;
    currentPage: number;
    pageSize: number;
  };
}

// === Auth ===

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  firstName?: string;
  lastName?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface UserResponse {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  enabled: boolean;
  roles: string[];
  /**
   * Fine-grained permission codes (e.g. {@code "PROJECT.READ"}, {@code "ADMIN_USER.UPDATE"})
   * resolved server-side from the user's profile/legacy role. Populated by
   * {@code /v1/auth/me} (Phase 2 — backend commit {@code e6385bf}). Empty array for users
   * with no profile mapping; ADMIN role short-circuits all gates regardless.
   */
  permissions?: string[];
  profileId?: string | null;
  profileName?: string | null;
  // IC-PMS fields (nullable for legacy users)
  organisationId?: string | null;
  designation?: string | null;
  primaryIcpmsRole?: string | null;
  authMethods?: AuthMethod[] | null;
  // PMS MasterData Screen 07 — Personnel Master
  employeeCode?: string | null;
  mobile?: string | null;
  department?: Department | null;
  joiningDate?: string | null;
  contractEndDate?: string | null;
  presenceStatus?: PresenceStatus | null;
  assignedStretchIds?: string[] | null;
}

// === Permission Profiles ===

export interface PermissionDescriptor {
  code: string;
  module: string;
  action: string;
  label: string;
}

export interface ProfileResponse {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  systemDefault: boolean;
  legacyRoleName: string;
  permissions: string[];
}

export interface CreateProfileRequest {
  code: string;
  name: string;
  description?: string;
  legacyRoleName: string;
  permissions: string[];
}

export interface UpdateProfileRequest {
  name?: string;
  description?: string;
  legacyRoleName?: string;
  permissions?: string[];
}

// === Project Structure ===

export type ProjectStatus = "PLANNED" | "ACTIVE" | "INACTIVE" | "COMPLETED";

export interface EpsNodeResponse {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  obsId: string | null;
  sortOrder: number;
  children: EpsNodeResponse[];
}

export interface ObsNodeResponse {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  description: string;
  sortOrder: number;
  children: ObsNodeResponse[];
}

export interface NodeSearchResult {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  ancestorIds: string[];
  pathLabel: string;
}

export interface ProjectResponse {
  id: string;
  code: string;
  name: string;
  description: string;
  epsNodeId: string;
  obsNodeId: string | null;
  plannedStartDate: string;
  plannedFinishDate: string;
  dataDate: string | null;
  status: ProjectStatus;
  industryCode: string | null;
  mustFinishByDate: string | null;
  priority: number;
  // PMS MasterData Screen 01 fields
  category: string | null;
  morthCode: string | null;
  fromChainageM: number | null;
  toChainageM: number | null;
  fromLocation: string | null;
  toLocation: string | null;
  totalLengthKm: number | null;
  calendarId: string | null;
  /** Deprecated: read-only mirror of {@link primaryBaselineId}. */
  activeBaselineId: string | null;
  /** Phase 3: P6-style PRIMARY baseline slot. Drives variance + Gantt overlay by default. */
  primaryBaselineId: string | null;
  /** Phase 3: SECONDARY slot. Independent of PRIMARY. */
  secondaryBaselineId: string | null;
  /** Phase 3: TERTIARY slot. */
  tertiaryBaselineId: string | null;
  /**
   * Set by listeners (e.g. VariationOrderApprovedListener) when something material changed
   * that should be reflected in a fresh baseline. Cleared on the next createBaseline call.
   * Drives the CC-1 banner on the project page.
   */
  requiresRebaseline?: boolean;
  contract: {
    contractId: string | null;
    contractNumber: string | null;
    contractType: string | null;
    contractValue: number | null;
    revisedValue: number | null;
    startDate: string | null;
    completionDate: string | null;
    dlpMonths: number | null;
  } | null;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
  // P6-style budget fields
  originalBudget: number | null;
  currentBudget: number | null;
  budgetCurrency: string | null;
}

export type WbsType = "PROGRAMME" | "NODE" | "PACKAGE" | "WORK_PACKAGE";
export type WbsPhase = "PROGRAMME" | "CONSTRUCTION" | "MOBILISATION" | "TENDER" | "PLANNING";
export type WbsStatus = "ACTIVE" | "IN_PROGRESS" | "NOT_STARTED" | "COMPLETED" | "DELAYED" | "AT_RISK";

export interface WbsNodeResponse {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  projectId: string;
  obsNodeId: string | null;
  sortOrder: number;
  summaryDuration: number | null;
  summaryPercentComplete: number | null;
  wbsLevel: number | null;
  wbsType: WbsType | null;
  phase: WbsPhase | null;
  wbsStatus: WbsStatus | null;
  responsibleOrganisationId: string | null;
  plannedStart: string | null;
  plannedFinish: string | null;
  budgetCrores: number | null;
  gisPolygonId: string | null;
  costAccountId: string | null;
  children: WbsNodeResponse[];
}

export interface CreateProjectRequest {
  code: string;
  name: string;
  description?: string;
  epsNodeId: string;
  obsNodeId?: string;
  plannedStartDate: string;
  plannedFinishDate?: string;
  priority?: number;
  // PMS MasterData Screen 01 enrichment
  category?: ProjectCategory | null;
  morthCode?: string | null;
  fromChainageM?: number | null;
  toChainageM?: number | null;
  fromLocation?: string | null;
  toLocation?: string | null;
  totalLengthKm?: number | null;
  calendarId?: string | null;
  contract?: ContractSummaryInput | null;
}

export interface UpdateProjectRequest {
  name?: string;
  description?: string;
  obsNodeId?: string;
  plannedStartDate?: string;
  plannedFinishDate?: string;
  mustFinishByDate?: string;
  dataDate?: string;
  status?: string;
  priority?: number;
  category?: ProjectCategory | null;
  morthCode?: string | null;
  fromChainageM?: number | null;
  toChainageM?: number | null;
  fromLocation?: string | null;
  toLocation?: string | null;
  totalLengthKm?: number | null;
  calendarId?: string | null;
  contract?: ContractSummaryInput | null;
}

export interface CreateEpsNodeRequest {
  code: string;
  name: string;
  parentId?: string;
  obsId?: string;
}

// === Activities ===

export interface ActivityResponse {
  id: string;
  code: string;
  name: string;
  projectId: string;
  wbsNodeId: string;
  status: string;
  plannedStartDate: string | null;
  plannedFinishDate: string | null;
  earlyStartDate?: string | null;
  earlyFinishDate?: string | null;
  lateStartDate?: string | null;
  lateFinishDate?: string | null;
  actualStartDate: string | null;
  actualFinishDate: string | null;
  // Backend returns `originalDuration` (float); `duration` kept for legacy callers.
  duration: number;
  originalDuration?: number | null;
  atCompletionDuration?: number | null;
  percentComplete: number;
  slack: number;
  totalFloat: number;
  freeFloat?: number;
  remainingDuration: number;
  isCritical?: boolean;
  calendarId?: string | null;
  createdAt: string;
  updatedAt: string;
}

// === Resources ===
// The canonical Resource shapes live in `src/lib/api/resourceApi.ts`. Re-exported
// here for callers that import from `@/lib/types` for legacy reasons.
export type {
  ResourceResponse,
  ResourceStatus,
  CreateResourceRequest,
  EquipmentDetailsDto,
  MaterialDetailsDto,
  ManpowerDto,
} from "@/lib/api/resourceApi";

// === Risk ===

export interface RiskAnalysisQuality {
  score: number;
  level: "NOT_ANALYSED" | "PARTIALLY_ANALYSED" | "WELL_ANALYSED";
  criteria: {
    hasOwner: boolean;
    hasRating: boolean;
    hasDescription: boolean;
    hasResponse: boolean;
  };
}

export interface RiskResponse {
  id: string;
  code: string;
  title: string;
  description: string;
  category: string;
  probability: number;
  impact: number;
  score: number;
  status: "OPEN" | "MITIGATED" | "CLOSED";
  owner: string;
  createdAt: string;
  updatedAt: string;
  analysisQuality?: RiskAnalysisQuality;
}

export interface CreateRiskRequest {
  code: string;
  title: string;
  description: string;
  /** Preferred FK to risk_category_master.id. */
  categoryId?: string;
  /** Back-compat: legacy enum-string code (e.g. "LAND_ACQUISITION"); resolved server-side. */
  legacyCategoryCode?: string;
  probability: number;
  impactCost: number;
  impactSchedule: number;
}

// === Calendar ===

export interface CalendarResponse {
  id: string;
  code: string;
  name: string;
  type: "GLOBAL" | "PROJECT" | "RESOURCE";
  baseCalendarId: string | null;
  workHoursPerDay: number;
  workDaysPerWeek: number;
  createdAt: string;
  updatedAt: string;
}

// === Documents ===

export interface DocumentFolder {
  id: string;
  projectId: string;
  name: string;
  code: string;
  category: string;
  parentId: string | null;
  wbsNodeId: string | null;
  sortOrder: number;
}

export interface DocumentResponse {
  id: string;
  folderId: string;
  projectId: string;
  documentNumber: string;
  title: string;
  description: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  filePath: string;
  currentVersion: number;
  status: "DRAFT" | "UNDER_REVIEW" | "APPROVED" | "SUPERSEDED" | "ARCHIVED";
  tags: string;
  createdAt: string;
  updatedAt: string;
}

export interface DrawingRegisterResponse {
  id: string;
  projectId: string;
  documentId: string | null;
  drawingNumber: string;
  title: string;
  discipline: string;
  revision: string;
  revisionDate: string;
  status: "PRELIMINARY" | "IFA" | "IFC" | "AS_BUILT" | "SUPERSEDED";
  packageCode: string;
  scale: string;
  createdAt: string;
  updatedAt: string;
}

export interface TransmittalResponse {
  id: string;
  projectId: string;
  transmittalNumber: string;
  subject: string;
  fromParty: string;
  toParty: string;
  sentDate: string;
  dueDate: string;
  status: "DRAFT" | "SENT" | "RECEIVED" | "ACKNOWLEDGED" | "OVERDUE";
  remarks: string;
  createdAt: string;
  updatedAt: string;
}

export interface RfiRegisterResponse {
  id: string;
  projectId: string;
  rfiNumber: string;
  subject: string;
  description: string;
  raisedBy: string;
  assignedTo: string;
  raisedDate: string;
  dueDate: string;
  closedDate: string | null;
  status: "OPEN" | "RESPONDED" | "CLOSED" | "OVERDUE";
  priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  response: string;
  createdAt: string;
  updatedAt: string;
}

// === Resources ===

export interface ResourceAssignmentResponse {
  id: string;
  activityId: string;
  resourceId: string | null;
  resourceName: string | null;
  roleId: string | null;
  roleName: string | null;
  projectId: string;
  plannedUnits: number;
  actualUnits: number;
  remainingUnits: number;
  rateType: string;
  plannedCost: number;
  actualCost: number;
  remainingCost: number;
  staffed: boolean;
  plannedStartDate: string | null;
  plannedFinishDate: string | null;
}

export interface CreateResourceAssignmentRequest {
  activityId: string;
  resourceId?: string;
  roleId?: string;
  plannedUnits: number;
  rateType: string;
}

// === Costs ===

export interface ExpenseResponse {
  id: string;
  projectId: string;
  activityId: string | null;
  name: string | null;
  description: string;
  expenseCategory: string;
  budgetedCost: number | null;
  actualCost: number;
  remainingCost: number | null;
  atCompletionCost: number | null;
  percentComplete: number | null;
  currency: string;
  plannedStartDate: string | null;
  plannedFinishDate: string | null;
  actualStartDate: string | null;
  actualFinishDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CostSummaryResponse {
  projectId: string;
  totalBudget: number;
  totalActual: number;
  totalRemaining: number;
  atCompletion: number;
  currency: string;
}

// === EVM ===

export interface EvmCalculationResponse {
  projectId: string;
  periodDate: string;
  plannedValue: number;
  earnedValue: number;
  actualCost: number;
  scheduleVariance: number;
  costVariance: number;
  spi: number;
  cpi: number;
  eac: number;
  etc: number;
}

export interface EvmMetricsResponse {
  projectId: string;
  periodDate: string;
  pv: number;
  ev: number;
  ac: number;
  sv: number;
  cv: number;
  spi: number;
  cpi: number;
  eac: number;
  etc: number;
  vac: number;
  tcpi: number;
}

export interface EvmHistoryEntryResponse {
  periodDate: string;
  pv: number;
  ev: number;
  ac: number;
}

// === Baseline ===

export interface BaselineResponse {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  baselineType: "PROJECT" | "PRIMARY" | "SECONDARY" | "TERTIARY";
  baselineDate: string;
  isActive: boolean;
  totalActivities: number;
  totalCost: number;
  projectDuration: number;
  projectStartDate: string | null;
  projectFinishDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BaselineVarianceRow {
  activityId: string;
  activityName: string;
  startVarianceDays: number;
  finishVarianceDays: number;
  durationVariance: number;
  costVariance: number;
}

// === Portfolio ===

export interface PortfolioResponse {
  id: string;
  code: string;
  name: string;
  description: string;
  projectCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePortfolioRequest {
  code: string;
  name: string;
  description?: string;
}

export interface PortfolioProjectResponse {
  projectId: string;
  projectCode: string;
  projectName: string;
}

export interface PortfolioScoringCriterion {
  id: string;
  name: string;
  weight: number;
  description?: string;
}

export interface PortfolioScenarioResponse {
  id: string;
  name: string;
  description: string;
  createdAt: string;
}

// === OBS (Organizational Breakdown Structure) ===

export interface CreateObsNodeRequest {
  code: string;
  name: string;
  description?: string;
  parentId?: string;
}

// === Contracts & Procurement ===

export type ProcurementMethod = "OPEN_TENDER" | "LIMITED_TENDER" | "GEM_PORTAL" | "SINGLE_SOURCE" | "EOI" | "DESIGN_BUILD";
export type ProcurementPlanStatus = "DRAFT" | "APPROVED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
export type TenderStatus = "DRAFT" | "PUBLISHED" | "BID_OPEN" | "EVALUATION" | "AWARDED" | "CANCELLED";
export type BidSubmissionStatus = "SUBMITTED" | "TECHNICALLY_QUALIFIED" | "NOT_QUALIFIED" | "L1" | "AWARDED" | "REJECTED";
export type ContractStatus =
  | "DRAFT"
  | "MOBILISATION"
  | "ACTIVE"
  | "ACTIVE_AT_RISK"
  | "ACTIVE_DELAYED"
  | "DELAYED"
  | "SUSPENDED"
  | "COMPLETED"
  | "TERMINATED"
  | "DLP";
export type MilestoneStatus = "PENDING" | "ACHIEVED" | "DELAYED" | "WAIVED";
export type VariationOrderStatus = "INITIATED" | "RECOMMENDED" | "APPROVED" | "REJECTED";
export type BondType = "PERFORMANCE_GUARANTEE" | "EMD" | "ADVANCE_GUARANTEE" | "RETENTION";
export type BondStatus = "ACTIVE" | "EXPIRED" | "RELEASED" | "INVOKED";

export interface ProcurementPlanResponse {
  id: string;
  projectId: string;
  wbsNodeId: string | null;
  planCode: string;
  description: string;
  procurementMethod: ProcurementMethod;
  estimatedValue: number;
  currency: string;
  targetNitDate: string;
  targetAwardDate: string;
  status: ProcurementPlanStatus;
  approvalLevel: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProcurementPlanRequest {
  projectId: string;
  wbsNodeId?: string;
  planCode: string;
  description: string;
  procurementMethod: ProcurementMethod;
  estimatedValue: number;
  currency: string;
  targetNitDate: string;
  targetAwardDate: string;
  approvalLevel?: string;
  approvedBy?: string;
}

export interface TenderResponse {
  id: string;
  procurementPlanId: string;
  projectId: string;
  tenderNumber: string;
  nitDate: string;
  scope: string;
  estimatedValue: number;
  emdAmount: number;
  completionPeriodDays: number;
  bidDueDate: string;
  bidOpenDate: string;
  status: TenderStatus;
  awardedContractId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateTenderRequest {
  procurementPlanId: string;
  projectId: string;
  tenderNumber: string;
  nitDate: string;
  scope: string;
  estimatedValue: number;
  emdAmount: number;
  completionPeriodDays: number;
  bidDueDate: string;
  bidOpenDate: string;
}

export interface BidSubmissionResponse {
  id: string;
  tenderId: string;
  bidderName: string;
  bidderCode: string;
  technicalScore: number;
  financialBid: number;
  status: BidSubmissionStatus;
  evaluationRemarks: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateBidSubmissionRequest {
  tenderId: string;
  bidderName: string;
  bidderCode: string;
  technicalScore: number;
  financialBid: number;
  evaluationRemarks?: string;
}

export type BillingCycle = "MONTHLY" | "QUARTERLY" | "MILESTONE_BASED" | "ON_COMPLETION";

export interface ContractResponse {
  id: string;
  projectId: string;
  tenderId: string | null;
  contractNumber: string;
  loaNumber: string | null;
  contractorName: string;
  contractorCode: string | null;
  contractValue: number;
  revisedValue: number | null;
  loaDate: string;
  startDate: string;
  completionDate: string;
  revisedCompletionDate: string | null;
  dlpMonths: number;
  ldRate: number;
  status: ContractStatus;
  contractType: ContractType;
  // P6/PCM-inspired commercial fields
  description: string | null;
  currency: string;
  ntpDate: string | null;
  mobilisationAdvancePct: number | null;
  retentionPct: number | null;
  performanceBgPct: number | null;
  paymentTermsDays: number | null;
  billingCycle: BillingCycle | null;
  // IC-PMS denormalised KPI fields
  wbsPackageCode: string | null;
  packageDescription: string | null;
  actualCompletionDate: string | null;
  spi: number | null;
  cpi: number | null;
  physicalProgressAi: number | null;
  cumulativeRaBillsCrores: number | null;
  voNumbersIssued: number | null;
  voValueCrores: number | null;
  performanceScore: number | null;
  bgExpiry: string | null;
  kpiRefreshedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateContractRequest {
  projectId: string;
  tenderId?: string;
  contractNumber: string;
  loaNumber?: string;
  contractorName: string;
  contractorCode?: string;
  contractValue: number;
  revisedValue?: number;
  loaDate: string;
  startDate: string;
  completionDate: string;
  revisedCompletionDate?: string;
  dlpMonths?: number;
  ldRate: number;
  contractType: ContractType;
  description?: string;
  currency?: string;
  ntpDate?: string;
  mobilisationAdvancePct?: number;
  retentionPct?: number;
  performanceBgPct?: number;
  paymentTermsDays?: number;
  billingCycle?: BillingCycle;
}

export interface ContractMilestoneResponse {
  id: string;
  contractId: string;
  milestoneCode: string;
  milestoneName: string;
  targetDate: string;
  actualDate: string | null;
  paymentPercentage: number;
  amount: number;
  status: MilestoneStatus;
  attachmentCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateContractMilestoneRequest {
  contractId: string;
  milestoneCode: string;
  milestoneName: string;
  targetDate: string;
  actualDate?: string;
  paymentPercentage: number;
  amount: number;
}

export type VoLineItemAction =
  | "ADD_ITEM"
  | "REVISE_QTY"
  | "REVISE_RATE"
  | "DELETE_ITEM";

export interface VoLineItemResponse {
  id: string;
  variationOrderId: string;
  action: VoLineItemAction;
  boqItemId: string | null;
  newItemNo: string | null;
  newItemDescription: string | null;
  newItemUnit: string | null;
  revisedQty: number | null;
  revisedRate: number | null;
  lineImpactAmount: number | null;
}

export interface VoLineItemRequest {
  id?: string | null;
  action: VoLineItemAction;
  boqItemId?: string | null;
  newItemNo?: string | null;
  newItemDescription?: string | null;
  newItemUnit?: string | null;
  revisedQty?: number | null;
  revisedRate?: number | null;
  lineImpactAmount?: number | null;
}

export interface VariationOrderResponse {
  id: string;
  contractId: string;
  voNumber: string;
  description: string;
  voValue: number;
  justification: string;
  status: VariationOrderStatus;
  impactOnBudget: number;
  impactOnScheduleDays: number;
  approvedBy: string | null;
  approvedAt: string | null;
  attachmentCount: number;
  createdAt: string;
  updatedAt: string;
  lineItems: VoLineItemResponse[];
}

export interface CreateVariationOrderRequest {
  contractId: string;
  voNumber: string;
  description: string;
  voValue: number;
  justification: string;
  impactOnBudget: number;
  impactOnScheduleDays: number;
  approvedBy?: string;
  lineItems?: VoLineItemRequest[];
}

export interface PerformanceBondResponse {
  id: string;
  contractId: string;
  bondType: BondType;
  bondValue: number;
  bankName: string;
  issueDate: string;
  expiryDate: string;
  status: BondStatus;
  attachmentCount: number;
  createdAt: string;
  updatedAt: string;
}

// === Contract Attachments ===

export type AttachmentEntityType =
  | "CONTRACT"
  | "MILESTONE"
  | "VARIATION_ORDER"
  | "PERFORMANCE_BOND";

export type ContractAttachmentType =
  | "LOA"
  | "AGREEMENT"
  | "BOQ"
  | "DRAWING"
  | "BG_SCAN"
  | "MOM"
  | "MEASUREMENT_BOOK"
  | "TEST_REPORT"
  | "CERTIFICATE"
  | "PHOTO"
  | "OTHER";

export interface ContractAttachment {
  id: string;
  projectId: string;
  contractId: string;
  entityType: AttachmentEntityType;
  entityId: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  attachmentType: ContractAttachmentType;
  description: string | null;
  uploadedBy: string | null;
  uploadedAt: string | null;
  createdAt: string;
}

export interface UploadContractAttachmentMetadata {
  attachmentType: ContractAttachmentType;
  description?: string | null;
}

export interface CreatePerformanceBondRequest {
  contractId: string;
  bondType: BondType;
  bondValue: number;
  bankName: string;
  issueDate: string;
  expiryDate: string;
}

export interface ContractorScorecardResponse {
  id: string;
  contractId: string;
  period: string;
  qualityScore: number;
  safetyScore: number;
  progressScore: number;
  paymentComplianceScore: number;
  overallScore: number;
  remarks: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateContractorScorecardRequest {
  contractId: string;
  period: string;
  qualityScore: number;
  safetyScore: number;
  progressScore: number;
  paymentComplianceScore: number;
  overallScore: number;
  remarks?: string;
}

// === WBS Templates and Asset Classes ===

export type AssetClass =
  | "ROAD"
  | "RAIL"
  | "POWER"
  | "WATER"
  | "ICT"
  | "BUILDING"
  | "GREEN_INFRASTRUCTURE";

export interface WbsTemplateResponse {
  id: string;
  code: string;
  name: string;
  assetClass: AssetClass;
  description: string;
  defaultStructure: string; // JSON string
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWbsTemplateRequest {
  code: string;
  name: string;
  assetClass: AssetClass;
  description?: string;
  defaultStructure: string;
  isActive?: boolean;
}

// === AI WBS Generation ===

export interface WbsAiNode {
  code: string;
  name: string;
  description?: string;
  /** When set, the AI is asking us to graft this node under an existing project node by code. */
  parentCode?: string | null;
  children?: WbsAiNode[];
}

export interface WbsAiGenerateRequest {
  assetClass?: AssetClass | null;
  projectTypeHint?: string;
  additionalContext?: string;
  targetDepth?: number;
}

export type ApplyMode = "MERGE" | "ADD_UNDER" | "REPLACE";

export interface WbsAiApplyRequest {
  parentId?: string | null;
  mode?: ApplyMode;
  nodes: WbsAiNode[];
}

export type CollisionAction =
  | "SKIPPED_DUPLICATE"
  | "RENAMED"
  | "RESOLVED_TO_EXISTING_PARENT"
  | "INSERTED_NEW"
  /** Activity-only: wbsNodeCode does not exist in this project; will be skipped on apply. */
  | "MISSING_WBS_NODE"
  /** Activity-only: wbsNodeCode close to an existing code; resolvedCode carries the suggestion. */
  | "WBS_NEAR_MATCH";

export interface CollisionResult {
  originalCode: string;
  resolvedCode: string;
  action: CollisionAction;
  reason?: string | null;
}

export interface WbsAiGenerationResponse {
  resolvedAssetClass: AssetClass | null;
  assetClassNeedsConfirmation: boolean;
  rationale: string;
  nodes: WbsAiNode[];
  /** Per-node dry-run annotations: what apply will do for each node. */
  previewAnnotations?: CollisionResult[] | null;
}

export type WbsAiJobStatus = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "CANCELLED";

/** 202 response body from POST .../generate-from-document. */
export interface WbsAiJobAccepted {
  jobId: string;
  status: WbsAiJobStatus;
}

/** GET .../jobs/{id} response. */
export interface WbsAiJobView {
  id: string;
  projectId: string;
  status: WbsAiJobStatus;
  progressStage?: string | null;
  progressPct?: number | null;
  result?: WbsAiGenerationResponse | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

// === AI Activity Generation ===

export interface ActivityAiNode {
  code: string;
  name: string;
  description?: string | null;
  wbsNodeCode: string;
  originalDurationDays: number;
  predecessorCodes: string[];
}

export interface ActivityAiGenerateRequest {
  projectTypeHint?: string;
  additionalContext?: string;
  targetActivityCount?: number;
  defaultDurationDays?: number;
}

/** Multipart metadata for the from-document activity-generation flow. */
export interface ActivityAiGenerateFromDocumentMetadata {
  targetActivityCount?: number;
  defaultDurationDays?: number;
}

export interface ActivityAiGenerationResponse {
  rationale: string;
  activities: ActivityAiNode[];
  /** Per-activity dry-run annotations: NEW / RENAMED / SKIPPED on apply. */
  previewAnnotations?: CollisionResult[] | null;
}

export interface ActivityAiApplyRequest {
  mode?: ApplyMode;
  activities: ActivityAiNode[];
  /**
   * Map of original wbsNodeCode (from the AI generation) to the existing
   * project WBS code the user wants to use instead. Populated by accepting
   * "WBS_NEAR_MATCH" suggestions in the preview.
   */
  wbsRemap?: Record<string, string>;
  /**
   * When true, apply aborts atomically if any activity has unresolved WBS.
   * Default false: skip-and-report (current behavior).
   */
  strictWbs?: boolean;
}

export interface ActivityAiApplyResponse {
  /** Categorized per-activity outcome (preferred — used to paint diff tags). */
  collisions?: CollisionResult[] | null;
  /** Legacy "A-001 -> A-001-AI" string list, kept for back-compat. */
  codeCollisions: string[];
  wbsResolutionFailures: string[];
  relationshipResolutionFailures: string[];
  createdActivityIds: string[];
  createdRelationshipIds: string[];
}

export interface CorridorCodeResponse {
  id: string;
  projectId: string;
  corridorPrefix: string;
  zoneCode: string;
  nodeCode: string;
  generatedCode: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCorridorCodeRequest {
  projectId: string;
  corridorPrefix: string;
  zoneCode: string;
  nodeCode: string;
}

// === IC-PMS Master Data ===

export type OrganisationType =
  | "EMPLOYER"
  | "SPV"
  | "PMC"
  | "EPC_CONTRACTOR"
  | "GOVERNMENT_AUDITOR"
  | "MAIN_CONTRACTOR"
  | "SUB_CONTRACTOR"
  | "CONSULTANT"
  | "IE"
  | "SUPPLIER";

export type OrganisationRegistrationStatus =
  | "ACTIVE"
  | "SUSPENDED"
  | "CLOSED"
  | "PENDING_KYC";

export interface OrganisationResponse {
  id: string;
  code: string;
  name: string;
  shortName: string | null;
  organisationType: OrganisationType;
  parentOrganisationId: string | null;
  active: boolean;
  contactPersonName: string | null;
  contactMobile: string | null;
  contactEmail: string | null;
  pan: string | null;
  gstin: string | null;
  registrationNumber: string | null;
  addressLine: string | null;
  city: string | null;
  state: string | null;
  pincode: string | null;
  registrationStatus: OrganisationRegistrationStatus | null;
  associatedProjectIds: string[];
}

export interface CreateOrganisationRequest {
  code?: string | null;
  name: string;
  shortName?: string | null;
  organisationType: OrganisationType;
  parentOrganisationId?: string | null;
  active?: boolean;
  contactPersonName?: string | null;
  contactMobile?: string | null;
  contactEmail?: string | null;
  pan?: string | null;
  gstin?: string | null;
  registrationNumber?: string | null;
  addressLine?: string | null;
  city?: string | null;
  state?: string | null;
  pincode?: string | null;
  registrationStatus?: OrganisationRegistrationStatus | null;
  associatedProjectIds?: string[];
}

// === PMS MasterData UI Screens ===

export type ProjectCategory = string;

export type ContractType =
  | "EPC_LUMP_SUM_FIDIC_YELLOW"
  | "EPC_LUMP_SUM_FIDIC_RED"
  | "EPC_LUMP_SUM_FIDIC_SILVER"
  | "ITEM_RATE_FIDIC_RED"
  | "PERCENTAGE_BASED_PMC"
  | "LUMP_SUM_UNIT_RATE"
  | "EPC"
  | "BOT"
  | "HAM"
  | "ITEM_RATE"
  | "LUMP_SUM"
  | "ANNUITY";

export interface ProjectContractSummary {
  contractId: string | null;
  contractNumber: string | null;
  contractType: ContractType | null;
  contractValue: number | null;
  revisedValue: number | null;
  startDate: string | null;
  completionDate: string | null;
  dlpMonths: number | null;
}

export interface ContractSummaryInput {
  contractNumber?: string | null;
  contractType?: ContractType | null;
  contractValue?: number | null;
  revisedValue?: number | null;
  startDate?: string | null;
  completionDate?: string | null;
  dlpMonths?: number | null;
  contractorName?: string | null;
}

export type BoqStatus = "PENDING" | "ACTIVE" | "COMPLETED" | "ON_HOLD";

export type ResourceOwnership = "OWNED" | "HIRED" | "SUB_CONTRACTOR_PROVIDED";

export type UnitRateCategory = "EQUIPMENT" | "MANPOWER" | "MATERIAL" | "SUB_CONTRACT";

export type Department =
  | "CIVIL"
  | "QUALITY"
  | "SURVEY"
  | "PLANT"
  | "HSE"
  | "STORES"
  | "ADMIN"
  | "FINANCE"
  | "OTHER";

export type PresenceStatus = "ON_SITE" | "ON_LEAVE" | "TRANSFERRED" | "RELEASED";

export type StretchStatus = "NOT_STARTED" | "ACTIVE" | "COMPLETE" | "SNAGGING";

export type MaterialSourceType =
  | "BORROW_AREA"
  | "QUARRY"
  | "BITUMEN_DEPOT"
  | "CEMENT_SOURCE";

export type LabTestStatus = "ALL_PASS" | "TESTS_PENDING" | "ONE_OR_MORE_FAIL";

export type MaterialCategory =
  | "BITUMINOUS"
  | "AGGREGATE"
  | "CEMENT"
  | "STEEL"
  | "GRANULAR"
  | "SAND"
  | "PRECAST"
  | "ROAD_MARKING";

export type MaterialStatus = "ACTIVE" | "INACTIVE" | "DISCONTINUED";

export type StockStatusTag = "OK" | "LOW" | "CRITICAL";

export type ResourceUnit =
  | "PER_DAY"
  | "MT"
  | "CU_M"
  | "RMT"
  | "NOS"
  | "KG"
  | "LITRE";

export interface StretchResponse {
  id: string;
  projectId: string;
  stretchCode: string;
  name: string | null;
  fromChainageM: number;
  toChainageM: number;
  lengthM: number | null;
  assignedSupervisorId: string | null;
  packageCode: string | null;
  status: StretchStatus | null;
  milestoneName: string | null;
  targetDate: string | null;
  boqItemIds: string[];
}

export interface CreateStretchRequest {
  stretchCode?: string | null;
  name?: string | null;
  fromChainageM: number;
  toChainageM: number;
  assignedSupervisorId?: string | null;
  packageCode?: string | null;
  status?: StretchStatus | null;
  milestoneName?: string | null;
  targetDate?: string | null;
  boqItemIds?: string[];
}

export interface MaterialSourceLabTestRow {
  id: string;
  testName: string | null;
  standardReference: string | null;
  resultValue: number | null;
  resultUnit: string | null;
  passed: boolean | null;
  testDate: string | null;
  remarks: string | null;
}

export interface MaterialSourceResponse {
  id: string;
  projectId: string;
  sourceCode: string;
  name: string | null;
  sourceType: MaterialSourceType;
  village: string | null;
  taluk: string | null;
  district: string | null;
  state: string | null;
  distanceKm: number | null;
  approvedQuantity: number | null;
  approvedQuantityUnit: ResourceUnit | null;
  approvalReference: string | null;
  approvalAuthority: string | null;
  cbrAveragePercent: number | null;
  mddGcc: number | null;
  labTestStatus: LabTestStatus | null;
  labTests: MaterialSourceLabTestRow[];
}

export interface CreateMaterialSourceRequest {
  sourceCode?: string | null;
  name?: string | null;
  sourceType: MaterialSourceType;
  village?: string | null;
  taluk?: string | null;
  district?: string | null;
  state?: string | null;
  distanceKm?: number | null;
  approvedQuantity?: number | null;
  approvedQuantityUnit?: ResourceUnit | null;
  approvalReference?: string | null;
  approvalAuthority?: string | null;
  cbrAveragePercent?: number | null;
  mddGcc?: number | null;
  labTestStatus?: LabTestStatus | null;
  labTests?: {
    testName?: string | null;
    standardReference?: string | null;
    resultValue?: number | null;
    resultUnit?: string | null;
    passed?: boolean | null;
    testDate?: string | null;
    remarks?: string | null;
  }[];
}

export interface MaterialResponse {
  id: string;
  projectId: string;
  code: string;
  name: string;
  category: MaterialCategory | null;
  unit: ResourceUnit | null;
  specificationGrade: string | null;
  minStockLevel: number | null;
  reorderQuantity: number | null;
  leadTimeDays: number | null;
  storageLocation: string | null;
  approvedSupplierId: string | null;
  status: MaterialStatus | null;
  applicableBoqItemIds: string[];
}

export interface CreateMaterialRequest {
  code?: string | null;
  name: string;
  category: MaterialCategory;
  unit?: ResourceUnit | null;
  specificationGrade?: string | null;
  minStockLevel?: number | null;
  reorderQuantity?: number | null;
  leadTimeDays?: number | null;
  storageLocation?: string | null;
  approvedSupplierId?: string | null;
  status?: MaterialStatus | null;
  applicableBoqItemIds?: string[];
}

export interface GoodsReceiptResponse {
  id: string;
  projectId: string;
  grnNumber: string;
  materialId: string;
  receivedDate: string;
  quantity: number;
  unitRate: number | null;
  amount: number | null;
  supplierOrganisationId: string | null;
  poNumber: string | null;
  vehicleNumber: string | null;
  receivedByUserId: string | null;
  acceptedQuantity: number | null;
  rejectedQuantity: number | null;
  remarks: string | null;
}

export interface CreateGoodsReceiptRequest {
  materialId: string;
  receivedDate: string;
  quantity: number;
  unitRate?: number | null;
  supplierOrganisationId?: string | null;
  poNumber?: string | null;
  vehicleNumber?: string | null;
  receivedByUserId?: string | null;
  acceptedQuantity?: number | null;
  rejectedQuantity?: number | null;
  remarks?: string | null;
}

export interface MaterialIssueResponse {
  id: string;
  projectId: string;
  challanNumber: string;
  materialId: string;
  issueDate: string;
  quantity: number;
  issuedToUserId: string | null;
  stretchId: string | null;
  activityId: string | null;
  wastageQuantity: number | null;
  remarks: string | null;
}

export interface CreateMaterialIssueRequest {
  materialId: string;
  issueDate: string;
  quantity: number;
  issuedToUserId?: string | null;
  stretchId?: string | null;
  activityId?: string | null;
  wastageQuantity?: number | null;
  remarks?: string | null;
}

export interface MaterialStockRow {
  id: string;
  projectId: string;
  materialId: string;
  materialCode: string | null;
  materialName: string | null;
  openingStock: number | null;
  receivedMonth: number | null;
  issuedMonth: number | null;
  currentStock: number;
  minStockLevel: number | null;
  reorderQuantity: number | null;
  stockValue: number | null;
  stockStatusTag: StockStatusTag | null;
  lastGrnId: string | null;
  lastIssueDate: string | null;
  cumulativeConsumed: number | null;
  wastagePercent: number | null;
}

export type AuthMethod = "AADHAAR_OTP" | "NIC_SSO" | "DSC_CLASS_3" | "USERNAME_PASSWORD";

export type IcpmsModule =
  | "M1_WBS_GIS"
  | "M2_SCHEDULE_EVM"
  | "M3_SATELLITE_MONITORING"
  | "M4_COST_RA_BILLS"
  | "M5_CONTRACTS"
  | "M6_DOCUMENTS"
  | "M7_RISKS"
  | "M8_RESOURCES"
  | "M9_REPORTS";

export type ModuleAccessLevel = "NONE" | "VIEW" | "EDIT" | "CERTIFY" | "APPROVE" | "FULL";

export interface UserModuleAccessResponse {
  id: string;
  userId: string;
  module: IcpmsModule;
  accessLevel: ModuleAccessLevel;
}

export interface UserCorridorScopeResponse {
  id: string;
  userId: string;
  wbsNodeId: string | null; // NULL = All Corridors
}

// === AI Insights ===

export interface InsightHighlight {
  label: string;
  value: string;
  severity: "info" | "warning" | "critical";
  trend: "up" | "down" | "flat" | null;
}

export interface InsightVariance {
  name: string;
  delta: string;
  explanation: string;
}

export interface InsightRecommendation {
  title: string;
  priority: "low" | "medium" | "high";
  action: string;
  rationale: string;
}

export interface InsightFinding {
  label: string;
  detail: string;
  severity: "info" | "warning" | "critical";
}

export type ChartType =
  | "kpi"
  | "line"
  | "bar"
  | "stacked-bar"
  | "pie"
  | "donut"
  | "gauge"
  | "dual-gauge"
  | "heatmap"
  | "scatter"
  | "treemap"
  | "waterfall"
  | "area";

export interface ChartSpec {
  id: string;
  title: string;
  type: ChartType | string;
  /** Raw Apache ECharts option object — passed directly to ReactECharts. */
  option: Record<string, unknown> | null;
  note?: string | null;
}

export interface InsightsResponse {
  summary: string;
  highlights?: InsightHighlight[] | null;
  variances?: InsightVariance[] | null;
  recommendations?: InsightRecommendation[] | null;
  findings?: InsightFinding[] | null;
  rationale: string;
  /** Short MDX narrative (LLM-authored) referencing chart IDs via <Chart id="..."/> tags. */
  mdx?: string | null;
  /** Chart specs built deterministically server-side. Always present in fresh responses. */
  charts?: ChartSpec[] | null;
}

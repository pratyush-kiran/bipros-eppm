import { apiClient } from "./client";
import type { ApiResponse, PagedResponse } from "../types";

// === Enums (mirroring backend) ===

export type ResourceStatus = "ACTIVE" | "INACTIVE";

export type ResourceOwnership = "OWNED" | "HIRED" | "SUB_CONTRACTOR_PROVIDED";

export type MaterialType = "CONSUMABLE" | "NON_CONSUMABLE";

// ManpowerCategory / EmploymentType / SkillLevel were removed — these dropdowns are now
// admin-managed master data (see ManpowerCategoryMaster, EmploymentTypeMaster, SkillLevelMaster).
// The DTO fields below carry the master row's `name` as a plain string.

export type PaymentMode = "BANK" | "CASH" | "CHEQUE";

export type AttendanceStatus = "PRESENT" | "ABSENT" | "ON_LEAVE" | "HALF_DAY";

export type ShiftType = "DAY" | "NIGHT";

export type AvailabilityStatus = "AVAILABLE" | "ASSIGNED" | "ON_LEAVE";

export type MedicalStatus = "FIT" | "UNFIT" | "PENDING";

// === Detail DTOs ===

export interface EquipmentDetailsDto {
  make?: string | null;
  model?: string | null;
  variant?: string | null;
  manufacturerName?: string | null;
  countryOfOrigin?: string | null;
  yearOfManufacture?: number | null;
  serialNumber?: string | null;
  chassisNumber?: string | null;
  engineNumber?: string | null;
  registrationNumber?: string | null;
  capacitySpec?: string | null;
  fuelLitresPerHour?: number | null;
  standardOutputPerDay?: number | null;
  standardOutputUnit?: string | null;
  ownershipType?: ResourceOwnership | null;
  quantityAvailable?: number | null;
  insuranceExpiry?: string | null;
  lastServiceDate?: string | null;
  nextServiceDate?: string | null;
}

export interface MaterialDetailsDto {
  materialType?: MaterialType | null;
  category?: string | null;
  subCategory?: string | null;
  materialGrade?: string | null;
  specification?: string | null;
  brand?: string | null;
  manufacturerName?: string | null;
  standardCode?: string | null;
  qualityClass?: string | null;
  baseUnit?: string | null;
  conversionFactor?: number | null;
  alternateUnits?: string | null;
  density?: number | null;
}

export interface ManpowerMasterDto {
  employeeCode?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  fullName?: string | null;
  /** Master row name from ManpowerCategoryMaster (top-level, parentId = null). */
  category?: string | null;
  /** Master row name from ManpowerCategoryMaster (children of the picked Category). */
  subCategory?: string | null;
  dateOfBirth?: string | null;
  gender?: string | null;
  /** Free-text or master row name from NationalityMaster (autocomplete-with-free-text). */
  nationality?: string | null;
  contactNumber?: string | null;
  email?: string | null;
  address?: string | null;
  emergencyContact?: string | null;
  photoUrl?: string | null;
  /** Master row name from EmploymentTypeMaster. */
  employmentType?: string | null;
  designation?: string | null;
  department?: string | null;
  joiningDate?: string | null;
  exitDate?: string | null;
  reportingManagerId?: string | null;
  companyName?: string | null;
  workLocation?: string | null;
}

export interface ManpowerSkillsDto {
  /** JSON-stringified array of skill names from SkillMaster (e.g. '["Mason","Welder"]'). */
  primarySkill?: string | null;
  /** JSON-stringified array of skill names from SkillMaster. */
  secondarySkills?: string | null;
  /** Master row name from SkillLevelMaster. */
  skillLevel?: string | null;
  certifications?: string | null;
  licenseDetails?: string | null;
  experienceYears?: number | null;
  trainingRecords?: string | null;
}

/**
 * HR/payroll record-keeping for a manpower resource. Intentionally does NOT carry the
 * project-cost rate — that lives on Resource.costPerUnit ("Default Rate"). Salary type and
 * salary fields removed: project costing is decoupled from payroll, matching Primavera P6 /
 * MSP / SAP PPM patterns.
 */
export interface ManpowerFinancialsDto {
  allowances?: string | null;
  deductions?: string | null;
  currency?: string | null;
  bankAccountDetails?: string | null;
  paymentMode?: PaymentMode | null;
  taxDetails?: string | null;
  pfNumber?: string | null;
  esiNumber?: string | null;
}

export interface ManpowerAttendanceDto {
  dailyAttendanceStatus?: AttendanceStatus | null;
  lastCheckInTime?: string | null;
  lastCheckOutTime?: string | null;
  workingHoursPerDay?: number | null;
  shiftType?: ShiftType | null;
  totalWorkHoursMtd?: number | null;
  overtimeHoursMtd?: number | null;
  leaveBalance?: number | null;
  leaveSchedule?: string | null;
}

export interface ManpowerAllocationDto {
  availabilityStatus?: AvailabilityStatus | null;
  currentProjectId?: string | null;
  assignedActivityId?: string | null;
  siteName?: string | null;
  roleInProject?: string | null;
  crewId?: string | null;
  utilizationPercentage?: number | null;
  standardOutputPerHour?: number | null;
  outputUnit?: string | null;
  efficiencyFactor?: number | null;
  performanceRating?: number | null;
  productivityTrend?: string | null;
  attritionRiskScore?: number | null;
  skillGapAnalysis?: string | null;
  recommendedTraining?: string | null;
  optimalAssignment?: string | null;
}

export interface ManpowerComplianceDto {
  idProofType?: string | null;
  idProofNumber?: string | null;
  laborLicenseNumber?: string | null;
  insuranceProvider?: string | null;
  insurancePolicyNumber?: string | null;
  insuranceExpiry?: string | null;
  medicalFitnessStatus?: MedicalStatus | null;
  medicalExpiry?: string | null;
  safetyTrainingCompleted?: boolean | null;
  safetyTrainingDate?: string | null;
  complianceCertificates?: string | null;
  resumeUrl?: string | null;
  certificationDocuments?: string | null;
  contractDocumentUrl?: string | null;
}

export interface ManpowerDto {
  master?: ManpowerMasterDto | null;
  skills?: ManpowerSkillsDto | null;
  financials?: ManpowerFinancialsDto | null;
  attendance?: ManpowerAttendanceDto | null;
  allocation?: ManpowerAllocationDto | null;
  compliance?: ManpowerComplianceDto | null;
}

// === Resource Response / Request ===

/**
 * Slim resource shape returned by list endpoints. The detail blocks
 * (equipment / material / manpower) are populated only on detail GETs.
 */
export interface ResourceResponse {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  roleId: string;
  roleCode: string;
  roleName: string;
  resourceTypeId: string;
  resourceTypeCode: string;
  resourceTypeName: string;
  availability?: number | null;
  costPerUnit?: number | null;
  unit?: string | null;
  /**
   * FK to the type-appropriate rate master row (manpower / equipment / material). When set,
   * Resource.unit and Resource.costPerUnit are snapshots of the rate master row's values
   * and are kept in sync when the rate master row is edited.
   */
  rateMasterId?: string | null;
  status: ResourceStatus;
  calendarId?: string | null;
  parentId?: string | null;
  userId?: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;

  // Populated on detail GET only.
  equipment?: EquipmentDetailsDto | null;
  material?: MaterialDetailsDto | null;
  manpower?: ManpowerDto | null;
}

export interface CreateResourceRequest {
  code?: string;
  name: string;
  description?: string;
  roleId: string;
  resourceTypeId: string;
  availability?: number | null;
  costPerUnit?: number | null;
  unit?: string | null;
  rateMasterId?: string | null;
  status?: ResourceStatus;
  calendarId?: string | null;
  parentId?: string | null;
  userId?: string | null;
  sortOrder?: number | null;

  /** Pass only the block matching the chosen resource type. */
  equipment?: EquipmentDetailsDto;
  material?: MaterialDetailsDto;
  manpower?: ManpowerDto;
}

// === Assignments / Leveling (unchanged wire shape) ===

export type LevelingMode = "LEVEL_WITHIN_FLOAT" | "LEVEL_ALL" | "SMOOTH";

export interface ResourceLevelingRequest {
  mode: LevelingMode;
  resourceIds?: string[];
}

export interface ShiftedActivity {
  activityId: string;
  originalStart: string;
  newStart: string;
  delayDays: number;
}

export interface ResourceLevelingResponse {
  mode: LevelingMode;
  activitiesShifted: number;
  iterationsUsed: number;
  peakUtilizationBefore: number;
  peakUtilizationAfter: number;
  shiftedActivities: ShiftedActivity[];
  messages: string[];
}

export interface UtilizationProfileEntry {
  date: string;
  resourceId: string;
  resourceName: string;
  demand: number;
  capacity: number;
  utilization: number;
}

export interface ResourceAssignmentResponse {
  id: string;
  activityId: string;
  activityName: string | null;
  resourceId: string | null;
  resourceName: string | null;
  roleId: string | null;
  roleName: string | null;
  effectiveRoleId: string | null;
  effectiveRoleName: string | null;
  unit: string | null;
  projectId: string | null;
  /** Phase 2: original committed units. Frozen unless an explicit Re-budget action runs. */
  budgetedUnits: number | null;
  /** Phase 2: original committed cost. Frozen unless an explicit Re-budget action runs. */
  budgetedCost: number | null;
  plannedUnits: number;
  /** Raw nos the planner entered for manpower/equipment. plannedUnits = headcount × duration
   *  (kept for DPR/EVA rollups); this field exposes the human-meaningful "nos". */
  headcount: number | null;
  /** Activity duration applied when computing plannedUnits. */
  duration: number | null;
  /** Raw quantity the planner entered for material assignments. */
  quantity: number | null;
  actualUnits: number;
  remainingUnits: number | null;
  atCompletionUnits: number | null;
  plannedCost: number | null;
  actualCost: number | null;
  remainingCost: number | null;
  atCompletionCost: number | null;
  rateType: string | null;
  plannedStartDate: string | null;
  plannedFinishDate: string | null;
  actualStartDate: string | null;
  actualFinishDate: string | null;
  staffed: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ActivityUsage {
  activityId: string;
  activityCode: string | null;
  activityName: string;
  plannedByPeriod: Record<string, number>;
  actualByPeriod: Record<string, number>;
}

export interface ResourceUsageNode {
  resourceId: string;
  resourceCode: string | null;
  resourceName: string;
  unit: string | null;
  plannedByPeriod: Record<string, number>;
  actualByPeriod: Record<string, number>;
  activities: ActivityUsage[];
}

export interface ResourceTypeUsage {
  resourceTypeId: string;
  resourceTypeCode: string;
  resourceTypeName: string;
  /** Null when child resources have differing productivity units (mixed-unit type). */
  unit: string | null;
  /** Null when {@link unit} is null — UI should render an em dash for those rows. */
  plannedByPeriod: Record<string, number> | null;
  /** Null when {@link unit} is null — UI should render an em dash for those rows. */
  actualByPeriod: Record<string, number> | null;
  resources: ResourceUsageNode[];
}

export interface ResourceUsageTimePhasedResponse {
  /** "YYYY-MM" period codes in chronological order. */
  periods: string[];
  fromDate: string | null;
  toDate: string | null;
  resourceTypes: ResourceTypeUsage[];
}

export interface CreateProjectResourceAssignmentRequest {
  activityId: string;
  resourceId?: string;
  roleId?: string;
  projectId: string;
  assignmentStartDate?: string;
  assignmentFinishDate?: string;
  plannedUnits: number;
  rateType?: string;
}

export interface StaffAssignmentRequest {
  resourceId: string;
  override?: boolean;
}

export interface SwapResourceRequest {
  resourceId: string;
  override?: boolean;
}

export const resourceApi = {
  // The backend `ResourceController.list` returns ApiResponse<List<ResourceResponse>>
  // (a flat array). Optional page/size positional args are accepted by callers
  // for legacy reasons; the server doesn't paginate this endpoint so we drop them.
  listResources: (...args: number[]) => {
    void args;
    return apiClient
      .get<ApiResponse<ResourceResponse[]>>("/v1/resources")
      .then((r) => r.data);
  },

  getResource: (id: string) =>
    apiClient
      .get<ApiResponse<ResourceResponse>>(`/v1/resources/${id}`)
      .then((r) => r.data),

  listByType: (typeCode: string) =>
    apiClient
      .get<ApiResponse<ResourceResponse[]>>(`/v1/resources/by-type/${typeCode}`)
      .then((r) => r.data),

  listByRole: (roleId: string) =>
    apiClient
      .get<ApiResponse<ResourceResponse[]>>(`/v1/resources/by-role/${roleId}`)
      .then((r) => r.data),

  listRoots: () =>
    apiClient
      .get<ApiResponse<ResourceResponse[]>>("/v1/resources/roots")
      .then((r) => r.data),

  listByParent: (parentId: string) =>
    apiClient
      .get<ApiResponse<ResourceResponse[]>>(`/v1/resources/by-parent/${parentId}`)
      .then((r) => r.data),

  listByStatus: (status: ResourceStatus) =>
    apiClient
      .get<ApiResponse<ResourceResponse[]>>(`/v1/resources/by-status/${status}`)
      .then((r) => r.data),

  createResource: (data: CreateResourceRequest) =>
    apiClient
      .post<ApiResponse<ResourceResponse>>("/v1/resources", data)
      .then((r) => r.data),

  updateResource: (id: string, data: CreateResourceRequest) =>
    apiClient
      .put<ApiResponse<ResourceResponse>>(`/v1/resources/${id}`, data)
      .then((r) => r.data),

  deleteResource: (id: string) => apiClient.delete(`/v1/resources/${id}`),

  deleteAllResources: () => apiClient.delete("/v1/resources"),

  // Per-section detail endpoints
  getEquipmentDetails: (id: string) =>
    apiClient
      .get<ApiResponse<EquipmentDetailsDto>>(`/v1/resources/${id}/equipment-details`)
      .then((r) => r.data),

  updateEquipmentDetails: (id: string, dto: EquipmentDetailsDto) =>
    apiClient
      .put<ApiResponse<EquipmentDetailsDto>>(`/v1/resources/${id}/equipment-details`, dto)
      .then((r) => r.data),

  getMaterialDetails: (id: string) =>
    apiClient
      .get<ApiResponse<MaterialDetailsDto>>(`/v1/resources/${id}/material-details`)
      .then((r) => r.data),

  updateMaterialDetails: (id: string, dto: MaterialDetailsDto) =>
    apiClient
      .put<ApiResponse<MaterialDetailsDto>>(`/v1/resources/${id}/material-details`, dto)
      .then((r) => r.data),

  getManpower: (id: string) =>
    apiClient
      .get<ApiResponse<ManpowerDto>>(`/v1/resources/${id}/manpower`)
      .then((r) => r.data),

  updateManpower: (id: string, dto: ManpowerDto) =>
    apiClient
      .put<ApiResponse<ManpowerDto>>(`/v1/resources/${id}/manpower`, dto)
      .then((r) => r.data),

  // ─── Assignments / leveling — wire shape unchanged ───

  getResourceAssignments: (resourceId: string, page = 0, size = 20) =>
    apiClient
      .get<ApiResponse<PagedResponse<ResourceAssignmentResponse>>>(
        `/v1/resources/${resourceId}/assignments`,
        { params: { page, size } }
      )
      .then((r) => r.data),

  assignResourceToActivity: (activityId: string, resourceId: string, units: number) =>
    apiClient
      .post<ApiResponse<ResourceAssignmentResponse>>(
        `/v1/activities/${activityId}/assign-resource`,
        { resourceId, plannedUnits: units }
      )
      .then((r) => r.data),

  getAssignmentsByActivity: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<ResourceAssignmentResponse[]>>(
        `/v1/projects/${projectId}/resource-assignments/activity/${activityId}`
      )
      .then((r) => r.data),

  /**
   * Picker-mode lookup for the DPR drawer's Manpower / Equipment / Material searchable
   * dropdowns. {@code kind} narrows by ResourceType (LABOR mapped to MANPOWER); {@code reportDate}
   * picks the rate that was effective on that date. Result rows already carry the snapshotted
   * {@code unitRate} so the row preview can compute cost without an extra round trip.
   */
  getAssignedResourcesByKind: (
    projectId: string,
    activityId: string,
    kind: "MANPOWER" | "EQUIPMENT" | "MATERIAL",
    reportDate?: string
  ) =>
    apiClient
      .get<ApiResponse<import("../types/dpr").AssignedResourceOption[]>>(
        `/v1/projects/${projectId}/resource-assignments/activity/${activityId}/picker`,
        { params: { kind, reportDate } }
      )
      .then((r) => r.data),

  getTimePhasedUsage: (
    projectId: string,
    params?: { from?: string; to?: string }
  ) =>
    apiClient
      .get<ApiResponse<ResourceUsageTimePhasedResponse>>(
        `/v1/projects/${projectId}/resource-usage/time-phased`,
        { params }
      )
      .then((r) => r.data),

  getProjectResourceAssignments: (projectId: string, page = 0, size = 20) =>
    apiClient
      .get<ApiResponse<PagedResponse<ResourceAssignmentResponse>>>(
        `/v1/projects/${projectId}/resource-assignments`,
        { params: { page, size } }
      )
      .then((r) => r.data),

  createProjectResourceAssignment: (
    projectId: string,
    data: CreateProjectResourceAssignmentRequest
  ) =>
    apiClient
      .post<ApiResponse<ResourceAssignmentResponse>>(
        `/v1/projects/${projectId}/resource-assignments`,
        data
      )
      .then((r) => r.data),

  staffAssignment: (projectId: string, id: string, data: StaffAssignmentRequest) =>
    apiClient
      .post<ApiResponse<ResourceAssignmentResponse>>(
        `/v1/projects/${projectId}/resource-assignments/${id}/staff`,
        data
      )
      .then((r) => r.data),

  swapResource: (projectId: string, id: string, data: SwapResourceRequest) =>
    apiClient
      .post<ApiResponse<ResourceAssignmentResponse>>(
        `/v1/projects/${projectId}/resource-assignments/${id}/swap`,
        data
      )
      .then((r) => r.data),

  recomputeProjectAssignmentCosts: (projectId: string) =>
    apiClient
      .post<ApiResponse<{ updated: number }>>(
        `/v1/projects/${projectId}/resource-assignments/recompute-costs`
      )
      .then((r) => r.data),

  /**
   * Re-budget — copy current planned values into budgeted_units / budgeted_cost. Only triggered
   * by deliberate user action; plan edits do NOT auto-update budgeted values.
   */
  rebudgetAssignment: (projectId: string, assignmentId: string) =>
    apiClient
      .post<ApiResponse<ResourceAssignmentResponse>>(
        `/v1/projects/${projectId}/resource-assignments/${assignmentId}/rebudget`
      )
      .then((r) => r.data),

  levelResources: (projectId: string, request: ResourceLevelingRequest) =>
    apiClient
      .post<ApiResponse<ResourceLevelingResponse>>(
        `/v1/projects/${projectId}/resource-assignments/level`,
        request
      )
      .then((r) => r.data),

  getUtilizationProfile: (projectId: string) =>
    apiClient
      .get<ApiResponse<UtilizationProfileEntry[]>>(
        `/v1/projects/${projectId}/resource-assignments/utilization-profile`
      )
      .then((r) => r.data),

  /**
   * Active LABOR resources whose role code is SUPERVISOR or FOREMAN, scoped to the project's
   * resource pool (falls back to project-agnostic when the project has no allocations yet).
   * Powers the DPR form's Supervisor dropdown — free-text "Other" entries on the form leave
   * supervisorUserId null.
   */
  getEligibleSupervisors: (projectId: string) =>
    apiClient
      .get<ApiResponse<EligibleSupervisor[]>>(
        `/v1/projects/${projectId}/eligible-supervisors`
      )
      .then((r) => r.data),
};

export interface EligibleSupervisor {
  id: string;
  code: string;
  name: string;
  roleCode: string | null;
  roleName: string | null;
}

export interface ResourceDprSummary {
  resourceId: string;
  days: number;
  sinceInclusive: string;
  count: number;
}

export const resourceDprSummaryApi = {
  get: (resourceId: string, days = 30) =>
    apiClient
      .get<ApiResponse<ResourceDprSummary>>(
        `/v1/resources/${resourceId}/dpr-summary`,
        { params: { days } },
      )
      .then((r) => r.data),
};

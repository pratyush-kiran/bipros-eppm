// QC domain types — mirror backend DTOs in bipros-project.

export type QcOutcome = "PASS" | "FAIL" | "REPEAT";

export interface QcTestType {
  id: string;
  projectId: string;
  name: string;
  unit: string | null;
  ircThreshold: number | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface QcTestTypeRequest {
  name: string;
  unit?: string | null;
  ircThreshold?: number | null;
}

export interface QcTestItemRow {
  testTypeId: string;
  sampleRefNo?: string | null;
  testResult?: number | null;
  requiredIrc?: number | null;
  outcome: QcOutcome;
  labInspector?: string | null;
}

export interface QcTestItemResponse {
  id: string;
  testTypeId: string;
  testTypeName: string;
  sampleRefNo: string | null;
  testResult: number | null;
  requiredIrc: number | null;
  outcome: QcOutcome;
  labInspector: string | null;
  createdAt: string;
}

export interface QcSession {
  id: string;
  projectId: string;
  activityId: string;
  activityName: string;
  testDate: string;
  chainageFrom: string | null;
  chainageTo: string | null;
  items: QcTestItemResponse[];
  createdAt: string;
  updatedAt: string;
}

export interface QcSessionRequest {
  activityId: string;
  activityName: string;
  testDate: string;
  chainageFrom?: string | null;
  chainageTo?: string | null;
  items: QcTestItemRow[];
}

export interface QcActivitySummary {
  activityId: string;
  activityName: string;
  pass: number;
  fail: number;
  repeat: number;
}

export interface QcDashboardResponse {
  totalTests: number;
  passCount: number;
  failCount: number;
  repeatCount: number;
  passRate: number;
  todayTests: number;
  byActivity: QcActivitySummary[];
  recentFails: QcTestItemResponse[];
}

import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type SkillCategory =
  | "SKILLED"
  | "SEMI_SKILLED"
  | "UNSKILLED"
  | "SUPERVISOR"
  | "ENGINEER";

export interface AttendanceResponse {
  id: string;
  projectId: string;
  date: string;
  contractorName: string;
  skillCategory: SkillCategory;
  plannedCount: number;
  actualCount: number;
  hoursWorked: number;
  notes: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  submittedBy: string;
  submittedAt: string;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string;
  updatedBy: string | null;
}

export interface CreateAttendanceRequest {
  date: string;
  contractorName: string;
  skillCategory: SkillCategory;
  plannedCount: number;
  actualCount: number;
  hoursWorked: number;
  notes?: string;
}

export interface UpdateAttendanceRequest {
  date?: string;
  contractorName?: string;
  skillCategory?: SkillCategory;
  plannedCount?: number;
  actualCount?: number;
  hoursWorked?: number;
  notes?: string;
}

export interface AttendanceSummary {
  skillCategory: SkillCategory;
  totalPlanned: number;
  totalActual: number;
  totalHoursWorked: number;
  rowCount: number;
}

export const attendanceApi = {
  list: (
    projectId: string,
    filters?: { from?: string; to?: string }
  ) =>
    apiClient
      .get<ApiResponse<AttendanceResponse[]>>(
        `/v1/projects/${projectId}/attendance`,
        { params: filters }
      )
      .then((r) => r.data),

  create: (projectId: string, data: CreateAttendanceRequest) =>
    apiClient
      .post<ApiResponse<AttendanceResponse>>(
        `/v1/projects/${projectId}/attendance`,
        data
      )
      .then((r) => r.data),

  update: (projectId: string, id: string, data: UpdateAttendanceRequest) =>
    apiClient
      .put<ApiResponse<AttendanceResponse>>(
        `/v1/projects/${projectId}/attendance/${id}`,
        data
      )
      .then((r) => r.data),

  approve: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<AttendanceResponse>>(
        `/v1/projects/${projectId}/attendance/${id}/approve`
      )
      .then((r) => r.data),

  summary: (
    projectId: string,
    filters?: { from?: string; to?: string }
  ) =>
    apiClient
      .get<ApiResponse<AttendanceSummary[]>>(
        `/v1/projects/${projectId}/attendance/summary`,
        { params: filters }
      )
      .then((r) => r.data),
};

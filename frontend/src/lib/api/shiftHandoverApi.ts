import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type Shift = "DAY" | "NIGHT";

export interface ShiftHandoverResponse {
  id: string;
  projectId: string;
  shiftDate: string;
  shift: Shift;
  fromUserId: string;
  toUserId: string;
  summary: string;
  pendingItems: string | null;
  handedOverAt: string;
  acknowledgedAt: string | null;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string;
  updatedBy: string | null;
}

export interface CreateShiftHandoverRequest {
  shiftDate: string;
  shift: Shift;
  toUserId: string;
  summary: string;
  pendingItems?: string;
}

export const shiftHandoverApi = {
  list: (
    projectId: string,
    filters?: { shiftDate?: string; shift?: Shift }
  ) =>
    apiClient
      .get<ApiResponse<ShiftHandoverResponse[]>>(
        `/v1/projects/${projectId}/shift-handovers`,
        { params: filters }
      )
      .then((r) => r.data),

  getById: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<ShiftHandoverResponse>>(
        `/v1/projects/${projectId}/shift-handovers/${id}`
      )
      .then((r) => r.data),

  create: (projectId: string, data: CreateShiftHandoverRequest) =>
    apiClient
      .post<ApiResponse<ShiftHandoverResponse>>(
        `/v1/projects/${projectId}/shift-handovers`,
        data
      )
      .then((r) => r.data),

  acknowledge: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<ShiftHandoverResponse>>(
        `/v1/projects/${projectId}/shift-handovers/${id}/acknowledge`
      )
      .then((r) => r.data),
};

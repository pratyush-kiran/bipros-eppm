import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type ChecklistType = "PRE_CONCRETE" | "EXCAVATION" | "SHUTTERING" | "OTHER";
export type EvidenceType = "NONE" | "PHOTO" | "NOTE";
export type ChecklistStatus = "IN_PROGRESS" | "SUBMITTED" | "APPROVED" | "REJECTED";
export type AnswerValue = "YES" | "NO" | "NA";

export interface ChecklistTemplateItemDto {
  id: string;
  sequence: number;
  label: string;
  mandatory: boolean;
  evidenceType: EvidenceType;
}

export interface ChecklistTemplateResponse {
  id: string;
  code: string;
  name: string;
  type: ChecklistType;
  active: boolean;
  items: ChecklistTemplateItemDto[];
}

export interface ChecklistAnswerDto {
  itemId: string;
  value?: AnswerValue | null;
  note?: string | null;
  photoUrl?: string | null;
}

export interface ChecklistInstanceResponse {
  id: string;
  projectId: string;
  activityId: string | null;
  templateId: string;
  templateCode: string | null;
  templateName: string | null;
  status: ChecklistStatus;
  startedBy: string | null;
  startedAt: string | null;
  submittedAt: string | null;
  signedBy: string | null;
  signedAt: string | null;
  answers: ChecklistAnswerDto[];
  createdAt: string;
  updatedAt: string;
}

export interface StartChecklistRequest {
  templateId: string;
  activityId?: string | null;
}

export interface SaveChecklistAnswersRequest {
  answers: ChecklistAnswerDto[];
}

export const checklistApi = {
  listTemplates: () =>
    apiClient
      .get<ApiResponse<ChecklistTemplateResponse[]>>(`/v1/checklist-templates`)
      .then((r) => r.data),

  getTemplate: (id: string) =>
    apiClient
      .get<ApiResponse<ChecklistTemplateResponse>>(`/v1/checklist-templates/${id}`)
      .then((r) => r.data),

  list: (projectId: string) =>
    apiClient
      .get<ApiResponse<ChecklistInstanceResponse[]>>(`/v1/projects/${projectId}/checklists`)
      .then((r) => r.data),

  get: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<ChecklistInstanceResponse>>(`/v1/projects/${projectId}/checklists/${id}`)
      .then((r) => r.data),

  start: (projectId: string, request: StartChecklistRequest) =>
    apiClient
      .post<ApiResponse<ChecklistInstanceResponse>>(
        `/v1/projects/${projectId}/checklists`,
        request
      )
      .then((r) => r.data),

  saveAnswers: (projectId: string, id: string, request: SaveChecklistAnswersRequest) =>
    apiClient
      .put<ApiResponse<ChecklistInstanceResponse>>(
        `/v1/projects/${projectId}/checklists/${id}/answers`,
        request
      )
      .then((r) => r.data),

  submit: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<ChecklistInstanceResponse>>(
        `/v1/projects/${projectId}/checklists/${id}/submit`
      )
      .then((r) => r.data),

  approve: (projectId: string, id: string, note?: string) =>
    apiClient
      .post<ApiResponse<ChecklistInstanceResponse>>(
        `/v1/projects/${projectId}/checklists/${id}/approve`,
        { note: note ?? null }
      )
      .then((r) => r.data),

  reject: (projectId: string, id: string, note?: string) =>
    apiClient
      .post<ApiResponse<ChecklistInstanceResponse>>(
        `/v1/projects/${projectId}/checklists/${id}/reject`,
        { note: note ?? null }
      )
      .then((r) => r.data),
};

import { apiClient } from "./client";
import type { ApiResponse } from "../types";
import type {
  CreateDailyProgressReportRequest,
  DailyProgressReportResponse,
  DprApprovalActionRequest,
  DprAttachment,
  DprPage,
  DprSummaryRow,
  DprVoiceNote,
  UpdateDailyProgressReportRequest,
} from "../types/dpr";

// Re-export so existing call sites (`import ... from "@/lib/api/dprApi"`) keep working.
export type {
  CreateDailyProgressReportRequest,
  DailyProgressReportResponse,
  DprApprovalActionRequest,
  DprAttachment,
  DprSummaryRow,
  DprVoiceNote,
  UpdateDailyProgressReportRequest,
} from "../types/dpr";

export interface DprListFilters {
  from?: string;
  to?: string;
  activity?: string;
  /** Exclusive day cursor — fetch reports strictly older than this date. */
  before?: string;
  /** Number of distinct days to fetch in this page. Defaults to 14 server-side. */
  days?: number;
}

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export interface ProductivityPreviewRequest {
  manpower: Array<{
    roleId: string | null;
    nos: number | null;
    workingHours?: number | null;
  }>;
  equipment: Array<{
    roleId: string | null;
    nos: number | null;
    workingHours?: number | null;
  }>;
}

export type ProductivityCoverage =
  | "MANPOWER_ONLY"
  | "EQUIPMENT_ONLY"
  | "BOTH"
  | "NONE"
  | "NO_WORK_ACTIVITY";

export interface ProductivityPreviewResponse {
  expectedFromManpower: number | null;
  expectedFromEquipment: number | null;
  expectedBottleneck: number | null;
  source: "BOTH" | "MANPOWER_ONLY" | "EQUIPMENT_ONLY" | "NONE";
  /**
   * What the Work Activity tracks at all (independent of what rows the user has logged).
   * The form uses this to decide whether to render the Manpower / Equipment sides at all
   * and to surface the "this activity is informational" banners.
   */
  coverage: ProductivityCoverage;
  /** How MP + EQ are combined for the BOTH case: SERIES (min) | PARALLEL (sum) | SUBSTITUTE (max). */
  normCombination: "SERIES" | "PARALLEL" | "SUBSTITUTE";
  warnings: string[];
}

export const dprApi = {
  list: (projectId: string, filters: DprListFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.activity) params.set("activity", filters.activity);
    if (filters.before) params.set("before", filters.before);
    if (filters.days != null) params.set("days", String(filters.days));
    const qs = params.toString() ? `?${params.toString()}` : "";
    return apiClient
      .get<ApiResponse<DprPage>>(`/v1/projects/${projectId}/dpr${qs}`)
      .then((r) => r.data);
  },

  get: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<DailyProgressReportResponse>>(`/v1/projects/${projectId}/dpr/${id}`)
      .then((r) => r.data),

  /**
   * Downloads the "Daily Activity Costing" workbook for a date range (one sheet per month,
   * APPROVED DPRs only) and triggers a browser save. The endpoint streams raw .xlsx bytes — not an
   * {@code ApiResponse} envelope — so we fetch with the JWT ourselves (mirrors fetchPhotoBlobUrl)
   * and build the download from the blob, taking the filename from Content-Disposition.
   */
  downloadMonthlyReport: async (projectId: string, from: string, to: string): Promise<void> => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const params = new URLSearchParams({ projectId, from, to });
    const res = await fetch(`${API_BASE_URL}/v1/reports/dpr/excel?${params.toString()}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`report fetch ${res.status}`);
    const blob = await res.blob();
    const cd = res.headers.get("Content-Disposition") ?? "";
    const match = /filename="?([^";]+)"?/.exec(cd);
    const fileName = match ? match[1] : `dpr-costing-${from}_${to}.xlsx`;
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },

  create: (projectId: string, request: CreateDailyProgressReportRequest) =>
    apiClient
      .post<ApiResponse<DailyProgressReportResponse>>(`/v1/projects/${projectId}/dpr`, request)
      .then((r) => r.data),

  update: (projectId: string, id: string, request: UpdateDailyProgressReportRequest) =>
    apiClient
      .put<ApiResponse<DailyProgressReportResponse>>(`/v1/projects/${projectId}/dpr/${id}`, request)
      .then((r) => r.data),

  delete: (projectId: string, id: string) =>
    apiClient.delete(`/v1/projects/${projectId}/dpr/${id}`),

  // ─── Approval workflow ────────────────────────────────────────────────────────

  approve: (projectId: string, id: string, reason?: string) =>
    apiClient
      .post<ApiResponse<DailyProgressReportResponse>>(
        `/v1/projects/${projectId}/dpr/${id}/approve`,
        { reason } satisfies DprApprovalActionRequest,
      )
      .then((r) => r.data),

  reject: (projectId: string, id: string, reason: string) =>
    apiClient
      .post<ApiResponse<DailyProgressReportResponse>>(
        `/v1/projects/${projectId}/dpr/${id}/reject`,
        { reason } satisfies DprApprovalActionRequest,
      )
      .then((r) => r.data),

  revoke: (projectId: string, id: string, reason?: string) =>
    apiClient
      .post<ApiResponse<DailyProgressReportResponse>>(
        `/v1/projects/${projectId}/dpr/${id}/revoke`,
        { reason } satisfies DprApprovalActionRequest,
      )
      .then((r) => r.data),

  pendingApprovals: (projectId: string) =>
    apiClient
      .get<ApiResponse<DprSummaryRow[]>>(`/v1/projects/${projectId}/dpr/approvals/pending`)
      .then((r) => r.data),

  unassignedApprovals: (projectId: string) =>
    apiClient
      .get<ApiResponse<DprSummaryRow[]>>(`/v1/projects/${projectId}/dpr/approvals/unassigned`)
      .then((r) => r.data),

  // ─── Photo attachments ───────────────────────────────────────────────────────
  // Multi-image upload. The backend accepts parallel `files[]` and `captions[]` parts; we
  // serialize captions as same-index entries so a missing index = no caption for that file.

  uploadPhotos: (
    projectId: string,
    dprId: string,
    files: File[],
    captions?: Array<string | null | undefined>
  ) => {
    const form = new FormData();
    files.forEach((f) => form.append("files", f));
    if (captions) {
      // Append as plain strings so each caption becomes a regular form field that the backend's
      // @RequestParam String[] resolver can read. Appending Blobs here would make the browser
      // emit file-typed parts, which Spring's MultipartResolver routes to MultipartFile and then
      // fails to coerce into String[].
      captions.forEach((c) => form.append("captions", c ?? ""));
    }
    return apiClient
      .post<ApiResponse<DprAttachment[]>>(
        `/v1/projects/${projectId}/dpr/${dprId}/photos`,
        form,
        // Override the JSON default so axios picks the multipart boundary from the FormData.
        { headers: { "Content-Type": "multipart/form-data" } }
      )
      .then((r) => r.data);
  },

  listPhotos: (projectId: string, dprId: string) =>
    apiClient
      .get<ApiResponse<DprAttachment[]>>(`/v1/projects/${projectId}/dpr/${dprId}/photos`)
      .then((r) => r.data),

  deletePhoto: (projectId: string, dprId: string, photoId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/dpr/${dprId}/photos/${photoId}`),

  /**
   * Fetches a JPEG/PNG/WebP/HEIC binary as an object URL suitable for {@code <img src>}.
   * The endpoint is JWT-protected, so we cannot point an `<img>` at it directly — we authenticate
   * the fetch ourselves, then turn the response into a blob URL. Caller is responsible for
   * revoking the URL when the image unmounts.
   */
  fetchPhotoBlobUrl: async (projectId: string, dprId: string, photoId: string): Promise<string> => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const res = await fetch(
      `${API_BASE_URL}/v1/projects/${projectId}/dpr/${dprId}/photos/${photoId}`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    if (!res.ok) throw new Error(`photo fetch ${res.status}`);
    const blob = await res.blob();
    return URL.createObjectURL(blob);
  },

  // ─── Voice notes (audio attachments) ─────────────────────────────────────────
  // Distinct from "Voice fill": these are persisted audio attachments, never transcribed.
  // Multi-file upload mirrors photos — parallel `files` + `captions` + `durations` parts; we
  // serialize captions/durations as same-index entries so a missing index = none for that file.

  uploadVoiceNotes: (
    projectId: string,
    dprId: string,
    files: File[],
    captions?: Array<string | null | undefined>,
    durations?: Array<number | null | undefined>
  ) => {
    const form = new FormData();
    files.forEach((f) => form.append("files", f));
    if (captions) {
      // Append as plain strings so each caption becomes a regular form field that the backend's
      // @RequestParam String[] resolver can read. Appending Blobs here would make the browser
      // emit file-typed parts, which Spring's MultipartResolver routes to MultipartFile and then
      // fails to coerce into String[].
      captions.forEach((c) => form.append("captions", c ?? ""));
    }
    if (durations) {
      // Same reasoning — plain-string parts. An empty string signals "no duration for this file".
      durations.forEach((d) => form.append("durations", String(d ?? "")));
    }
    return apiClient
      .post<ApiResponse<DprVoiceNote[]>>(
        `/v1/projects/${projectId}/dpr/${dprId}/voice-notes`,
        form,
        // Override the JSON default so axios picks the multipart boundary from the FormData.
        { headers: { "Content-Type": "multipart/form-data" } }
      )
      .then((r) => r.data);
  },

  listVoiceNotes: (projectId: string, dprId: string) =>
    apiClient
      .get<ApiResponse<DprVoiceNote[]>>(`/v1/projects/${projectId}/dpr/${dprId}/voice-notes`)
      .then((r) => r.data),

  deleteVoiceNote: (projectId: string, dprId: string, voiceNoteId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/dpr/${dprId}/voice-notes/${voiceNoteId}`),

  /**
   * Fetches a voice-note's audio bytes as an object URL suitable for {@code <audio src>}.
   * The stream endpoint is JWT-protected, so we cannot point an `<audio>` at it directly — we
   * authenticate the fetch ourselves, then turn the response into a blob URL. Caller is
   * responsible for revoking the URL when the player unmounts.
   */
  fetchVoiceNoteBlobUrl: async (
    projectId: string,
    dprId: string,
    voiceNoteId: string
  ): Promise<string> => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const res = await fetch(
      `${API_BASE_URL}/v1/projects/${projectId}/dpr/${dprId}/voice-notes/${voiceNoteId}/stream`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    if (!res.ok) throw new Error(`voice-note fetch ${res.status}`);
    const blob = await res.blob();
    return URL.createObjectURL(blob);
  },

  // ─── Voice form-fill ─────────────────────────────────────────────────────────

  voiceFill: async (
    projectId: string,
    audio: Blob,
    state: unknown,
    history: DprVoiceTurn[],
    dprId?: string | null
  ): Promise<DprVoiceFillResponse> => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const form = new FormData();
    form.append("audio", audio, "voice.webm");
    form.append("state", new Blob([JSON.stringify(state)], { type: "application/json" }));
    form.append("history", new Blob([JSON.stringify(history)], { type: "application/json" }));
    if (dprId) form.append("dprId", dprId);

    const res = await fetch(
      `${API_BASE_URL}/v1/projects/${projectId}/dpr/voice-fill`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: form,
      }
    );
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`voice-fill ${res.status}: ${text || "request failed"}`);
    }
    const data: ApiResponse<DprVoiceFillResponse> = await res.json();
    if (!data.data) throw new Error("voice-fill returned empty body");
    return data.data;
  },

  /** Live productivity preview for the DPR form. Read-only, never writes. */
  productivityPreview: (
    projectId: string,
    activityId: string,
    payload: ProductivityPreviewRequest,
  ) =>
    apiClient
      .post<ApiResponse<ProductivityPreviewResponse>>(
        `/v1/projects/${projectId}/dpr/activities/${activityId}/productivity-preview`,
        payload,
      )
      .then((r) => r.data),
};

// ─── Voice form-fill types ──────────────────────────────────────────────────────

export interface DprVoiceTurn {
  role: "user" | "assistant";
  content: string;
}

export interface DprVoicePhotoCaption {
  photoId: string;
  caption: string;
}

/**
 * Patch shape returned by the backend voice form-fill endpoint. Mirrors {@code DprBaseFields}
 * keys so the form can deep-merge it directly. Every key is optional and may be {@code null}
 * (meaning "the user did not address this field this turn").
 */
export interface DprVoicePatch {
  reportDate?: string | null;
  supervisorUserId?: string | null;
  supervisorName?: string | null;
  activityId?: string | null;
  activityName?: string | null;
  contractorName?: string | null;
  weatherCondition?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  shift?: "DAY" | "NIGHT" | null;
  approvalStatus?: "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED" | null;
  side?: "LHS" | "RHS" | "CENTER" | null;
  landmark?: string | null;
  chainageFromM?: number | null;
  chainageToM?: number | null;
  boqItemNo?: string | null;
  unit?: string | null;
  qtyExecuted?: number | null;
  remarks?: string | null;
  delayReason?: string | null;
  safetyObservation?: string | null;
  safetyIncidentType?: "NONE" | "NEAR_MISS" | "INCIDENT" | null;
  manpower?: Array<Record<string, unknown>>;
  equipment?: Array<Record<string, unknown>>;
  materials?: Array<Record<string, unknown>>;
}

export interface DprVoiceFillResponse {
  transcript: string;
  patch: DprVoicePatch;
  photoCaptions: DprVoicePhotoCaption[];
  followUpQuestion: string | null;
  complete: boolean;
  assistantTurn: DprVoiceTurn;
}

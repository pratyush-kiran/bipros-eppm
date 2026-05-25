import { apiClient } from "./client";
import type { ApiResponse } from "@/lib/types";

export type HdsDiscipline =
  | "HIGHWAY"
  | "BRIDGE"
  | "GEOTECH"
  | "PAVEMENT"
  | "TRAFFIC"
  | "DRAINAGE"
  | "OTHER";

export type HdsVersionStatus =
  | "PENDING"
  | "PARSING"
  | "CHUNKING"
  | "EMBEDDING"
  | "INDEXED"
  | "FAILED";

export interface HdsDocument {
  id: string;
  title: string;
  shortCode: string;
  discipline: HdsDiscipline;
  issuingAuthority?: string | null;
  country?: string | null;
  description?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HdsVersion {
  id: string;
  hdsDocumentId: string;
  versionLabel: string;
  revisionYear?: number | null;
  effectiveDate?: string | null;
  fileName?: string;
  fileSizeBytes?: number;
  pageCount?: number | null;
  status: HdsVersionStatus;
  indexingProgressPct: number;
  chunkCount?: number | null;
  uploadedAt: string;
  indexedAt?: string | null;
}

export interface HdsVersionDetail {
  version: HdsVersion;
  indexingError?: string | null;
}

export interface HdsChunk {
  id: string;
  versionId: string;
  versionLabel: string;
  pageStart: number;
  pageEnd: number;
  sectionPath: string;
  chunkType: string;
  content: string;
}

export interface CreateHdsDocumentRequest {
  title: string;
  shortCode: string;
  discipline: HdsDiscipline;
  issuingAuthority?: string;
  country?: string;
  description?: string;
}

export interface HdsProgressEvent {
  stage: string;
  progressPct: number;
  message: string;
}

export const hdsApi = {
  listDocuments: async (): Promise<HdsDocument[]> => {
    const { data } = await apiClient.get<ApiResponse<HdsDocument[]>>(
      "/v1/hds/admin/documents"
    );
    return data.data ?? [];
  },

  createDocument: async (req: CreateHdsDocumentRequest): Promise<HdsDocument> => {
    const { data } = await apiClient.post<ApiResponse<HdsDocument>>(
      "/v1/hds/admin/documents",
      req
    );
    if (!data.data) throw new Error("createDocument: empty response");
    return data.data;
  },

  updateDocument: async (
    id: string,
    patch: Partial<CreateHdsDocumentRequest>
  ): Promise<HdsDocument> => {
    const { data } = await apiClient.patch<ApiResponse<HdsDocument>>(
      `/v1/hds/admin/documents/${id}`,
      patch
    );
    if (!data.data) throw new Error("updateDocument: empty response");
    return data.data;
  },

  deleteDocument: async (id: string): Promise<void> => {
    await apiClient.delete(`/v1/hds/admin/documents/${id}`);
  },

  listVersions: async (): Promise<HdsVersion[]> => {
    const { data } = await apiClient.get<ApiResponse<HdsVersion[]>>(
      "/v1/hds/versions"
    );
    return data.data ?? [];
  },

  getVersion: async (id: string): Promise<HdsVersionDetail> => {
    const { data } = await apiClient.get<ApiResponse<HdsVersionDetail>>(
      `/v1/hds/admin/versions/${id}`
    );
    if (!data.data) throw new Error("getVersion: empty response");
    return data.data;
  },

  uploadVersion: async (
    documentId: string,
    versionLabel: string,
    revisionYear: number | undefined,
    file: File,
    onProgress?: (pct: number) => void
  ): Promise<HdsVersion> => {
    const form = new FormData();
    form.append("versionLabel", versionLabel);
    if (revisionYear !== undefined) form.append("revisionYear", String(revisionYear));
    form.append("file", file);
    const { data } = await apiClient.post<ApiResponse<HdsVersion>>(
      `/v1/hds/admin/documents/${documentId}/versions`,
      form,
      {
        headers: { "Content-Type": "multipart/form-data" },
        onUploadProgress: (e) => {
          if (onProgress && e.total) {
            onProgress(Math.round((e.loaded / e.total) * 100));
          }
        },
        maxBodyLength: Infinity,
        maxContentLength: Infinity,
      }
    );
    if (!data.data) throw new Error("uploadVersion: empty response");
    return data.data;
  },

  retryVersion: async (id: string): Promise<void> => {
    await apiClient.post(`/v1/hds/admin/versions/${id}/retry`);
  },

  deleteVersion: async (id: string): Promise<void> => {
    await apiClient.delete(`/v1/hds/admin/versions/${id}`);
  },

  getChunk: async (id: string): Promise<HdsChunk> => {
    const { data } = await apiClient.get<ApiResponse<HdsChunk>>(
      `/v1/hds/chunks/${id}`
    );
    if (!data.data) throw new Error("getChunk: empty response");
    return data.data;
  },

  presignPdf: async (
    versionId: string
  ): Promise<{ url: string; expiresAt: string }> => {
    const { data } = await apiClient.get<
      ApiResponse<{ url: string; expiresAt: string }>
    >(`/v1/hds/versions/${versionId}/pdf`);
    if (!data.data) throw new Error("presignPdf: empty response");
    return data.data;
  },

  /**
   * Subscribe to SSE ingestion progress for a version.
   * Returns an AbortController-like handle with `.close()`.
   */
  subscribeProgress: (
    versionId: string,
    onEvent: (ev: HdsProgressEvent) => void
  ): { close: () => void } => {
    const token =
      typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const url = `${
      process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080"
    }/v1/hds/admin/versions/${versionId}/progress`;
    const controller = new AbortController();

    (async () => {
      const res = await fetch(url, {
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "text/event-stream",
        },
        signal: controller.signal,
      });
      if (!res.ok || !res.body) return;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const events = buf.split("\n\n");
        buf = events.pop() ?? "";
        for (const block of events) {
          const dataLine = block.split("\n").find((l) => l.startsWith("data:"));
          if (!dataLine) continue;
          try {
            const parsed = JSON.parse(dataLine.slice(5).trim()) as HdsProgressEvent;
            onEvent(parsed);
          } catch {
            /* ignore parse errors */
          }
        }
      }
    })().catch(() => {
      /* aborted or network error */
    });

    return { close: () => controller.abort() };
  },
};

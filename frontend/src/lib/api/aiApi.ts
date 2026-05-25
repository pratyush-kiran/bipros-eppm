import { apiClient } from "./client";
import type {
  ApiResponse,
  AssetClass,
  CollisionResult,
  WbsAiGenerateRequest,
  WbsAiApplyRequest,
  WbsAiGenerationResponse,
  WbsAiJobAccepted,
  WbsAiJobView,
  ActivityAiGenerateRequest,
  ActivityAiGenerateFromDocumentMetadata,
  ActivityAiGenerationResponse,
  ActivityAiApplyRequest,
  ActivityAiApplyResponse,
  InsightsResponse,
} from "../types";

export interface WbsAiGenerateFromDocumentMetadata {
  assetClass?: AssetClass;
  targetDepth?: number;
}

export interface LlmProviderResponse {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  maxTokens: number;
  temperature: number;
  timeoutMs: number;
  authScheme: string;
  supportsNativeTools: boolean;
  isDefault: boolean;
  isActive: boolean;
}

export interface CreateLlmProviderRequest {
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  maxTokens?: number;
  temperature?: number;
  timeoutMs?: number;
  authScheme?: string;
  supportsNativeTools?: boolean;
  isDefault?: boolean;
  isActive?: boolean;
}

export interface UpdateLlmProviderRequest {
  name?: string;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  maxTokens?: number;
  temperature?: number;
  timeoutMs?: number;
  authScheme?: string;
  supportsNativeTools?: boolean;
  isDefault?: boolean;
  isActive?: boolean;
}

export interface ProviderTestResponse {
  ok: boolean;
  latencyMs: number;
  tokensIn: number;
  tokensOut: number;
  modelEcho: string | null;
  error: string | null;
}

export interface ChatRequest {
  conversationId?: string | null;
  // Nullable for cross-project ("general") agentic search.
  projectId: string | null;
  module: string;
  message: string;
  imageUrl?: string | null;
  /**
   * UUIDs of HDS document versions the user has scoped the chat to. When
   * non-empty the orchestrator switches to deterministic HDS retrieval mode
   * (see `runHdsDeterministic` on the backend) and the answer is grounded
   * in citations from those versions.
   */
  hdsVersionIds?: string[];
}

export interface SseEvent {
  event: string;
  data: Record<string, unknown>;
}

export interface ConversationSummary {
  id: string;
  title: string;
  module: string;
  lastMessageAt: string;
}

export interface ConversationMessage {
  role: string;
  content: string;
  createdAt: string;
}

export interface ConversationDetail {
  id: string;
  title: string;
  messages: ConversationMessage[];
}

export const aiApi = {
  listProviders: () =>
    apiClient.get<ApiResponse<LlmProviderResponse[]>>("/v1/admin/llm-providers").then((r) => r.data),

  getProvider: (id: string) =>
    apiClient.get<ApiResponse<LlmProviderResponse>>(`/v1/admin/llm-providers/${id}`).then((r) => r.data),

  createProvider: (data: CreateLlmProviderRequest) =>
    apiClient.post<ApiResponse<LlmProviderResponse>>("/v1/admin/llm-providers", data).then((r) => r.data),

  updateProvider: (id: string, data: UpdateLlmProviderRequest) =>
    apiClient.put<ApiResponse<LlmProviderResponse>>(`/v1/admin/llm-providers/${id}`, data).then((r) => r.data),

  deleteProvider: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/v1/admin/llm-providers/${id}`).then((r) => r.data),

  testProvider: (id: string) =>
    apiClient.post<ApiResponse<ProviderTestResponse>>(`/v1/admin/llm-providers/${id}/test`).then((r) => r.data),

  speechToText: (audioBlob: Blob) => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const form = new FormData();
    form.append("audio", audioBlob, "recording.webm");
    return fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ""}/v1/ai/speech-to-text`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    }).then(async (r) => {
      if (!r.ok) throw new Error(`STT ${r.status}`);
      const data = await r.json();
      return data.data as string;
    });
  },

  uploadImage: (conversationId: string, imageFile: File) => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const form = new FormData();
    form.append("conversationId", conversationId);
    form.append("image", imageFile);
    return fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ""}/v1/ai/images`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    }).then(async (r) => {
      if (!r.ok) throw new Error(`upload ${r.status}`);
      const data = await r.json();
      return data.data as { fileName: string; url: string; mimeType: string };
    });
  },

  generateWbs: (projectId: string, body: WbsAiGenerateRequest) =>
    apiClient.post<ApiResponse<WbsAiGenerationResponse>>(`/v1/projects/${projectId}/wbs/ai/generate`, body).then((r) => r.data),

  /**
   * Submit a from-document generation job. Returns 202 + {jobId} immediately;
   * the actual extraction runs on the bounded executor on the backend. Poll
   * {@link aiApi.getWbsAiJob} every ~2 s until status is DONE / FAILED / CANCELLED.
   */
  generateWbsFromDocument: (
    projectId: string,
    metadata: WbsAiGenerateFromDocumentMetadata,
    file: File
  ) => {
    const form = new FormData();
    form.append(
      "metadata",
      new Blob([JSON.stringify(metadata)], { type: "application/json" })
    );
    form.append("file", file);
    return apiClient
      .post<ApiResponse<WbsAiJobAccepted>>(
        `/v1/projects/${projectId}/wbs/ai/generate-from-document`,
        form,
        { headers: { "Content-Type": "multipart/form-data" } }
      )
      .then((r) => r.data);
  },

  getWbsAiJob: (projectId: string, jobId: string) =>
    apiClient
      .get<ApiResponse<WbsAiJobView>>(`/v1/projects/${projectId}/wbs/ai/jobs/${jobId}`)
      .then((r) => r.data),

  cancelWbsAiJob: (projectId: string, jobId: string) =>
    apiClient.delete<void>(`/v1/projects/${projectId}/wbs/ai/jobs/${jobId}`).then((r) => r.data),

  applyGeneratedWbs: (projectId: string, body: WbsAiApplyRequest) =>
    apiClient
      .post<ApiResponse<CollisionResult[]>>(`/v1/projects/${projectId}/wbs/ai/apply`, body)
      .then((r) => r.data),

  generateActivities: (projectId: string, body: ActivityAiGenerateRequest) =>
    apiClient.post<ApiResponse<ActivityAiGenerationResponse>>(
      `/v1/projects/${projectId}/activities/ai/generate`, body
    ).then((r) => r.data),

  /**
   * Submit a from-document activity-generation request. Mirrors the WBS path:
   * the file is sent natively to OpenAI (Files API for >5 MB); the model reads
   * tables, layout, and scanned content from the PDF directly.
   */
  generateActivitiesFromDocument: (
    projectId: string,
    metadata: ActivityAiGenerateFromDocumentMetadata,
    file: File
  ) => {
    const form = new FormData();
    form.append(
      "metadata",
      new Blob([JSON.stringify(metadata)], { type: "application/json" })
    );
    form.append("file", file);
    return apiClient
      .post<ApiResponse<ActivityAiGenerationResponse>>(
        `/v1/projects/${projectId}/activities/ai/generate-from-document`,
        form,
        { headers: { "Content-Type": "multipart/form-data" } }
      )
      .then((r) => r.data);
  },

  applyGeneratedActivities: (projectId: string, body: ActivityAiApplyRequest) =>
    apiClient.post<ApiResponse<ActivityAiApplyResponse>>(
      `/v1/projects/${projectId}/activities/ai/apply`, body
    ).then((r) => r.data),

  getInsights: (endpoint: string, projectId: string, force?: boolean) => {
    const url = `${endpoint}${force ? "?force=true" : ""}`;
    return apiClient.post<ApiResponse<InsightsResponse>>(url, { projectId }).then((r) => r.data);
  },

  listConversations: (params?: { projectId?: string; limit?: number }) =>
    apiClient
      .get<ApiResponse<ConversationSummary[]>>("/v1/ai/conversations", { params })
      .then((r) => r.data),

  getConversation: (id: string) =>
    apiClient
      .get<ApiResponse<ConversationDetail>>(`/v1/ai/conversations/${id}`)
      .then((r) => r.data),

  deleteConversation: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/v1/ai/conversations/${id}`).then((r) => r.data),

  streamChat: async function* (req: ChatRequest, signal: AbortSignal): AsyncGenerator<SseEvent> {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ""}/v1/ai/chat/stream`, {
      method: "POST",
      signal,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        Accept: "text/event-stream",
      },
      body: JSON.stringify(req),
    });
    if (!res.ok || !res.body) throw new Error(`stream ${res.status}`);
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buf = "";
    const parseField = (line: string, prefix: string): string | null => {
      if (!line.startsWith(prefix)) return null;
      const rest = line.slice(prefix.length);
      return rest.startsWith(" ") ? rest.slice(1) : rest;
    };
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += value;
      const frames = buf.split(/\r?\n\r?\n/);
      buf = frames.pop() ?? "";
      for (const f of frames) {
        const lines = f.split(/\r?\n/);
        let event = "message";
        const dataLines: string[] = [];
        for (const line of lines) {
          if (!line || line.startsWith(":")) continue;
          const ev = parseField(line, "event:");
          if (ev !== null) {
            event = ev.trim();
            continue;
          }
          const d = parseField(line, "data:");
          if (d !== null) {
            dataLines.push(d);
          }
        }
        const data = dataLines.join("\n");
        if (data) {
          try {
            yield { event, data: JSON.parse(data) };
          } catch {
            yield { event, data: { raw: data } };
          }
        }
      }
    }
  },
};

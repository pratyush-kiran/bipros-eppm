import { apiClient } from "./client";
import type {
  ApiResponse,
  CreateGoodsReceiptRequest,
  CreateMaterialIssueRequest,
  CreateMaterialRequest,
  GoodsReceiptResponse,
  MaterialCategory,
  MaterialIssueResponse,
  MaterialResponse,
  MaterialStockRow,
} from "../types";
import type { MaterialRateMaster } from "./materialRateMasterApi";

/** PMS MasterData Screen 09a — Material Catalogue CRUD. */
export const materialCatalogueApi = {
  listByProject: (projectId: string, category?: MaterialCategory) => {
    const qs = category ? `?category=${category}` : "";
    return apiClient
      .get<ApiResponse<MaterialResponse[]>>(`/v1/projects/${projectId}/materials${qs}`)
      .then((r) => r.data);
  },

  create: (projectId: string, body: CreateMaterialRequest) =>
    apiClient
      .post<ApiResponse<MaterialResponse>>(`/v1/projects/${projectId}/materials`, body)
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<ApiResponse<MaterialResponse>>(`/v1/materials/${id}`).then((r) => r.data),

  update: (id: string, body: Partial<CreateMaterialRequest>) =>
    apiClient.put<ApiResponse<MaterialResponse>>(`/v1/materials/${id}`, body).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete<ApiResponse<void>>(`/v1/materials/${id}`).then((r) => r.data),

  /**
   * Resolve the active Material Rate Master row matching this material's category +
   * specification grade. Backend returns {@code null} (wrapped in ApiResponse) when no
   * matching master row exists — the UI surfaces an "unmapped" hint that links to the rate
   * master screen so the user can create one.
   */
  getEffectiveRate: (id: string) =>
    apiClient
      .get<ApiResponse<MaterialRateMaster | null>>(`/v1/materials/${id}/effective-rate`)
      .then((r) => r.data),
};

/** PMS MasterData Screen 09b — Stock Register + GRN + Issue. */
export const stockApi = {
  listByProject: (projectId: string) =>
    apiClient
      .get<ApiResponse<MaterialStockRow[]>>(`/v1/projects/${projectId}/stock-register`)
      .then((r) => r.data),

  getForMaterial: (projectId: string, materialId: string) =>
    apiClient
      .get<ApiResponse<MaterialStockRow>>(`/v1/projects/${projectId}/materials/${materialId}/stock`)
      .then((r) => r.data),
};

export const grnApi = {
  listByProject: (projectId: string) =>
    apiClient
      .get<ApiResponse<GoodsReceiptResponse[]>>(`/v1/projects/${projectId}/grns`)
      .then((r) => r.data),

  create: (projectId: string, body: CreateGoodsReceiptRequest) =>
    apiClient
      .post<ApiResponse<GoodsReceiptResponse>>(`/v1/projects/${projectId}/grns`, body)
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<ApiResponse<GoodsReceiptResponse>>(`/v1/grns/${id}`).then((r) => r.data),

  listByMaterial: (materialId: string) =>
    apiClient
      .get<ApiResponse<GoodsReceiptResponse[]>>(`/v1/materials/${materialId}/grns`)
      .then((r) => r.data),
};

export const materialIssueApi = {
  listByProject: (projectId: string) =>
    apiClient
      .get<ApiResponse<MaterialIssueResponse[]>>(`/v1/projects/${projectId}/issues`)
      .then((r) => r.data),

  create: (projectId: string, body: CreateMaterialIssueRequest) =>
    apiClient
      .post<ApiResponse<MaterialIssueResponse>>(`/v1/projects/${projectId}/issues`, body)
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<ApiResponse<MaterialIssueResponse>>(`/v1/issues/${id}`).then((r) => r.data),

  listByMaterial: (materialId: string) =>
    apiClient
      .get<ApiResponse<MaterialIssueResponse[]>>(`/v1/materials/${materialId}/issues`)
      .then((r) => r.data),
};

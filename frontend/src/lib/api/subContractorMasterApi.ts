import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface SubContractorWorkActivityMappingRow {
  id?: string | null;
  scWorkTypeId: string;
  workTypeName?: string | null;
  unit?: string | null;
  ratePerUnit?: number | null;
  outputPerDay?: number | null;
}

export interface SubContractorMaster {
  id: string;
  code: string;
  name: string;
  location?: string | null;
  primaryContactName?: string | null;
  primaryContactNumber?: string | null;
  remarks?: string | null;
  active: boolean;
  workActivityMappings: SubContractorWorkActivityMappingRow[];
  createdAt: string;
  updatedAt: string;
}

export interface SubContractorMasterWithMappingsRequest {
  code: string;
  name: string;
  location?: string | null;
  primaryContactName?: string | null;
  primaryContactNumber?: string | null;
  remarks?: string | null;
  active?: boolean;
  workActivityMappings: SubContractorWorkActivityMappingRow[];
}

export const subContractorMasterApi = {
  list: () =>
    apiClient
      .get<ApiResponse<SubContractorMaster[]>>("/v1/admin/sub-contractors")
      .then((r) => r.data),

  get: (id: string) =>
    apiClient
      .get<ApiResponse<SubContractorMaster>>(`/v1/admin/sub-contractors/${id}`)
      .then((r) => r.data),

  create: (request: SubContractorMasterWithMappingsRequest) =>
    apiClient
      .post<ApiResponse<SubContractorMaster>>("/v1/admin/sub-contractors", request)
      .then((r) => r.data),

  update: (id: string, request: SubContractorMasterWithMappingsRequest) =>
    apiClient
      .put<ApiResponse<SubContractorMaster>>(`/v1/admin/sub-contractors/${id}`, request)
      .then((r) => r.data),

  delete: (id: string) => apiClient.delete(`/v1/admin/sub-contractors/${id}`),
};

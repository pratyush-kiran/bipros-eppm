import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface SubContractorWorkType {
  id: string;
  name: string;
  defaultUnit?: string | null;
}

export const scWorkTypeApi = {
  search: (q: string) =>
    apiClient
      .get<ApiResponse<SubContractorWorkType[]>>(
        `/v1/admin/sc-work-types?q=${encodeURIComponent(q)}`,
      )
      .then((r) => r.data),

  listAll: () =>
    apiClient
      .get<ApiResponse<SubContractorWorkType[]>>("/v1/admin/sc-work-types")
      .then((r) => r.data),

  findOrCreate: (name: string, defaultUnit?: string | null) =>
    apiClient
      .post<ApiResponse<SubContractorWorkType>>(
        "/v1/admin/sc-work-types/find-or-create",
        { name, defaultUnit },
      )
      .then((r) => r.data),
};

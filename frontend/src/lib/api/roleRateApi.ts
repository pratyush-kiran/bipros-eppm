import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/** A manpower role's rate row — one per (category, grade) combination on the role. */
export interface ManpowerRoleRate {
  id: string;
  roleId: string;
  roleName?: string | null;
  categoryId: string;
  categoryName?: string | null;
  gradeId: string;
  gradeName?: string | null;
  unit: string;
  rate: number;
  active: boolean;
}

export interface ManpowerRoleRateRequest {
  categoryId: string;
  gradeId: string;
  unit: string;
  rate: number;
  active?: boolean;
}

/** An equipment role's variant — one per (make, model) on the role. */
export interface EquipmentRoleVariant {
  id: string;
  roleId: string;
  roleName?: string | null;
  make: string;
  model: string;
  unit: string;
  rate: number;
  active: boolean;
}

export interface EquipmentRoleVariantRequest {
  make: string;
  model: string;
  unit: string;
  rate: number;
  active?: boolean;
}

/** A material role's variant — one per spec/grade on the role. */
export interface MaterialRoleVariant {
  id: string;
  roleId: string;
  roleName?: string | null;
  specGrade: string;
  unit: string;
  rate: number;
  active: boolean;
}

export interface MaterialRoleVariantRequest {
  specGrade: string;
  unit: string;
  rate: number;
  active?: boolean;
}

export interface ProjectRoleRateOverride {
  id: string;
  projectId: string;
  roleType: "MANPOWER" | "EQUIPMENT" | "MATERIAL";
  variantId: string;
  variantLabel?: string | null;
  overrideRate: number;
  masterRate?: number | null;
  active: boolean;
}

export interface ProjectRoleRateOverrideRequest {
  manpowerRoleRateId?: string;
  equipmentRoleVariantId?: string;
  materialRoleVariantId?: string;
  overrideRate: number;
  active?: boolean;
}

// === Combined role + variants (one-shot save) ===

export interface RoleWithVariantsRequest {
  code: string;
  name: string;
  description?: string | null;
  resourceTypeId: string;
  sortOrder?: number | null;
  active?: boolean;
  manpowerVariants?: ManpowerVariantInput[];
  equipmentVariants?: EquipmentVariantInput[];
  materialVariants?: MaterialVariantInput[];
}

export interface ManpowerVariantInput {
  id?: string | null;
  categoryId: string;
  gradeId: string;
  unit: string;
  rate: number;
  active?: boolean;
}

export interface EquipmentVariantInput {
  id?: string | null;
  make: string;
  model: string;
  unit: string;
  rate: number;
  active?: boolean;
}

export interface MaterialVariantInput {
  id?: string | null;
  specGrade: string;
  unit: string;
  rate: number;
  active?: boolean;
}

export interface RoleWithVariantsResponse {
  role: {
    id: string;
    code: string;
    name: string;
    description?: string | null;
    resourceTypeId: string;
    resourceTypeCode: string;
    resourceTypeName: string;
    sortOrder: number;
    active: boolean;
  };
  manpowerVariants: ManpowerRoleRate[];
  equipmentVariants: EquipmentRoleVariant[];
  materialVariants: MaterialRoleVariant[];
}

export const roleRateApi = {
  // ===== Manpower =====
  listManpowerForRole: (roleId: string) =>
    apiClient
      .get<ApiResponse<ManpowerRoleRate[]>>(`/v1/roles/${roleId}/manpower-rates`)
      .then((r) => r.data),
  // Flat rate-book listing across every manpower role — DPR Add dropdown source.
  listAllManpower: () =>
    apiClient
      .get<ApiResponse<ManpowerRoleRate[]>>(`/v1/role-rates/manpower`)
      .then((r) => r.data),
  createManpowerRate: (roleId: string, req: ManpowerRoleRateRequest) =>
    apiClient
      .post<ApiResponse<ManpowerRoleRate>>(`/v1/roles/${roleId}/manpower-rates`, req)
      .then((r) => r.data),
  updateManpowerRate: (id: string, req: ManpowerRoleRateRequest) =>
    apiClient
      .put<ApiResponse<ManpowerRoleRate>>(`/v1/manpower-rates/${id}`, req)
      .then((r) => r.data),
  deleteManpowerRate: (id: string) => apiClient.delete(`/v1/manpower-rates/${id}`),

  // ===== Equipment =====
  listEquipmentForRole: (roleId: string) =>
    apiClient
      .get<ApiResponse<EquipmentRoleVariant[]>>(`/v1/roles/${roleId}/equipment-variants`)
      .then((r) => r.data),
  listAllEquipment: () =>
    apiClient
      .get<ApiResponse<EquipmentRoleVariant[]>>(`/v1/role-rates/equipment`)
      .then((r) => r.data),
  createEquipmentVariant: (roleId: string, req: EquipmentRoleVariantRequest) =>
    apiClient
      .post<ApiResponse<EquipmentRoleVariant>>(`/v1/roles/${roleId}/equipment-variants`, req)
      .then((r) => r.data),
  updateEquipmentVariant: (id: string, req: EquipmentRoleVariantRequest) =>
    apiClient
      .put<ApiResponse<EquipmentRoleVariant>>(`/v1/equipment-variants/${id}`, req)
      .then((r) => r.data),
  deleteEquipmentVariant: (id: string) => apiClient.delete(`/v1/equipment-variants/${id}`),

  // ===== Material =====
  listMaterialForRole: (roleId: string) =>
    apiClient
      .get<ApiResponse<MaterialRoleVariant[]>>(`/v1/roles/${roleId}/material-variants`)
      .then((r) => r.data),
  listAllMaterial: () =>
    apiClient
      .get<ApiResponse<MaterialRoleVariant[]>>(`/v1/role-rates/material`)
      .then((r) => r.data),
  createMaterialVariant: (roleId: string, req: MaterialRoleVariantRequest) =>
    apiClient
      .post<ApiResponse<MaterialRoleVariant>>(`/v1/roles/${roleId}/material-variants`, req)
      .then((r) => r.data),
  updateMaterialVariant: (id: string, req: MaterialRoleVariantRequest) =>
    apiClient
      .put<ApiResponse<MaterialRoleVariant>>(`/v1/material-variants/${id}`, req)
      .then((r) => r.data),
  deleteMaterialVariant: (id: string) => apiClient.delete(`/v1/material-variants/${id}`),

  // ===== Project Overrides =====
  listOverridesForProject: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectRoleRateOverride[]>>(
        `/v1/projects/${projectId}/role-rate-overrides`,
      )
      .then((r) => r.data),
  createOverride: (projectId: string, req: ProjectRoleRateOverrideRequest) =>
    apiClient
      .post<ApiResponse<ProjectRoleRateOverride>>(
        `/v1/projects/${projectId}/role-rate-overrides`,
        req,
      )
      .then((r) => r.data),
  deleteManpowerOverride: (id: string) =>
    apiClient.delete(`/v1/role-rate-overrides/manpower/${id}`),
  deleteEquipmentOverride: (id: string) =>
    apiClient.delete(`/v1/role-rate-overrides/equipment/${id}`),
  deleteMaterialOverride: (id: string) =>
    apiClient.delete(`/v1/role-rate-overrides/material/${id}`),

  // ===== Combined role + variants (one-shot save) =====
  getWithVariants: (roleId: string) =>
    apiClient
      .get<ApiResponse<RoleWithVariantsResponse>>(
        `/v1/resource-roles/${roleId}/with-variants`,
      )
      .then((r) => r.data),
  createWithVariants: (req: RoleWithVariantsRequest) =>
    apiClient
      .post<ApiResponse<RoleWithVariantsResponse>>(
        `/v1/resource-roles/with-variants`,
        req,
      )
      .then((r) => r.data),
  updateWithVariants: (roleId: string, req: RoleWithVariantsRequest) =>
    apiClient
      .put<ApiResponse<RoleWithVariantsResponse>>(
        `/v1/resource-roles/${roleId}/with-variants`,
        req,
      )
      .then((r) => r.data),
};

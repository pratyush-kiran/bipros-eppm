import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface SubContractorAssignmentLine {
  activityId: string;
  workTypeName: string;
  unit: string;
  plannedUnits: number;
  ratePerUnit: number;
  plannedCost: number;
  actualUnits: number;
  actualCost: number;
}

export interface ProjectSubContractorSummaryResponse {
  subContractorMasterId: string;
  code: string;
  name: string;
  location: string;
  primaryContactName: string;
  primaryContactNumber: string;
  assignmentCount: number;
  plannedCost: number;
  actualCost: number;
  costVariance: number;
  percentComplete: number;
  lines: SubContractorAssignmentLine[];
}

export interface VendorReceiptLine {
  grnId: string;
  grnNumber: string;
  receivedDate: string;
  materialId: string;
  materialName: string;
  unit: string;
  quantity: number;
  unitRate: number;
  amount: number;
  acceptedQuantity: number;
  rejectedQuantity: number;
}

export interface VendorMaterialLine {
  materialId: string;
  code: string;
  name: string;
  category: string;
  unit: string;
}

export interface ProjectVendorSummaryResponse {
  supplierOrganisationId: string | null;
  materialCount: number;
  receiptCount: number;
  totalValueReceived: number;
  lastReceiptDate: string | null;
  receipts: VendorReceiptLine[];
  materials: VendorMaterialLine[];
}

export const procurementApi = {
  subContractors: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectSubContractorSummaryResponse[]>>(
        `/v1/projects/${projectId}/procurement/sub-contractors`,
      )
      .then((r) => r.data),

  vendors: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectVendorSummaryResponse[]>>(
        `/v1/projects/${projectId}/procurement/vendors`,
      )
      .then((r) => r.data),
};

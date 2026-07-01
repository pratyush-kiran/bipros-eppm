import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface DocumentFolder {
  id: string;
  projectId: string;
  name: string;
  code: string;
  category: string;
  parentId: string | null;
  wbsNodeId: string | null;
  sortOrder: number;
}

export type DocumentStatus =
  | "DRAFT"
  | "UNDER_REVIEW"
  | "APPROVED"
  | "SUPERSEDED"
  | "ARCHIVED"
  | "IFC"
  | "IFA"
  | "PUBLISHED"
  | "EXECUTED"
  | "VALID"
  | "OPEN"
  | "CLOSED";

export type DocumentType =
  | "DRAWING"
  | "SPECIFICATION"
  | "RFI"
  | "MINUTES"
  | "CONTRACT_DOCUMENT"
  | "REPORT"
  | "BANK_GUARANTEE"
  | "LOA";

export type DrawingDisciplineType =
  | "CIVIL"
  | "STRUCTURAL"
  | "ELECTRICAL"
  | "MECHANICAL"
  | "ARCHITECTURAL"
  | "PLUMBING"
  | "HVAC"
  | "ICT"
  | "MANAGEMENT"
  | "LEGAL"
  | "FINANCE";

export interface Document {
  id: string;
  folderId: string;
  projectId: string;
  documentNumber: string;
  title: string;
  description: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  filePath: string;
  currentVersion: number;
  status: DocumentStatus;
  documentType?: DocumentType | null;
  discipline?: DrawingDisciplineType | null;
  transmittalNumber?: string | null;
  tags: string;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentVersion {
  id: string;
  documentId: string;
  versionNumber: number;
  fileName: string;
  filePath: string;
  fileSize: number;
  changeDescription: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface DrawingRegister {
  id: string;
  projectId: string;
  documentId: string | null;
  drawingNumber: string;
  title: string;
  discipline: string;
  revision: string;
  revisionDate: string;
  status: "PRELIMINARY" | "IFA" | "IFC" | "AS_BUILT" | "SUPERSEDED";
  packageCode: string;
  scale: string;
  createdAt: string;
  updatedAt: string;
}

export interface Transmittal {
  id: string;
  projectId: string;
  transmittalNumber: string;
  subject: string;
  fromParty: string;
  toParty: string;
  sentDate: string;
  dueDate: string;
  status: "DRAFT" | "SENT" | "RECEIVED" | "ACKNOWLEDGED" | "OVERDUE";
  remarks: string;
  createdAt: string;
  updatedAt: string;
}

export interface RfiRegister {
  id: string;
  projectId: string;
  rfiNumber: string;
  subject: string;
  description: string;
  raisedBy: string;
  assignedTo: string;
  raisedDate: string;
  dueDate: string;
  closedDate: string | null;
  status: "OPEN" | "RESPONDED" | "CLOSED" | "OVERDUE";
  priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  response: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateFolderRequest {
  name: string;
  code: string;
  category: string;
  parentId?: string | null;
  wbsNodeId?: string | null;
  sortOrder?: number;
}

export interface UpdateFolderRequest {
  name?: string;
  code?: string;
  category?: string;
  parentId?: string | null;
  wbsNodeId?: string | null;
  sortOrder?: number;
}

export interface CreateRfiRequest {
  projectId: string;
  rfiNumber: string;
  subject: string;
  description?: string;
  priority?: string;
  raisedBy: string;
  assignedTo: string;
  raisedDate: string;
  dueDate: string;
}

export interface UpdateRfiRequest {
  rfiNumber: string;
  subject: string;
  description?: string;
  priority?: string;
  raisedBy: string;
  assignedTo: string;
  raisedDate: string;
  dueDate: string;
  response?: string;
  status?: string;
  closedDate?: string | null;
}

export interface CreateDrawingRequest {
  projectId: string;
  drawingNumber: string;
  title: string;
  discipline?: string;
  revision?: string;
  status?: string;
}

export interface UpdateDrawingRequest {
  drawingNumber?: string;
  title?: string;
  discipline?: string;
  revision?: string;
  revisionDate?: string;
  status?: string;
  packageCode?: string;
  scale?: string;
}

export interface CreateDocumentRequest {
  projectId: string;
  title: string;
  documentNumber?: string;
  folderId?: string;
  status?: string;
  description?: string;
  tags?: string;
}

export interface UpdateDocumentRequest {
  title?: string;
  documentNumber?: string;
  folderId?: string;
  status?: string;
  description?: string;
  tags?: string;
}

export interface AddDocumentVersionRequest {
  changeDescription?: string;
}

/**
 * Metadata for multipart uploadDocument — mirrors backend UploadDocumentRequest.
 * The binary itself is passed separately as a File; all file-derived fields
 * (fileName, fileSize, mimeType, filePath) are set server-side.
 */
export interface UploadDocumentMetadata {
  folderId?: string | null;
  documentNumber: string;
  title: string;
  description?: string | null;
  status?: DocumentStatus | null;
  documentType?: DocumentType | null;
  discipline?: DrawingDisciplineType | null;
  transmittalNumber?: string | null;
  wbsPackageCode?: string | null;
  issuedBy?: string | null;
  issuedDate?: string | null;
  approvedBy?: string | null;
  approvedDate?: string | null;
  tags?: string | null;
}

export interface CreateTransmittalRequest {
  projectId: string;
  transmittalNumber: string;
  subject: string;
  fromParty: string;
  toParty: string;
  sentDate: string;
  dueDate?: string;
  status?: string;
  remarks?: string;
}

export interface UpdateTransmittalRequest {
  transmittalNumber?: string;
  subject?: string;
  fromParty?: string;
  toParty?: string;
  sentDate?: string;
  dueDate?: string;
  status?: string;
  remarks?: string;
}

export const documentApi = {
  // Document Folders
  listRootFolders: (projectId: string) =>
    apiClient
      .get<ApiResponse<DocumentFolder[]>>(
        `/v1/projects/${projectId}/document-folders/root`
      )
      .then((r) => r.data),

  listChildFolders: (projectId: string, folderId: string) =>
    apiClient
      .get<ApiResponse<DocumentFolder[]>>(
        `/v1/projects/${projectId}/document-folders/${folderId}/children`
      )
      .then((r) => r.data),

  createFolder: (projectId: string, data: CreateFolderRequest) =>
    apiClient
      .post<ApiResponse<DocumentFolder>>(
        `/v1/projects/${projectId}/document-folders`,
        data
      )
      .then((r) => r.data),

  updateFolder: (projectId: string, folderId: string, data: UpdateFolderRequest) =>
    apiClient
      .put<ApiResponse<DocumentFolder>>(
        `/v1/projects/${projectId}/document-folders/${folderId}`,
        data
      )
      .then((r) => r.data),

  deleteFolder: (projectId: string, folderId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/document-folders/${folderId}`),

  // Documents
  listDocuments: (projectId: string) =>
    apiClient
      .get<ApiResponse<Document[]>>(`/v1/projects/${projectId}/documents`)
      .then((r) => r.data),

  getDocument: (projectId: string, documentId: string) =>
    apiClient
      .get<ApiResponse<Document>>(
        `/v1/projects/${projectId}/documents/${documentId}`
      )
      .then((r) => r.data),

  createDocument: (projectId: string, data: CreateDocumentRequest) =>
    apiClient
      .post<ApiResponse<Document>>(`/v1/projects/${projectId}/documents`, data)
      .then((r) => r.data),

  /**
   * Multipart upload: metadata JSON part + binary file part.
   * Server derives fileName/fileSize/mimeType/filePath from the file.
   */
  uploadDocument: (
    projectId: string,
    metadata: UploadDocumentMetadata,
    file: File
  ) => {
    const form = new FormData();
    form.append(
      "metadata",
      new Blob([JSON.stringify(metadata)], { type: "application/json" })
    );
    form.append("file", file);
    return apiClient
      .post<ApiResponse<Document>>(
        `/v1/projects/${projectId}/documents/upload`,
        form,
        { headers: { "Content-Type": "multipart/form-data" } }
      )
      .then((r) => r.data);
  },

  /** Downloads the current-version binary as a Blob. */
  downloadDocument: (projectId: string, documentId: string) =>
    apiClient
      .get<Blob>(
        `/v1/projects/${projectId}/documents/${documentId}/download`,
        { responseType: "blob", params: { disposition: "attachment" } }
      )
      .then((r) => r.data),

  /** Downloads a specific historical version binary as a Blob. */
  downloadVersion: (
    projectId: string,
    documentId: string,
    versionNumber: number
  ) =>
    apiClient
      .get<Blob>(
        `/v1/projects/${projectId}/documents/${documentId}/versions/${versionNumber}/download`,
        { responseType: "blob", params: { disposition: "attachment" } }
      )
      .then((r) => r.data),

  /** Multipart upload of a new version of an existing document. */
  uploadVersion: (
    projectId: string,
    documentId: string,
    file: File,
    changeDescription?: string
  ) => {
    const form = new FormData();
    form.append("file", file);
    if (changeDescription) {
      form.append("changeDescription", changeDescription);
    }
    return apiClient
      .post<ApiResponse<DocumentVersion>>(
        `/v1/projects/${projectId}/documents/${documentId}/versions/upload`,
        form,
        { headers: { "Content-Type": "multipart/form-data" } }
      )
      .then((r) => r.data);
  },

  updateDocument: (projectId: string, documentId: string, data: UpdateDocumentRequest) =>
    apiClient
      .put<ApiResponse<Document>>(
        `/v1/projects/${projectId}/documents/${documentId}`,
        data
      )
      .then((r) => r.data),

  deleteDocument: (projectId: string, documentId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/documents/${documentId}`),

  getDocumentVersions: (projectId: string, documentId: string) =>
    apiClient
      .get<ApiResponse<DocumentVersion[]>>(
        `/v1/projects/${projectId}/documents/${documentId}/versions`
      )
      .then((r) => r.data),

  addDocumentVersion: (projectId: string, documentId: string, data: AddDocumentVersionRequest) =>
    apiClient
      .post<ApiResponse<DocumentVersion>>(
        `/v1/projects/${projectId}/documents/${documentId}/versions`,
        data
      )
      .then((r) => r.data),

  // Drawings
  listDrawings: (projectId: string) =>
    apiClient
      .get<ApiResponse<DrawingRegister[]>>(
        `/v1/projects/${projectId}/drawings`
      )
      .then((r) => r.data),

  getDrawing: (projectId: string, drawingId: string) =>
    apiClient
      .get<ApiResponse<DrawingRegister>>(
        `/v1/projects/${projectId}/drawings/${drawingId}`
      )
      .then((r) => r.data),

  createDrawing: (projectId: string, data: CreateDrawingRequest) =>
    apiClient
      .post<ApiResponse<DrawingRegister>>(
        `/v1/projects/${projectId}/drawings`,
        data
      )
      .then((r) => r.data),

  updateDrawing: (projectId: string, drawingId: string, data: UpdateDrawingRequest) =>
    apiClient
      .put<ApiResponse<DrawingRegister>>(
        `/v1/projects/${projectId}/drawings/${drawingId}`,
        data
      )
      .then((r) => r.data),

  deleteDrawing: (projectId: string, drawingId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/drawings/${drawingId}`),

  // Transmittals
  listTransmittals: (projectId: string) =>
    apiClient
      .get<ApiResponse<Transmittal[]>>(
        `/v1/projects/${projectId}/transmittals`
      )
      .then((r) => r.data),

  getTransmittal: (projectId: string, transmittalId: string) =>
    apiClient
      .get<ApiResponse<Transmittal>>(
        `/v1/projects/${projectId}/transmittals/${transmittalId}`
      )
      .then((r) => r.data),

  createTransmittal: (projectId: string, data: CreateTransmittalRequest) =>
    apiClient
      .post<ApiResponse<Transmittal>>(
        `/v1/projects/${projectId}/transmittals`,
        data
      )
      .then((r) => r.data),

  updateTransmittal: (projectId: string, transmittalId: string, data: UpdateTransmittalRequest) =>
    apiClient
      .put<ApiResponse<Transmittal>>(
        `/v1/projects/${projectId}/transmittals/${transmittalId}`,
        data
      )
      .then((r) => r.data),

  deleteTransmittal: (projectId: string, transmittalId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/transmittals/${transmittalId}`),

  // RFIs
  listRfis: (projectId: string) =>
    apiClient
      .get<ApiResponse<RfiRegister[]>>(`/v1/projects/${projectId}/rfis`)
      .then((r) => r.data),

  getRfi: (projectId: string, rfiId: string) =>
    apiClient
      .get<ApiResponse<RfiRegister>>(`/v1/projects/${projectId}/rfis/${rfiId}`)
      .then((r) => r.data),

  createRfi: (projectId: string, data: CreateRfiRequest) =>
    apiClient
      .post<ApiResponse<RfiRegister>>(`/v1/projects/${projectId}/rfis`, data)
      .then((r) => r.data),

  updateRfi: (projectId: string, rfiId: string, data: UpdateRfiRequest) =>
    apiClient
      .put<ApiResponse<RfiRegister>>(
        `/v1/projects/${projectId}/rfis/${rfiId}`,
        data
      )
      .then((r) => r.data),

  deleteRfi: (projectId: string, rfiId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/rfis/${rfiId}`),
};

"use client";

import { Suspense, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { contractApi } from "@/lib/api/contractApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { ContractForm } from "@/components/contracts/ContractForm";
import { AttachmentList } from "@/components/contracts/AttachmentList";
import { AttachmentUploadForm } from "@/components/contracts/AttachmentUploadForm";
import type {
  AttachmentEntityType,
  ContractAttachment,
  ContractMilestoneResponse,
  ContractorScorecardResponse,
  CreateContractMilestoneRequest,
  CreateContractorScorecardRequest,
  CreatePerformanceBondRequest,
  CreateVariationOrderRequest,
  CreateContractRequest,
  PerformanceBondResponse,
  UploadContractAttachmentMetadata,
  VariationOrderResponse,
} from "@/lib/types";

/** Triggers a save-as for a Blob using a temporary anchor. Mirrors the documents page pattern. */
function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export default function ContractDetailPage() {
  return (
    <Suspense fallback={<div className="p-6 text-center text-text-muted">Loading…</div>}>
      <ContractDetailPageInner />
    </Suspense>
  );
}

function ContractDetailPageInner() {
  const params = useParams();
  const searchParams = useSearchParams();
  const projectId = params.projectId as string;
  const contractId = params.contractId as string;
  const activeTab = searchParams.get("tab") || "milestones";
  const queryClient = useQueryClient();
  const { moneyCompact } = useProjectCurrency();

  const [isEditing, setIsEditing] = useState(false);

  const { data: contractData, isLoading } = useQuery({
    queryKey: ["contract", projectId, contractId],
    queryFn: () => contractApi.getContract(projectId, contractId),
    select: (response) => response.data,
  });

  const { data: budgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    staleTime: 5 * 60 * 1000,
  });
  const projectCurrency = budgetData?.data?.budgetCurrency ?? "INR";

  const { data: milestones = [] } = useQuery({
    queryKey: ["contract-milestones", contractId],
    queryFn: () => contractApi.listContractMilestones(contractId),
    select: (response) => response.data || [],
  });

  const { data: variationOrders = [] } = useQuery({
    queryKey: ["variation-orders", contractId],
    queryFn: () => contractApi.listVariationOrders(contractId),
    select: (response) => response.data || [],
  });

  const { data: bonds = [] } = useQuery({
    queryKey: ["performance-bonds", contractId],
    queryFn: () => contractApi.listPerformanceBonds(contractId),
    select: (response) => response.data || [],
  });

  const { data: scorecards = [] } = useQuery({
    queryKey: ["contractor-scorecards", contractId],
    queryFn: () => contractApi.listContractorScorecards(contractId),
    select: (response) => response.data || [],
  });

  const { data: allAttachments = [] } = useQuery({
    queryKey: ["contract-attachments", projectId, contractId],
    queryFn: () => contractApi.listContractAttachments(projectId, contractId),
    select: (response) => response.data || [],
  });

  const updateContractMutation = useMutation({
    mutationFn: (data: CreateContractRequest) =>
      contractApi.updateContract(projectId, contractId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contract", projectId, contractId] });
      queryClient.invalidateQueries({ queryKey: ["contracts", projectId] });
      setIsEditing(false);
    },
  });

  const createMilestoneMutation = useMutation({
    mutationFn: (data: CreateContractMilestoneRequest) =>
      contractApi.createContractMilestone(contractId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contract-milestones", contractId] });
    },
  });

  const createVoMutation = useMutation({
    mutationFn: (data: CreateVariationOrderRequest) =>
      contractApi.createVariationOrder(contractId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["variation-orders", contractId] });
    },
  });

  const createBondMutation = useMutation({
    mutationFn: (data: CreatePerformanceBondRequest) =>
      contractApi.createPerformanceBond(contractId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["performance-bonds", contractId] });
    },
  });

  const createScorecardMutation = useMutation({
    mutationFn: (data: CreateContractorScorecardRequest) =>
      contractApi.createContractorScorecard(contractId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contractor-scorecards", contractId] });
    },
  });

  const handleAttachmentDownload = async (a: ContractAttachment) => {
    const blob = await contractApi.downloadContractAttachment(projectId, contractId, a.id);
    saveBlob(blob, a.fileName);
  };

  const deleteAttachmentMutation = useMutation({
    mutationFn: (a: ContractAttachment) =>
      contractApi.deleteContractAttachment(projectId, contractId, a.id),
    onSuccess: (_d, a) => {
      // invalidate everything that surfaces attachments or counts
      queryClient.invalidateQueries({ queryKey: ["contract-attachments", projectId, contractId] });
      if (a.entityType === "MILESTONE") {
        queryClient.invalidateQueries({ queryKey: ["contract-milestones", contractId] });
        queryClient.invalidateQueries({ queryKey: ["milestone-attachments", a.entityId] });
      } else if (a.entityType === "VARIATION_ORDER") {
        queryClient.invalidateQueries({ queryKey: ["variation-orders", contractId] });
        queryClient.invalidateQueries({ queryKey: ["vo-attachments", a.entityId] });
      } else if (a.entityType === "PERFORMANCE_BOND") {
        queryClient.invalidateQueries({ queryKey: ["performance-bonds", contractId] });
        queryClient.invalidateQueries({ queryKey: ["bond-attachments", a.entityId] });
      }
    },
  });

  const handleAttachmentDelete = (a: ContractAttachment) => {
    if (!window.confirm(`Delete ${a.fileName}?`)) return;
    deleteAttachmentMutation.mutate(a);
  };

  if (isLoading) {
    return <div className="p-6 text-center text-text-muted">Loading contract...</div>;
  }

  if (!contractData) {
    return <div className="p-6 text-center text-danger">Contract not found</div>;
  }

  const tabs = [
    { id: "milestones", label: "Milestones" },
    { id: "variation-orders", label: "Variation Orders" },
    { id: "bonds", label: "Performance Bonds" },
    { id: "scorecard", label: "Scorecard" },
    { id: "attachments", label: `Attachments${allAttachments.length ? ` (${allAttachments.length})` : ""}` },
  ];

  return (
    <div className="space-y-6">
      <div>
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">{contractData.contractNumber}</h1>
            <p className="text-sm text-text-secondary mt-1">
              Contractor: {contractData.contractorName}
            </p>
            {contractData.description ? (
              <p className="text-sm text-text-secondary mt-1 max-w-3xl">{contractData.description}</p>
            ) : null}
          </div>
          <button
            onClick={() => setIsEditing(!isEditing)}
            className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors text-sm"
          >
            {isEditing ? "Cancel" : "Edit"}
          </button>
        </div>

        <div className="mt-4 grid grid-cols-4 gap-4">
          <div className="bg-surface/80 rounded-xl p-3 border border-border">
            <p className="text-xs text-text-secondary">Contract Value</p>
            <p className="text-lg font-semibold text-text-primary">
              {moneyCompact(contractData.contractValue)}
            </p>
            {contractData.revisedValue != null &&
            contractData.revisedValue !== contractData.contractValue ? (
              <p className="text-xs text-text-secondary mt-0.5">
                Revised: {moneyCompact(contractData.revisedValue)}
              </p>
            ) : null}
          </div>
          <div className="bg-surface/80 rounded-xl p-3 border border-border">
            <p className="text-xs text-text-secondary">Status</p>
            <p className="text-lg font-semibold text-text-primary">{contractData.status}</p>
          </div>
          <div className="bg-surface/80 rounded-xl p-3 border border-border">
            <p className="text-xs text-text-secondary">Type</p>
            <p className="text-lg font-semibold text-text-primary">
              {contractData.contractType.replace(/_/g, " ")}
            </p>
          </div>
          <div className="bg-surface/80 rounded-xl p-3 border border-border">
            <p className="text-xs text-text-secondary">DLP</p>
            <p className="text-lg font-semibold text-text-primary">{contractData.dlpMonths} months</p>
            {contractData.revisedCompletionDate ? (
              <p className="text-xs text-text-secondary mt-0.5">
                Revised completion {new Date(contractData.revisedCompletionDate).toLocaleDateString()}
              </p>
            ) : null}
          </div>
        </div>
      </div>

      {isEditing ? (
        <div className="bg-surface/50 border border-border rounded-xl p-6 shadow-xl">
          <h3 className="text-lg font-medium text-text-primary mb-4">Edit Contract</h3>
          <ContractForm
            projectId={projectId}
            initialValue={contractData}
            isPending={updateContractMutation.isPending}
            submitLabel="Save changes"
            onSubmit={(data) => updateContractMutation.mutate(data)}
            onCancel={() => setIsEditing(false)}
            defaultCurrency={projectCurrency}
          />
          {updateContractMutation.isError ? (
            <p className="text-sm text-danger mt-3">
              {(updateContractMutation.error as Error)?.message ?? "Failed to update contract"}
            </p>
          ) : null}
        </div>
      ) : null}

      <div className="border-b border-border">
        <nav className="flex gap-8" aria-label="Tabs">
          {tabs.map((t) => (
            <a
              key={t.id}
              href={`?tab=${t.id}`}
              className={`px-1 py-4 text-sm font-medium border-b-2 transition-colors ${
                activeTab === t.id
                  ? "border-accent text-accent"
                  : "border-transparent text-text-secondary hover:text-text-primary hover:border-border"
              }`}
            >
              {t.label}
            </a>
          ))}
        </nav>
      </div>

      <div>
        {activeTab === "milestones" && (
          <MilestonesTab
            projectId={projectId}
            contractId={contractId}
            milestones={milestones}
            isCreating={createMilestoneMutation.isPending}
            onCreate={(data) => createMilestoneMutation.mutate(data)}
            onAttachmentDownload={handleAttachmentDownload}
            onAttachmentDelete={handleAttachmentDelete}
          />
        )}

        {activeTab === "variation-orders" && (
          <VariationOrdersTab
            projectId={projectId}
            contractId={contractId}
            variationOrders={variationOrders}
            isCreating={createVoMutation.isPending}
            onCreate={(data) => createVoMutation.mutate(data)}
            onAttachmentDownload={handleAttachmentDownload}
            onAttachmentDelete={handleAttachmentDelete}
          />
        )}

        {activeTab === "bonds" && (
          <PerformanceBondsTab
            projectId={projectId}
            contractId={contractId}
            bonds={bonds}
            isCreating={createBondMutation.isPending}
            onCreate={(data) => createBondMutation.mutate(data)}
            onAttachmentDownload={handleAttachmentDownload}
            onAttachmentDelete={handleAttachmentDelete}
          />
        )}

        {activeTab === "scorecard" && (
          <ScorecardTab
            contractId={contractId}
            scorecards={scorecards}
            isCreating={createScorecardMutation.isPending}
            onCreate={(data) => createScorecardMutation.mutate(data)}
          />
        )}

        {activeTab === "attachments" && (
          <AttachmentsTab
            projectId={projectId}
            contractId={contractId}
            attachments={allAttachments}
            onDownload={handleAttachmentDownload}
            onDelete={handleAttachmentDelete}
          />
        )}
      </div>
    </div>
  );
}

// ────────────────────────────────────────────── Attachments tab (contract-level)

interface AttachmentsTabProps {
  projectId: string;
  contractId: string;
  attachments: ContractAttachment[];
  onDownload: (a: ContractAttachment) => void;
  onDelete: (a: ContractAttachment) => void;
}

function AttachmentsTab({
  projectId,
  contractId,
  attachments,
  onDownload,
  onDelete,
}: AttachmentsTabProps) {
  const [showUpload, setShowUpload] = useState(false);
  const queryClient = useQueryClient();

  const uploadMutation = useMutation({
    mutationFn: ({
      metadata,
      file,
    }: {
      metadata: UploadContractAttachmentMetadata;
      file: File;
    }) => contractApi.uploadContractAttachment(projectId, contractId, metadata, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contract-attachments", projectId, contractId] });
      setShowUpload(false);
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-text-secondary">
          Upload contract-level documents (LOA, agreement, BOQ, drawings, etc.). Sub-entity
          attachments live under their respective tabs.
        </p>
        <button
          onClick={() => setShowUpload((s) => !s)}
          className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors"
        >
          {showUpload ? "Cancel" : "Upload"}
        </button>
      </div>

      {showUpload ? (
        <AttachmentUploadForm
          isPending={uploadMutation.isPending}
          onSubmit={(metadata, file) => uploadMutation.mutate({ metadata, file })}
          onCancel={() => setShowUpload(false)}
        />
      ) : null}

      {uploadMutation.isError ? (
        <p className="text-sm text-danger">
          {(uploadMutation.error as Error)?.message ?? "Upload failed"}
        </p>
      ) : null}

      <AttachmentList
        attachments={attachments}
        onDownload={onDownload}
        onDelete={onDelete}
        groupBy="entityType"
        emptyText="No attachments yet — upload a file to get started."
      />
    </div>
  );
}

// ────────────────────────────────────────────── Per-row attachment expand panel

interface RowAttachmentsProps {
  projectId: string;
  contractId: string;
  entityType: AttachmentEntityType;
  entityId: string;
  onDownload: (a: ContractAttachment) => void;
  onDelete: (a: ContractAttachment) => void;
}

function RowAttachmentsPanel({
  projectId,
  contractId,
  entityType,
  entityId,
  onDownload,
  onDelete,
}: RowAttachmentsProps) {
  const queryClient = useQueryClient();
  const queryKey =
    entityType === "MILESTONE"
      ? ["milestone-attachments", entityId]
      : entityType === "VARIATION_ORDER"
        ? ["vo-attachments", entityId]
        : ["bond-attachments", entityId];

  const { data: attachments = [] } = useQuery({
    queryKey,
    queryFn: () => {
      if (entityType === "MILESTONE")
        return contractApi.listMilestoneAttachments(projectId, contractId, entityId);
      if (entityType === "VARIATION_ORDER")
        return contractApi.listVariationOrderAttachments(projectId, contractId, entityId);
      return contractApi.listPerformanceBondAttachments(projectId, contractId, entityId);
    },
    select: (r) => r.data || [],
  });

  const uploadMutation = useMutation({
    mutationFn: ({
      metadata,
      file,
    }: {
      metadata: UploadContractAttachmentMetadata;
      file: File;
    }) => {
      if (entityType === "MILESTONE")
        return contractApi.uploadMilestoneAttachment(
          projectId,
          contractId,
          entityId,
          metadata,
          file,
        );
      if (entityType === "VARIATION_ORDER")
        return contractApi.uploadVariationOrderAttachment(
          projectId,
          contractId,
          entityId,
          metadata,
          file,
        );
      return contractApi.uploadPerformanceBondAttachment(
        projectId,
        contractId,
        entityId,
        metadata,
        file,
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey });
      queryClient.invalidateQueries({ queryKey: ["contract-attachments", projectId, contractId] });
      // refresh badge counts on the parent list
      if (entityType === "MILESTONE") {
        queryClient.invalidateQueries({ queryKey: ["contract-milestones", contractId] });
      } else if (entityType === "VARIATION_ORDER") {
        queryClient.invalidateQueries({ queryKey: ["variation-orders", contractId] });
      } else {
        queryClient.invalidateQueries({ queryKey: ["performance-bonds", contractId] });
      }
    },
  });

  return (
    <div className="mt-3 pt-3 border-t border-border/60 space-y-3">
      <AttachmentUploadForm
        isPending={uploadMutation.isPending}
        onSubmit={(metadata, file) => uploadMutation.mutate({ metadata, file })}
      />
      {uploadMutation.isError ? (
        <p className="text-sm text-danger">
          {(uploadMutation.error as Error)?.message ?? "Upload failed"}
        </p>
      ) : null}
      <AttachmentList
        attachments={attachments}
        onDownload={onDownload}
        onDelete={onDelete}
        emptyText="No attachments for this row yet."
      />
    </div>
  );
}

// ────────────────────────────────────────────── Milestones

interface MilestonesTabProps {
  projectId: string;
  contractId: string;
  milestones: ContractMilestoneResponse[];
  isCreating: boolean;
  onCreate: (data: CreateContractMilestoneRequest) => void;
  onAttachmentDownload: (a: ContractAttachment) => void;
  onAttachmentDelete: (a: ContractAttachment) => void;
}

function MilestonesTab({
  projectId,
  contractId,
  milestones,
  isCreating,
  onCreate,
  onAttachmentDownload,
  onAttachmentDelete,
}: MilestonesTabProps) {
  const [showForm, setShowForm] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    onCreate({
      contractId,
      milestoneCode: formData.get("milestoneCode") as string,
      milestoneName: formData.get("milestoneName") as string,
      targetDate: formData.get("targetDate") as string,
      actualDate: (formData.get("actualDate") as string) || undefined,
      paymentPercentage: parseFloat(formData.get("paymentPercentage") as string),
      amount: parseFloat(formData.get("amount") as string),
    });
    setShowForm(false);
  };

  return (
    <div className="space-y-4">
      <button
        onClick={() => setShowForm(!showForm)}
        className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors"
      >
        {showForm ? "Cancel" : "Add Milestone"}
      </button>

      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="bg-surface/50 border border-border rounded-xl p-4 space-y-3 shadow-xl"
        >
          <div className="grid grid-cols-2 gap-3">
            <input type="text" name="milestoneCode" placeholder="Code" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="text" name="milestoneName" placeholder="Name" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="date" name="targetDate" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary" />
            <input type="date" name="actualDate" placeholder="Actual Date (optional)" className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="paymentPercentage" placeholder="Payment %" step="0.01" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="amount" placeholder="Amount" step="0.01" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
          </div>
          <button
            type="submit"
            disabled={isCreating}
            className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:bg-border"
          >
            Create
          </button>
        </form>
      )}

      <div className="space-y-2">
        {milestones.length === 0 ? (
          <p className="text-text-muted text-center py-4">No milestones</p>
        ) : (
          milestones.map((m) => (
            <div key={m.id} className="bg-surface/50 border border-border rounded-xl p-4 shadow-xl">
              <div className="flex justify-between items-start gap-3">
                <div className="flex-1">
                  <p className="font-semibold text-text-primary">{m.milestoneName}</p>
                  <p className="text-sm text-text-secondary">
                    Target: {new Date(m.targetDate).toLocaleDateString()}
                  </p>
                  <p className="text-sm text-text-secondary">
                    Payment: {m.paymentPercentage}% | Amount: {m.amount.toLocaleString()}
                  </p>
                  <span className="inline-block mt-2 px-2 py-1 text-xs font-semibold rounded-full bg-surface-active/50 text-text-secondary ring-1 ring-border/50">
                    {m.status}
                  </span>
                </div>
                <button
                  onClick={() => setExpandedId(expandedId === m.id ? null : m.id)}
                  className="px-3 py-2 text-sm font-medium text-accent hover:text-blue-300 whitespace-nowrap"
                >
                  📎 {m.attachmentCount ?? 0}
                </button>
              </div>
              {expandedId === m.id ? (
                <RowAttachmentsPanel
                  projectId={projectId}
                  contractId={contractId}
                  entityType="MILESTONE"
                  entityId={m.id}
                  onDownload={onAttachmentDownload}
                  onDelete={onAttachmentDelete}
                />
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

// ────────────────────────────────────────────── Variation Orders

interface VariationOrdersTabProps {
  projectId: string;
  contractId: string;
  variationOrders: VariationOrderResponse[];
  isCreating: boolean;
  onCreate: (data: CreateVariationOrderRequest) => void;
  onAttachmentDownload: (a: ContractAttachment) => void;
  onAttachmentDelete: (a: ContractAttachment) => void;
}

function VariationOrdersTab({
  projectId,
  contractId,
  variationOrders,
  isCreating,
  onCreate,
  onAttachmentDownload,
  onAttachmentDelete,
}: VariationOrdersTabProps) {
  const [showForm, setShowForm] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    onCreate({
      contractId,
      voNumber: formData.get("voNumber") as string,
      description: formData.get("description") as string,
      voValue: parseFloat(formData.get("voValue") as string),
      justification: formData.get("justification") as string,
      impactOnBudget: parseFloat(formData.get("impactOnBudget") as string),
      impactOnScheduleDays: parseInt(formData.get("impactOnScheduleDays") as string),
      approvedBy: (formData.get("approvedBy") as string) || undefined,
    });
    setShowForm(false);
  };

  return (
    <div className="space-y-4">
      <button
        onClick={() => setShowForm(!showForm)}
        className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors"
      >
        {showForm ? "Cancel" : "Add Variation Order"}
      </button>

      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="bg-surface/50 border border-border rounded-xl p-4 space-y-3 shadow-xl"
        >
          <div className="grid grid-cols-2 gap-3">
            <input type="text" name="voNumber" placeholder="VO Number" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="voValue" placeholder="Value" step="0.01" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <textarea name="description" placeholder="Description" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg col-span-2 text-text-primary placeholder-text-muted"></textarea>
            <textarea name="justification" placeholder="Justification" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg col-span-2 text-text-primary placeholder-text-muted"></textarea>
            <input type="number" name="impactOnBudget" placeholder="Budget Impact" step="0.01" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="impactOnScheduleDays" placeholder="Schedule Impact (days)" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="text" name="approvedBy" placeholder="Approved By (optional)" className="px-3 py-2 bg-surface-hover border border-border rounded-lg col-span-2 text-text-primary placeholder-text-muted" />
          </div>
          <button
            type="submit"
            disabled={isCreating}
            className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:bg-border"
          >
            Create
          </button>
        </form>
      )}

      <div className="space-y-2">
        {variationOrders.length === 0 ? (
          <p className="text-text-muted text-center py-4">No variation orders</p>
        ) : (
          variationOrders.map((vo) => (
            <div key={vo.id} className="bg-surface/50 border border-border rounded-xl p-4 shadow-xl">
              <div className="flex justify-between items-start gap-3">
                <div className="flex-1">
                  <p className="font-semibold text-text-primary">{vo.voNumber}</p>
                  <p className="text-sm text-text-secondary">{vo.description}</p>
                  <p className="text-sm text-text-secondary">
                    Value: {vo.voValue.toLocaleString()} | Budget Impact:{" "}
                    {vo.impactOnBudget.toLocaleString()}
                  </p>
                  <p className="text-sm text-text-secondary">
                    Schedule Impact: {vo.impactOnScheduleDays} days
                  </p>
                  <span className="inline-block mt-2 px-2 py-1 text-xs font-semibold rounded-full bg-surface-active/50 text-text-secondary ring-1 ring-border/50">
                    {vo.status}
                  </span>
                </div>
                <button
                  onClick={() => setExpandedId(expandedId === vo.id ? null : vo.id)}
                  className="px-3 py-2 text-sm font-medium text-accent hover:text-blue-300 whitespace-nowrap"
                >
                  📎 {vo.attachmentCount ?? 0}
                </button>
              </div>
              {expandedId === vo.id ? (
                <RowAttachmentsPanel
                  projectId={projectId}
                  contractId={contractId}
                  entityType="VARIATION_ORDER"
                  entityId={vo.id}
                  onDownload={onAttachmentDownload}
                  onDelete={onAttachmentDelete}
                />
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

// ────────────────────────────────────────────── Performance Bonds

interface PerformanceBondsTabProps {
  projectId: string;
  contractId: string;
  bonds: PerformanceBondResponse[];
  isCreating: boolean;
  onCreate: (data: CreatePerformanceBondRequest) => void;
  onAttachmentDownload: (a: ContractAttachment) => void;
  onAttachmentDelete: (a: ContractAttachment) => void;
}

function PerformanceBondsTab({
  projectId,
  contractId,
  bonds,
  isCreating,
  onCreate,
  onAttachmentDownload,
  onAttachmentDelete,
}: PerformanceBondsTabProps) {
  const [showForm, setShowForm] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    onCreate({
      contractId,
      bondType: formData.get("bondType") as PerformanceBondResponse["bondType"],
      bondValue: parseFloat(formData.get("bondValue") as string),
      bankName: formData.get("bankName") as string,
      issueDate: formData.get("issueDate") as string,
      expiryDate: formData.get("expiryDate") as string,
    });
    setShowForm(false);
  };

  return (
    <div className="space-y-4">
      <button
        onClick={() => setShowForm(!showForm)}
        className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors"
      >
        {showForm ? "Cancel" : "Add Bond"}
      </button>

      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="bg-surface/50 border border-border rounded-xl p-4 space-y-3 shadow-xl"
        >
          <div className="grid grid-cols-2 gap-3">
            <select name="bondType" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary">
              <option value="">Select Type</option>
              <option value="PERFORMANCE_GUARANTEE">Performance Guarantee</option>
              <option value="EMD">EMD</option>
              <option value="ADVANCE_GUARANTEE">Advance Guarantee</option>
              <option value="RETENTION">Retention</option>
            </select>
            <input type="number" name="bondValue" placeholder="Value" step="0.01" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="text" name="bankName" placeholder="Bank Name" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="date" name="issueDate" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary" />
            <input type="date" name="expiryDate" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary" />
          </div>
          <button
            type="submit"
            disabled={isCreating}
            className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:bg-border"
          >
            Create
          </button>
        </form>
      )}

      <div className="space-y-2">
        {bonds.length === 0 ? (
          <p className="text-text-muted text-center py-4">No bonds</p>
        ) : (
          bonds.map((bond) => (
            <div key={bond.id} className="bg-surface/50 border border-border rounded-xl p-4 shadow-xl">
              <div className="flex justify-between items-start gap-3">
                <div className="flex-1">
                  <p className="font-semibold text-text-primary">{bond.bondType}</p>
                  <p className="text-sm text-text-secondary">Bank: {bond.bankName}</p>
                  <p className="text-sm text-text-secondary">
                    Value: {bond.bondValue.toLocaleString()} | Valid:{" "}
                    {new Date(bond.issueDate).toLocaleDateString()} -{" "}
                    {new Date(bond.expiryDate).toLocaleDateString()}
                  </p>
                  <span className="inline-block mt-2 px-2 py-1 text-xs font-semibold rounded-full bg-surface-active/50 text-text-secondary ring-1 ring-border/50">
                    {bond.status}
                  </span>
                </div>
                <button
                  onClick={() => setExpandedId(expandedId === bond.id ? null : bond.id)}
                  className="px-3 py-2 text-sm font-medium text-accent hover:text-blue-300 whitespace-nowrap"
                >
                  📎 {bond.attachmentCount ?? 0}
                </button>
              </div>
              {expandedId === bond.id ? (
                <RowAttachmentsPanel
                  projectId={projectId}
                  contractId={contractId}
                  entityType="PERFORMANCE_BOND"
                  entityId={bond.id}
                  onDownload={onAttachmentDownload}
                  onDelete={onAttachmentDelete}
                />
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

// ────────────────────────────────────────────── Scorecard (unchanged behaviour)

interface ScorecardTabProps {
  contractId: string;
  scorecards: ContractorScorecardResponse[];
  isCreating: boolean;
  onCreate: (data: CreateContractorScorecardRequest) => void;
}

function ScorecardTab({ contractId, scorecards, isCreating, onCreate }: ScorecardTabProps) {
  const [showForm, setShowForm] = useState(false);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    onCreate({
      contractId,
      period: formData.get("period") as string,
      qualityScore: parseFloat(formData.get("qualityScore") as string),
      safetyScore: parseFloat(formData.get("safetyScore") as string),
      progressScore: parseFloat(formData.get("progressScore") as string),
      paymentComplianceScore: parseFloat(formData.get("paymentComplianceScore") as string),
      overallScore: parseFloat(formData.get("overallScore") as string),
      remarks: (formData.get("remarks") as string) || undefined,
    });
    setShowForm(false);
  };

  return (
    <div className="space-y-4">
      <button
        onClick={() => setShowForm(!showForm)}
        className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors"
      >
        {showForm ? "Cancel" : "Add Scorecard"}
      </button>

      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="bg-surface/50 border border-border rounded-xl p-4 space-y-3 shadow-xl"
        >
          <div className="grid grid-cols-2 gap-3">
            <input type="text" name="period" placeholder="Period (e.g., Q1-2024)" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="qualityScore" placeholder="Quality Score" step="0.1" min="0" max="10" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="safetyScore" placeholder="Safety Score" step="0.1" min="0" max="10" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="progressScore" placeholder="Progress Score" step="0.1" min="0" max="10" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="paymentComplianceScore" placeholder="Payment Compliance Score" step="0.1" min="0" max="10" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <input type="number" name="overallScore" placeholder="Overall Score" step="0.1" min="0" max="10" required className="px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted" />
            <textarea name="remarks" placeholder="Remarks (optional)" className="px-3 py-2 bg-surface-hover border border-border rounded-lg col-span-2 text-text-primary placeholder-text-muted"></textarea>
          </div>
          <button
            type="submit"
            disabled={isCreating}
            className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:bg-border"
          >
            Create
          </button>
        </form>
      )}

      <div className="space-y-2">
        {scorecards.length === 0 ? (
          <p className="text-text-muted text-center py-4">No scorecards</p>
        ) : (
          scorecards.map((sc) => (
            <div key={sc.id} className="bg-surface/50 border border-border rounded-xl p-4 shadow-xl">
              <div className="flex justify-between items-start">
                <div className="flex-1">
                  <p className="font-semibold text-text-primary">{sc.period}</p>
                  <div className="mt-2 grid grid-cols-5 gap-2 text-sm">
                    <div>
                      <p className="text-text-secondary">Quality</p>
                      <p className="font-semibold text-text-primary">{sc.qualityScore}</p>
                    </div>
                    <div>
                      <p className="text-text-secondary">Safety</p>
                      <p className="font-semibold text-text-primary">{sc.safetyScore}</p>
                    </div>
                    <div>
                      <p className="text-text-secondary">Progress</p>
                      <p className="font-semibold text-text-primary">{sc.progressScore}</p>
                    </div>
                    <div>
                      <p className="text-text-secondary">Compliance</p>
                      <p className="font-semibold text-text-primary">{sc.paymentComplianceScore}</p>
                    </div>
                    <div>
                      <p className="text-text-secondary">Overall</p>
                      <p className="font-semibold text-text-primary">{sc.overallScore}</p>
                    </div>
                  </div>
                  {sc.remarks && <p className="text-sm text-text-secondary mt-2">{sc.remarks}</p>}
                </div>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

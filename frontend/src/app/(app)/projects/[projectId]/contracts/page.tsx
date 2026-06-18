"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { contractApi } from "@/lib/api/contractApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { TabTip } from "@/components/common/TabTip";
import { ContractForm } from "@/components/contracts/ContractForm";
// import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";
import type { ContractResponse, CreateContractRequest } from "@/lib/types";

export default function ContractsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const { moneyCompact } = useProjectCurrency();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const queryClient = useQueryClient();

  const { data: contractsData, isLoading } = useQuery({
    queryKey: ["contracts", projectId, page],
    queryFn: () => contractApi.listContracts(projectId, page, size),
    select: (response) =>
      response.data || {
        content: [],
        pagination: { totalElements: 0, totalPages: 0, currentPage: 0, pageSize: 0 },
      },
  });

  const { data: budgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    staleTime: 5 * 60 * 1000,
  });
  const projectCurrency = budgetData?.data?.budgetCurrency ?? "INR";

  const createMutation = useMutation({
    mutationFn: (data: CreateContractRequest) => contractApi.createContract(projectId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contracts", projectId] });
      setShowCreateForm(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => contractApi.deleteContract(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contracts", projectId] });
    },
  });

  if (isLoading) {
    return <div className="p-6 text-center text-text-muted">Loading contracts...</div>;
  }

  const contracts = contractsData?.content || [];
  const pagination = contractsData?.pagination;
  const totalPages = pagination?.totalPages || 0;

  return (
    <div className="space-y-6">
      {/* <AiInsightsPanel
        projectId={projectId}
        endpoint={`/v1/projects/${projectId}/contracts/ai/insights`}
      /> */}
      <TabTip
        title="Contracts & Procurement"
        description="Track contractor agreements, LOA values, milestones, and variation orders. Create contracts to link with project activities and costs."
      />
      <div className="flex justify-between items-center">
        <h2 className="text-xl font-semibold text-text-primary">Contracts</h2>
        <button
          onClick={() => setShowCreateForm(!showCreateForm)}
          className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors"
        >
          {showCreateForm ? "Cancel" : "Create Contract"}
        </button>
      </div>

      {showCreateForm && (
        <div className="bg-surface/50 border border-border rounded-xl p-6 shadow-xl">
          <h3 className="text-lg font-medium text-text-primary mb-4">New Contract</h3>
          <ContractForm
            projectId={projectId}
            isPending={createMutation.isPending}
            submitLabel="Create"
            onSubmit={(data) => createMutation.mutate(data)}
            onCancel={() => setShowCreateForm(false)}
            defaultCurrency={projectCurrency}
          />
          {createMutation.isError ? (
            <p className="text-sm text-danger mt-3">
              {(createMutation.error as Error)?.message ?? "Failed to create contract"}
            </p>
          ) : null}
        </div>
      )}

      <div className="grid gap-4">
        {contracts.length === 0 ? (
          <div className="text-center py-8 text-text-muted">No contracts found</div>
        ) : (
          contracts.map((contract: ContractResponse) => {
            const statusBadge =
              contract.status === "ACTIVE"
                ? "bg-success/10 text-success ring-1 ring-success/20"
                : contract.status === "COMPLETED"
                  ? "bg-accent/10 text-accent ring-1 ring-accent/20"
                  : contract.status === "ACTIVE_AT_RISK"
                    ? "bg-warning/10 text-warning ring-1 ring-amber-500/20"
                    : contract.status === "ACTIVE_DELAYED" || contract.status === "DELAYED"
                      ? "bg-danger/10 text-danger ring-1 ring-red-500/20"
                      : contract.status === "MOBILISATION"
                        ? "bg-indigo-500/10 text-indigo-400 ring-1 ring-indigo-500/20"
                        : "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
            const spiBadge = (v: number | null | undefined) =>
              v == null
                ? ""
                : v >= 0.95
                  ? "bg-success/10 text-success ring-1 ring-success/20"
                  : v >= 0.85
                    ? "bg-warning/10 text-warning ring-1 ring-amber-500/20"
                    : "bg-danger/10 text-danger ring-1 ring-red-500/20";
            const bgDays = contract.bgExpiry
              ? Math.round(
                  (new Date(contract.bgExpiry).getTime() - Date.now()) / (1000 * 60 * 60 * 24),
                )
              : null;
            const bgExpiryClass =
              bgDays == null
                ? "bg-surface-active/50 text-text-secondary ring-1 ring-border/50"
                : bgDays < 30
                  ? "bg-danger/10 text-danger ring-1 ring-red-500/20"
                  : bgDays < 90
                    ? "bg-warning/10 text-warning ring-1 ring-amber-500/20"
                    : "bg-success/10 text-success ring-1 ring-success/20";
            return (
              <div
                key={contract.id}
                className="bg-surface/50 border border-border rounded-xl p-4 hover:bg-surface/70 transition-colors shadow-xl"
              >
                <div className="flex justify-between items-start">
                  <div className="flex-1">
                    <h3 className="text-lg font-semibold text-text-primary">
                      {contract.contractNumber}
                    </h3>
                    {contract.packageDescription && (
                      <p className="text-sm text-text-secondary mt-0.5">
                        {contract.packageDescription}
                      </p>
                    )}
                    <p className="text-sm text-text-secondary mt-1">
                      <span className="font-medium">Contractor:</span> {contract.contractorName}
                      {contract.wbsPackageCode && (
                        <>
                          {" "}
                          · <span className="font-medium">WBS:</span> {contract.wbsPackageCode}
                        </>
                      )}
                    </p>
                    <p className="text-sm text-text-secondary">
                      <span className="font-medium">Value:</span> {moneyCompact(contract.contractValue)}
                      {contract.revisedValue != null && contract.revisedValue !== contract.contractValue && (
                        <>
                          {" "}
                          · <span className="font-medium">Revised:</span>{" "}
                          {moneyCompact(contract.revisedValue)}
                        </>
                      )}{" "}
                      | <span className="font-medium">Type:</span>{" "}
                      {contract.contractType.replace(/_/g, " ")}
                      {contract.currency && contract.currency !== "INR" && (
                        <>
                          {" "}
                          · <span className="font-medium">Currency:</span> {contract.currency}
                        </>
                      )}
                    </p>
                    <p className="text-sm text-text-secondary">
                      <span className="font-medium">Dates:</span>{" "}
                      {new Date(contract.loaDate).toLocaleDateString()} -{" "}
                      {new Date(contract.completionDate).toLocaleDateString()}
                    </p>
                    <div className="mt-2 flex flex-wrap gap-2 items-center">
                      <span
                        className={`inline-block px-2 py-1 text-xs font-semibold rounded-full ${statusBadge}`}
                      >
                        {contract.status}
                      </span>
                      {contract.spi != null && (
                        <span
                          className={`inline-block px-2 py-1 text-xs font-semibold rounded-full ${spiBadge(contract.spi)}`}
                        >
                          SPI {contract.spi.toFixed(2)}
                        </span>
                      )}
                      {contract.cpi != null && (
                        <span
                          className={`inline-block px-2 py-1 text-xs font-semibold rounded-full ${spiBadge(contract.cpi)}`}
                        >
                          CPI {contract.cpi.toFixed(2)}
                        </span>
                      )}
                      {contract.physicalProgressAi != null && (
                        <span className="inline-block px-2 py-1 text-xs font-semibold rounded-full bg-sky-500/10 text-sky-400 ring-1 ring-sky-500/20">
                          Progress {contract.physicalProgressAi.toFixed(1)}%
                        </span>
                      )}
                      {contract.voNumbersIssued != null && contract.voNumbersIssued > 0 && (
                        <span className="inline-block px-2 py-1 text-xs font-semibold rounded-full bg-surface-active/50 text-text-secondary ring-1 ring-border/50">
                          VOs {contract.voNumbersIssued}
                          {contract.voValueCrores != null && ` · ${contract.voValueCrores.toFixed(1)} M ${contract.currency ?? projectCurrency}`}
                        </span>
                      )}
                      {contract.bgExpiry && (
                        <span
                          className={`inline-block px-2 py-1 text-xs font-semibold rounded-full ${bgExpiryClass}`}
                        >
                          BG {new Date(contract.bgExpiry).toLocaleDateString()}
                          {bgDays != null && bgDays < 90 && ` (${bgDays}d)`}
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <a
                      href={`/projects/${projectId}/contracts/${contract.id}`}
                      className="px-3 py-2 text-sm font-medium text-accent hover:text-blue-300"
                    >
                      View
                    </a>
                    <button
                      onClick={() => deleteMutation.mutate(contract.id)}
                      disabled={deleteMutation.isPending}
                      className="px-3 py-2 text-sm font-medium text-danger hover:text-danger disabled:text-text-muted"
                    >
                      Delete
                    </button>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {totalPages > 1 && (
        <div className="flex justify-center gap-2 mt-6">
          <button
            onClick={() => setPage(Math.max(0, page - 1))}
            disabled={page === 0}
            className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg disabled:bg-surface-hover disabled:text-text-muted hover:bg-border transition-colors"
          >
            Previous
          </button>
          <span className="px-4 py-2 text-text-secondary">
            Page {page + 1} of {totalPages}
          </span>
          <button
            onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
            disabled={page >= totalPages - 1}
            className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg disabled:bg-surface-hover disabled:text-text-muted hover:bg-border transition-colors"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}

"use client";

import { useMemo, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { contractApi } from "@/lib/api/contractApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import type {
  ContractResponse,
  CreateVariationOrderRequest,
  VariationOrderResponse,
} from "@/lib/types";

interface CreateForm {
  contractId: string;
  voNumber: string;
  description: string;
  voValue: string;
  justification: string;
  impactOnBudget: string;
  impactOnScheduleDays: string;
}

const initialForm: CreateForm = {
  contractId: "",
  voNumber: "",
  description: "",
  voValue: "",
  justification: "",
  impactOnBudget: "",
  impactOnScheduleDays: "",
};

function statusBadgeClass(status: string): string {
  switch (status) {
    case "APPROVED":
      return "bg-success/20 text-success";
    case "REJECTED":
      return "bg-red-500/20 text-red-300";
    case "RECOMMENDED":
      return "bg-blue-500/20 text-blue-300";
    case "INITIATED":
    default:
      return "bg-slate-500/20 text-slate-300";
  }
}

export default function VariationOrdersListPage() {
  const params = useParams();
  const router = useRouter();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const { money, symbol } = useProjectCurrency();

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<CreateForm>(initialForm);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const { data: contractsData, isLoading: contractsLoading } = useQuery({
    queryKey: ["contracts", projectId],
    queryFn: () => contractApi.listContracts(projectId, 0, 200),
    enabled: !!projectId,
  });
  const contracts: ContractResponse[] = contractsData?.data?.content ?? [];

  // Fetch VOs for each contract once and merge — reuses existing contract-scoped endpoints.
  const voQueries = useQuery({
    queryKey: ["variation-orders", projectId, contracts.map((c) => c.id).join(",")],
    queryFn: async () => {
      const all: Array<VariationOrderResponse & { contractLabel?: string }> = [];
      for (const c of contracts) {
        const res = await contractApi.listVariationOrders(c.id);
        for (const vo of res.data ?? []) {
          all.push({ ...vo, contractLabel: c.contractNumber });
        }
      }
      return all;
    },
    enabled: !contractsLoading && contracts.length > 0,
  });
  const variationOrders = voQueries.data ?? [];

  const contractOptions = useMemo(
    () =>
      contracts.map((c) => ({
        value: c.id,
        label: `${c.contractNumber} — ${c.contractorName ?? c.contractType ?? "Contract"}`,
      })),
    [contracts],
  );

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.contractId) {
      setError("Pick a contract for this VO.");
      return;
    }
    if (!form.voNumber.trim()) {
      setError("VO Number is required.");
      return;
    }

    const payload: CreateVariationOrderRequest = {
      contractId: form.contractId,
      voNumber: form.voNumber.trim(),
      description: form.description,
      voValue: form.voValue === "" ? 0 : Number(form.voValue),
      justification: form.justification,
      impactOnBudget: form.impactOnBudget === "" ? 0 : Number(form.impactOnBudget),
      impactOnScheduleDays:
        form.impactOnScheduleDays === "" ? 0 : Number(form.impactOnScheduleDays),
      lineItems: [],
    };

    setSubmitting(true);
    try {
      const res = await contractApi.createVariationOrder(form.contractId, payload);
      queryClient.invalidateQueries({ queryKey: ["variation-orders", projectId] });
      const newId = res.data?.id;
      if (newId) {
        router.push(
          `/projects/${projectId}/variation-orders/${newId}?contractId=${form.contractId}`,
        );
      } else {
        setShowForm(false);
        setForm(initialForm);
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create VO"));
    } finally {
      setSubmitting(false);
    }
  };

  if (contractsLoading) {
    return <div className="p-6 text-text-muted">Loading contracts…</div>;
  }

  if (contracts.length === 0) {
    return (
      <div className="p-6">
        <TabTip
          title="Variation Orders"
          description="Formal scope/rate/qty changes to the contract. Add line items here so approval mutates the BOQ in lockstep."
        />
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Variation Orders</h1>
        <p className="text-text-muted">
          This project has no contracts yet. Create a contract before raising a VO.
        </p>
      </div>
    );
  }

  return (
    <div className="p-6">
      <TabTip
        title="Variation Orders"
        description="Formal scope/rate/qty changes to the contract. Add line items here so approval mutates the BOQ (qty/rate/new lines) in lockstep with the VO transitioning to APPROVED."
      />
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-3xl font-bold text-text-primary">Variation Orders</h1>
        <button
          type="button"
          onClick={() => {
            setShowForm((v) => !v);
            setError(null);
            if (!showForm && contracts.length === 1) {
              setForm({ ...initialForm, contractId: contracts[0].id });
            } else if (!showForm) {
              setForm(initialForm);
            }
          }}
          className="px-4 py-2 bg-accent text-text-primary rounded-lg hover:bg-accent-hover"
        >
          {showForm ? "Cancel" : "New VO"}
        </button>
      </div>

      {error && <div className="text-danger mb-4">{error}</div>}

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl"
        >
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="md:col-span-2">
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Contract
              </label>
              <select
                value={form.contractId}
                onChange={(e) => setForm({ ...form, contractId: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                required
              >
                <option value="">Select contract…</option>
                {contractOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                VO Number
              </label>
              <input
                type="text"
                value={form.voNumber}
                onChange={(e) => setForm({ ...form, voNumber: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                required
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                VO Value ({symbol})
              </label>
              <input
                type="number"
                step="any"
                value={form.voValue}
                onChange={(e) => setForm({ ...form, voValue: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
            <div className="md:col-span-2">
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Description
              </label>
              <textarea
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
                rows={2}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
            <div className="md:col-span-2">
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Justification
              </label>
              <textarea
                value={form.justification}
                onChange={(e) => setForm({ ...form, justification: e.target.value })}
                rows={2}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Impact on Budget ({symbol})
              </label>
              <input
                type="number"
                step="any"
                value={form.impactOnBudget}
                onChange={(e) => setForm({ ...form, impactOnBudget: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Impact on Schedule (days)
              </label>
              <input
                type="number"
                step="1"
                value={form.impactOnScheduleDays}
                onChange={(e) => setForm({ ...form, impactOnScheduleDays: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
          </div>
          <p className="mt-3 text-xs text-text-muted">
            Line items (ADD/REVISE/DELETE BOQ rows) are added on the VO detail page after this is
            saved. The BOQ side is not touched until the VO transitions to APPROVED.
          </p>
          <div className="flex gap-2 mt-4">
            <button
              type="submit"
              disabled={submitting}
              className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:opacity-50"
            >
              {submitting ? "Saving…" : "Create VO"}
            </button>
            <button
              type="button"
              onClick={() => {
                setShowForm(false);
                setError(null);
              }}
              className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      <div className="overflow-x-auto">
        <table className="w-full border-collapse border border-border">
          <thead>
            <tr className="bg-surface/80 text-text-secondary">
              <th className="border border-border px-4 py-2 text-left">VO Number</th>
              <th className="border border-border px-4 py-2 text-left">Contract</th>
              <th className="border border-border px-4 py-2 text-left">Status</th>
              <th className="border border-border px-4 py-2 text-right">VO Value ({symbol})</th>
              <th className="border border-border px-4 py-2 text-right">Lines</th>
              <th className="border border-border px-4 py-2 text-left">Description</th>
              <th className="border border-border px-4 py-2 text-left">Approved At</th>
            </tr>
          </thead>
          <tbody>
            {variationOrders.length === 0 && (
              <tr>
                <td colSpan={7} className="border border-border px-4 py-3 text-text-muted text-center">
                  No variation orders yet.
                </td>
              </tr>
            )}
            {variationOrders.map((vo) => (
              <tr
                key={vo.id}
                className="hover:bg-surface-hover/30 text-text-primary cursor-pointer"
                onClick={() =>
                  router.push(
                    `/projects/${projectId}/variation-orders/${vo.id}?contractId=${vo.contractId}`,
                  )
                }
              >
                <td className="border border-border px-4 py-2">{vo.voNumber}</td>
                <td className="border border-border px-4 py-2">
                  {(vo as VariationOrderResponse & { contractLabel?: string }).contractLabel ?? vo.contractId}
                </td>
                <td className="border border-border px-4 py-2">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${statusBadgeClass(vo.status)}`}
                  >
                    {vo.status}
                  </span>
                </td>
                <td className="border border-border px-4 py-2 text-right">
                  {money(vo.voValue, { decimals: 0, symbol: false })}
                </td>
                <td className="border border-border px-4 py-2 text-right">
                  {vo.lineItems?.length ?? 0}
                </td>
                <td className="border border-border px-4 py-2 text-text-secondary truncate max-w-xs">
                  {vo.description || "—"}
                </td>
                <td className="border border-border px-4 py-2 text-text-secondary">
                  {vo.approvedAt ? new Date(vo.approvedAt).toLocaleString() : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

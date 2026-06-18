"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { grnApi, materialCatalogueApi } from "@/lib/api/materialCatalogueApi";
import { organisationApi } from "@/lib/api/organisationApi";
import { PageHeader } from "@/components/common/PageHeader";
import type { CreateGoodsReceiptRequest } from "@/lib/types";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

export default function NewGrnPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const router = useRouter();
  const queryClient = useQueryClient();
  const { symbol } = useProjectCurrency();

  const [state, setState] = useState<CreateGoodsReceiptRequest>({
    materialId: "",
    receivedDate: new Date().toISOString().slice(0, 10),
    quantity: 0,
  });
  const [error, setError] = useState<string | null>(null);

  const { data: materialsData } = useQuery({
    queryKey: ["materials", projectId, "ALL"],
    queryFn: () => materialCatalogueApi.listByProject(projectId),
    enabled: !!projectId,
  });
  const { data: suppliersData } = useQuery({
    queryKey: ["organisations", "suppliers"],
    queryFn: () => organisationApi.listByType("SUPPLIER"),
  });

  const materials = materialsData?.data ?? [];
  const suppliers = suppliersData?.data ?? [];

  const mutation = useMutation({
    mutationFn: (body: CreateGoodsReceiptRequest) => grnApi.create(projectId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["grns", projectId] });
      queryClient.invalidateQueries({ queryKey: ["stock-register", projectId] });
      router.push(`/projects/${projectId}/grns`);
    },
    onError: (err: { response?: { data?: { error?: { message?: string } } } }) => {
      setError(err.response?.data?.error?.message ?? "Failed to record GRN");
    },
  });

  const set = <K extends keyof CreateGoodsReceiptRequest>(
    k: K,
    v: CreateGoodsReceiptRequest[K],
  ) => setState((s) => ({ ...s, [k]: v }));

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!state.materialId) {
      setError("Material is required");
      return;
    }
    if (!state.quantity || state.quantity <= 0) {
      setError("Quantity must be greater than 0");
      return;
    }
    mutation.mutate(state);
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <PageHeader title="New GRN" description="Record a goods receipt against a project material." />

      <form onSubmit={handleSubmit} className="space-y-6 rounded-lg border border-border bg-surface p-6">
        {error && (
          <div className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            {error}
          </div>
        )}

        <div>
          <label className="block text-sm font-medium text-text-secondary">Material *</label>
          <select
            value={state.materialId}
            onChange={(e) => set("materialId", e.target.value)}
            required
            className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
          >
            <option value="">— Select —</option>
            {materials.map((m) => (
              <option key={m.id} value={m.id}>
                {m.code} — {m.name}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Received Date *</label>
            <input
              type="date"
              value={state.receivedDate}
              onChange={(e) => set("receivedDate", e.target.value)}
              required
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Quantity *</label>
            <input
              type="number"
              step="0.001"
              value={state.quantity || ""}
              onChange={(e) => set("quantity", Number(e.target.value))}
              required
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Unit Rate ({symbol})</label>
            <input
              type="number"
              step="0.01"
              value={state.unitRate ?? ""}
              onChange={(e) => set("unitRate", e.target.value ? Number(e.target.value) : null)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Supplier</label>
            <select
              value={state.supplierOrganisationId ?? ""}
              onChange={(e) => set("supplierOrganisationId", e.target.value || null)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            >
              <option value="">—</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">PO Number</label>
            <input
              value={state.poNumber ?? ""}
              onChange={(e) => set("poNumber", e.target.value)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Vehicle Number</label>
            <input
              value={state.vehicleNumber ?? ""}
              onChange={(e) => set("vehicleNumber", e.target.value)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">Remarks</label>
          <textarea
            value={state.remarks ?? ""}
            onChange={(e) => set("remarks", e.target.value)}
            rows={2}
            className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
          />
        </div>

        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={() => router.push(`/projects/${projectId}/grns`)}
            className="rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={mutation.isPending}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-50"
          >
            {mutation.isPending ? "Saving…" : "Record GRN"}
          </button>
        </div>
      </form>
    </div>
  );
}

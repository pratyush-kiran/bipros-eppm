"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { materialCatalogueApi } from "@/lib/api/materialCatalogueApi";
import { organisationApi } from "@/lib/api/organisationApi";
import { PageHeader } from "@/components/common/PageHeader";
import type {
  CreateMaterialRequest,
  MaterialCategory,
  MaterialStatus,
  ResourceUnit,
} from "@/lib/types";

const CATEGORY_OPTIONS: MaterialCategory[] = [
  "BITUMINOUS",
  "AGGREGATE",
  "CEMENT",
  "STEEL",
  "GRANULAR",
  "SAND",
  "PRECAST",
  "ROAD_MARKING",
];

const UNIT_OPTIONS: ResourceUnit[] = ["PER_DAY", "MT", "CU_M", "RMT", "NOS", "KG", "LITRE"];
const STATUS_OPTIONS: MaterialStatus[] = ["ACTIVE", "INACTIVE", "DISCONTINUED"];

export default function EditMaterialPage() {
  const params = useParams<{ projectId: string; materialId: string }>();
  const projectId = params.projectId;
  const materialId = params.materialId;
  const router = useRouter();
  const queryClient = useQueryClient();

  const { data: materialData, isLoading } = useQuery({
    queryKey: ["material", materialId],
    queryFn: () => materialCatalogueApi.get(materialId),
    enabled: !!materialId,
  });

  // Effective rate is a join lookup on (category, specGrade). Backend returns null when
  // no master row matches — surface that as an actionable hint, not a hard error.
  const { data: rateData } = useQuery({
    queryKey: ["material-effective-rate", materialId],
    queryFn: () => materialCatalogueApi.getEffectiveRate(materialId),
    enabled: !!materialId,
  });

  const [state, setState] = useState<CreateMaterialRequest | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (materialData?.data) {
      const m = materialData.data;
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setState({
        code: m.code,
        name: m.name,
        category: (m.category ?? "CEMENT") as MaterialCategory,
        unit: m.unit ?? null,
        specificationGrade: m.specificationGrade ?? null,
        minStockLevel: m.minStockLevel,
        reorderQuantity: m.reorderQuantity,
        leadTimeDays: m.leadTimeDays,
        storageLocation: m.storageLocation ?? null,
        approvedSupplierId: m.approvedSupplierId ?? null,
        status: m.status ?? "ACTIVE",
      });
    }
  }, [materialData]);

  const { data: suppliersData } = useQuery({
    queryKey: ["organisations", "suppliers"],
    queryFn: () => organisationApi.listByType("SUPPLIER"),
  });
  const suppliers = suppliersData?.data ?? [];

  const mutation = useMutation({
    mutationFn: (body: CreateMaterialRequest) => materialCatalogueApi.update(materialId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["materials", projectId] });
      queryClient.invalidateQueries({ queryKey: ["material", materialId] });
      router.push(`/projects/${projectId}/materials`);
    },
    onError: (err: { response?: { data?: { error?: { message?: string } } } }) => {
      setError(err.response?.data?.error?.message ?? "Failed to update material");
    },
  });

  const set = <K extends keyof CreateMaterialRequest>(
    key: K,
    value: CreateMaterialRequest[K],
  ) => setState((s) => (s ? { ...s, [key]: value } : s));

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!state) return;
    if (!state.name.trim()) {
      setError("Name is required");
      return;
    }
    mutation.mutate(state);
  };

  if (isLoading || !state) {
    return <div className="p-6 text-text-muted">Loading material…</div>;
  }

  const rate = rateData?.data ?? null;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title="Edit Material" description="Update material catalogue entry." />

      <div className="rounded-lg border border-border bg-surface p-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="text-xs uppercase tracking-wide text-text-secondary">
              Current rate (Material Rate Master)
            </div>
            {rate ? (
              <div className="mt-1">
                <span className="text-lg font-semibold text-text-primary">
                  {rate.rate} / {rate.unit}
                </span>
                <span className="ml-3 text-sm text-text-secondary">
                  {rate.categoryName ?? rate.categoryCode ?? "—"} / {rate.specGrade}
                </span>
                {!rate.active && (
                  <span className="ml-3 rounded bg-yellow-500/10 px-2 py-0.5 text-xs text-yellow-300">
                    inactive
                  </span>
                )}
              </div>
            ) : (
              <div className="mt-1 text-sm text-text-secondary">
                No matching rate master row for{" "}
                <span className="text-text-primary">
                  {state.category} / {state.specificationGrade || "(no spec grade)"}
                </span>
                . Set a category and specification grade, then add a row in the rate master.
              </div>
            )}
          </div>
          <Link
            href="/admin/rate-master"
            className="rounded-md border border-border bg-surface-hover px-3 py-2 text-sm font-medium text-text-primary hover:bg-surface"
          >
            Edit rate master
          </Link>
        </div>
      </div>

      <form
        onSubmit={handleSubmit}
        className="space-y-6 rounded-lg border border-border bg-surface p-6"
      >
        {error && (
          <div className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            {error}
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Material Code</label>
            <input
              value={state.code ?? ""}
              onChange={(e) => set("code", e.target.value)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Material Name *</label>
            <input
              value={state.name}
              onChange={(e) => set("name", e.target.value)}
              required
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Category *</label>
            <select
              value={state.category}
              onChange={(e) => set("category", e.target.value as MaterialCategory)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            >
              {CATEGORY_OPTIONS.map((c) => (
                <option key={c} value={c}>
                  {c.replace("_", " ")}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Unit</label>
            <select
              value={state.unit ?? ""}
              onChange={(e) => set("unit", (e.target.value || null) as ResourceUnit | null)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            >
              <option value="">—</option>
              {UNIT_OPTIONS.map((u) => (
                <option key={u} value={u}>
                  {u}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Status</label>
            <select
              value={state.status ?? "ACTIVE"}
              onChange={(e) => set("status", e.target.value as MaterialStatus)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            >
              {STATUS_OPTIONS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">Specification / Grade</label>
          <input
            value={state.specificationGrade ?? ""}
            onChange={(e) => set("specificationGrade", e.target.value)}
            placeholder="IS 73:2013, OPC 43 Grade, Fe500D"
            className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Min Stock Level</label>
            <input
              type="number"
              step="0.001"
              value={state.minStockLevel ?? ""}
              onChange={(e) =>
                set("minStockLevel", e.target.value ? Number(e.target.value) : null)
              }
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Reorder Quantity</label>
            <input
              type="number"
              step="0.001"
              value={state.reorderQuantity ?? ""}
              onChange={(e) =>
                set("reorderQuantity", e.target.value ? Number(e.target.value) : null)
              }
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Lead Time (days)</label>
            <input
              type="number"
              value={state.leadTimeDays ?? ""}
              onChange={(e) =>
                set("leadTimeDays", e.target.value ? Number(e.target.value) : null)
              }
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Storage Location</label>
            <input
              value={state.storageLocation ?? ""}
              onChange={(e) => set("storageLocation", e.target.value)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Approved Supplier</label>
            <select
              value={state.approvedSupplierId ?? ""}
              onChange={(e) => set("approvedSupplierId", e.target.value || null)}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
            >
              <option value="">— None —</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={() => router.push(`/projects/${projectId}/materials`)}
            className="rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={mutation.isPending}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-50"
          >
            {mutation.isPending ? "Saving…" : "Save Material"}
          </button>
        </div>
      </form>
    </div>
  );
}

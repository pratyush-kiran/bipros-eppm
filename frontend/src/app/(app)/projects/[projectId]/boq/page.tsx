"use client";

import { useMemo, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import {
  boqApi,
  type BoqItemResponse,
  type BoqSummaryResponse,
  type CreateBoqItemRequest,
  type UpdateBoqItemRequest,
} from "@/lib/api/boqApi";
import { TabTip } from "@/components/common/TabTip";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import { getErrorMessage } from "@/lib/utils/error";
import { cn } from "@/lib/utils/cn";
import { unitOptionsWithFallback, STANDARD_UNITS } from "@/lib/constants/units";

type EditableField = "qtyExecutedToDate" | "actualRate";

function unbilledOverrunValue(item: BoqItemResponse): number {
  const qty = item.qtyExecutedToDate ?? 0;
  const boqQty = item.boqQty ?? 0;
  const rate = item.boqRate ?? 0;
  if (qty <= boqQty) return 0;
  return (qty - boqQty) * rate;
}

interface BoqForm {
  itemNo: string;
  description: string;
  unit: string;
  boqQty: string;
  boqRate: string;
  budgetedRate: string;
  qtyExecutedToDate: string;
  actualRate: string;
}

const initialFormState: BoqForm = {
  itemNo: "",
  description: "",
  unit: "",
  boqQty: "",
  boqRate: "",
  budgetedRate: "",
  qtyExecutedToDate: "",
  actualRate: "",
};

function formatAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString("en-IN");
}

function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return (value * 100).toFixed(2) + "%";
}

function statusPillClass(status: string | null | undefined): string {
  if (!status) return "";
  switch (status) {
    case "COMPLETED":
      return "bg-blue-500/20 text-blue-300";
    case "ACTIVE":
      return "bg-success/20 text-success";
    case "ON_HOLD":
      return "bg-amber-500/20 text-warning";
    case "OVERRUN":
      return "bg-red-500/30 text-red-200";
    default:
      return "bg-slate-500/20 text-slate-300";
  }
}

function varianceClass(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return "";
  return value > 0 ? "text-danger" : "text-success";
}

export default function BoqPage() {
  const params = useParams();
  const router = useRouter();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<BoqForm>(initialFormState);
  const [formError, setFormError] = useState<string | null>(null);
  const [editingCell, setEditingCell] = useState<{ itemId: string; field: EditableField } | null>(null);
  const [editingValue, setEditingValue] = useState<string>("");
  const [overrunOnly, setOverrunOnly] = useState(false);

  const {
    data: summaryResponse,
    isLoading,
    error: queryError,
  } = useQuery({
    queryKey: ["boq", projectId],
    queryFn: () => boqApi.list(projectId),
  });

  const summary: BoqSummaryResponse | null | undefined = summaryResponse?.data;
  const allItems: BoqItemResponse[] = summary?.items ?? [];
  const overrunItems = allItems.filter((i) => i.status === "OVERRUN");
  const overrunCount = overrunItems.length;
  const overrunUnbilled = overrunItems.reduce((sum, item) => sum + unbilledOverrunValue(item), 0);
  const items = overrunOnly ? overrunItems : allItems;

  const updateMutation = useMutation({
    mutationFn: ({ itemId, request }: { itemId: string; request: UpdateBoqItemRequest }) =>
      boqApi.update(projectId, itemId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["boq", projectId] });
    },
  });

  const createMutation = useMutation({
    mutationFn: (request: CreateBoqItemRequest) => boqApi.create(projectId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["boq", projectId] });
      setFormData(initialFormState);
      setShowForm(false);
      setFormError(null);
    },
    onError: (err: unknown) => {
      setFormError(getErrorMessage(err, "Failed to create BOQ item"));
    },
  });

  const beginEdit = (item: BoqItemResponse, field: EditableField) => {
    setEditingCell({ itemId: item.id, field });
    const current = item[field];
    setEditingValue(current === null || current === undefined ? "" : String(current));
  };

  const commitEdit = () => {
    if (!editingCell) return;
    const trimmed = editingValue.trim();
    const numericValue = trimmed === "" ? null : Number(trimmed);
    if (numericValue !== null && Number.isNaN(numericValue)) {
      setEditingCell(null);
      return;
    }
    const item = items.find((it) => it.id === editingCell.itemId);
    const previous = item ? item[editingCell.field] : null;
    if (previous === numericValue) {
      setEditingCell(null);
      return;
    }
    const request: UpdateBoqItemRequest =
      editingCell.field === "qtyExecutedToDate"
        ? { qtyExecutedToDate: numericValue }
        : { actualRate: numericValue };
    updateMutation.mutate({ itemId: editingCell.itemId, request });
    setEditingCell(null);
  };

  const cancelEdit = () => {
    setEditingCell(null);
    setEditingValue("");
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    if (!formData.itemNo.trim() || !formData.description.trim() || !formData.unit) {
      setFormError("Item No, Description and Unit are required");
      return;
    }
    const request: CreateBoqItemRequest = {
      itemNo: formData.itemNo.trim(),
      description: formData.description.trim(),
      unit: formData.unit,
      boqQty: formData.boqQty === "" ? undefined : Number(formData.boqQty),
      boqRate: formData.boqRate === "" ? undefined : Number(formData.boqRate),
      budgetedRate: formData.budgetedRate === "" ? undefined : Number(formData.budgetedRate),
      qtyExecutedToDate: formData.qtyExecutedToDate === "" ? undefined : Number(formData.qtyExecutedToDate),
      actualRate: formData.actualRate === "" ? undefined : Number(formData.actualRate),
    };
    createMutation.mutate(request);
  };

  const renderEditableNumberCell = (item: BoqItemResponse, field: EditableField) => {
    const isEditing = editingCell?.itemId === item.id && editingCell.field === field;
    if (isEditing) {
      return (
        <input
          autoFocus
          type="number"
          step="any"
          value={editingValue}
          onChange={(e) => setEditingValue(e.target.value)}
          onBlur={commitEdit}
          onClick={(e) => e.stopPropagation()}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              commitEdit();
            } else if (e.key === "Escape") {
              e.preventDefault();
              cancelEdit();
            }
          }}
          className="w-full rounded border border-gold/40 bg-paper px-2 py-1 text-right text-sm text-charcoal focus:outline-none focus:ring-1 focus:ring-gold dark:text-[#F5F2E8]"
        />
      );
    }
    return (
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          beginEdit(item, field);
        }}
        className="block w-full text-right hover:text-gold focus:text-gold focus:outline-none"
        title="Click to edit"
      >
        {formatAmount(item[field])}
      </button>
    );
  };

  const columns = useMemo<ColumnDef<BoqItemResponse>[]>(
    () => [
      {
        accessorKey: "itemNo",
        header: "Item No.",
        size: 90,
        meta: { className: "font-medium" },
      },
      {
        accessorKey: "chapter",
        header: "Chapter",
        size: 110,
        cell: (info) => {
          const v = info.getValue() as string | null | undefined;
          return v ? v : <span className="text-ash">—</span>;
        },
      },
      {
        accessorKey: "description",
        header: "Description",
        size: 280,
        meta: { className: "whitespace-normal" },
      },
      {
        accessorKey: "unit",
        header: "Unit",
        size: 70,
      },
      {
        accessorKey: "status",
        header: "Status",
        size: 110,
        cell: (info) => {
          const status = info.getValue() as string | null | undefined;
          if (!status) return <span className="text-ash">—</span>;
          return (
            <span
              className={cn(
                "inline-block rounded-full px-2 py-0.5 text-[11px] font-medium",
                statusPillClass(status)
              )}
            >
              {status.replace("_", " ")}
            </span>
          );
        },
      },
      {
        accessorKey: "boqQty",
        header: "BOQ Qty",
        size: 90,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatAmount(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "boqRate",
        header: "BOQ Rate",
        size: 100,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatAmount(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "boqAmount",
        header: "BOQ Amount",
        size: 120,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatAmount(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "budgetedRate",
        header: "Budgeted Rate",
        size: 120,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatAmount(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "budgetedAmount",
        header: "Budgeted Amt",
        size: 130,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatAmount(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "qtyExecutedToDate",
        header: "Qty Executed",
        size: 120,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => renderEditableNumberCell(info.row.original, "qtyExecutedToDate"),
      },
      {
        accessorKey: "actualRate",
        header: "Actual Rate",
        size: 110,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => renderEditableNumberCell(info.row.original, "actualRate"),
      },
      {
        accessorKey: "actualAmount",
        header: "Actual Amount",
        size: 130,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatAmount(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "percentComplete",
        header: "% Complete",
        size: 110,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => formatPercent(info.getValue() as number | null | undefined),
      },
      {
        accessorKey: "costVariance",
        header: "Cost Variance",
        size: 130,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => {
          const v = info.getValue() as number | null | undefined;
          return <span className={varianceClass(v)}>{formatAmount(v)}</span>;
        },
      },
      {
        accessorKey: "costVariancePercent",
        header: "Var %",
        size: 90,
        meta: { className: "text-right tabular-nums" },
        cell: (info) => {
          const v = info.getValue() as number | null | undefined;
          return <span className={varianceClass(v)}>{formatPercent(v)}</span>;
        },
      },
    ],
    // editingCell/editingValue captured via closures inside renderEditableNumberCell — must re-build columns when they change so the input re-renders
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [editingCell, editingValue]
  );

  const grandTotalFooter = summary ? (
    <tr className="text-charcoal dark:text-[#F5F2E8] font-semibold">
      <td className="px-4 py-3" colSpan={7}>
        Grand Total
      </td>
      <td className="px-4 py-3 text-right tabular-nums">{formatAmount(summary.boqGrandTotal)}</td>
      <td className="px-4 py-3" />
      <td className="px-4 py-3 text-right tabular-nums">{formatAmount(summary.budgetedGrandTotal)}</td>
      <td className="px-4 py-3" />
      <td className="px-4 py-3" />
      <td className="px-4 py-3 text-right tabular-nums">{formatAmount(summary.actualGrandTotal)}</td>
      <td className="px-4 py-3 text-right tabular-nums">{formatPercent(summary.overallPercentComplete)}</td>
      <td className={cn("px-4 py-3 text-right tabular-nums", varianceClass(summary.grandCostVariance))}>
        {formatAmount(summary.grandCostVariance)}
      </td>
      <td className={cn("px-4 py-3 text-right tabular-nums", varianceClass(summary.grandCostVariancePercent))}>
        {formatPercent(summary.grandCostVariancePercent)}
      </td>
    </tr>
  ) : null;

  if (isLoading) {
    return <div className="p-6 text-text-muted">Loading BOQ...</div>;
  }

  const errorMessage = queryError ? getErrorMessage(queryError, "Failed to load BOQ") : null;

  return (
    <div className="p-6">
      <TabTip
        title="Bill of Quantities"
        description="Plan each line item by quantity multiplied by rate, then track executed quantity and actual rate to surface cost variance against the original BOQ and the internal budget."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Bill of Quantities</h1>

        {overrunCount > 0 && (
          <div className="mb-4 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="text-sm text-amber-200">
                <strong>{overrunCount}</strong>{" "}
                {overrunCount === 1 ? "item has" : "items have"} overrun their contracted
                quantities.{" "}
                <strong>₹{overrunUnbilled.toLocaleString("en-IN", { maximumFractionDigits: 2 })}</strong>{" "}
                of work executed cannot be billed until a Variation Order is approved.
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setOverrunOnly((v) => !v)}
                  className="px-3 py-1.5 text-xs rounded border border-amber-500/40 bg-amber-500/10 hover:bg-amber-500/20 text-amber-100"
                >
                  {overrunOnly ? "Show all items" : "Show only overrun"}
                </button>
                <button
                  type="button"
                  onClick={() => router.push(`/projects/${projectId}/variation-orders`)}
                  className="px-3 py-1.5 text-xs rounded bg-amber-500 text-black font-medium hover:bg-amber-400"
                >
                  Create VO
                </button>
              </div>
            </div>
          </div>
        )}

        <button
          onClick={() => {
            setShowForm(!showForm);
            setFormError(null);
          }}
          className="mb-6 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
        >
          {showForm ? "Cancel" : "Add BOQ Item"}
        </button>

        {errorMessage && <div className="text-danger mb-4">{errorMessage}</div>}
        {formError && <div className="text-danger mb-4">{formError}</div>}

        {showForm && (
          <form onSubmit={handleSubmit} className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Item No</label>
                <input
                  type="text"
                  value={formData.itemNo}
                  onChange={(e) => setFormData({ ...formData, itemNo: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Description</label>
                <input
                  type="text"
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Unit</label>
                <select
                  value={formData.unit}
                  onChange={(e) => setFormData({ ...formData, unit: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="">— select a unit —</option>
                  {unitOptionsWithFallback(formData.unit).map((u) => (
                    <option key={u} value={u}>
                      {u}
                      {!(STANDARD_UNITS as readonly string[]).includes(u) ? " (legacy)" : ""}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">BOQ Qty</label>
                <input
                  type="number"
                  step="any"
                  value={formData.boqQty}
                  onChange={(e) => setFormData({ ...formData, boqQty: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">BOQ Rate</label>
                <input
                  type="number"
                  step="any"
                  value={formData.boqRate}
                  onChange={(e) => setFormData({ ...formData, boqRate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Budgeted Rate</label>
                <input
                  type="number"
                  step="any"
                  value={formData.budgetedRate}
                  onChange={(e) => setFormData({ ...formData, budgetedRate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Qty Executed (optional)</label>
                <input
                  type="number"
                  step="any"
                  value={formData.qtyExecutedToDate}
                  onChange={(e) => setFormData({ ...formData, qtyExecutedToDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Actual Rate (optional)</label>
                <input
                  type="number"
                  step="any"
                  value={formData.actualRate}
                  onChange={(e) => setFormData({ ...formData, actualRate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
            </div>
            <div className="flex gap-2 mt-4">
              <button
                type="submit"
                disabled={createMutation.isPending}
                className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:opacity-50"
              >
                {createMutation.isPending ? "Saving..." : "Save Item"}
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowForm(false);
                  setFormError(null);
                }}
                className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        <VirtualDataTable
          data={items}
          columns={columns}
          getRowId={(row) => row.id}
          searchable
          sortable
          resizable
          maxHeight={640}
          emptyMessage={overrunOnly ? "No overrun items." : "No BOQ items yet. Click “Add BOQ Item” to start."}
          footer={grandTotalFooter}
        />
      </div>
    </div>
  );
}

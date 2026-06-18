"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  raBillApi,
  type CreateRaBillRequest,
  type DraftPreview,
  type RaBill,
  type SatelliteGate,
} from "@/lib/api/raBillApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { Button } from "@/components/ui/button";
import { Plus, FileText } from "lucide-react";
import { TabTip } from "@/components/common/TabTip";
import { contractApi } from "@/lib/api/contractApi";
import { getErrorMessage } from "@/lib/utils/error";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

const gateBadge = (gate?: SatelliteGate | null) => {
  if (!gate) return "bg-surface-active/40 text-text-secondary";
  switch (gate) {
    case "PASS":
      return "bg-success/20 text-success border border-success/40";
    case "HOLD_VARIANCE":
      return "bg-amber-500/20 text-warning border border-warning/40";
    case "RED_VARIANCE":
    case "HOLD_SATELLITE_DISPUTE":
      return "bg-red-500/20 text-danger border border-danger/40";
    default:
      return "bg-surface-active/40 text-text-secondary";
  }
};

const statusBadge = (status: string) => {
  switch (status) {
    case "PAID":
    case "PAID_PMC_OVERRIDE":
      return "bg-success/20 text-success";
    case "APPROVED":
    case "CERTIFIED":
      return "bg-blue-500/20 text-blue-300";
    case "PMC_REVIEW_PENDING":
    case "SUBMITTED":
      return "bg-indigo-500/20 text-indigo-300";
    case "HOLD_SATELLITE_DISPUTE":
      return "bg-amber-500/20 text-warning";
    case "REJECTED":
      return "bg-red-500/20 text-danger";
    default:
      return "bg-surface-active/40 text-text-secondary";
  }
};

function lastMonthRange(): { from: string; to: string } {
  const now = new Date();
  const startOfThisMonth = new Date(now.getFullYear(), now.getMonth(), 1);
  const endOfLastMonth = new Date(startOfThisMonth.getTime() - 24 * 60 * 60 * 1000);
  const startOfLastMonth = new Date(endOfLastMonth.getFullYear(), endOfLastMonth.getMonth(), 1);
  const fmt = (d: Date) => d.toISOString().split("T")[0];
  return { from: fmt(startOfLastMonth), to: fmt(endOfLastMonth) };
}

export default function RaBillsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const { money, symbol } = useProjectCurrency();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [selectedBillId, setSelectedBillId] = useState<string | null>(null);

  const initialRange = lastMonthRange();
  const [showDraftDialog, setShowDraftDialog] = useState(false);
  const [draftFrom, setDraftFrom] = useState(initialRange.from);
  const [draftTo, setDraftTo] = useState(initialRange.to);
  const [draftContractId, setDraftContractId] = useState<string>("");
  const [draftPreview, setDraftPreview] = useState<DraftPreview | null>(null);
  const [draftError, setDraftError] = useState<string | null>(null);
  const [draftBusy, setDraftBusy] = useState(false);

  const { data: contractsData } = useQuery({
    queryKey: ["contracts", projectId],
    queryFn: () => contractApi.listContracts(projectId, 0, 200),
    enabled: !!projectId,
  });
  const projectContracts = contractsData?.data?.content ?? [];

  const { data: billsData, isLoading: isLoadingBills } = useQuery({
    queryKey: ["ra-bills", projectId],
    queryFn: () => raBillApi.getRaBillsByProject(projectId),
  });

  const { data: billItemsData, isLoading: isLoadingItems } = useQuery({
    queryKey: ["ra-bill-items", selectedBillId],
    queryFn: () =>
      selectedBillId ? raBillApi.getRaBillItems(selectedBillId) : null,
    enabled: !!selectedBillId,
  });

  const createBillMutation = useMutation({
    mutationFn: (request: CreateRaBillRequest) =>
      raBillApi.createRaBill(projectId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["ra-bills", projectId] });
      setShowCreateForm(false);
    },
  });

  const bills = billsData?.data ?? [];
  const billItems = billItemsData?.data ?? [];

  const billColumns: ColumnDef<RaBill>[] = [
    { accessorKey: "billNumber", header: "Bill Number", enableSorting: true },
    { accessorKey: "wbsPackageCode", header: "Package", enableSorting: true },
    { accessorKey: "billPeriodFrom", header: "From", enableSorting: true },
    { accessorKey: "billPeriodTo", header: "To", enableSorting: true },
    {
      accessorKey: "grossAmount",
      header: "Gross",
      enableSorting: true,
      cell: (info) => money(Number(info.getValue()), { decimals: 0 }),
    },
    {
      accessorKey: "netAmount",
      header: "Net",
      enableSorting: true,
      cell: (info) => money(Number(info.getValue()), { decimals: 0 }),
    },
    {
      accessorKey: "contractorClaimedPercent",
      header: "Claim %",
      enableSorting: true,
      cell: (info) => {
        const value = info.getValue();
        return value != null ? `${Number(value).toFixed(1)}%` : "—";
      },
    },
    {
      accessorKey: "aiSatellitePercent",
      header: "AI %",
      enableSorting: true,
      cell: (info) => {
        const value = info.getValue();
        return value != null ? `${Number(value).toFixed(1)}%` : "—";
      },
    },
    {
      accessorKey: "satelliteGate",
      header: "Satellite Gate",
      enableSorting: true,
      cell: (info) => {
        const row = info.row.original;
        const gate = info.getValue() as SatelliteGate | null | undefined;
        if (!gate) return <span className="text-text-muted">—</span>;
        const variance = row.satelliteGateVariance;
        return (
          <span
            className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${gateBadge(gate)}`}
          >
            {gate.replace(/_/g, " ")}
            {variance != null && ` (Δ${Number(variance).toFixed(1)}%)`}
          </span>
        );
      },
    },
    {
      accessorKey: "status",
      header: "Status",
      enableSorting: true,
      cell: (info) => {
        const value = info.getValue();
        return (
          <span
            className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${statusBadge(String(value))}`}
          >
            {String(value).replace(/_/g, " ")}
          </span>
        );
      },
    },
  ];

  const handleCreateBill = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);

    const num = (name: string) => {
      const v = formData.get(name);
      return v ? Number(v) : undefined;
    };

    const gross = Number(formData.get("grossAmount"));
    const mob = num("mobAdvanceRecovery") ?? 0;
    const ret = num("retention5Pct") ?? 0;
    const tds = num("tds2Pct") ?? 0;
    const gst = num("gst18Pct") ?? 0;
    const deductions = mob + ret + tds + gst;

    const request: CreateRaBillRequest = {
      projectId,
      contractId: (formData.get("contractId") as string) || undefined,
      wbsPackageCode: (formData.get("wbsPackageCode") as string) || undefined,
      billNumber: formData.get("billNumber") as string,
      billPeriodFrom: formData.get("billPeriodFrom") as string,
      billPeriodTo: formData.get("billPeriodTo") as string,
      grossAmount: gross,
      deductions,
      mobAdvanceRecovery: mob || undefined,
      retention5Pct: ret || undefined,
      tds2Pct: tds || undefined,
      gst18Pct: gst || undefined,
      netAmount: gross - deductions,
      contractorClaimedPercent: num("contractorClaimedPercent"),
      remarks: (formData.get("remarks") as string) || undefined,
    };

    createBillMutation.mutate(request);
  };

  const inputCls =
    "mt-1 block w-full rounded-md border border-border bg-surface-hover text-text-primary placeholder-text-muted shadow-sm focus:border-accent focus:ring-accent sm:text-sm";

  return (
    <div className="space-y-6 p-6">
      <TabTip
        title="Running Account Bills (RA Bills)"
        description="RA Bills are periodic payment certificates for contractors. The Satellite Gate compares contractor-claimed progress against AI-derived satellite progress — variance >5% holds the bill, >10% is a hard stop."
      />
      <div className="flex items-center justify-between">
        <h1 className="text-3xl font-bold text-text-primary">RA Bills</h1>
        <div className="flex gap-2">
          <Button
            onClick={() => {
              setDraftPreview(null);
              setDraftError(null);
              if (projectContracts.length === 1) {
                setDraftContractId(projectContracts[0].id);
              }
              setShowDraftDialog(true);
            }}
            variant="secondary"
            className="gap-2"
          >
            <FileText size={20} />
            Generate Draft
          </Button>
          <Button
            onClick={() => setShowCreateForm(!showCreateForm)}
            className="gap-2"
          >
            <Plus size={20} />
            Create RA Bill
          </Button>
        </div>
      </div>

      {showDraftDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={() => setShowDraftDialog(false)}>
          <div
            className="max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-lg border border-border bg-surface p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-text-primary">Generate RA Bill Draft</h2>
              <button
                type="button"
                onClick={() => setShowDraftDialog(false)}
                className="text-text-muted hover:text-text-primary"
              >
                ✕
              </button>
            </div>

            <p className="mb-4 text-sm text-text-muted">
              Builds line items from BOQ × DPR for the selected period. Rates are frozen at the
              moment you save — a later VO that revises a rate will not change saved DRAFTs.
            </p>

            {draftError && <div className="mb-4 text-danger">{draftError}</div>}

            {!draftPreview && (
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">From</label>
                    <input
                      type="date"
                      value={draftFrom}
                      onChange={(e) => setDraftFrom(e.target.value)}
                      className={inputCls}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">To</label>
                    <input
                      type="date"
                      value={draftTo}
                      onChange={(e) => setDraftTo(e.target.value)}
                      className={inputCls}
                    />
                  </div>
                </div>
                {projectContracts.length > 1 && (
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">Contract</label>
                    <select
                      value={draftContractId}
                      onChange={(e) => setDraftContractId(e.target.value)}
                      className={inputCls}
                    >
                      <option value="">Select a contract…</option>
                      {projectContracts.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.contractNumber} — {c.contractorName}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
                <div className="flex gap-2">
                  <button
                    type="button"
                    disabled={draftBusy}
                    onClick={async () => {
                      setDraftError(null);
                      setDraftBusy(true);
                      try {
                        const res = await raBillApi.generateDraft(projectId, {
                          from: draftFrom,
                          to: draftTo,
                          contractId: draftContractId || undefined,
                          save: false,
                        });
                        if (res.data) setDraftPreview(res.data);
                      } catch (err: unknown) {
                        setDraftError(getErrorMessage(err, "Failed to generate draft"));
                      } finally {
                        setDraftBusy(false);
                      }
                    }}
                    className="rounded-md bg-accent px-4 py-2 text-text-primary hover:bg-accent-hover disabled:opacity-50"
                  >
                    {draftBusy ? "Generating…" : "Preview"}
                  </button>
                  <button
                    type="button"
                    onClick={() => setShowDraftDialog(false)}
                    className="rounded-md bg-surface-active/50 px-4 py-2 text-text-secondary hover:bg-border"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}

            {draftPreview && (
              <div className="space-y-4">
                <div className="rounded border border-border bg-surface-hover/30 p-3 text-sm">
                  <div>Period: {draftPreview.bill.billPeriodFrom} → {draftPreview.bill.billPeriodTo}</div>
                  <div>Gross: {money(Number(draftPreview.bill.grossAmount), { decimals: 0 })}</div>
                  <div>Net: {money(Number(draftPreview.bill.netAmount), { decimals: 0 })} (after Mob/Retention/TDS/GST)</div>
                  <div>Cumulative: {money(Number(draftPreview.bill.cumulativeAmount), { decimals: 0 })}</div>
                  <div>Lines: {draftPreview.items.length}</div>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full border-collapse border border-border text-sm">
                    <thead>
                      <tr className="bg-surface/80 text-text-secondary">
                        <th className="border border-border px-3 py-1.5 text-left">Item</th>
                        <th className="border border-border px-3 py-1.5 text-left">Description</th>
                        <th className="border border-border px-3 py-1.5 text-right">Prev Qty</th>
                        <th className="border border-border px-3 py-1.5 text-right">Cur Qty</th>
                        <th className="border border-border px-3 py-1.5 text-right">Δ Qty</th>
                        <th className="border border-border px-3 py-1.5 text-right">Rate</th>
                        <th className="border border-border px-3 py-1.5 text-right">Amount</th>
                      </tr>
                    </thead>
                    <tbody>
                      {draftPreview.items.map((it) => {
                        const delta = (it.currentQuantity ?? 0) - (it.previousQuantity ?? 0);
                        return (
                          <tr key={it.itemCode + (it.boqItemId ?? "")} className="text-text-primary">
                            <td className="border border-border px-3 py-1.5">{it.itemCode}</td>
                            <td className="border border-border px-3 py-1.5">{it.description}</td>
                            <td className="border border-border px-3 py-1.5 text-right">{it.previousQuantity ?? "—"}</td>
                            <td className="border border-border px-3 py-1.5 text-right">{it.currentQuantity ?? "—"}</td>
                            <td className="border border-border px-3 py-1.5 text-right">{delta.toFixed(3)}</td>
                            <td className="border border-border px-3 py-1.5 text-right">{it.rate ?? "—"}</td>
                            <td className="border border-border px-3 py-1.5 text-right">
                              {money(Number(it.amount), { decimals: 0 })}
                            </td>
                          </tr>
                        );
                      })}
                      {draftPreview.items.length === 0 && (
                        <tr>
                          <td colSpan={7} className="border border-border px-3 py-3 text-center text-text-muted">
                            No new qty executed in this period — nothing to bill.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    disabled={draftBusy || draftPreview.items.length === 0}
                    onClick={async () => {
                      setDraftError(null);
                      setDraftBusy(true);
                      try {
                        await raBillApi.generateDraft(projectId, {
                          from: draftFrom,
                          to: draftTo,
                          contractId: draftPreview.resolvedContractId,
                          save: true,
                        });
                        queryClient.invalidateQueries({ queryKey: ["ra-bills", projectId] });
                        setShowDraftDialog(false);
                        setDraftPreview(null);
                      } catch (err: unknown) {
                        setDraftError(getErrorMessage(err, "Failed to save draft"));
                      } finally {
                        setDraftBusy(false);
                      }
                    }}
                    className="rounded-md bg-green-600 px-4 py-2 text-text-primary hover:bg-green-500 disabled:opacity-50"
                  >
                    {draftBusy ? "Saving…" : "Save as DRAFT"}
                  </button>
                  <button
                    type="button"
                    onClick={() => setDraftPreview(null)}
                    className="rounded-md bg-surface-active/50 px-4 py-2 text-text-secondary hover:bg-border"
                  >
                    Discard preview
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {showCreateForm && (
        <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-xl">
          <h2 className="mb-4 text-lg font-semibold text-text-primary">
            Create New RA Bill
          </h2>
          <form onSubmit={handleCreateBill} className="space-y-4">
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Bill Number
                </label>
                <input
                  type="text"
                  name="billNumber"
                  required
                  placeholder="DMIC-N03-P01-RA-001"
                  className={inputCls}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  WBS Package Code
                </label>
                <input
                  type="text"
                  name="wbsPackageCode"
                  placeholder="DMIC-N03-P01"
                  className={inputCls}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Contract ID (Optional)
                </label>
                <input type="text" name="contractId" className={inputCls} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Bill Period From
                </label>
                <input
                  type="date"
                  name="billPeriodFrom"
                  required
                  className={inputCls}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Bill Period To
                </label>
                <input
                  type="date"
                  name="billPeriodTo"
                  required
                  className={inputCls}
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Gross Amount ({symbol})
                </label>
                <input
                  type="number"
                  name="grossAmount"
                  step="0.01"
                  required
                  className={inputCls}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Contractor Claimed Progress %
                </label>
                <input
                  type="number"
                  name="contractorClaimedPercent"
                  step="0.01"
                  min="0"
                  max="100"
                  placeholder="e.g. 49.5"
                  className={inputCls}
                />
              </div>
            </div>

            <div className="rounded-md border border-border bg-background/40 p-4">
              <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-text-secondary">
                Deduction Breakdown (CPWD standard)
              </p>
              <div className="grid grid-cols-4 gap-4">
                <div>
                  <label className="block text-xs text-text-secondary">
                    Mob Advance (10%)
                  </label>
                  <input
                    type="number"
                    name="mobAdvanceRecovery"
                    step="0.01"
                    className={inputCls}
                  />
                </div>
                <div>
                  <label className="block text-xs text-text-secondary">
                    Retention (5%)
                  </label>
                  <input
                    type="number"
                    name="retention5Pct"
                    step="0.01"
                    className={inputCls}
                  />
                </div>
                <div>
                  <label className="block text-xs text-text-secondary">
                    TDS (2%)
                  </label>
                  <input
                    type="number"
                    name="tds2Pct"
                    step="0.01"
                    className={inputCls}
                  />
                </div>
                <div>
                  <label className="block text-xs text-text-secondary">
                    GST (18%)
                  </label>
                  <input
                    type="number"
                    name="gst18Pct"
                    step="0.01"
                    className={inputCls}
                  />
                </div>
              </div>
              <p className="mt-2 text-xs text-text-muted">
                Net amount and total deductions are derived from the four
                breakdowns.
              </p>
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Remarks (Optional)
              </label>
              <textarea
                name="remarks"
                rows={3}
                className={inputCls}
              />
            </div>

            <div className="flex gap-2">
              <button
                type="submit"
                disabled={createBillMutation.isPending}
                className="rounded-md bg-accent px-4 py-2 text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                {createBillMutation.isPending ? "Creating..." : "Create Bill"}
              </button>
              <button
                type="button"
                onClick={() => setShowCreateForm(false)}
                className="rounded-md bg-surface-active/50 px-4 py-2 text-text-secondary hover:bg-border"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-xl">
        <h2 className="mb-4 text-lg font-semibold text-text-primary">RA Bills List</h2>
        {isLoadingBills ? (
          <div className="text-center text-text-muted">Loading RA Bills...</div>
        ) : bills.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <h3 className="text-lg font-medium text-text-primary">No RA Bills</h3>
            <p className="mt-2 text-text-muted">
              No RA bills created yet. Create one to get started.
            </p>
          </div>
        ) : (
          <VirtualDataTable
            columns={billColumns}
            data={bills}
            sortable
            resizable
            onRowClick={(bill) => setSelectedBillId(bill.id)}
          />
        )}
      </div>

      {selectedBillId && (
        <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-xl">
          <h2 className="mb-4 text-lg font-semibold text-text-primary">Bill Items</h2>
          {isLoadingItems ? (
            <div className="text-center text-text-muted">
              Loading bill items...
            </div>
          ) : billItems.length === 0 ? (
            <div className="rounded-lg border border-dashed border-border py-8 text-center">
              <p className="text-text-muted">No items in this bill yet.</p>
            </div>
          ) : (
            <div className="space-y-4">
              {billItems.map((item) => (
                <div
                  key={item.id}
                  className="flex justify-between border-b border-border pb-4"
                >
                  <div>
                    <p className="font-medium text-text-primary">{item.itemCode}</p>
                    <p className="text-sm text-text-muted">{item.description}</p>
                  </div>
                  <div className="text-right">
                    <p className="font-medium text-text-primary">
                      {money(Number(item.amount), { decimals: 0 })}
                    </p>
                    {item.unit && (
                      <p className="text-sm text-text-muted">Unit: {item.unit}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

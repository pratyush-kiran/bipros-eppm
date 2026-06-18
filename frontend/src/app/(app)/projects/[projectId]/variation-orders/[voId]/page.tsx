"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { contractApi } from "@/lib/api/contractApi";
import { boqApi } from "@/lib/api/boqApi";
import { TabTip } from "@/components/common/TabTip";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { getErrorMessage } from "@/lib/utils/error";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import type {
  VariationOrderResponse,
  VoLineItemAction,
  VoLineItemRequest,
} from "@/lib/types";

interface LineRow extends VoLineItemRequest {
  __key: string;
}

const ACTION_OPTIONS: VoLineItemAction[] = [
  "ADD_ITEM",
  "REVISE_QTY",
  "REVISE_RATE",
  "DELETE_ITEM",
];

function newKey() {
  return `tmp-${Math.random().toString(36).slice(2, 9)}`;
}

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

function lineImpact(line: VoLineItemRequest, currentBoqRate?: number, currentBoqQty?: number): number {
  const qty = line.revisedQty ?? 0;
  const rate = line.revisedRate ?? 0;
  switch (line.action) {
    case "ADD_ITEM":
      return qty * rate;
    case "REVISE_QTY":
      return ((qty || 0) - (currentBoqQty ?? 0)) * (currentBoqRate ?? 0);
    case "REVISE_RATE":
      return (currentBoqQty ?? 0) * ((rate || 0) - (currentBoqRate ?? 0));
    case "DELETE_ITEM":
      return -((currentBoqQty ?? 0) * (currentBoqRate ?? 0));
    default:
      return 0;
  }
}

export default function VariationOrderDetailPage() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const projectId = params.projectId as string;
  const voId = params.voId as string;
  const contractIdQs = searchParams.get("contractId") ?? "";
  const { money, symbol } = useProjectCurrency();

  const [contractId, setContractId] = useState<string>(contractIdQs);
  const [vo, setVo] = useState<VariationOrderResponse | null>(null);
  const [headerError, setHeaderError] = useState<string | null>(null);
  const [savingLines, setSavingLines] = useState(false);
  const [statusBusy, setStatusBusy] = useState(false);

  // Header form state
  const [voNumber, setVoNumber] = useState("");
  const [description, setDescription] = useState("");
  const [voValue, setVoValue] = useState("");
  const [justification, setJustification] = useState("");
  const [impactOnBudget, setImpactOnBudget] = useState("");
  const [impactOnScheduleDays, setImpactOnScheduleDays] = useState("");
  const [approvedBy, setApprovedBy] = useState("");

  const [lines, setLines] = useState<LineRow[]>([]);

  // If contractId not in querystring, find it by scanning project's contracts.
  const { data: contractsData } = useQuery({
    queryKey: ["contracts", projectId],
    queryFn: () => contractApi.listContracts(projectId, 0, 200),
    enabled: !!projectId && !contractIdQs,
  });

  useEffect(() => {
    if (contractId) return;
    const contracts = contractsData?.data?.content ?? [];
    if (!contracts.length) return;
    let cancelled = false;
    (async () => {
      for (const c of contracts) {
        try {
          const res = await contractApi.getVariationOrder(c.id, voId);
          if (cancelled) return;
          if (res.data) {
            setContractId(c.id);
            return;
          }
        } catch {
          // try next contract
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [contractsData, contractId, voId]);

  const { data: voData, isLoading: voLoading } = useQuery({
    queryKey: ["vo", contractId, voId],
    queryFn: () => contractApi.getVariationOrder(contractId, voId),
    enabled: !!contractId && !!voId,
  });

  useEffect(() => {
    if (!voData?.data) return;
    const v = voData.data;
    setVo(v);
    setVoNumber(v.voNumber);
    setDescription(v.description ?? "");
    setVoValue(v.voValue != null ? String(v.voValue) : "");
    setJustification(v.justification ?? "");
    setImpactOnBudget(v.impactOnBudget != null ? String(v.impactOnBudget) : "");
    setImpactOnScheduleDays(
      v.impactOnScheduleDays != null ? String(v.impactOnScheduleDays) : "",
    );
    setApprovedBy(v.approvedBy ?? "");
    setLines(
      (v.lineItems ?? []).map((li) => ({
        __key: li.id,
        id: li.id,
        action: li.action,
        boqItemId: li.boqItemId,
        newItemNo: li.newItemNo,
        newItemDescription: li.newItemDescription,
        newItemUnit: li.newItemUnit,
        revisedQty: li.revisedQty,
        revisedRate: li.revisedRate,
        lineImpactAmount: li.lineImpactAmount,
      })),
    );
  }, [voData]);

  const { data: boqData } = useQuery({
    queryKey: ["boq", projectId],
    queryFn: () => boqApi.list(projectId),
    enabled: !!projectId,
  });
  const boqItems = boqData?.data?.items ?? [];

  const boqOptions = useMemo(
    () =>
      boqItems
        .slice()
        .sort((a, b) =>
          a.itemNo.localeCompare(b.itemNo, undefined, { numeric: true, sensitivity: "base" }),
        )
        .map((i) => ({
          value: i.id,
          label: `${i.itemNo} — ${i.description}${i.unit ? ` (${i.unit})` : ""}`,
        })),
    [boqItems],
  );

  const isLocked = vo?.status === "APPROVED" || vo?.status === "REJECTED";

  const totalLineImpact = useMemo(() => {
    return lines.reduce((sum, line) => {
      const refItem = line.boqItemId ? boqItems.find((b) => b.id === line.boqItemId) : null;
      return (
        sum +
        lineImpact(line, refItem?.boqRate ?? undefined, refItem?.boqQty ?? undefined)
      );
    }, 0);
  }, [lines, boqItems]);

  const addLine = (action: VoLineItemAction) => {
    setLines((cur) => [
      ...cur,
      {
        __key: newKey(),
        id: undefined,
        action,
        boqItemId: action === "ADD_ITEM" ? null : null,
        newItemNo: action === "ADD_ITEM" ? "" : null,
        newItemDescription: action === "ADD_ITEM" ? "" : null,
        newItemUnit: action === "ADD_ITEM" ? "Each" : null,
        revisedQty: null,
        revisedRate: null,
      },
    ]);
  };

  const updateLine = (key: string, patch: Partial<LineRow>) => {
    setLines((cur) => cur.map((l) => (l.__key === key ? { ...l, ...patch } : l)));
  };

  const removeLine = (key: string) => {
    setLines((cur) => cur.filter((l) => l.__key !== key));
  };

  const saveAll = async () => {
    if (!vo || !contractId) return;
    setHeaderError(null);
    setSavingLines(true);
    try {
      const payload = {
        contractId,
        voNumber,
        description,
        voValue: voValue === "" ? 0 : Number(voValue),
        justification,
        impactOnBudget: impactOnBudget === "" ? 0 : Number(impactOnBudget),
        impactOnScheduleDays:
          impactOnScheduleDays === "" ? 0 : Number(impactOnScheduleDays),
        approvedBy: approvedBy || undefined,
        lineItems: lines.map((l) => {
          const refItem = l.boqItemId ? boqItems.find((b) => b.id === l.boqItemId) : null;
          return {
            id: l.id ?? null,
            action: l.action,
            boqItemId: l.boqItemId ?? null,
            newItemNo: l.newItemNo ?? null,
            newItemDescription: l.newItemDescription ?? null,
            newItemUnit: l.newItemUnit ?? null,
            revisedQty: l.revisedQty ?? null,
            revisedRate: l.revisedRate ?? null,
            lineImpactAmount: lineImpact(
              l,
              refItem?.boqRate ?? undefined,
              refItem?.boqQty ?? undefined,
            ),
          } as VoLineItemRequest;
        }),
      };
      await contractApi.updateVariationOrder(contractId, voId, payload);
      queryClient.invalidateQueries({ queryKey: ["vo", contractId, voId] });
      queryClient.invalidateQueries({ queryKey: ["variation-orders", projectId] });
    } catch (err: unknown) {
      setHeaderError(getErrorMessage(err, "Failed to save VO"));
    } finally {
      setSavingLines(false);
    }
  };

  const transition = async (status: "RECOMMENDED" | "APPROVED" | "REJECTED") => {
    if (!vo || !contractId) return;
    if (status === "APPROVED") {
      const confirmMsg =
        lines.length === 0
          ? "Approve this VO? No line items are attached, so the BOQ will not change."
          : `Approve this VO? ${lines.length} line item${lines.length === 1 ? "" : "s"} will mutate the BOQ (qty/rate/new items) — this is irreversible.`;
      if (!confirm(confirmMsg)) return;
    }
    setHeaderError(null);
    setStatusBusy(true);
    try {
      await contractApi.updateVariationOrderStatus(contractId, voId, {
        status,
        approvedBy: approvedBy || undefined,
      });
      queryClient.invalidateQueries({ queryKey: ["vo", contractId, voId] });
      queryClient.invalidateQueries({ queryKey: ["variation-orders", projectId] });
      queryClient.invalidateQueries({ queryKey: ["boq", projectId] });
    } catch (err: unknown) {
      setHeaderError(getErrorMessage(err, "Failed to transition VO status"));
    } finally {
      setStatusBusy(false);
    }
  };

  if (!contractId) {
    return <div className="p-6 text-text-muted">Resolving contract for VO…</div>;
  }
  if (voLoading || !vo) {
    return <div className="p-6 text-text-muted">Loading VO…</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title={`VO ${vo.voNumber}`}
        description="Add line items so VO approval mutates the BOQ in lockstep. Header changes lock once APPROVED."
      />

      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => router.push(`/projects/${projectId}/variation-orders`)}
            className="text-sm text-text-secondary hover:text-text-primary"
          >
            ← Back to VOs
          </button>
          <h1 className="text-3xl font-bold text-text-primary">VO {vo.voNumber}</h1>
          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${statusBadgeClass(vo.status)}`}
          >
            {vo.status}
          </span>
        </div>
        <div className="flex gap-2">
          {vo.status === "INITIATED" && (
            <button
              type="button"
              onClick={() => transition("RECOMMENDED")}
              disabled={statusBusy}
              className="px-3 py-1.5 text-sm rounded bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50"
            >
              Recommend
            </button>
          )}
          {(vo.status === "INITIATED" || vo.status === "RECOMMENDED") && (
            <>
              <button
                type="button"
                onClick={() => transition("APPROVED")}
                disabled={statusBusy}
                className="px-3 py-1.5 text-sm rounded bg-green-600 text-white hover:bg-green-500 disabled:opacity-50"
              >
                Approve
              </button>
              <button
                type="button"
                onClick={() => transition("REJECTED")}
                disabled={statusBusy}
                className="px-3 py-1.5 text-sm rounded bg-red-600 text-white hover:bg-red-500 disabled:opacity-50"
              >
                Reject
              </button>
            </>
          )}
        </div>
      </div>

      {headerError && <div className="text-danger mb-4">{headerError}</div>}

      <div className="bg-surface/50 p-4 rounded-lg border border-border mb-6">
        <h2 className="text-lg font-semibold mb-3 text-text-primary">Header</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">VO Number</label>
            <input
              type="text"
              value={voNumber}
              onChange={(e) => setVoNumber(e.target.value)}
              disabled={isLocked}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">VO Value ({symbol})</label>
            <input
              type="number"
              step="any"
              value={voValue}
              onChange={(e) => setVoValue(e.target.value)}
              disabled={isLocked}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
          <div className="md:col-span-2">
            <label className="block text-sm font-medium mb-1 text-text-secondary">Description</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={isLocked}
              rows={2}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
          <div className="md:col-span-2">
            <label className="block text-sm font-medium mb-1 text-text-secondary">Justification</label>
            <textarea
              value={justification}
              onChange={(e) => setJustification(e.target.value)}
              disabled={isLocked}
              rows={2}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">Impact on Budget ({symbol})</label>
            <input
              type="number"
              step="any"
              value={impactOnBudget}
              onChange={(e) => setImpactOnBudget(e.target.value)}
              disabled={isLocked}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">Impact on Schedule (days)</label>
            <input
              type="number"
              step="1"
              value={impactOnScheduleDays}
              onChange={(e) => setImpactOnScheduleDays(e.target.value)}
              disabled={isLocked}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">Approved By</label>
            <input
              type="text"
              value={approvedBy}
              onChange={(e) => setApprovedBy(e.target.value)}
              disabled={isLocked}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg disabled:opacity-60"
            />
          </div>
        </div>
      </div>

      <div className="bg-surface/50 p-4 rounded-lg border border-border mb-6">
        <div className="flex flex-wrap items-center justify-between gap-3 mb-3">
          <h2 className="text-lg font-semibold text-text-primary">Line Items</h2>
          <div className="flex gap-2">
            {ACTION_OPTIONS.map((a) => (
              <button
                key={a}
                type="button"
                onClick={() => addLine(a)}
                disabled={isLocked}
                className="px-3 py-1.5 text-xs rounded border border-border bg-surface-hover hover:bg-surface-active disabled:opacity-50 text-text-primary"
              >
                + {a.replace("_", " ")}
              </button>
            ))}
          </div>
        </div>

        {lines.length === 0 && (
          <p className="text-sm text-text-muted">
            No line items. Approving without lines leaves BOQ untouched (legacy header-only behaviour).
          </p>
        )}

        {lines.map((line) => {
          const refItem = line.boqItemId ? boqItems.find((b) => b.id === line.boqItemId) : null;
          const impact = lineImpact(
            line,
            refItem?.boqRate ?? undefined,
            refItem?.boqQty ?? undefined,
          );
          return (
            <div
              key={line.__key}
              className="grid grid-cols-1 md:grid-cols-12 gap-2 mb-3 p-3 bg-surface-hover/30 rounded border border-border"
            >
              <div className="md:col-span-2 text-xs font-medium text-text-secondary uppercase">
                {line.action.replace("_", " ")}
              </div>
              {line.action === "ADD_ITEM" ? (
                <>
                  <input
                    type="text"
                    placeholder="Item No"
                    value={line.newItemNo ?? ""}
                    onChange={(e) => updateLine(line.__key, { newItemNo: e.target.value })}
                    disabled={isLocked}
                    className="md:col-span-2 px-2 py-1 border border-border bg-surface-hover text-text-primary rounded text-sm"
                  />
                  <input
                    type="text"
                    placeholder="Description"
                    value={line.newItemDescription ?? ""}
                    onChange={(e) =>
                      updateLine(line.__key, { newItemDescription: e.target.value })
                    }
                    disabled={isLocked}
                    className="md:col-span-3 px-2 py-1 border border-border bg-surface-hover text-text-primary rounded text-sm"
                  />
                  <input
                    type="text"
                    placeholder="Unit"
                    value={line.newItemUnit ?? ""}
                    onChange={(e) => updateLine(line.__key, { newItemUnit: e.target.value })}
                    disabled={isLocked}
                    className="md:col-span-1 px-2 py-1 border border-border bg-surface-hover text-text-primary rounded text-sm"
                  />
                </>
              ) : (
                <div className="md:col-span-6">
                  <SearchableSelect
                    options={boqOptions}
                    value={line.boqItemId ?? ""}
                    onChange={(v) => updateLine(line.__key, { boqItemId: v })}
                    placeholder="Pick BOQ item…"
                    disabled={isLocked || boqOptions.length === 0}
                    className="w-full"
                  />
                  {refItem && (
                    <p className="mt-1 text-xs text-text-muted">
                      Current: qty {refItem.boqQty ?? 0}, rate {money(refItem.boqRate ?? 0)}
                    </p>
                  )}
                </div>
              )}
              {(line.action === "ADD_ITEM" || line.action === "REVISE_QTY") && (
                <input
                  type="number"
                  step="any"
                  placeholder="New Qty"
                  value={line.revisedQty ?? ""}
                  onChange={(e) =>
                    updateLine(line.__key, {
                      revisedQty: e.target.value === "" ? null : Number(e.target.value),
                    })
                  }
                  disabled={isLocked}
                  className="md:col-span-1 px-2 py-1 border border-border bg-surface-hover text-text-primary rounded text-sm text-right"
                />
              )}
              {(line.action === "ADD_ITEM" || line.action === "REVISE_RATE") && (
                <input
                  type="number"
                  step="any"
                  placeholder="New Rate"
                  value={line.revisedRate ?? ""}
                  onChange={(e) =>
                    updateLine(line.__key, {
                      revisedRate: e.target.value === "" ? null : Number(e.target.value),
                    })
                  }
                  disabled={isLocked}
                  className="md:col-span-1 px-2 py-1 border border-border bg-surface-hover text-text-primary rounded text-sm text-right"
                />
              )}
              <div className="md:col-span-2 text-right text-sm">
                <span className="text-text-muted">Impact:</span>{" "}
                <span
                  className={
                    impact > 0 ? "text-success" : impact < 0 ? "text-red-300" : "text-text-secondary"
                  }
                >
                  {money(impact)}
                </span>
              </div>
              <button
                type="button"
                onClick={() => removeLine(line.__key)}
                disabled={isLocked}
                className="md:col-span-1 px-2 py-1 text-xs rounded bg-red-600 text-white hover:bg-red-500 disabled:opacity-50"
              >
                Remove
              </button>
            </div>
          );
        })}

        {lines.length > 0 && (
          <div className="mt-3 text-right text-sm">
            <span className="text-text-muted">Sum of line impacts: </span>
            <strong>
              {money(totalLineImpact)}
            </strong>
            {voValue && Math.abs(totalLineImpact - Number(voValue)) > 0.01 && (
              <span className="ml-2 text-amber-300">
                ≠ header VO Value ({money(Number(voValue), { decimals: 0 })})
              </span>
            )}
          </div>
        )}
      </div>

      <div className="flex gap-2">
        <button
          type="button"
          onClick={saveAll}
          disabled={savingLines || isLocked}
          className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600 disabled:opacity-50"
        >
          {savingLines ? "Saving…" : "Save VO"}
        </button>
        <button
          type="button"
          onClick={() => router.push(`/projects/${projectId}/variation-orders`)}
          className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
        >
          Back
        </button>
      </div>
    </div>
  );
}

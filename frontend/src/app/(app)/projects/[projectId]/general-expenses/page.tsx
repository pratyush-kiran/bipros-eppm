"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  formatInputMonth,
  generalExpensesApi,
  parseInputMonth,
  toYearMonth,
  type GeneralExpensePlanItem,
  type MonthlyActualsResponse,
  type PlanItemUpsertRequest,
} from "@/lib/api/generalExpensesApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";

function fmt(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

function numOrNull(value: string): number | null {
  if (value === "" || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export default function GeneralExpensesPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();

  const today = new Date();
  const [yearMonth, setYearMonth] = useState<number>(
    toYearMonth(today.getFullYear(), today.getMonth() + 1),
  );

  // ── plan items ────────────────────────────────────────────────────────────

  const {
    data: planResp,
    isLoading: planLoading,
    error: planError,
  } = useQuery({
    queryKey: ["genexp-plan", projectId],
    queryFn: () => generalExpensesApi.listPlanItems(projectId),
    enabled: !!projectId,
  });

  const plan: GeneralExpensePlanItem[] = useMemo(
    () => planResp?.data ?? [],
    [planResp],
  );

  const updatePlan = useMutation({
    mutationFn: (input: { itemId: string; body: PlanItemUpsertRequest }) =>
      generalExpensesApi.updatePlanItem(projectId, input.itemId, input.body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["genexp-plan", projectId] }),
  });

  // Local edit buffer keyed by plan item id; flushed on blur.
  const [planEdits, setPlanEdits] = useState<Record<string, { planQty?: string; planAmount?: string; rate?: string }>>(
    {},
  );

  function commitPlanEdit(item: GeneralExpensePlanItem, field: "planQty" | "planAmount" | "rate") {
    const buf = planEdits[item.id];
    if (!buf || buf[field] === undefined) return;
    const next = numOrNull(buf[field] as string);
    const current = item[field] ?? null;
    if (next === current) {
      // clear the buffer for this field so the input falls back to the canonical value
      setPlanEdits((e) => {
        const copy = { ...e };
        if (copy[item.id]) {
          const inner = { ...copy[item.id] };
          delete inner[field];
          copy[item.id] = inner;
        }
        return copy;
      });
      return;
    }
    const patch: PlanItemUpsertRequest = { [field]: next };
    // When the user edits planQty or rate, recompute planAmount automatically if
    // both are known and the user did NOT also edit planAmount in this commit.
    if (field === "planQty" || field === "rate") {
      const qty = field === "planQty" ? next : item.planQty ?? null;
      const rate = field === "rate" ? next : item.rate ?? null;
      if (qty !== null && rate !== null && buf.planAmount === undefined) {
        patch.planAmount = qty * rate;
      }
    }
    updatePlan.mutate({ itemId: item.id, body: patch });
  }

  // ── monthly actuals ──────────────────────────────────────────────────────

  const {
    data: actualsResp,
    isLoading: actualsLoading,
    error: actualsError,
  } = useQuery({
    queryKey: ["genexp-actuals", projectId, yearMonth],
    queryFn: () => generalExpensesApi.getActuals(projectId, yearMonth),
    enabled: !!projectId,
  });

  const actuals: MonthlyActualsResponse | undefined = actualsResp?.data ?? undefined;

  const upsertActual = useMutation({
    mutationFn: (input: { planItemId: string; achievedQty: number | null; achievedAmount: number | null; notes: string | null }) =>
      generalExpensesApi.upsertActual(projectId, input.planItemId, yearMonth, {
        achievedQty: input.achievedQty,
        achievedAmount: input.achievedAmount,
        notes: input.notes,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["genexp-actuals", projectId, yearMonth] }),
  });

  const [actualEdits, setActualEdits] = useState<
    Record<string, { achievedQty?: string; achievedAmount?: string; notes?: string }>
  >({});

  // Wipe the buffer whenever the month changes so the inputs hydrate from the new response.
  useEffect(() => setActualEdits({}), [yearMonth]);

  function commitActualEdit(
    planItemId: string,
    field: "achievedQty" | "achievedAmount" | "notes",
    currentRow: { achievedQty?: number | null; achievedAmount?: number | null; notes?: string | null } | null,
  ) {
    const buf = actualEdits[planItemId];
    if (!buf || buf[field] === undefined) return;
    const baseQty = currentRow?.achievedQty ?? null;
    const baseAmt = currentRow?.achievedAmount ?? null;
    const baseNotes = currentRow?.notes ?? null;
    const nextQty = buf.achievedQty !== undefined ? numOrNull(buf.achievedQty) : baseQty;
    const nextAmt = buf.achievedAmount !== undefined ? numOrNull(buf.achievedAmount) : baseAmt;
    const nextNotes = buf.notes !== undefined ? buf.notes : baseNotes;
    if (nextQty === baseQty && nextAmt === baseAmt && nextNotes === baseNotes) return;
    upsertActual.mutate({ planItemId, achievedQty: nextQty, achievedAmount: nextAmt, notes: nextNotes ?? null });
  }

  // ── totals ──────────────────────────────────────────────────────────────

  const planTotal = useMemo(
    () => plan.reduce((acc, p) => acc + (p.planAmount ?? 0), 0),
    [plan],
  );

  return (
    <div className="p-6">
      <TabTip
        title="Section G — General Expenses"
        description="Project-level monthly overheads from the DBS Excel PRE sheet (Electricity, Rent, Insurance, …). Edit per-line plan amounts above; log monthly actuals below. Backend prorates the month total across each day so the PM DBS view stays consistent."
      />

      <h1 className="mb-4 font-display text-3xl font-semibold text-charcoal">General Expenses</h1>

      {(planError || actualsError) && (
        <div className="mb-4 text-danger">
          {getErrorMessage(planError ?? actualsError, "Failed to load general expenses")}
        </div>
      )}

      {/* Plan grid */}
      <section className="mb-8 rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <div className="mb-3 flex items-baseline justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-widest text-slate">Plan items (20 seeded defaults)</h2>
          <span className="text-sm text-slate">Plan total: <strong className="text-charcoal">{fmt(planTotal)}</strong></span>
        </div>
        {planLoading ? (
          <div className="py-8 text-center text-slate">Loading plan…</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-hairline text-left text-xs uppercase tracking-wider text-slate">
                  <th className="px-2 py-2 w-10">#</th>
                  <th className="px-2 py-2">Description</th>
                  <th className="px-2 py-2 w-20">Unit</th>
                  <th className="px-2 py-2 w-24 text-right">Rate</th>
                  <th className="px-2 py-2 w-24 text-right">Plan Qty</th>
                  <th className="px-2 py-2 w-32 text-right">Plan Amount</th>
                  <th className="px-2 py-2 w-32">Formula</th>
                </tr>
              </thead>
              <tbody>
                {plan.map((item) => {
                  const buf = planEdits[item.id] ?? {};
                  return (
                    <tr key={item.id} className="border-b border-hairline/40 hover:bg-surface-hover/30">
                      <td className="px-2 py-1.5 text-slate">{item.sortOrder}</td>
                      <td className="px-2 py-1.5 text-charcoal">{item.description}</td>
                      <td className="px-2 py-1.5 text-slate">{item.unit}</td>
                      <td className="px-2 py-1.5 text-right">
                        <input
                          type="number"
                          step="0.01"
                          className="w-full rounded border border-hairline bg-ivory px-1.5 py-0.5 text-right"
                          value={buf.rate ?? (item.rate ?? "")}
                          onChange={(e) =>
                            setPlanEdits((s) => ({ ...s, [item.id]: { ...s[item.id], rate: e.target.value } }))
                          }
                          onBlur={() => commitPlanEdit(item, "rate")}
                        />
                      </td>
                      <td className="px-2 py-1.5 text-right">
                        <input
                          type="number"
                          step="0.01"
                          className="w-full rounded border border-hairline bg-ivory px-1.5 py-0.5 text-right"
                          value={buf.planQty ?? (item.planQty ?? "")}
                          onChange={(e) =>
                            setPlanEdits((s) => ({ ...s, [item.id]: { ...s[item.id], planQty: e.target.value } }))
                          }
                          onBlur={() => commitPlanEdit(item, "planQty")}
                        />
                      </td>
                      <td className="px-2 py-1.5 text-right">
                        <input
                          type="number"
                          step="0.01"
                          className="w-full rounded border border-hairline bg-ivory px-1.5 py-0.5 text-right"
                          value={buf.planAmount ?? (item.planAmount ?? "")}
                          onChange={(e) =>
                            setPlanEdits((s) => ({ ...s, [item.id]: { ...s[item.id], planAmount: e.target.value } }))
                          }
                          onBlur={() => commitPlanEdit(item, "planAmount")}
                        />
                      </td>
                      <td className="px-2 py-1.5 text-xs text-slate">
                        {item.formulaType === "PCT_CONTRACT_VALUE"
                          ? `${((item.formulaPct ?? 0) * 100).toFixed(3)} % of CV`
                          : "—"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Monthly actuals */}
      <section className="rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <div className="mb-3 flex items-center justify-between gap-4">
          <h2 className="text-sm font-semibold uppercase tracking-widest text-slate">Monthly actuals</h2>
          <div className="flex items-center gap-3">
            <label className="text-sm text-slate">Month</label>
            <input
              type="month"
              className="rounded border border-hairline bg-ivory px-2 py-1 text-sm"
              value={formatInputMonth(yearMonth)}
              onChange={(e) => setYearMonth(parseInputMonth(e.target.value))}
            />
            <span className="text-sm text-slate">
              Total: <strong className="text-charcoal">{fmt(actuals?.monthlyTotal)}</strong>
            </span>
          </div>
        </div>
        {actualsLoading ? (
          <div className="py-8 text-center text-slate">Loading actuals…</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-hairline text-left text-xs uppercase tracking-wider text-slate">
                  <th className="px-2 py-2 w-10">#</th>
                  <th className="px-2 py-2">Description</th>
                  <th className="px-2 py-2 w-32 text-right">Plan Amount</th>
                  <th className="px-2 py-2 w-24 text-right">Achieved Qty</th>
                  <th className="px-2 py-2 w-32 text-right">Achieved Amount</th>
                  <th className="px-2 py-2">Notes</th>
                </tr>
              </thead>
              <tbody>
                {(actuals?.rows ?? []).map((row) => {
                  const buf = actualEdits[row.planItem.id] ?? {};
                  const baseQty = row.actual?.achievedQty ?? null;
                  const baseAmt = row.actual?.achievedAmount ?? null;
                  const baseNotes = row.actual?.notes ?? "";
                  return (
                    <tr key={row.planItem.id} className="border-b border-hairline/40 hover:bg-surface-hover/30">
                      <td className="px-2 py-1.5 text-slate">{row.planItem.sortOrder}</td>
                      <td className="px-2 py-1.5 text-charcoal">{row.planItem.description}</td>
                      <td className="px-2 py-1.5 text-right text-slate">{fmt(row.planItem.planAmount)}</td>
                      <td className="px-2 py-1.5 text-right">
                        <input
                          type="number"
                          step="0.01"
                          className="w-full rounded border border-hairline bg-ivory px-1.5 py-0.5 text-right"
                          value={buf.achievedQty ?? (baseQty ?? "")}
                          onChange={(e) =>
                            setActualEdits((s) => ({
                              ...s,
                              [row.planItem.id]: { ...s[row.planItem.id], achievedQty: e.target.value },
                            }))
                          }
                          onBlur={() => commitActualEdit(row.planItem.id, "achievedQty", row.actual)}
                        />
                      </td>
                      <td className="px-2 py-1.5 text-right">
                        <input
                          type="number"
                          step="0.01"
                          className="w-full rounded border border-hairline bg-ivory px-1.5 py-0.5 text-right"
                          value={buf.achievedAmount ?? (baseAmt ?? "")}
                          onChange={(e) =>
                            setActualEdits((s) => ({
                              ...s,
                              [row.planItem.id]: { ...s[row.planItem.id], achievedAmount: e.target.value },
                            }))
                          }
                          onBlur={() => commitActualEdit(row.planItem.id, "achievedAmount", row.actual)}
                        />
                      </td>
                      <td className="px-2 py-1.5">
                        <input
                          type="text"
                          className="w-full rounded border border-hairline bg-ivory px-1.5 py-0.5"
                          value={buf.notes ?? baseNotes}
                          onChange={(e) =>
                            setActualEdits((s) => ({
                              ...s,
                              [row.planItem.id]: { ...s[row.planItem.id], notes: e.target.value },
                            }))
                          }
                          onBlur={() => commitActualEdit(row.planItem.id, "notes", row.actual)}
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

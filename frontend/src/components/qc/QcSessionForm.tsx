"use client";

import { useEffect, useMemo, useState } from "react";
import { ArrowRight, FlaskConical, Plus, Trash2 } from "lucide-react";
import { Drawer } from "@/components/common/Drawer";
import type { QcSession, QcSessionRequest, QcTestItemRow, QcTestType } from "@/lib/types/qc";
import type { SelectOption } from "@/components/common/SearchableSelect";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { cn } from "@/lib/utils/cn";

interface Props {
  open: boolean;
  onClose: () => void;
  projectId: string;
  editing: QcSession | null;
  activityOptions: SelectOption[];
  testTypeOptions: QcTestType[];
  onSave: (req: QcSessionRequest) => Promise<void>;
}

const OUTCOMES = ["PASS", "FAIL", "REPEAT"] as const;

const OUTCOME_STYLE = {
  PASS: {
    active: "bg-emerald text-white shadow-sm",
    inactive: "text-emerald/70 hover:bg-emerald/10 border border-emerald/30",
  },
  FAIL: {
    active: "bg-burgundy text-white shadow-sm",
    inactive: "text-burgundy/70 hover:bg-burgundy/10 border border-burgundy/30",
  },
  REPEAT: {
    active: "bg-bronze-warn text-white shadow-sm",
    inactive: "text-bronze-warn/70 hover:bg-bronze-warn/10 border border-bronze-warn/30",
  },
} as const;

const blankItem = (): QcTestItemRow => ({
  testTypeId: "",
  sampleRefNo: null,
  testResult: null,
  requiredIrc: null,
  outcome: "PASS",
  labInspector: null,
});

const inputCls =
  "w-full rounded border border-hairline bg-white dark:bg-[#2A2520] px-2.5 py-1.5 text-sm text-charcoal placeholder:text-ash focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/30 transition";

const numInputCls = cn(inputCls, "text-right tabular-nums");

function passStatus(result: number | null | undefined, required: number | null | undefined): "over" | "under" | null {
  if (result == null || required == null) return null;
  return result >= required ? "over" : "under";
}

export function QcSessionForm({
  open,
  onClose,
  editing,
  activityOptions,
  testTypeOptions,
  onSave,
}: Props) {
  const [activityId, setActivityId] = useState("");
  const [activityName, setActivityName] = useState("");
  const [testDate, setTestDate] = useState(new Date().toISOString().split("T")[0]);
  const [chainageFrom, setChainageFrom] = useState("");
  const [chainageTo, setChainageTo] = useState("");
  const [items, setItems] = useState<QcTestItemRow[]>([blankItem()]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (editing) {
      setActivityId(editing.activityId);
      setActivityName(editing.activityName);
      setTestDate(editing.testDate);
      setChainageFrom(editing.chainageFrom ?? "");
      setChainageTo(editing.chainageTo ?? "");
      setItems(
        editing.items.length > 0
          ? editing.items.map((i) => ({
              testTypeId: i.testTypeId,
              sampleRefNo: i.sampleRefNo,
              testResult: i.testResult,
              requiredIrc: i.requiredIrc,
              outcome: i.outcome,
              labInspector: i.labInspector,
            }))
          : [blankItem()]
      );
    } else {
      setActivityId("");
      setActivityName("");
      setTestDate(new Date().toISOString().split("T")[0]);
      setChainageFrom("");
      setChainageTo("");
      setItems([blankItem()]);
    }
    setError(null);
  }, [editing, open]);

  const handleActivityChange = (id: string) => {
    setActivityId(id);
    setActivityName(activityOptions.find((o) => o.value === id)?.label ?? "");
  };

  const updateItem = (idx: number, patch: Partial<QcTestItemRow>) =>
    setItems((prev) => prev.map((r, i) => (i === idx ? { ...r, ...patch } : r)));

  const handleTypeChange = (idx: number, typeId: string) => {
    const type = testTypeMap.get(typeId);
    updateItem(idx, { testTypeId: typeId, requiredIrc: type?.ircThreshold ?? null });
  };

  const addRow = () => setItems((prev) => [...prev, blankItem()]);
  const removeRow = (idx: number) => setItems((prev) => prev.filter((_, i) => i !== idx));

  const testTypeSelectOptions = useMemo<SelectOption[]>(
    () => testTypeOptions.map((t) => ({ value: t.id, label: t.unit ? `${t.name} (${t.unit})` : t.name })),
    [testTypeOptions]
  );

  const testTypeMap = useMemo(
    () => new Map(testTypeOptions.map((t) => [t.id, t])),
    [testTypeOptions]
  );

  const passCount = items.filter((r) => r.outcome === "PASS").length;
  const failCount = items.filter((r) => r.outcome === "FAIL").length;
  const repeatCount = items.filter((r) => r.outcome === "REPEAT").length;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activityId || !testDate) {
      setError("Activity and Test Date are required.");
      return;
    }
    if (items.some((r) => !r.testTypeId)) {
      setError("Each test row needs a Test Type.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave({
        activityId,
        activityName,
        testDate,
        chainageFrom: chainageFrom || null,
        chainageTo: chainageTo || null,
        items,
      });
      onClose();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to save.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={editing ? "Edit QC Session" : "New QC Session"}
      widthClass="max-w-5xl"
    >
      <form onSubmit={handleSubmit} className="flex h-full flex-col">
        {/* ── Session header card ── */}
        <div className="border-b border-hairline bg-parchment/40 px-6 py-4">
          <div className="mb-3 flex items-center gap-2">
            <FlaskConical className="h-4 w-4 text-gold-deep" />
            <span className="text-xs font-semibold uppercase tracking-widest text-slate">
              Session Details
            </span>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-12">
            {/* Activity — takes most space */}
            <div className="sm:col-span-5">
              <label className="mb-1 block text-xs font-medium text-slate">
                Activity <span className="text-burgundy">*</span>
              </label>
              <SearchableSelect
                options={activityOptions}
                value={activityId}
                onChange={handleActivityChange}
                placeholder="Select activity…"
              />
            </div>

            {/* Test Date */}
            <div className="sm:col-span-3">
              <label className="mb-1 block text-xs font-medium text-slate">
                Test Date <span className="text-burgundy">*</span>
              </label>
              <input
                type="date"
                value={testDate}
                onChange={(e) => setTestDate(e.target.value)}
                className={inputCls}
              />
            </div>

            {/* Chainage from → to */}
            <div className="sm:col-span-4">
              <label className="mb-1 block text-xs font-medium text-slate">Chainage</label>
              <div className="flex items-center gap-1.5">
                <input
                  type="text"
                  value={chainageFrom}
                  onChange={(e) => setChainageFrom(e.target.value)}
                  placeholder="45+000"
                  className={inputCls}
                />
                <ArrowRight className="h-3.5 w-3.5 shrink-0 text-ash" />
                <input
                  type="text"
                  value={chainageTo}
                  onChange={(e) => setChainageTo(e.target.value)}
                  placeholder="46+000"
                  className={inputCls}
                />
              </div>
            </div>
          </div>
        </div>

        {/* ── Grid area — scrollable ── */}
        <div className="flex-1 overflow-auto">
          {/* Grid toolbar */}
          <div className="flex items-center justify-between border-b border-hairline bg-paper px-6 py-2.5">
            <div className="flex items-center gap-3">
              <span className="text-sm font-semibold text-charcoal">Test Entries</span>
              {items.length > 0 && (
                <div className="flex gap-2 text-xs">
                  <span className="rounded-full bg-emerald/10 px-2 py-0.5 font-medium text-emerald">
                    {passCount} Pass
                  </span>
                  {failCount > 0 && (
                    <span className="rounded-full bg-burgundy/10 px-2 py-0.5 font-medium text-burgundy">
                      {failCount} Fail
                    </span>
                  )}
                  {repeatCount > 0 && (
                    <span className="rounded-full bg-bronze-warn/10 px-2 py-0.5 font-medium text-bronze-warn">
                      {repeatCount} Repeat
                    </span>
                  )}
                </div>
              )}
            </div>
            <button
              type="button"
              onClick={addRow}
              className="inline-flex items-center gap-1.5 rounded-md bg-gold px-3 py-1.5 text-xs font-semibold text-gold-ink hover:bg-gold-deep transition"
            >
              <Plus className="h-3.5 w-3.5" />
              Add Row
            </button>
          </div>

          {/* Spreadsheet table */}
          <div className="overflow-x-auto">
            <table className="w-full min-w-[780px] border-collapse text-sm">
              <thead className="sticky top-0 z-10">
                <tr className="border-b-2 border-gold/30 bg-charcoal/90 dark:bg-parchment text-xs font-semibold uppercase tracking-wider text-white/80">
                  <th className="w-8 px-3 py-2.5 text-center text-white/40">#</th>
                  <th className="px-3 py-2.5 text-left" style={{ minWidth: 210 }}>Test Type</th>
                  <th className="px-3 py-2.5 text-left" style={{ minWidth: 130 }}>Sample Ref</th>
                  <th className="px-3 py-2.5 text-right" style={{ minWidth: 100 }}>Result</th>
                  <th className="px-3 py-2.5 text-right" style={{ minWidth: 120 }}>IRC Spec</th>
                  <th className="px-3 py-2.5 text-center" style={{ minWidth: 190 }}>Outcome</th>
                  <th className="px-3 py-2.5 text-left" style={{ minWidth: 160 }}>Lab / Inspector</th>
                  <th className="w-10 px-2 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {items.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="py-12 text-center text-sm text-ash">
                      No test entries yet. Click "Add Row" to start recording.
                    </td>
                  </tr>
                ) : (
                  items.map((row, idx) => {
                    const status = passStatus(row.testResult, row.requiredIrc);
                    const isEven = idx % 2 === 0;
                    return (
                      <tr
                        key={idx}
                        className={cn(
                          "group border-b border-hairline/60 transition-colors",
                          isEven ? "bg-white dark:bg-[#161616]" : "bg-ivory/40 dark:bg-[#1E1E1E]",
                          "hover:bg-gold-tint/20"
                        )}
                      >
                        {/* Row number */}
                        <td className="px-3 py-2 text-center">
                          <span className="text-xs font-mono text-ash">{idx + 1}</span>
                        </td>

                        {/* Test Type */}
                        <td className="px-2 py-1.5">
                          <SearchableSelect
                            options={testTypeSelectOptions}
                            value={row.testTypeId}
                            onChange={(id) => handleTypeChange(idx, id)}
                            placeholder="— Select type —"
                            className={cn(!row.testTypeId && "ring-1 ring-burgundy/40")}
                          />
                        </td>

                        {/* Sample Ref */}
                        <td className="px-2 py-1.5">
                          <input
                            type="text"
                            value={row.sampleRefNo ?? ""}
                            onChange={(e) => updateItem(idx, { sampleRefNo: e.target.value || null })}
                            placeholder="QC-2026-001"
                            className={inputCls}
                          />
                        </td>

                        {/* Result — coloured if vs IRC */}
                        <td className="px-2 py-1.5">
                          <input
                            type="number"
                            step="0.0001"
                            value={row.testResult ?? ""}
                            onChange={(e) =>
                              updateItem(idx, { testResult: e.target.value ? Number(e.target.value) : null })
                            }
                            className={cn(
                              numInputCls,
                              status === "over" && "border-emerald/40 bg-emerald/5 text-emerald",
                              status === "under" && "border-burgundy/40 bg-burgundy/5 text-burgundy"
                            )}
                          />
                        </td>

                        {/* IRC Spec */}
                        <td className="px-2 py-1.5">
                          <input
                            type="number"
                            step="0.0001"
                            value={row.requiredIrc ?? ""}
                            onChange={(e) =>
                              updateItem(idx, { requiredIrc: e.target.value ? Number(e.target.value) : null })
                            }
                            className={numInputCls}
                          />
                        </td>

                        {/* Outcome chips */}
                        <td className="px-2 py-1.5">
                          <div className="flex justify-center gap-1">
                            {OUTCOMES.map((o) => (
                              <button
                                key={o}
                                type="button"
                                onClick={() => updateItem(idx, { outcome: o })}
                                className={cn(
                                  "rounded px-2.5 py-1 text-xs font-semibold transition-all",
                                  row.outcome === o
                                    ? OUTCOME_STYLE[o].active
                                    : OUTCOME_STYLE[o].inactive
                                )}
                              >
                                {o}
                              </button>
                            ))}
                          </div>
                        </td>

                        {/* Lab / Inspector */}
                        <td className="px-2 py-1.5">
                          <input
                            type="text"
                            value={row.labInspector ?? ""}
                            onChange={(e) =>
                              updateItem(idx, { labInspector: e.target.value || null })
                            }
                            placeholder="Lab or inspector name"
                            className={inputCls}
                          />
                        </td>

                        {/* Delete row */}
                        <td className="px-2 py-1.5 text-center">
                          <button
                            type="button"
                            onClick={() => removeRow(idx)}
                            disabled={items.length === 1}
                            className="rounded p-1.5 text-ash opacity-0 transition-all group-hover:opacity-100 hover:bg-burgundy/10 hover:text-burgundy disabled:pointer-events-none disabled:opacity-0"
                            aria-label="Remove row"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>

              {/* Summary footer row */}
              {items.length > 1 && (
                <tfoot>
                  <tr className="border-t-2 border-hairline bg-parchment/60 text-xs font-semibold text-slate">
                    <td colSpan={3} className="px-3 py-2 text-right">
                      {items.length} tests total
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums text-charcoal">
                      {items.some((r) => r.testResult != null)
                        ? (
                            items.reduce((s, r) => s + (r.testResult ?? 0), 0) /
                            items.filter((r) => r.testResult != null).length
                          ).toFixed(2)
                        : "—"}
                      <span className="ml-1 font-normal text-ash">avg</span>
                    </td>
                    <td />
                    <td className="px-3 py-2 text-center">
                      <span className="text-emerald">{passCount}P</span>
                      {" · "}
                      <span className="text-burgundy">{failCount}F</span>
                      {" · "}
                      <span className="text-bronze-warn">{repeatCount}R</span>
                    </td>
                    <td colSpan={2} />
                  </tr>
                </tfoot>
              )}
            </table>
          </div>
        </div>

        {/* ── Sticky footer ── */}
        <div className="border-t border-hairline bg-paper px-6 py-4">
          {error && (
            <div className="mb-3 rounded-md border border-burgundy/20 bg-burgundy/8 px-3 py-2 text-sm text-burgundy">
              {error}
            </div>
          )}
          <div className="flex items-center justify-between">
            <p className="text-xs text-ash">
              {items.length} test{items.length === 1 ? "" : "s"} · {passCount} pass
              {failCount > 0 && ` · ${failCount} fail`}
            </p>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={onClose}
                className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-medium text-charcoal hover:bg-ivory transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={saving}
                className="rounded-md bg-gold px-5 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep disabled:opacity-50 transition"
              >
                {saving ? "Saving…" : editing ? "Update Session" : "Save Session"}
              </button>
            </div>
          </div>
        </div>
      </form>
    </Drawer>
  );
}

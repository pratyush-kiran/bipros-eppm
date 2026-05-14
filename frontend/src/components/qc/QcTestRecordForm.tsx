"use client";

import { useMemo, useState } from "react";
import { Drawer } from "@/components/common/Drawer";
import type { QcTestRecord, QcTestRecordRequest, QcTestType } from "@/lib/types/qc";
import type { SelectOption } from "@/components/common/SearchableSelect";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { cn } from "@/lib/utils/cn";

interface Props {
  open: boolean;
  onClose: () => void;
  projectId: string;
  editing: QcTestRecord | null;
  activityOptions: SelectOption[];
  testTypeOptions: QcTestType[];
  onSave: (req: QcTestRecordRequest) => Promise<void>;
}

const OUTCOMES = ["PASS", "FAIL", "REPEAT"] as const;

export function QcTestRecordForm({
  open,
  onClose,
  editing,
  activityOptions,
  testTypeOptions,
  onSave,
}: Props) {
  const [activityId, setActivityId] = useState<string>("");
  const [activityName, setActivityName] = useState<string>("");
  const [testTypeId, setTestTypeId] = useState<string>("");
  const [testDate, setTestDate] = useState<string>("");
  const [chainage, setChainage] = useState<string>("");
  const [sampleRefNo, setSampleRefNo] = useState<string>("");
  const [testResult, setTestResult] = useState<string>("");
  const [requiredIrc, setRequiredIrc] = useState<string>("");
  const [outcome, setOutcome] = useState<string>("PASS");
  const [labInspector, setLabInspector] = useState<string>("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useMemo(() => {
    if (editing) {
      setActivityId(editing.activityId);
      setActivityName(editing.activityName);
      setTestTypeId(editing.testTypeId);
      setTestDate(editing.testDate);
      setChainage(editing.chainage ?? "");
      setSampleRefNo(editing.sampleRefNo ?? "");
      setTestResult(editing.testResult?.toString() ?? "");
      setRequiredIrc(editing.requiredIrc?.toString() ?? "");
      setOutcome(editing.outcome);
      setLabInspector(editing.labInspector ?? "");
    } else {
      setActivityId("");
      setActivityName("");
      setTestTypeId("");
      setTestDate(new Date().toISOString().split("T")[0]);
      setChainage("");
      setSampleRefNo("");
      setTestResult("");
      setRequiredIrc("");
      setOutcome("PASS");
      setLabInspector("");
    }
    setError(null);
  }, [editing]);

  const selectedType = useMemo(
    () => testTypeOptions.find((t) => t.id === testTypeId),
    [testTypeId, testTypeOptions]
  );

  const handleTypeChange = (id: string) => {
    setTestTypeId(id);
    const type = testTypeOptions.find((t) => t.id === id);
    if (type && type.ircThreshold != null && !editing) {
      setRequiredIrc(String(type.ircThreshold));
    }
  };

  const handleActivityChange = (id: string) => {
    setActivityId(id);
    const opt = activityOptions.find((o) => o.value === id);
    setActivityName(opt?.label ?? "");
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activityId || !activityName || !testTypeId || !testDate || !outcome) {
      setError("Please fill in all required fields.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave({
        activityId,
        activityName,
        testTypeId,
        testDate,
        chainage: chainage || null,
        sampleRefNo: sampleRefNo || null,
        testResult: testResult ? Number(testResult) : null,
        requiredIrc: requiredIrc ? Number(requiredIrc) : null,
        outcome: outcome as "PASS" | "FAIL" | "REPEAT",
        labInspector: labInspector || null,
      });
      onClose();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to save QC record.";
      setError(msg);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer open={open} onClose={onClose} title={editing ? "Edit QC Record" : "Add QC Record"} widthClass="max-w-2xl">
      <form onSubmit={handleSubmit} className="space-y-4 p-1">
        {error && <div className="rounded-md bg-burgundy/10 p-3 text-sm text-burgundy">{error}</div>}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Activity <span className="text-burgundy">*</span>
            </label>
            <SearchableSelect
              options={activityOptions}
              value={activityId}
              onChange={handleActivityChange}
              placeholder="Select activity..."
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Test Type <span className="text-burgundy">*</span>
            </label>
            <select
              value={testTypeId}
              onChange={(e) => handleTypeChange(e.target.value)}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            >
              <option value="">Select test type...</option>
              {testTypeOptions.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} {t.unit ? `(${t.unit})` : ""}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Test Date <span className="text-burgundy">*</span>
            </label>
            <input
              type="date"
              value={testDate}
              onChange={(e) => setTestDate(e.target.value)}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Chainage
            </label>
            <input
              type="text"
              value={chainage}
              onChange={(e) => setChainage(e.target.value)}
              placeholder="e.g. 46+400"
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Sample Ref No
            </label>
            <input
              type="text"
              value={sampleRefNo}
              onChange={(e) => setSampleRefNo(e.target.value)}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Test Result
            </label>
            <input
              type="number"
              step="0.0001"
              value={testResult}
              onChange={(e) => setTestResult(e.target.value)}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Required IRC
            </label>
            <input
              type="number"
              step="0.0001"
              value={requiredIrc}
              onChange={(e) => setRequiredIrc(e.target.value)}
              placeholder={selectedType?.ircThreshold?.toString() ?? ""}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Outcome <span className="text-burgundy">*</span>
            </label>
            <div className="flex gap-2">
              {OUTCOMES.map((o) => (
                <button
                  key={o}
                  type="button"
                  onClick={() => setOutcome(o)}
                  className={cn(
                    "rounded-md border px-3 py-2 text-xs font-semibold transition",
                    outcome === o
                      ? o === "PASS"
                        ? "border-success bg-success/10 text-success"
                        : o === "FAIL"
                          ? "border-burgundy bg-burgundy/10 text-burgundy"
                          : "border-bronze-warn bg-bronze-warn/10 text-bronze-warn"
                      : "border-hairline bg-paper text-slate hover:bg-ivory"
                  )}
                >
                  {o}
                </button>
              ))}
            </div>
          </div>

          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
              Lab / Inspector
            </label>
            <input
              type="text"
              value={labInspector}
              onChange={(e) => setLabInspector(e.target.value)}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
            />
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 pt-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-semibold text-charcoal hover:bg-ivory"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep disabled:opacity-50"
          >
            {saving ? "Saving…" : editing ? "Update" : "Save"}
          </button>
        </div>
      </form>
    </Drawer>
  );
}

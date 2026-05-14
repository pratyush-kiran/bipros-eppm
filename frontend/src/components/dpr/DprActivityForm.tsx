"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { AlertTriangle, Briefcase, HardHat, Info, Package, Save } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import { chainageLabel, parseChainage } from "@/lib/format/chainage";
import { dprApi, type DprVoicePatch } from "@/lib/api/dprApi";
import type {
  DailyProgressReportResponse,
  DprApprovalStatus,
  DprAttachment,
  DprBaseFields,
  DprEquipmentRow,
  DprIssueRow,
  DprManpowerRow,
  DprMaterialRow,
  Shift,
  Side,
} from "@/lib/types/dpr";
import { ManpowerGrid } from "./ManpowerGrid";
import { EquipmentGrid } from "./EquipmentGrid";
import { MaterialGrid } from "./MaterialGrid";
import { IssuesGrid } from "./IssuesGrid";
import { SafetyDelaySection } from "./SafetyDelaySection";
import { DprTotalsBar } from "./DprTotalsBar";
import { DprPhotosSection, type PendingPhoto } from "./DprPhotosSection";
import { DprVoiceAssistant } from "./DprVoiceAssistant";

type Tab = "manpower" | "equipment" | "material" | "issues";

interface FormState extends DprBaseFields {
  chainageFromRaw: string;
  chainageToRaw: string;
}

interface Props {
  projectId: string;
  editing: DailyProgressReportResponse | null;
  defaultDate: string;
  supervisorOptions: SelectOption[];
  /** {@code value=activityId, label=name} so we can hand the id down to the resource picker. */
  activityOptions: SelectOption[];
  /** id → name lookup used when rehydrating an editing DPR (server stores activityId; UI shows name). */
  activityNameById: Map<string, string>;
  /** lowercased name → id, used for legacy DPRs whose server payload only carries activityName. */
  activityIdByName: Map<string, string>;
  /**
   * activityId → its assigned supervisor (from `Activity.responsibleResourceId`). Powers the
   * cross-filter / auto-fill between the Supervisor and Activity pickers. `null` means the
   * activity has no supervisor assigned yet.
   */
  supervisorByActivityId: Map<string, { id: string; name: string } | null>;
  /**
   * activityId → the linked WorkActivity's `default_unit`. The form auto-fills the unit
   * dropdown when an activity is picked so DPRs default to the activity's unit instead of
   * the hardcoded "Cum". When the user manually overrides, an inline warning appears.
   */
  defaultUnitByActivityId: Map<string, string | null>;
  boqOptions: SelectOption[];
  onCancel: () => void;
  /**
   * Saves the DPR and returns the persisted record so the form can chain photo uploads against
   * the freshly-minted id. The legacy {@code Promise<void>} contract is still acceptable: when no
   * record is returned, photos are simply skipped (with an inline notice for the user).
   */
  onSave: (payload: DprBaseFields) => Promise<DailyProgressReportResponse | void>;
}

const todayIso = () => new Date().toISOString().split("T")[0];

const SUPERVISOR_OTHER = "__other__";

const SIDE_OPTS: Array<{ value: Side; label: string }> = [
  { value: "LHS", label: "LHS" },
  { value: "RHS", label: "RHS" },
  { value: "CENTER", label: "Center" },
];
const SHIFT_OPTS: Array<{ value: Shift; label: string }> = [
  { value: "DAY", label: "Day" },
  { value: "NIGHT", label: "Night" },
];
const STATUS_OPTS: Array<{ value: DprApprovalStatus; label: string }> = [
  { value: "DRAFT", label: "Draft" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
];
import { STANDARD_UNITS, unitOptionsWithFallback } from "@/lib/constants/units";
const UNIT_OPTS = STANDARD_UNITS;
const WEATHER_OPTS = ["Clear", "Cloudy", "Rain", "Hot", "Cold", "Windy"];

const initialState = (
  editing: DailyProgressReportResponse | null,
  defaultDate: string
): FormState => {
  if (editing) {
    return {
      reportDate: editing.reportDate,
      supervisorResourceId: editing.supervisorResourceId ?? null,
      supervisorName: editing.supervisorName,
      chainageFromM: editing.chainageFromM,
      chainageToM: editing.chainageToM,
      chainageFromRaw: editing.chainageFromM != null ? chainageLabel(editing.chainageFromM) : "",
      chainageToRaw: editing.chainageToM != null ? chainageLabel(editing.chainageToM) : "",
      activityId: editing.activityId ?? null,
      activityName: editing.activityName,
      wbsNodeId: editing.wbsNodeId,
      boqItemNo: editing.boqItemNo,
      unit: editing.unit,
      qtyExecuted: editing.qtyExecuted,
      weatherCondition: editing.weatherCondition,
      remarks: editing.remarks,
      side: editing.side ?? null,
      landmark: editing.landmark,
      startTime: editing.startTime,
      endTime: editing.endTime,
      shift: editing.shift ?? null,
      approvalStatus: editing.approvalStatus ?? "DRAFT",
      contractorName: editing.contractorName,
      delayReason: editing.delayReason,
      safetyObservation: editing.safetyObservation,
      safetyIncidentType: editing.safetyIncidentType ?? "NONE",
      manpower: editing.manpower ?? [],
      equipment: editing.equipment ?? [],
      materials: editing.materials ?? [],
      issues: editing.issues ?? [],
    };
  }
  return {
    reportDate: defaultDate || todayIso(),
    supervisorResourceId: null,
    supervisorName: "",
    chainageFromM: null,
    chainageToM: null,
    chainageFromRaw: "",
    chainageToRaw: "",
    activityId: null,
    activityName: "",
    wbsNodeId: null,
    boqItemNo: null,
    unit: "Cum",
    qtyExecuted: 0,
    weatherCondition: null,
    remarks: null,
    side: null,
    landmark: null,
    startTime: null,
    endTime: null,
    shift: "DAY",
    approvalStatus: "DRAFT",
    contractorName: null,
    delayReason: null,
    safetyObservation: null,
    safetyIncidentType: "NONE",
    manpower: [],
    equipment: [],
    materials: [],
    issues: [],
  };
};

export function DprActivityForm({
  projectId,
  editing,
  defaultDate,
  supervisorOptions,
  activityOptions,
  activityNameById,
  activityIdByName,
  supervisorByActivityId,
  defaultUnitByActivityId,
  boqOptions,
  onCancel,
  onSave,
}: Props) {
  const [state, setState] = useState<FormState>(() => {
    const s = initialState(editing, defaultDate);
    // Editing path: backend may not yet carry activityId on legacy rows. Resolve from name.
    if (editing && !s.activityId && editing.activityName) {
      const id = activityIdByName.get(editing.activityName.toLowerCase());
      if (id) s.activityId = id;
    }
    return s;
  });
  const [tab, setTab] = useState<Tab>("manpower");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [photoUploadStatus, setPhotoUploadStatus] = useState<string | null>(null);
  const [pendingPhotos, setPendingPhotos] = useState<PendingPhoto[]>([]);
  const [existingPhotos, setExistingPhotos] = useState<DprAttachment[]>(
    () => editing?.attachments ?? []
  );

  // Voice fill needs to read fresh form state from inside async callbacks. Closures captured at
  // render time would go stale across recordings, so we mirror state into a ref instead.
  const stateRef = useRef(state);
  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  const getVoiceState = useCallback(() => {
    const s = stateRef.current;
    return {
      reportDate: s.reportDate,
      supervisorResourceId:
        s.supervisorResourceId === SUPERVISOR_OTHER ? null : s.supervisorResourceId,
      supervisorName: s.supervisorName,
      activityId: s.activityId,
      activityName: s.activityName,
      contractorName: s.contractorName,
      weatherCondition: s.weatherCondition,
      startTime: s.startTime,
      endTime: s.endTime,
      shift: s.shift,
      side: s.side,
      landmark: s.landmark,
      chainageFromM: s.chainageFromM,
      chainageToM: s.chainageToM,
      boqItemNo: s.boqItemNo,
      unit: s.unit,
      qtyExecuted: s.qtyExecuted,
      remarks: s.remarks,
      delayReason: s.delayReason,
      safetyObservation: s.safetyObservation,
      safetyIncidentType: s.safetyIncidentType,
      manpower: s.manpower ?? [],
      equipment: s.equipment ?? [],
      materials: s.materials ?? [],
    };
  }, []);

  /**
   * Merge a backend-returned patch into form state. Non-array fields override only when the patch
   * carries a non-null value. Row arrays (manpower/equipment/material) APPEND — the LLM is told
   * to never re-emit rows the user already typed, so we trust it on append semantics.
   */
  const applyVoicePatch = useCallback((patch: DprVoicePatch) => {
    setState((current) => {
      const next: FormState = { ...current };
      const setIfPresent = <K extends keyof FormState>(key: K, value: FormState[K] | null | undefined) => {
        if (value !== undefined && value !== null) next[key] = value as FormState[K];
      };
      setIfPresent("reportDate", patch.reportDate);
      setIfPresent("supervisorResourceId", patch.supervisorResourceId);
      setIfPresent("supervisorName", patch.supervisorName);
      setIfPresent("activityId", patch.activityId);
      setIfPresent("activityName", patch.activityName);
      setIfPresent("contractorName", patch.contractorName);
      setIfPresent("weatherCondition", patch.weatherCondition);
      setIfPresent("startTime", patch.startTime);
      setIfPresent("endTime", patch.endTime);
      setIfPresent("shift", patch.shift);
      setIfPresent("approvalStatus", patch.approvalStatus);
      setIfPresent("side", patch.side);
      setIfPresent("landmark", patch.landmark);
      setIfPresent("boqItemNo", patch.boqItemNo);
      setIfPresent("unit", patch.unit);
      setIfPresent("qtyExecuted", patch.qtyExecuted);
      setIfPresent("remarks", patch.remarks);
      setIfPresent("delayReason", patch.delayReason);
      setIfPresent("safetyObservation", patch.safetyObservation);
      setIfPresent("safetyIncidentType", patch.safetyIncidentType);
      // Chainage: when the numeric updates, refresh the raw string so the input shows the same.
      if (patch.chainageFromM !== undefined && patch.chainageFromM !== null) {
        next.chainageFromM = patch.chainageFromM;
        next.chainageFromRaw = chainageLabel(patch.chainageFromM);
      }
      if (patch.chainageToM !== undefined && patch.chainageToM !== null) {
        next.chainageToM = patch.chainageToM;
        next.chainageToRaw = chainageLabel(patch.chainageToM);
      }
      if (Array.isArray(patch.manpower) && patch.manpower.length > 0) {
        next.manpower = [
          ...(current.manpower ?? []),
          ...(patch.manpower as unknown as DprManpowerRow[]),
        ];
      }
      if (Array.isArray(patch.equipment) && patch.equipment.length > 0) {
        next.equipment = [
          ...(current.equipment ?? []),
          ...(patch.equipment as unknown as DprEquipmentRow[]),
        ];
      }
      if (Array.isArray(patch.materials) && patch.materials.length > 0) {
        next.materials = [
          ...(current.materials ?? []),
          ...(patch.materials as unknown as DprMaterialRow[]),
        ];
      }
      return next;
    });
  }, []);

  const patch = (delta: Partial<FormState>) => setState((s) => ({ ...s, ...delta }));

  const supervisorPickerValue = state.supervisorResourceId || "";
  const supervisorIsOther = supervisorPickerValue === SUPERVISOR_OTHER;

  /**
   * Activities owned by the currently selected supervisor (per `Activity.responsibleResourceId`).
   * If the user hasn't picked a supervisor — or picked the free-text "Other" — show the full list.
   * If the picked supervisor has zero assigned activities, fall back to showing all activities
   * (the user explicitly asked for "do nothing" in that case; surfacing all is the closest
   * reasonable behavior so the form stays usable).
   */
  const filteredActivityOptions = useMemo(() => {
    if (!state.supervisorResourceId || supervisorIsOther) return activityOptions;
    const filtered = activityOptions.filter((a) => {
      const sup = supervisorByActivityId.get(a.value);
      return sup?.id === state.supervisorResourceId;
    });
    return filtered.length === 0 ? activityOptions : filtered;
  }, [activityOptions, state.supervisorResourceId, supervisorIsOther, supervisorByActivityId]);

  const supervisorHasNoActivities = useMemo(() => {
    if (!state.supervisorResourceId || supervisorIsOther) return false;
    return !activityOptions.some(
      (a) => supervisorByActivityId.get(a.value)?.id === state.supervisorResourceId
    );
  }, [activityOptions, state.supervisorResourceId, supervisorIsOther, supervisorByActivityId]);

  /** Inline mismatch when the picked supervisor isn't the activity's owner. */
  const activitySupervisorMismatch = useMemo(() => {
    if (!state.activityId || !state.supervisorResourceId || supervisorIsOther) return null;
    const sup = supervisorByActivityId.get(state.activityId);
    if (!sup || sup.id === state.supervisorResourceId) return null;
    return sup.name || "another supervisor";
  }, [state.activityId, state.supervisorResourceId, supervisorIsOther, supervisorByActivityId]);

  /** Tab counters reflect rows that will actually be saved (FK picker filled).
   *  Role-only rows have variantId set instead of resourceAssignmentId. */
  const manpowerFilledCount = useMemo(
    () =>
      (state.manpower ?? []).filter(
        (r) => !!r.manpowerRoleRateId || !!r.resourceAssignmentId,
      ).length,
    [state.manpower],
  );
  const equipmentFilledCount = useMemo(
    () =>
      (state.equipment ?? []).filter(
        (r) => !!r.equipmentRoleVariantId || !!r.resourceAssignmentId,
      ).length,
    [state.equipment],
  );
  const materialsFilledCount = useMemo(
    () =>
      (state.materials ?? []).filter(
        (r) => !!r.materialRoleVariantId || !!r.resourceAssignmentId,
      ).length,
    [state.materials],
  );
  const issuesFilledCount = useMemo(
    () => (state.issues ?? []).filter((r) => !!r.title?.trim()).length,
    [state.issues]
  );

  const supervisorAutoFilled = useMemo(() => {
    if (!state.activityId || !state.supervisorResourceId || supervisorIsOther) return false;
    const sup = supervisorByActivityId.get(state.activityId);
    return sup?.id === state.supervisorResourceId;
  }, [state.activityId, state.supervisorResourceId, supervisorIsOther, supervisorByActivityId]);

  /**
   * Activity dropdown change: when rows already exist for the previous activity, prompt to clear
   * them — they reference assignments scoped to the old activity and would fail server validation
   * after a switch. Cancelling the prompt reverts the picker. When the new activity carries an
   * assigned supervisor and the form's supervisor is empty (or "Other"), auto-fill the supervisor
   * — saves a click and surfaces the activity→supervisor relationship that already exists in the
   * domain model.
   */
  const handleActivityChange = (newActivityId: string) => {
    const existingRows =
      (state.manpower?.length ?? 0) +
      (state.equipment?.length ?? 0) +
      (state.materials?.length ?? 0);
    if (newActivityId === state.activityId) return;
    if (existingRows > 0 && state.activityId !== null) {
      const ok = window.confirm(
        `Switching the activity will clear all ${existingRows} manpower / equipment / material row(s). Continue?`
      );
      if (!ok) return;
    }
    const name = activityNameById.get(newActivityId) ?? "";
    const delta: Partial<FormState> = {
      activityId: newActivityId || null,
      activityName: name,
      manpower: [],
      equipment: [],
      materials: [],
    };
    // Auto-fill the unit from the activity's WorkActivity.default_unit. Without this, the form
    // sticks to its hardcoded "Cum" default and DPRs end up with units that don't match the
    // productivity norm — which is exactly what makes Capacity Utilization show 999%.
    const activityUnit = newActivityId
      ? defaultUnitByActivityId.get(newActivityId) ?? null
      : null;
    if (activityUnit && activityUnit.trim().length > 0) {
      delta.unit = activityUnit.trim();
    }
    const sup = newActivityId ? supervisorByActivityId.get(newActivityId) : null;
    const supervisorEmpty = !state.supervisorResourceId || supervisorIsOther;
    if (sup && supervisorEmpty) {
      // Verify the supervisor actually exists in the eligible list before auto-filling — if the
      // activity's snapshot points at someone no longer eligible (e.g. role changed), fall back
      // to leaving the supervisor untouched rather than silently picking an invalid value.
      const match = supervisorOptions.find((s) => s.value === sup.id);
      if (match) {
        delta.supervisorResourceId = sup.id;
        delta.supervisorName = match.label.split(" (")[0];
      }
    }
    patch(delta);
  };

  const handleSupervisorChange = (value: string) => {
    if (value === SUPERVISOR_OTHER) {
      patch({ supervisorResourceId: SUPERVISOR_OTHER, supervisorName: "" });
      return;
    }
    const match = supervisorOptions.find((s) => s.value === value);
    patch({ supervisorResourceId: value || null, supervisorName: match?.label.split(" (")[0] ?? "" });
  };

  const handleChainageBlur = (which: "from" | "to") => {
    const raw = which === "from" ? state.chainageFromRaw : state.chainageToRaw;
    if (!raw) {
      patch(which === "from" ? { chainageFromM: null } : { chainageToM: null });
      return;
    }
    const parsed = parseChainage(raw);
    if (parsed === null) {
      setError(`Chainage ${which} is invalid (expected km+metres, e.g. 145+000)`);
      return;
    }
    setError(null);
    patch(which === "from" ? { chainageFromM: parsed } : { chainageToM: parsed });
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!state.activityName.trim()) return setError("Activity name is required.");
    if (!state.supervisorName.trim()) return setError("Supervisor name is required.");
    if (!state.unit) return setError("Unit is required.");
    if (!state.qtyExecuted || state.qtyExecuted <= 0) return setError("Executed quantity must be > 0.");

    const supervisorResourceId =
      state.supervisorResourceId && state.supervisorResourceId !== SUPERVISOR_OTHER
        ? state.supervisorResourceId
        : null;

    // Drop skeleton rows where the user opened the tab but never picked a role.
    // Role-only model: a row is "real" if it has the variant FK set (manpowerRoleRateId for
    // manpower, equipmentRoleVariantId for equipment, materialRoleVariantId for material).
    // Legacy resourceAssignmentId is still accepted for older / migrated DPRs.
    const manpower = (state.manpower ?? []).filter(
      (r) => !!r.manpowerRoleRateId || !!r.resourceAssignmentId,
    );
    const equipment = (state.equipment ?? []).filter(
      (r) => !!r.equipmentRoleVariantId || !!r.resourceAssignmentId,
    );
    const materials = (state.materials ?? []).filter(
      (r) => !!r.materialRoleVariantId || !!r.resourceAssignmentId,
    );
    // Issues use merge-by-id server-side: rows present in the DB but absent from this
    // payload are deleted, so we must include EVERY issue the user can still see —
    // including ones with no title yet aren't sent (treated as cancelled add).
    const issues = (state.issues ?? []).filter((r) => !!r.title?.trim());

    const payload: DprBaseFields = {
      reportDate: state.reportDate,
      supervisorResourceId,
      supervisorName: state.supervisorName,
      chainageFromM: state.chainageFromM,
      chainageToM: state.chainageToM,
      activityId: state.activityId ?? null,
      activityName: state.activityName,
      wbsNodeId: state.wbsNodeId,
      boqItemNo: state.boqItemNo || null,
      unit: state.unit,
      qtyExecuted: state.qtyExecuted,
      weatherCondition: state.weatherCondition || null,
      remarks: state.remarks || null,
      side: state.side,
      landmark: state.landmark || null,
      startTime: state.startTime || null,
      endTime: state.endTime || null,
      shift: state.shift,
      approvalStatus: state.approvalStatus,
      contractorName: state.contractorName || null,
      delayReason: state.delayReason || null,
      safetyObservation: state.safetyObservation || null,
      safetyIncidentType: state.safetyIncidentType,
      manpower,
      equipment,
      materials,
      issues,
    };

    setSubmitting(true);
    setPhotoUploadStatus(null);
    try {
      const saved = await onSave(payload);
      // Two-step upload: DPR is now persisted (either freshly-created or updated). If the user
      // queued any photos in the drawer, ship them against the saved id. The drawer is closed by
      // calling onCancel() — only after the upload step so the form stays mounted in the meantime.
      let uploadFailed = false;
      if (pendingPhotos.length > 0) {
        const dprId = saved?.id ?? editing?.id ?? null;
        if (!dprId) {
          setPhotoUploadStatus(
            "DPR saved, but the server did not return an id — photos were not uploaded."
          );
          uploadFailed = true;
        } else {
          setPhotoUploadStatus(`Uploading ${pendingPhotos.length} photo(s)…`);
          try {
            await dprApi.uploadPhotos(
              projectId,
              dprId,
              pendingPhotos.map((p) => p.file),
              pendingPhotos.map((p) => p.caption || null)
            );
            pendingPhotos.forEach((p) => URL.revokeObjectURL(p.previewUrl));
            setPendingPhotos([]);
            setPhotoUploadStatus(null);
          } catch (uploadErr: unknown) {
            const msg = uploadErr instanceof Error ? uploadErr.message : "photo upload failed";
            setPhotoUploadStatus(`DPR saved, but photo upload failed: ${msg}. You can retry.`);
            uploadFailed = true;
          }
        }
      }
      // Keep the drawer open if the photo upload failed so the user can retry without re-typing
      // the row. Otherwise close — the parent already invalidated the list query inside onSave.
      if (!uploadFailed) {
        onCancel();
      }
    } catch (err: unknown) {
      // Surface DPR_OVERRUN (hard-block) with the full server-side detail string.
      const axiosErr = err as {
        response?: { data?: { error?: { code?: string; message?: string } } };
      };
      const apiErr = axiosErr?.response?.data?.error;
      if (apiErr?.code === "DPR_OVERRUN") {
        setError(apiErr.message ?? "DPR would exceed planned units for one or more roles.");
      } else {
        const msg = err instanceof Error ? err.message : "Failed to save DPR.";
        setError(msg);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 border-b border-hairline px-5 py-3">
        <Badge variant={editing ? "info" : "gold"} withDot>
          {state.approvalStatus ?? "DRAFT"}
        </Badge>
        {state.shift && (
          <Badge variant="neutral">{state.shift === "DAY" ? "Day shift" : "Night shift"}</Badge>
        )}
        <div className="ml-auto">
          <DprVoiceAssistant
            projectId={projectId}
            dprId={editing?.id ?? null}
            getState={getVoiceState}
            applyPatch={applyVoicePatch}
          />
        </div>
      </div>

      {/* Anchoring row: Supervisor + Activity drive everything below, so they lead. */}
      <div className="grid gap-4 px-5 py-4 md:grid-cols-2">
        <Field label="Supervisor">
          <SearchableSelect
            options={[...supervisorOptions, { value: SUPERVISOR_OTHER, label: "Other (free-text)" }]}
            value={supervisorPickerValue}
            onChange={handleSupervisorChange}
            placeholder="Search supervisor…"
          />
          {supervisorIsOther && (
            <input
              type="text"
              value={state.supervisorName}
              onChange={(e) => patch({ supervisorName: e.target.value })}
              placeholder="Supervisor name"
              className={`mt-2 ${inputCls}`}
              required
            />
          )}
          {supervisorHasNoActivities && (
            <p className="mt-1 inline-flex items-center gap-1 text-xs text-slate">
              <Info className="h-3 w-3" />
              No activities assigned to this supervisor — showing all.
            </p>
          )}
        </Field>
        <Field label="Activity name">
          <SearchableSelect
            options={filteredActivityOptions}
            value={state.activityId ?? ""}
            onChange={handleActivityChange}
            placeholder="Search activity…"
            selectedLabel={state.activityName || undefined}
          />
          {supervisorAutoFilled && (
            <p className="mt-1 inline-flex items-center gap-1 text-xs text-slate">
              <Info className="h-3 w-3" />
              Supervisor auto-filled from this activity.
            </p>
          )}
          {activitySupervisorMismatch && (
            <p className="mt-1 inline-flex items-center gap-1 text-xs text-burgundy">
              <Info className="h-3 w-3" />
              Activity is supervised by {activitySupervisorMismatch}, not the selected supervisor.
            </p>
          )}
        </Field>
      </div>

      {/* Header: timing + state + logistics */}
      <div className="grid gap-4 border-t border-hairline px-5 py-4 md:grid-cols-3">
        <Field label="Date">
          <input
            type="date"
            value={state.reportDate}
            onChange={(e) => patch({ reportDate: e.target.value })}
            className={inputCls}
            required
          />
        </Field>
        <Field label="Shift">
          <select
            value={state.shift ?? ""}
            onChange={(e) => patch({ shift: (e.target.value || null) as Shift | null })}
            className={inputCls}
          >
            <option value="">—</option>
            {SHIFT_OPTS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Approval status">
          <select
            value={state.approvalStatus ?? "DRAFT"}
            onChange={(e) =>
              patch({ approvalStatus: e.target.value as DprApprovalStatus })
            }
            className={inputCls}
          >
            {STATUS_OPTS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Contractor">
          <input
            type="text"
            value={state.contractorName ?? ""}
            onChange={(e) => patch({ contractorName: e.target.value || null })}
            placeholder="Lead contractor"
            className={inputCls}
          />
        </Field>
        <Field label="Weather">
          <select
            value={state.weatherCondition ?? ""}
            onChange={(e) => patch({ weatherCondition: e.target.value || null })}
            className={inputCls}
          >
            <option value="">—</option>
            {WEATHER_OPTS.map((w) => (
              <option key={w} value={w}>
                {w}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Start time">
          <input
            type="time"
            value={state.startTime ?? ""}
            onChange={(e) => patch({ startTime: e.target.value || null })}
            className={inputCls}
          />
        </Field>
        <Field label="End time">
          <input
            type="time"
            value={state.endTime ?? ""}
            onChange={(e) => patch({ endTime: e.target.value || null })}
            className={inputCls}
          />
        </Field>
      </div>

      {/* Activity details */}
      <div className="border-t border-hairline px-5 py-4">
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-charcoal">
          <Briefcase className="h-4 w-4 text-gold-deep" />
          Activity details
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          <Field label="BOQ item">
            <SearchableSelect
              options={boqOptions}
              value={state.boqItemNo ?? ""}
              onChange={(v) => patch({ boqItemNo: v || null })}
              placeholder={
                boqOptions.length ? "Optional — link to BOQ" : "No BOQ items defined"
              }
              disabled={boqOptions.length === 0}
            />
          </Field>
          <Field label="Chainage from">
            <input
              type="text"
              value={state.chainageFromRaw}
              onChange={(e) => patch({ chainageFromRaw: e.target.value })}
              onBlur={() => handleChainageBlur("from")}
              placeholder="145+000"
              className={inputCls}
            />
          </Field>
          <Field label="Chainage to">
            <input
              type="text"
              value={state.chainageToRaw}
              onChange={(e) => patch({ chainageToRaw: e.target.value })}
              onBlur={() => handleChainageBlur("to")}
              placeholder="145+200"
              className={inputCls}
            />
          </Field>
          <Field label="Side">
            <select
              value={state.side ?? ""}
              onChange={(e) => patch({ side: (e.target.value || null) as Side | null })}
              className={inputCls}
            >
              <option value="">—</option>
              {SIDE_OPTS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Landmark" className="md:col-span-3">
            <input
              type="text"
              value={state.landmark ?? ""}
              onChange={(e) => patch({ landmark: e.target.value || null })}
              placeholder="Near Main Road junction"
              className={inputCls}
            />
          </Field>
          <Field label="Workdone Quantity" required>
            <input
              type="number"
              step="0.001"
              min="0"
              value={state.qtyExecuted}
              onChange={(e) => patch({ qtyExecuted: parseFloat(e.target.value) || 0 })}
              className={inputCls}
              required
            />
          </Field>
          <Field label="Unit">
            <select
              value={state.unit}
              onChange={(e) => patch({ unit: e.target.value })}
              className={inputCls}
              required
            >
              {unitOptionsWithFallback(state.unit).map((u) => (
                <option key={u} value={u}>
                  {u}
                  {!(UNIT_OPTS as readonly string[]).includes(u) ? " (legacy)" : ""}
                </option>
              ))}
            </select>
            {(() => {
              const activityUnit = state.activityId
                ? defaultUnitByActivityId.get(state.activityId) ?? null
                : null;
              if (
                activityUnit &&
                activityUnit.trim().length > 0 &&
                state.unit &&
                state.unit.trim().toLowerCase() !== activityUnit.trim().toLowerCase()
              ) {
                return (
                  <p className="mt-1 text-xs text-warning">
                    ⚠️ This activity is normally measured in{" "}
                    <strong>{activityUnit}</strong>. Saving with{" "}
                    <strong>{state.unit}</strong> will make the productivity-norm comparison
                    meaningless on the Capacity Utilization page.
                  </p>
                );
              }
              return null;
            })()}
          </Field>
          <Field label="Remarks" className="md:col-span-3">
            <textarea
              value={state.remarks ?? ""}
              onChange={(e) => patch({ remarks: e.target.value || null })}
              rows={2}
              className={inputCls}
            />
          </Field>
        </div>
      </div>

      {/* Tabs */}
      <div className="border-t border-hairline">
        <div className="flex gap-1 border-b border-hairline px-5 pt-3">
          <TabButton active={tab === "manpower"} onClick={() => setTab("manpower")}>
            <HardHat className="h-4 w-4" /> Manpower ({manpowerFilledCount})
          </TabButton>
          <TabButton active={tab === "equipment"} onClick={() => setTab("equipment")}>
            <Briefcase className="h-4 w-4" /> Equipment ({equipmentFilledCount})
          </TabButton>
          <TabButton active={tab === "material"} onClick={() => setTab("material")}>
            <Package className="h-4 w-4" /> Material ({materialsFilledCount})
          </TabButton>
          <TabButton active={tab === "issues"} onClick={() => setTab("issues")}>
            <AlertTriangle className="h-4 w-4" /> Issues ({issuesFilledCount})
          </TabButton>
        </div>
        <div className="px-5 py-4">
          {tab === "manpower" && (
            <ManpowerGrid
              projectId={projectId}
              activityId={state.activityId ?? null}
              reportDate={state.reportDate}
              rows={state.manpower ?? []}
              onChange={(rows: DprManpowerRow[]) => patch({ manpower: rows })}
            />
          )}
          {tab === "equipment" && (
            <EquipmentGrid
              projectId={projectId}
              activityId={state.activityId ?? null}
              reportDate={state.reportDate}
              rows={state.equipment ?? []}
              onChange={(rows: DprEquipmentRow[]) => patch({ equipment: rows })}
            />
          )}
          {tab === "material" && (
            <MaterialGrid
              projectId={projectId}
              activityId={state.activityId ?? null}
              reportDate={state.reportDate}
              rows={state.materials ?? []}
              onChange={(rows: DprMaterialRow[]) => patch({ materials: rows })}
            />
          )}
          {tab === "issues" && (
            <IssuesGrid
              rows={state.issues ?? []}
              onChange={(rows: DprIssueRow[]) => patch({ issues: rows })}
              supervisorOptions={supervisorOptions}
              defaultSupervisorName={state.supervisorName || undefined}
            />
          )}
        </div>
      </div>

      {/* Safety & Delay */}
      <div className="border-t border-hairline px-5 py-4">
        <SafetyDelaySection
          delayReason={state.delayReason}
          safetyObservation={state.safetyObservation}
          safetyIncidentType={state.safetyIncidentType}
          onChange={patch}
        />
      </div>

      {/* Photos */}
      <div className="border-t border-hairline px-5 py-4">
        <DprPhotosSection
          projectId={projectId}
          dprId={editing?.id ?? null}
          pending={pendingPhotos}
          existing={existingPhotos}
          onPendingChange={setPendingPhotos}
          onExistingChange={setExistingPhotos}
        />
        {photoUploadStatus && (
          <div className="mt-2 text-xs text-slate">{photoUploadStatus}</div>
        )}
      </div>

      {/* Sticky footer: totals + save */}
      <div className="sticky bottom-0 z-10 -mx-1 rounded-lg border border-hairline bg-paper/95 p-3 shadow-[0_-4px_20px_rgba(28,28,28,0.04)] backdrop-blur">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex-1 min-w-[300px]">
            <DprTotalsBar
              manpower={state.manpower ?? []}
              equipment={state.equipment ?? []}
              materials={state.materials ?? []}
              qtyExecuted={state.qtyExecuted}
              unit={state.unit}
            />
          </div>
          <div className="flex items-center gap-2">
            {error && <span className="text-sm text-burgundy">{error}</span>}
            <button
              type="button"
              onClick={onCancel}
              className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-semibold text-charcoal hover:bg-ivory"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-1.5 rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <Save className="h-4 w-4" />
              {submitting
                ? "Saving…"
                : editing
                  ? "Save changes"
                  : "Save DPR"}
            </button>
          </div>
        </div>
      </div>
    </form>
  );
}

const inputCls =
  "w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40";

function Field({
  label,
  className,
  required,
  children,
}: {
  label: string;
  className?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className={className}>
      <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
        {label}
        {required && <span className="ml-0.5 text-burgundy">*</span>}
      </label>
      {children}
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`-mb-px inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-semibold transition ${active
          ? "border-gold text-charcoal"
          : "border-transparent text-slate hover:text-charcoal"
        }`}
    >
      {children}
    </button>
  );
}

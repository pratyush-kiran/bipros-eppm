"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { AlertTriangle, Briefcase, HardHat, Info, Package, Save, X } from "lucide-react";
import toast from "react-hot-toast";
import { Badge } from "@/components/ui/badge";
import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import { chainageLabel, parseChainage } from "@/lib/format/chainage";
import { dprApi, type DprVoicePatch } from "@/lib/api/dprApi";
import { boqApi, type BoqItemResponse } from "@/lib/api/boqApi";
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
import {
  ProductivityPreviewBanner,
  type ProductivityPreviewData,
} from "./ProductivityPreviewBanner";
import { ProductivityCoverageBanner } from "./ProductivityCoverageBanner";

type FormError = string | null;

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
   * activityId → its assigned supervisors (multi). Powers the cross-filter / auto-fill
   * between the Supervisor and Activity pickers and the set-membership mismatch warning.
   * Empty array means the activity has no supervisors assigned yet.
   */
  supervisorsByActivityId: Map<string, Array<{ id: string; name: string }>>;
  /**
   * activityId → the linked WorkActivity's `default_unit`. The form auto-fills the unit
   * dropdown when an activity is picked so DPRs default to the activity's unit instead of
   * the hardcoded "Cum". When the user manually overrides, an inline warning appears.
   */
  defaultUnitByActivityId: Map<string, string | null>;
  boqOptions: SelectOption[];
  /**
   * Optional seed for new DPRs (ignored on edit). When a user clicks "Create DPR" from an
   * Activity, the parent DPR page passes the activity + supervisor + unit so the drawer opens
   * pre-filled instead of forcing the user to re-pick. Supervisor is validated against
   * {@code supervisorOptions} at initialisation; a no-longer-eligible user is silently dropped
   * (matching the on-pick auto-fill guard further down in this file).
   */
  defaultPrefill?: {
    activityId: string | null;
    activityName: string | null;
    supervisorUserId: string | null;
    supervisorName: string | null;
    unit: string | null;
  } | null;
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
  defaultDate: string,
  prefill?: Props["defaultPrefill"],
  supervisorOptions?: SelectOption[]
): FormState => {
  if (editing) {
    return {
      reportDate: editing.reportDate,
      supervisorUserId: editing.supervisorUserId ?? null,
      supervisorName: editing.supervisorName,
      chainageFromM: editing.chainageFromM,
      chainageToM: editing.chainageToM,
      chainageFromRaw: editing.chainageFromM != null ? chainageLabel(editing.chainageFromM) : "",
      chainageToRaw: editing.chainageToM != null ? chainageLabel(editing.chainageToM) : "",
      activityId: editing.activityId ?? null,
      activityName: editing.activityName,
      wbsNodeId: editing.wbsNodeId,
      boqItemId: editing.boqItemId ?? null,
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
      approvalStatus: editing.approvalStatus ?? "SUBMITTED",
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
  // Validate the seeded supervisor against the eligible options. Mirrors the on-pick auto-fill
  // guard below (line ~408): if the activity's snapshot points at a user who has since lost
  // their supervisor role, fall back to an empty supervisor rather than persisting an invalid id.
  const eligibleSeed =
    prefill?.supervisorUserId &&
    (supervisorOptions ?? []).some((s) => s.value === prefill.supervisorUserId)
      ? { id: prefill.supervisorUserId, name: prefill.supervisorName ?? "" }
      : null;
  const seedUnit = prefill?.unit && prefill.unit.trim().length > 0 ? prefill.unit.trim() : null;

  return {
    reportDate: defaultDate || todayIso(),
    supervisorUserId: eligibleSeed?.id ?? null,
    supervisorName: eligibleSeed?.name ?? "",
    chainageFromM: null,
    chainageToM: null,
    chainageFromRaw: "",
    chainageToRaw: "",
    activityId: prefill?.activityId ?? null,
    activityName: prefill?.activityName ?? "",
    wbsNodeId: null,
    boqItemId: null,
    boqItemNo: null,
    unit: seedUnit ?? "Cum",
    qtyExecuted: 0,
    weatherCondition: null,
    remarks: null,
    side: null,
    landmark: null,
    startTime: null,
    endTime: null,
    shift: "DAY",
    approvalStatus: "SUBMITTED",
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
  supervisorsByActivityId,
  defaultUnitByActivityId,
  boqOptions,
  defaultPrefill,
  onCancel,
  onSave,
}: Props) {
  const [state, setState] = useState<FormState>(() => {
    const s = initialState(editing, defaultDate, defaultPrefill, supervisorOptions);
    // Editing path: backend may not yet carry activityId on legacy rows. Resolve from name.
    if (editing && !s.activityId && editing.activityName) {
      const id = activityIdByName.get(editing.activityName.toLowerCase());
      if (id) s.activityId = id;
    }
    return s;
  });
  const [tab, setTab] = useState<Tab>("manpower");
  const [error, setError] = useState<FormError>(null);
  /**
   * Inline error pinned to the Activity selector. Set when the server rejects the DPR with
   * {@code ACTIVITY_DRAFT_DPR_REJECTED} (activity is still in DRAFT). Surfacing the message
   * next to the picker — instead of in the generic footer banner — makes the recovery
   * action obvious: pick a different activity, or ask someone with {@code ACTIVITY.LOCK} to
   * lock the chosen one.
   */
  const [activityFieldError, setActivityFieldError] = useState<FormError>(null);
  const [preview, setPreview] = useState<ProductivityPreviewData | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Server-side soft warnings from the most recent save (overrun / missing-rate). The DPR is
  // saved successfully when these are present — they're advisory, not blockers.
  const [recentWarnings, setRecentWarnings] = useState<string[]>([]);
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

  // Debounced productivity preview. Fires when manpower / equipment / activityId change and an
  // activity is set. Cancels in-flight requests on rapid edits so the panel reflects the latest
  // input. Never blocks save — purely advisory; the soft-warn lives on the banner itself.
  const previewSignature = useMemo(() => {
    const mp = (state.manpower ?? []).map((r) => ({
      roleId: (r as { roleId?: string | null }).roleId ?? null,
      nos: (r as { nos?: number | null }).nos ?? null,
    }));
    const eq = (state.equipment ?? []).map((r) => ({
      roleId: (r as { roleId?: string | null }).roleId ?? null,
      nos: (r as { nos?: number | null }).nos ?? null,
      workingHours: (r as { workingHours?: number | null }).workingHours ?? null,
    }));
    return JSON.stringify({ a: state.activityId ?? null, mp, eq });
  }, [state.manpower, state.equipment, state.activityId]);

  useEffect(() => {
    if (!state.activityId) {
      setPreview(null);
      return;
    }
    const { a, mp, eq } = JSON.parse(previewSignature) as {
      a: string;
      mp: Array<{ roleId: string | null; nos: number | null }>;
      eq: Array<{
        roleId: string | null;
        nos: number | null;
        workingHours: number | null;
      }>;
    };
    // Always call the preview — even with empty rows — so the coverage banner has data to
    // show what the Work Activity tracks before the user adds any resources. The endpoint is
    // read-only and the response is cheap; coverage drives both the banner and the inline panel.
    let cancelled = false;
    const handle = setTimeout(() => {
      dprApi
        .productivityPreview(projectId, a, { manpower: mp, equipment: eq })
        .then((r) => {
          if (!cancelled && r.data) setPreview(r.data);
        })
        .catch(() => {
          if (!cancelled) setPreview(null);
        });
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(handle);
    };
  }, [previewSignature, projectId, state.activityId]);

  /**
   * Workstream B1: BOQ candidates for the selected activity. When exactly one match, we
   * auto-bind to {@code boqItemId} so the supervisor doesn't have to pick. Each candidate
   * carries the budgeted rate, surfaced inline so the user sees the contractual rate before
   * saving.
   */
  const [activityBoqCandidates, setActivityBoqCandidates] = useState<BoqItemResponse[]>([]);
  useEffect(() => {
    if (!state.activityId) {
      setActivityBoqCandidates([]);
      return;
    }
    let cancelled = false;
    boqApi
      .listForActivity(projectId, state.activityId)
      .then((r) => {
        if (cancelled) return;
        const items = r.data ?? [];
        setActivityBoqCandidates(items);
        // Auto-select when exactly one match and the form hasn't already pinned a BoQ.
        if (items.length === 1 && !state.boqItemId) {
          setState((s) => ({
            ...s,
            boqItemId: items[0].id,
            boqItemNo: items[0].itemNo,
          }));
        }
      })
      .catch(() => {
        if (!cancelled) setActivityBoqCandidates([]);
      });
    return () => {
      cancelled = true;
    };
    // state.boqItemId is intentionally NOT in the dep array — re-fetching whenever the user
    // changes the BoQ would flicker the candidate list and re-trigger the auto-select.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, state.activityId]);

  const selectedBoq = useMemo<BoqItemResponse | null>(() => {
    if (!state.boqItemId) return null;
    return activityBoqCandidates.find((b) => b.id === state.boqItemId) ?? null;
  }, [activityBoqCandidates, state.boqItemId]);

  const getVoiceState = useCallback(() => {
    const s = stateRef.current;
    return {
      reportDate: s.reportDate,
      supervisorUserId:
        s.supervisorUserId === SUPERVISOR_OTHER ? null : s.supervisorUserId,
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
      setIfPresent("supervisorUserId", patch.supervisorUserId);
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

  const supervisorPickerValue = state.supervisorUserId || "";
  const supervisorIsOther = supervisorPickerValue === SUPERVISOR_OTHER;

  /**
   * Activities the currently selected supervisor co-supervises. If the user hasn't picked
   * a supervisor — or picked the free-text "Other" — show the full list. If the picked
   * supervisor has zero assigned activities, fall back to showing all (so the form stays
   * usable when an admin/PM is filing on someone else's behalf).
   */
  const filteredActivityOptions = useMemo(() => {
    if (!state.supervisorUserId || supervisorIsOther) return activityOptions;
    const filtered = activityOptions.filter((a) => {
      const sups = supervisorsByActivityId.get(a.value) ?? [];
      return sups.some((s) => s.id === state.supervisorUserId);
    });
    return filtered.length === 0 ? activityOptions : filtered;
  }, [activityOptions, state.supervisorUserId, supervisorIsOther, supervisorsByActivityId]);

  const supervisorHasNoActivities = useMemo(() => {
    if (!state.supervisorUserId || supervisorIsOther) return false;
    return !activityOptions.some((a) =>
      (supervisorsByActivityId.get(a.value) ?? []).some((s) => s.id === state.supervisorUserId)
    );
  }, [activityOptions, state.supervisorUserId, supervisorIsOther, supervisorsByActivityId]);

  /**
   * Inline mismatch when the picked supervisor isn't in the activity's supervisor set.
   * Returns the comma-joined names of the activity's actual supervisors so the message
   * can name them; null when the picked user IS one of them (or no check is needed).
   */
  const activitySupervisorMismatch = useMemo<string | null>(() => {
    if (!state.activityId || !state.supervisorUserId || supervisorIsOther) return null;
    const sups = supervisorsByActivityId.get(state.activityId) ?? [];
    if (sups.length === 0) return null;
    if (sups.some((s) => s.id === state.supervisorUserId)) return null;
    return sups.map((s) => s.name).filter(Boolean).join(", ") || "another supervisor";
  }, [state.activityId, state.supervisorUserId, supervisorIsOther, supervisorsByActivityId]);

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
    if (!state.activityId || !state.supervisorUserId || supervisorIsOther) return false;
    const sups = supervisorsByActivityId.get(state.activityId) ?? [];
    return sups.some((s) => s.id === state.supervisorUserId);
  }, [state.activityId, state.supervisorUserId, supervisorIsOther, supervisorsByActivityId]);

  /**
   * Activity dropdown change: when rows already exist for the previous activity, prompt to clear
   * them — they reference assignments scoped to the old activity and would fail server validation
   * after a switch. Cancelling the prompt reverts the picker. When the new activity carries an
   * assigned supervisor and the form's supervisor is empty (or "Other"), auto-fill the supervisor
   * — saves a click and surfaces the activity→supervisor relationship that already exists in the
   * domain model.
   */
  const handleActivityChange = (newActivityId: string) => {
    // Switching activities is the recovery for an ACTIVITY_DRAFT_DPR_REJECTED — wipe the
    // inline error so the field doesn't keep complaining after the user has moved on.
    if (activityFieldError) setActivityFieldError(null);
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
    // Multi-supervisor: pick the first supervisor in the activity's list that is still
    // eligible (i.e. still has a supervisor role). If none are eligible, leave the supervisor
    // untouched rather than silently filling an invalid id.
    const sups = newActivityId ? (supervisorsByActivityId.get(newActivityId) ?? []) : [];
    const supervisorEmpty = !state.supervisorUserId || supervisorIsOther;
    if (sups.length > 0 && supervisorEmpty) {
      for (const sup of sups) {
        const match = supervisorOptions.find((s) => s.value === sup.id);
        if (match) {
          delta.supervisorUserId = sup.id;
          delta.supervisorName = match.label.split(" (")[0];
          break;
        }
      }
    }
    patch(delta);
  };

  const handleSupervisorChange = (value: string) => {
    if (value === SUPERVISOR_OTHER) {
      patch({ supervisorUserId: SUPERVISOR_OTHER, supervisorName: "" });
      return;
    }
    const match = supervisorOptions.find((s) => s.value === value);
    patch({ supervisorUserId: value || null, supervisorName: match?.label.split(" (")[0] ?? "" });
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

    const supervisorUserId =
      state.supervisorUserId && state.supervisorUserId !== SUPERVISOR_OTHER
        ? state.supervisorUserId
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
      supervisorUserId,
      supervisorName: state.supervisorName,
      chainageFromM: state.chainageFromM,
      chainageToM: state.chainageToM,
      activityId: state.activityId ?? null,
      activityName: state.activityName,
      wbsNodeId: state.wbsNodeId,
      boqItemId: state.boqItemId ?? null,
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
      // Surface soft warnings (overrun on planned rows, missing rate on unplanned rows). The
      // DPR is already persisted — these are advisory. One summary toast keeps the page tidy
      // even when many warnings fire; the sticky banner above the tabs lists every one.
      const warnings = saved?.warnings ?? [];
      if (warnings.length > 0) {
        toast(
          warnings.length === 1
            ? "Saved with 1 warning — see details above the tabs."
            : `Saved with ${warnings.length} warnings — see details above the tabs.`,
          { icon: "⚠️", duration: 5000 },
        );
        setRecentWarnings(warnings);
      } else {
        setRecentWarnings([]);
      }
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
      const axiosErr = err as {
        response?: { data?: { error?: { code?: string; message?: string } } };
      };
      const apiErr = axiosErr?.response?.data?.error;
      if (apiErr?.code === "ACTIVITY_DRAFT_DPR_REJECTED") {
        // Pin this one to the Activity field — recovery is "pick a different activity" or
        // "lock this one", both of which the user does at the picker.
        setActivityFieldError(
          apiErr.message ??
            "This activity is still in Draft — lock it before submitting a DPR against it."
        );
        setError(null);
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
              Activity is supervised by {activitySupervisorMismatch} — not the selected supervisor.
            </p>
          )}
          {activityFieldError && (
            <p className="mt-1 inline-flex items-start gap-1 text-xs text-burgundy">
              <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
              <span>{activityFieldError}</span>
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
            {(() => {
              // When the user has picked an activity, prefer the narrowed candidate list. When
              // no activity (or no candidates), fall back to the full project BoQ list so the
              // form is never blocked from linking.
              const useCandidates =
                state.activityId !== null && activityBoqCandidates.length > 0;
              const options: SelectOption[] = useCandidates
                ? activityBoqCandidates.map((b) => ({
                    value: b.id,
                    label: `${b.itemNo} — ${b.description}`,
                  }))
                : boqOptions;
              // Candidate mode binds to boqItemId (the new canonical FK); fallback mode keeps
              // the legacy boqItemNo binding so users can still link projects whose data
              // pre-dates the activity-BoQ mapping.
              const currentValue = useCandidates
                ? state.boqItemId ?? ""
                : state.boqItemNo ?? "";
              return (
                <>
                  <SearchableSelect
                    options={options}
                    value={currentValue}
                    onChange={(v) => {
                      if (useCandidates) {
                        const picked = activityBoqCandidates.find((b) => b.id === v);
                        patch({
                          boqItemId: picked?.id ?? null,
                          boqItemNo: picked?.itemNo ?? null,
                        });
                      } else {
                        patch({ boqItemNo: v || null, boqItemId: null });
                      }
                    }}
                    placeholder={
                      options.length
                        ? "Optional — link to BOQ"
                        : "No BOQ items defined"
                    }
                    disabled={options.length === 0}
                  />
                  {selectedBoq && (
                    <p className="mt-1 text-xs text-text-secondary">
                      {selectedBoq.description}
                      {selectedBoq.budgetedRate != null && (
                        <>
                          {" · "}
                          <span className="font-medium">
                            Budgeted rate:{" "}
                            {selectedBoq.budgetedRate.toLocaleString("en-IN")} /{" "}
                            {selectedBoq.unit}
                          </span>
                        </>
                      )}
                    </p>
                  )}
                </>
              );
            })()}
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
            <ProductivityPreviewBanner
              preview={preview}
              workdone={state.qtyExecuted ?? null}
              unit={state.unit}
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

      {state.activityId && (
        <div className="px-5">
          <ProductivityCoverageBanner coverage={preview?.coverage ?? null} />
        </div>
      )}

      {recentWarnings.length > 0 && (
        <div className="mx-5 mt-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-900 dark:text-amber-200">
          <div className="mb-1 flex items-start justify-between gap-3">
            <div className="flex items-center gap-1.5 font-semibold">
              <AlertTriangle className="h-3.5 w-3.5" />
              Saved with warnings ({recentWarnings.length})
            </div>
            <button
              type="button"
              onClick={() => setRecentWarnings([])}
              className="text-amber-900/70 hover:text-amber-900 dark:text-amber-200/70 dark:hover:text-amber-200"
              aria-label="Dismiss warnings"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
          <ul className="list-disc space-y-0.5 pl-4">
            {recentWarnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </div>
      )}

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

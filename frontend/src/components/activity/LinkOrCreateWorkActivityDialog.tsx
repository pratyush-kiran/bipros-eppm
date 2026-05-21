"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { X, AlertTriangle, Library, Gauge } from "lucide-react";
import toast from "react-hot-toast";
import {
  workActivityApi,
  type NormCombination,
  type WorkActivityResponse,
} from "@/lib/api/workActivityApi";
import {
  productivityNormApi,
  type CreateProductivityNormRequest,
} from "@/lib/api/productivityNormApi";
import { activityApi } from "@/lib/api/activityApi";
import { resourceRoleApi } from "@/lib/api/resourceRoleApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { unitOptionsWithFallback } from "@/lib/constants/units";
import { useAuthStore } from "@/lib/state/store";
import { notificationHelpers } from "@/lib/notificationHelpers";

export type DialogMode = "LINK_OR_CREATE" | "CONFIGURE_NORMS_ONLY";

interface Props {
  open: boolean;
  onClose: () => void;
  mode: DialogMode;
  /** The project that owns the activity; used to invalidate caches and patch the activity. */
  projectId?: string;
  /** Activity to patch with the resolved master id. Omit when wiring this from the
   *  new-activity form — the parent receives the master id via {@code onMasterSelected}
   *  and merges it into its own form state. */
  activityId?: string;
  /** Pre-filled values for the "Create new master" step. Drive these from the activity
   *  the user is looking at, or from the new-activity form fields. */
  defaultCode?: string;
  defaultName?: string;
  defaultUnit?: string | null;
  /** Pre-resolved master id, required when {@code mode === "CONFIGURE_NORMS_ONLY"}. */
  existingMasterId?: string;
  existingMasterName?: string;
  existingMasterDefaultUnit?: string | null;
  /** Called whenever a master is linked / created. Returned id is what's now on the activity
   *  (or what the parent should attach to its in-progress form). */
  onMasterSelected?: (masterId: string) => void;
}

type Step = "CHOOSE" | "NORM";
type Tab = "LINK" | "CREATE";

const NORM_COMBINATION_OPTIONS: ReadonlyArray<{
  value: NormCombination;
  title: string;
  hint: string;
}> = [
  {
    value: "SERIES",
    title: "Series — bottleneck (default)",
    hint: "Manpower and equipment work on the same unit in sequence. Expected = min(MP, EQ). Use for excavation, concreting, paving.",
  },
  {
    value: "PARALLEL",
    title: "Parallel — independent teams",
    hint: "Teams work independently on different stretches. Expected = MP + EQ. Use for side clearance, brush cutting, survey.",
  },
  {
    value: "SUBSTITUTE",
    title: "Substitute — either alone",
    hint: "Either side alone finishes the unit. Expected = max(MP, EQ). Rare — e.g. demolition.",
  },
];

/**
 * Two-step inline workflow that closes the Activity → Master Work Activity gap:
 *   step 1  — link the activity to an existing master OR create a new master inline
 *   step 2  — capture a minimal UNSCOPED Productivity Norm so Capacity Utilization
 *             has something to compute against.
 * Designed so the planner never leaves the screen they're on.
 */
export function LinkOrCreateWorkActivityDialog({
  open,
  onClose,
  mode,
  projectId,
  activityId,
  defaultCode,
  defaultName,
  defaultUnit,
  existingMasterId,
  existingMasterName,
  existingMasterDefaultUnit,
  onMasterSelected,
}: Props) {
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCreateMaster = hasPermission("ADMIN_MASTER.UPDATE");

  const [step, setStep] = useState<Step>(
    mode === "CONFIGURE_NORMS_ONLY" ? "NORM" : "CHOOSE",
  );
  const [tab, setTab] = useState<Tab>(canCreateMaster ? "CREATE" : "LINK");

  // step 1 — link state
  const [linkMasterId, setLinkMasterId] = useState<string>("");

  // step 1 — create state
  const [createForm, setCreateForm] = useState<{
    code: string;
    name: string;
    defaultUnit: string;
    discipline: string;
    description: string;
    normCombination: NormCombination;
  }>({
    code: defaultCode ?? "",
    name: defaultName ?? "",
    defaultUnit: defaultUnit ?? "",
    discipline: "",
    description: "",
    normCombination: "SERIES",
  });

  // The master that step 2 operates on. Populated from {@code existingMasterId}
  // (CONFIGURE_NORMS_ONLY) or from the newly created master after step 1.
  const [resolvedMaster, setResolvedMaster] = useState<{
    id: string;
    name: string;
    defaultUnit: string | null;
  } | null>(
    existingMasterId
      ? {
          id: existingMasterId,
          name: existingMasterName ?? "—",
          defaultUnit: existingMasterDefaultUnit ?? null,
        }
      : null,
  );

  // step 2 — norm state. Scope + Unit are shared between Manpower and Equipment sections;
  // each section has its own enabled toggle, role picker, and output fields so a user can
  // save Manpower-only, Equipment-only, or both in a single bulk call.
  const [scope, setScope] = useState<"UNSCOPED" | "ROLE">("UNSCOPED");
  const [normUnit, setNormUnit] = useState<string>(
    existingMasterDefaultUnit ?? defaultUnit ?? "",
  );
  const [manpowerEnabled, setManpowerEnabled] = useState<boolean>(true);
  const [manpowerRoleId, setManpowerRoleId] = useState<string>("");
  const [outputPerManPerDay, setOutputPerManPerDay] = useState<string>("");
  const [crewSize, setCrewSize] = useState<string>("");
  const [equipmentEnabled, setEquipmentEnabled] = useState<boolean>(false);
  const [equipmentRoleId, setEquipmentRoleId] = useState<string>("");
  const [outputPerHour, setOutputPerHour] = useState<string>("");
  const [workingHoursPerDay, setWorkingHoursPerDay] = useState<string>("8");

  // Reset on open so reopening with new defaults takes effect.
  useEffect(() => {
    if (!open) return;
    setStep(mode === "CONFIGURE_NORMS_ONLY" ? "NORM" : "CHOOSE");
    setTab(canCreateMaster ? "CREATE" : "LINK");
    setLinkMasterId("");
    setCreateForm({
      code: defaultCode ?? "",
      name: defaultName ?? "",
      defaultUnit: defaultUnit ?? "",
      discipline: "",
      description: "",
      normCombination: "SERIES",
    });
    setResolvedMaster(
      existingMasterId
        ? {
            id: existingMasterId,
            name: existingMasterName ?? "—",
            defaultUnit: existingMasterDefaultUnit ?? null,
          }
        : null,
    );
    setScope("UNSCOPED");
    setNormUnit(existingMasterDefaultUnit ?? defaultUnit ?? "");
    setManpowerEnabled(true);
    setManpowerRoleId("");
    setOutputPerManPerDay("");
    setCrewSize("");
    setEquipmentEnabled(false);
    setEquipmentRoleId("");
    setOutputPerHour("");
    setWorkingHoursPerDay("8");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const { data: workActivitiesData } = useQuery({
    queryKey: ["work-activities", "active"],
    queryFn: () => workActivityApi.list(true),
    enabled: open,
  });
  const masters = useMemo<WorkActivityResponse[]>(
    () => workActivitiesData?.data ?? [],
    [workActivitiesData],
  );

  const linkOptions = useMemo(
    () => [
      { value: "", label: "— select a master —" },
      ...masters.map((m: WorkActivityResponse) => ({
        value: m.id,
        label: m.defaultUnit ? `${m.name} (${m.defaultUnit})` : m.name,
      })),
    ],
    [masters],
  );

  // Roles for the per-section role pickers when scope = ROLE. The list is filtered to the
  // norm-type bucket: MANPOWER/LABOR for the manpower section, EQUIPMENT/MACHINE for the
  // equipment section. Matches the filtering in /admin/productivity-norms.
  const { data: rolesData } = useQuery({
    queryKey: ["resource-roles", "all"],
    queryFn: () => resourceRoleApi.list(),
    enabled: open && step === "NORM" && scope === "ROLE",
  });
  const allRoles = useMemo(() => rolesData?.data ?? [], [rolesData]);
  const manpowerRoleOptions = useMemo(() => {
    const codes = new Set(["MANPOWER", "LABOR"]);
    return [
      { value: "", label: "— pick role —" },
      ...allRoles
        .filter((r) => r.active && codes.has(r.resourceTypeCode.toUpperCase()))
        .map((r) => ({ value: r.id, label: r.name })),
    ];
  }, [allRoles]);
  const equipmentRoleOptions = useMemo(() => {
    const codes = new Set(["EQUIPMENT", "MACHINE"]);
    return [
      { value: "", label: "— pick role —" },
      ...allRoles
        .filter((r) => r.active && codes.has(r.resourceTypeCode.toUpperCase()))
        .map((r) => ({ value: r.id, label: r.name })),
    ];
  }, [allRoles]);

  const invalidateAfterMasterChange = (newMasterId: string) => {
    queryClient.invalidateQueries({ queryKey: ["work-activities", "active"] });
    queryClient.invalidateQueries({
      queryKey: ["work-activity-coverage", newMasterId],
    });
    if (projectId) {
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
    }
    if (projectId && activityId) {
      queryClient.invalidateQueries({
        queryKey: ["activity", projectId, activityId],
      });
    }
  };

  const linkMutation = useMutation({
    mutationFn: async (masterId: string) => {
      if (projectId && activityId) {
        await activityApi.updateActivity(projectId, activityId, {
          workActivityId: masterId,
        });
      }
      return masterId;
    },
    onSuccess: (masterId) => {
      const m = masters.find((x) => x.id === masterId);
      toast.success("Activity linked to master Work Activity");
      onMasterSelected?.(masterId);
      invalidateAfterMasterChange(masterId);
      if (m) {
        setResolvedMaster({
          id: m.id,
          name: m.name,
          defaultUnit: m.defaultUnit,
        });
        setNormUnit(m.defaultUnit ?? "");
      }
      // After a successful link, advance into norm config — the linked master may also lack norms.
      setStep("NORM");
    },
    onError: (e) => notificationHelpers.handleApiError(e, "Failed to link master"),
  });

  const createMasterMutation = useMutation({
    mutationFn: async () => {
      const created = await workActivityApi.create({
        code: createForm.code.trim() || undefined,
        name: createForm.name.trim(),
        defaultUnit: createForm.defaultUnit.trim() || null,
        discipline: createForm.discipline.trim() || null,
        description: createForm.description.trim() || null,
        normCombination: createForm.normCombination,
        active: true,
      });
      const newMaster = created.data;
      if (!newMaster) throw new Error("Master Work Activity create returned no body");
      if (projectId && activityId) {
        await activityApi.updateActivity(projectId, activityId, {
          workActivityId: newMaster.id,
        });
      }
      return newMaster;
    },
    onSuccess: (newMaster) => {
      toast.success("Master Work Activity created");
      onMasterSelected?.(newMaster.id);
      invalidateAfterMasterChange(newMaster.id);
      setResolvedMaster({
        id: newMaster.id,
        name: newMaster.name,
        defaultUnit: newMaster.defaultUnit,
      });
      setNormUnit(newMaster.defaultUnit ?? "");
      setStep("NORM");
    },
    onError: (e) => notificationHelpers.handleApiError(e, "Failed to create master"),
  });

  const createNormMutation = useMutation({
    mutationFn: async () => {
      if (!resolvedMaster) throw new Error("No master resolved");
      const unit = normUnit.trim();
      if (!unit) throw new Error("Unit is required");
      const payloads: CreateProductivityNormRequest[] = [];

      if (manpowerEnabled) {
        const out = Number(outputPerManPerDay);
        if (!Number.isFinite(out) || out <= 0) {
          throw new Error("Manpower: Output per man per day is required");
        }
        const body: CreateProductivityNormRequest = {
          normType: "MANPOWER",
          workActivityId: resolvedMaster.id,
          unit,
          outputPerManPerDay: out,
          roleId: scope === "ROLE" ? manpowerRoleId || null : null,
        };
        const crew = parseInt(crewSize, 10);
        if (Number.isFinite(crew) && crew > 0) {
          body.crewSize = crew;
          body.outputPerDay = +(out * crew).toFixed(4);
        }
        payloads.push(body);
      }

      if (equipmentEnabled) {
        const out = Number(outputPerHour);
        if (!Number.isFinite(out) || out <= 0) {
          throw new Error("Equipment: Output per hour is required");
        }
        const body: CreateProductivityNormRequest = {
          normType: "EQUIPMENT",
          workActivityId: resolvedMaster.id,
          unit,
          outputPerHour: out,
          roleId: scope === "ROLE" ? equipmentRoleId || null : null,
        };
        const hrs = Number(workingHoursPerDay);
        if (Number.isFinite(hrs) && hrs > 0) {
          body.workingHoursPerDay = hrs;
          body.outputPerDay = +(out * hrs).toFixed(4);
        }
        payloads.push(body);
      }

      if (payloads.length === 0) {
        throw new Error("Pick at least Manpower or Equipment");
      }
      // Role scope: each picked section must have a role chosen.
      if (scope === "ROLE") {
        if (manpowerEnabled && !manpowerRoleId) {
          throw new Error("Manpower: pick a role for role-scoped norm");
        }
        if (equipmentEnabled && !equipmentRoleId) {
          throw new Error("Equipment: pick a role for role-scoped norm");
        }
      }
      return productivityNormApi.createBulk(payloads);
    },
    onSuccess: (res) => {
      const n = res.data?.length ?? 0;
      toast.success(n === 1 ? "Productivity Norm created" : `Saved ${n} productivity norms`);
      if (resolvedMaster) invalidateAfterMasterChange(resolvedMaster.id);
      onClose();
    },
    onError: (e) => notificationHelpers.handleApiError(e, "Failed to create norm"),
  });

  if (!open) return null;

  const isBusy =
    linkMutation.isPending ||
    createMasterMutation.isPending ||
    createNormMutation.isPending;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="flex max-h-[calc(100vh-2rem)] w-full max-w-xl flex-col overflow-y-auto rounded-xl border border-border bg-surface p-6 shadow-xl">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className="mt-0.5 text-warning">
              <AlertTriangle size={20} />
            </div>
            <div>
              <h3 className="text-base font-semibold text-text-primary">
                {step === "CHOOSE"
                  ? "Link to Master Work Activity"
                  : "Configure Productivity Norm"}
              </h3>
              <p className="mt-1 text-sm text-text-secondary">
                {step === "CHOOSE"
                  ? "Without a master, productivity norms cannot resolve and Capacity Utilization will be inaccurate."
                  : resolvedMaster
                    ? `For master: ${resolvedMaster.name}`
                    : "Configure a first productivity norm for this master."}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isBusy}
            className="rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-text-primary disabled:opacity-50"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>

        {step === "CHOOSE" && (
          <div className="mt-5 space-y-4">
            {/* Tabs */}
            <div className="flex gap-1 rounded-md bg-surface-hover p-1">
              {canCreateMaster && (
                <button
                  type="button"
                  onClick={() => setTab("CREATE")}
                  className={`flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                    tab === "CREATE"
                      ? "bg-surface text-text-primary shadow-sm"
                      : "text-text-secondary hover:text-text-primary"
                  }`}
                >
                  <Library size={14} /> Create new master
                </button>
              )}
              <button
                type="button"
                onClick={() => setTab("LINK")}
                className={`flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                  tab === "LINK"
                    ? "bg-surface text-text-primary shadow-sm"
                    : "text-text-secondary hover:text-text-primary"
                }`}
              >
                <Library size={14} /> Link to existing
              </button>
            </div>

            {tab === "LINK" ? (
              <div className="space-y-3">
                <label className="block text-sm font-medium text-text-secondary">
                  Master Work Activity
                </label>
                <SearchableSelect
                  value={linkMasterId}
                  onChange={setLinkMasterId}
                  placeholder="Search master library..."
                  options={linkOptions}
                />
                <p className="text-xs text-text-muted">
                  Picks the existing master to attach to this activity. The activity is updated
                  immediately; the next step lets you add a productivity norm if the master has none.
                </p>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-3">
                <div className="col-span-2">
                  <label className="block text-sm font-medium text-text-secondary">
                    Name <span className="text-danger">*</span>
                  </label>
                  <input
                    type="text"
                    value={createForm.name}
                    onChange={(e) =>
                      setCreateForm((p) => ({ ...p, name: e.target.value }))
                    }
                    className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                    placeholder="e.g. PCC Concrete Pouring"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary">
                    Code
                  </label>
                  <input
                    type="text"
                    value={createForm.code}
                    onChange={(e) =>
                      setCreateForm((p) => ({ ...p, code: e.target.value }))
                    }
                    className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                    placeholder="auto-slugified from name"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary">
                    Default unit
                  </label>
                  <select
                    value={createForm.defaultUnit}
                    onChange={(e) =>
                      setCreateForm((p) => ({ ...p, defaultUnit: e.target.value }))
                    }
                    className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  >
                    <option value="">— none —</option>
                    {unitOptionsWithFallback(createForm.defaultUnit).map((u) => (
                      <option key={u} value={u}>
                        {u}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="col-span-2">
                  <label className="block text-sm font-medium text-text-secondary">
                    Discipline
                  </label>
                  <input
                    type="text"
                    value={createForm.discipline}
                    onChange={(e) =>
                      setCreateForm((p) => ({ ...p, discipline: e.target.value }))
                    }
                    className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                    placeholder="e.g. earthwork, pavement"
                  />
                </div>
                <div className="col-span-2">
                  <label className="block text-sm font-medium text-text-secondary">
                    Description
                  </label>
                  <textarea
                    value={createForm.description}
                    onChange={(e) =>
                      setCreateForm((p) => ({ ...p, description: e.target.value }))
                    }
                    rows={2}
                    className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  />
                </div>
                <div className="col-span-2">
                  <label className="block text-sm font-medium text-text-secondary">
                    Norm combination
                  </label>
                  <p className="mt-1 text-xs text-text-muted">
                    How Manpower + Equipment norms combine on the DPR preview when this activity
                    has <em>both</em>. Ignored when only one side has a norm.
                  </p>
                  <div className="mt-2 flex flex-col gap-2">
                    {NORM_COMBINATION_OPTIONS.map((opt) => (
                      <label
                        key={opt.value}
                        className={`flex items-start gap-2 rounded-md border px-3 py-2 text-sm cursor-pointer ${
                          createForm.normCombination === opt.value
                            ? "border-accent bg-accent/10"
                            : "border-border bg-surface-hover"
                        }`}
                      >
                        <input
                          type="radio"
                          name="create-norm-combination"
                          value={opt.value}
                          checked={createForm.normCombination === opt.value}
                          onChange={() =>
                            setCreateForm((p) => ({ ...p, normCombination: opt.value }))
                          }
                          className="mt-0.5"
                        />
                        <div className="flex-1">
                          <div className="font-medium text-text-primary">{opt.title}</div>
                          <div className="mt-0.5 text-xs text-text-muted">{opt.hint}</div>
                        </div>
                      </label>
                    ))}
                  </div>
                </div>
              </div>
            )}

            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={onClose}
                disabled={isBusy}
                className="rounded-md border border-border bg-surface/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover disabled:opacity-50"
              >
                Cancel
              </button>
              {tab === "LINK" ? (
                <button
                  type="button"
                  disabled={!linkMasterId || isBusy}
                  onClick={() => linkMutation.mutate(linkMasterId)}
                  className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
                >
                  {linkMutation.isPending ? "Linking..." : "Link & continue"}
                </button>
              ) : (
                <button
                  type="button"
                  disabled={!createForm.name.trim() || isBusy}
                  onClick={() => createMasterMutation.mutate()}
                  className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
                >
                  {createMasterMutation.isPending
                    ? "Creating..."
                    : "Create & continue"}
                </button>
              )}
            </div>
          </div>
        )}

        {step === "NORM" && resolvedMaster && (
          <div className="mt-5 space-y-4">
            <div className="rounded-md border border-info/30 bg-info/5 px-3 py-2 text-xs text-text-secondary">
              <div className="flex items-start gap-2">
                <Gauge className="mt-0.5 h-4 w-4 flex-shrink-0 text-info" />
                <p>
                  Configure productivity norms for{" "}
                  <span className="font-semibold">{resolvedMaster.name}</span>. Pick Manpower,
                  Equipment, or both — each section is saved as its own norm. Variant-level
                  norms (skill / grade / make / model) can still be added from Admin → Productivity
                  Norms.
                </p>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Scope
              </label>
              <div className="mt-1 flex gap-4 text-sm">
                <label className="flex items-center gap-2 text-text-primary">
                  <input
                    type="radio"
                    name="norm-scope"
                    value="UNSCOPED"
                    checked={scope === "UNSCOPED"}
                    onChange={() => setScope("UNSCOPED")}
                  />
                  Unscoped (any role on this activity)
                </label>
                <label className="flex items-center gap-2 text-text-primary">
                  <input
                    type="radio"
                    name="norm-scope"
                    value="ROLE"
                    checked={scope === "ROLE"}
                    onChange={() => setScope("ROLE")}
                  />
                  Specific role
                </label>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Unit <span className="text-danger">*</span>
              </label>
              <select
                value={resolvedMaster.defaultUnit ?? ""}
                disabled
                aria-readonly="true"
                className="mt-1 block w-full cursor-not-allowed rounded-md border border-border bg-surface-hover/50 px-3 py-2 text-sm text-text-secondary opacity-80"
              >
                {resolvedMaster.defaultUnit ? (
                  <option value={resolvedMaster.defaultUnit}>
                    {resolvedMaster.defaultUnit}
                  </option>
                ) : (
                  <option value="">— not set on master —</option>
                )}
              </select>
              <p className="mt-1 text-xs text-text-muted">
                Locked to the master&apos;s default unit so Capacity Utilization can compare DPR
                workdone against the norm in matching units.
              </p>
              {!resolvedMaster.defaultUnit && (
                <p className="mt-1 flex items-start gap-1.5 text-xs text-danger">
                  <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" />
                  <span>
                    The master has no default unit. Cancel, set a default unit on the master,
                    then return here.
                  </span>
                </p>
              )}
            </div>

            {/* Manpower section */}
            <div className="rounded-md border border-border bg-surface-hover/40 p-3">
              <label className="flex items-center gap-2 text-sm font-medium text-text-primary">
                <input
                  type="checkbox"
                  checked={manpowerEnabled}
                  onChange={(e) => setManpowerEnabled(e.target.checked)}
                />
                Configure Manpower norm
              </label>
              {manpowerEnabled && (
                <div className="mt-3 grid grid-cols-2 gap-3">
                  {scope === "ROLE" && (
                    <div className="col-span-2">
                      <label className="block text-sm font-medium text-text-secondary">
                        Manpower role <span className="text-danger">*</span>
                      </label>
                      <SearchableSelect
                        value={manpowerRoleId}
                        onChange={setManpowerRoleId}
                        placeholder="— pick role —"
                        options={manpowerRoleOptions}
                      />
                    </div>
                  )}
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">
                      Output per man / day <span className="text-danger">*</span>
                    </label>
                    <input
                      type="number"
                      step="0.0001"
                      min="0"
                      value={outputPerManPerDay}
                      onChange={(e) => setOutputPerManPerDay(e.target.value)}
                      className="mt-1 block w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      placeholder="e.g. 5"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">
                      Crew size (optional)
                    </label>
                    <input
                      type="number"
                      step="1"
                      min="1"
                      value={crewSize}
                      onChange={(e) => setCrewSize(e.target.value)}
                      className="mt-1 block w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      placeholder="If given, gang output / day is derived"
                    />
                  </div>
                  {crewSize && outputPerManPerDay && (
                    <p className="col-span-2 -mt-1 text-xs text-text-muted">
                      Derived gang output:{" "}
                      <span className="font-semibold text-text-secondary">
                        {(Number(outputPerManPerDay) * Number(crewSize)).toFixed(2)}{" "}
                        {normUnit || "units"} / day
                      </span>
                    </p>
                  )}
                </div>
              )}
            </div>

            {/* Equipment section */}
            <div className="rounded-md border border-border bg-surface-hover/40 p-3">
              <label className="flex items-center gap-2 text-sm font-medium text-text-primary">
                <input
                  type="checkbox"
                  checked={equipmentEnabled}
                  onChange={(e) => setEquipmentEnabled(e.target.checked)}
                />
                Configure Equipment norm
              </label>
              {equipmentEnabled && (
                <div className="mt-3 grid grid-cols-2 gap-3">
                  {scope === "ROLE" && (
                    <div className="col-span-2">
                      <label className="block text-sm font-medium text-text-secondary">
                        Equipment role <span className="text-danger">*</span>
                      </label>
                      <SearchableSelect
                        value={equipmentRoleId}
                        onChange={setEquipmentRoleId}
                        placeholder="— pick role —"
                        options={equipmentRoleOptions}
                      />
                    </div>
                  )}
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">
                      Output per hour <span className="text-danger">*</span>
                    </label>
                    <input
                      type="number"
                      step="0.0001"
                      min="0"
                      value={outputPerHour}
                      onChange={(e) => setOutputPerHour(e.target.value)}
                      className="mt-1 block w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                      placeholder="e.g. 12.5"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary">
                      Working hours / day
                    </label>
                    <input
                      type="number"
                      step="0.5"
                      min="0"
                      value={workingHoursPerDay}
                      onChange={(e) => setWorkingHoursPerDay(e.target.value)}
                      className="mt-1 block w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                    />
                  </div>
                  {workingHoursPerDay && outputPerHour && (
                    <p className="col-span-2 -mt-1 text-xs text-text-muted">
                      Derived output / day:{" "}
                      <span className="font-semibold text-text-secondary">
                        {(Number(outputPerHour) * Number(workingHoursPerDay)).toFixed(2)}{" "}
                        {normUnit || "units"} / day
                      </span>
                    </p>
                  )}
                </div>
              )}
            </div>

            <div className="flex items-center justify-between gap-2 pt-2">
              <button
                type="button"
                onClick={onClose}
                disabled={isBusy}
                className="text-xs text-text-muted hover:text-text-secondary underline disabled:opacity-50"
              >
                Skip — I&apos;ll configure norms later
              </button>
              <button
                type="button"
                disabled={
                  isBusy ||
                  !normUnit.trim() ||
                  (!manpowerEnabled && !equipmentEnabled) ||
                  (manpowerEnabled && !outputPerManPerDay) ||
                  (equipmentEnabled && !outputPerHour) ||
                  (scope === "ROLE" && manpowerEnabled && !manpowerRoleId) ||
                  (scope === "ROLE" && equipmentEnabled && !equipmentRoleId)
                }
                onClick={() => createNormMutation.mutate()}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                {createNormMutation.isPending
                  ? "Saving..."
                  : manpowerEnabled && equipmentEnabled
                    ? "Save norms"
                    : "Save norm"}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

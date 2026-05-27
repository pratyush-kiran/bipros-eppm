"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/common/PageHeader";
import { Breadcrumb } from "@/components/common/Breadcrumb";
import {
  resourceApi,
  type CreateResourceRequest,
  type EquipmentDetailsDto,
  type ManpowerDto,
  type MaterialDetailsDto,
  type ResourceStatus,
  type ResourceOwnership,
  type MaterialType,
} from "@/lib/api/resourceApi";
import { resourceTypeApi } from "@/lib/api/resourceTypeApi";
import { resourceRoleApi } from "@/lib/api/resourceRoleApi";
import { calendarApi } from "@/lib/api/calendarApi";
import { withBasePath } from "@/lib/basePath";
import { manpowerCategoryMasterApi } from "@/lib/api/manpowerCategoryMasterApi";
import { employmentTypeMasterApi } from "@/lib/api/employmentTypeMasterApi";
import { skillMasterApi } from "@/lib/api/skillMasterApi";
import { skillLevelMasterApi } from "@/lib/api/skillLevelMasterApi";
import { nationalityMasterApi } from "@/lib/api/nationalityMasterApi";
import { manpowerRateMasterApi } from "@/lib/api/manpowerRateMasterApi";
import { equipmentRateMasterApi } from "@/lib/api/equipmentRateMasterApi";
import { materialRateMasterApi } from "@/lib/api/materialRateMasterApi";
import { getErrorMessage } from "@/lib/utils/error";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { MultiSelect } from "@/components/common/MultiSelect";
import toast from "react-hot-toast";

type TypeKind = "MANPOWER" | "EQUIPMENT" | "MATERIAL" | "OTHER";

function classifyType(code: string | undefined | null): TypeKind {
  switch (code) {
    case "MANPOWER":
    case "LABOR":
      return "MANPOWER";
    case "EQUIPMENT":
    case "NONLABOR":
      return "EQUIPMENT";
    case "MATERIAL":
      return "MATERIAL";
    default:
      return "OTHER";
  }
}

function codePrefixFor(kind: TypeKind): string {
  switch (kind) {
    case "MANPOWER":
      return "LAB-";
    case "EQUIPMENT":
      return "EQ-";
    case "MATERIAL":
      return "MAT-";
    default:
      return "RES-";
  }
}

const inputCls =
  "mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent";

const labelCls = "block text-sm font-medium text-text-secondary";

interface CommonForm {
  code: string;
  name: string;
  description: string;
  availability: string;
  costPerUnit: string;
  unit: string;
  rateMasterId: string;
  status: ResourceStatus;
  calendarId: string;
  parentId: string;
}

const emptyCommon = (): CommonForm => ({
  code: "",
  name: "",
  description: "",
  availability: "",
  costPerUnit: "",
  unit: "",
  rateMasterId: "",
  status: "ACTIVE",
  calendarId: "",
  parentId: "",
});

const emptyEquipment = (): EquipmentDetailsDto => ({});
const emptyMaterial = (): MaterialDetailsDto => ({});
const emptyManpower = (): ManpowerDto => ({
  master: {},
  skills: {},
  financials: {},
  attendance: {},
  allocation: {},
  compliance: {},
});

function toNumberOrUndef(value: string | undefined | null): number | undefined {
  if (value == null) return undefined;
  const trimmed = String(value).trim();
  if (trimmed === "") return undefined;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : undefined;
}

function toIntOrUndef(value: string | undefined | null): number | undefined {
  const n = toNumberOrUndef(value);
  if (n === undefined) return undefined;
  return Math.trunc(n);
}

function parseJsonStringOrThrow(value: string, label: string): string | undefined {
  const trimmed = value.trim();
  if (trimmed === "") return undefined;
  try {
    JSON.parse(trimmed);
  } catch {
    throw new Error(`${label} must be valid JSON`);
  }
  return trimmed;
}

export default function NewResourcePage() {
  const router = useRouter();

  const [step, setStep] = useState<1 | 2>(1);
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<{
    typeId?: string;
    roleId?: string;
    name?: string;
    rateMasterId?: string;
    costPerUnit?: string;
    unit?: string;
  }>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Step 1
  const [typeId, setTypeId] = useState("");
  const [roleId, setRoleId] = useState("");
  const [common, setCommon] = useState<CommonForm>(emptyCommon());

  // Step 2 — type-specific
  const [equipment, setEquipment] = useState<EquipmentDetailsDto>(emptyEquipment());
  const [material, setMaterial] = useState<MaterialDetailsDto>(emptyMaterial());
  const [manpower, setManpower] = useState<ManpowerDto>(emptyManpower());
  // 4 sections (financials/attendance/allocation/compliance) hidden for now — see ManpowerForm.
  const [activeManpowerSection, setActiveManpowerSection] = useState<"master" | "skills">("master");

  // Reference data
  const { data: typesData } = useQuery({
    queryKey: ["resource-types"],
    queryFn: () => resourceTypeApi.list(),
  });
  const types = useMemo(
    () => (typesData?.data ?? []).filter((t) => t.active),
    [typesData]
  );

  const { data: rolesData, isLoading: rolesLoading } = useQuery({
    queryKey: ["resource-roles", "by-type", typeId],
    queryFn: () => resourceRoleApi.listByType(typeId),
    enabled: !!typeId,
  });
  const roles = useMemo(
    () => (rolesData?.data ?? []).filter((r) => r.active),
    [rolesData]
  );

  const { data: calendarsData, isLoading: calendarsLoading } = useQuery({
    queryKey: ["calendars", "all"],
    queryFn: () => calendarApi.listCalendars(),
  });
  const resourceCalendars = calendarsData?.data ?? [];

  const selectedType = types.find((t) => t.id === typeId) ?? null;
  const kind = classifyType(selectedType?.code);

  // Rate master fetches keyed by kind. The picker only fires the relevant query.
  const { data: manpowerRatesData } = useQuery({
    queryKey: ["manpower-rate-master"],
    queryFn: () => manpowerRateMasterApi.list(),
    enabled: kind === "MANPOWER",
  });
  const { data: equipmentRatesData } = useQuery({
    queryKey: ["equipment-rate-master"],
    queryFn: () => equipmentRateMasterApi.list(),
    enabled: kind === "EQUIPMENT",
  });
  const { data: materialRatesData } = useQuery({
    queryKey: ["material-rate-master"],
    queryFn: () => materialRateMasterApi.list(),
    enabled: kind === "MATERIAL",
  });

  /**
   * Single dropdown of rate-master options for the active kind. Each option carries
   * the row's id, display label, and the unit/rate to autofill when picked.
   */
  const rateMasterOptions = useMemo(() => {
    if (kind === "MANPOWER") {
      return (manpowerRatesData?.data ?? [])
        .filter((r) => r.active)
        .map((r) => ({
          id: r.id,
          label: `${r.roleName ?? "?"} / ${r.categoryName ?? "?"} / Grade ${r.gradeCode ?? "?"} — ${r.unit} @ ${r.rate}`,
          unit: r.unit,
          rate: r.rate,
          roleId: r.roleId,
        }));
    }
    if (kind === "EQUIPMENT") {
      return (equipmentRatesData?.data ?? [])
        .filter((r) => r.active)
        .map((r) => ({
          id: r.id,
          label: `${r.equipmentName} / ${r.make} ${r.model} — ${r.unit} @ ${r.rate}`,
          unit: r.unit,
          rate: r.rate,
          roleId: undefined as string | undefined,
        }));
    }
    if (kind === "MATERIAL") {
      return (materialRatesData?.data ?? [])
        .filter((r) => r.active)
        .map((r) => ({
          id: r.id,
          label: `${r.categoryName ?? "?"} / ${r.specGrade} — ${r.unit} @ ${r.rate}`,
          unit: r.unit,
          rate: r.rate,
          roleId: undefined as string | undefined,
        }));
    }
    return [];
  }, [kind, manpowerRatesData, equipmentRatesData, materialRatesData]);

  // Auto-fill code prefix when type changes (only when code is empty or still a prefix-only string)
  useEffect(() => {
    if (!selectedType) return;
    setCommon((prev) => {
      const prefix = codePrefixFor(kind);
      // Only seed the prefix if user hasn't typed a real code yet.
      if (prev.code.trim() === "" || /^(LAB-|EQ-|MAT-|RES-)$/.test(prev.code.trim())) {
        return { ...prev, code: prefix };
      }
      return prev;
    });
  }, [selectedType, kind]);

  // Phase 8: role.productivityUnit is deprecated. Unit comes from the rate master picker —
  // no role-driven auto-fill. Name is NOT auto-filled either (Manpower expects a real person
  // name, not the role label).

  const canAdvanceToStep2 = !!typeId && !!roleId && common.name.trim() !== "";

  const handleSubmit = async () => {
    setError("");

    // Build per-field error map. Default Rate / Unit are required only when no rate master is
    // linked — the rate master snapshots those values when present.
    const errs: typeof fieldErrors = {};
    if (!typeId) errs.typeId = "Pick a Resource Type";
    if (!roleId) errs.roleId = "Pick a Role";
    if (!common.name.trim()) errs.name = "Name is required";
    if (!common.rateMasterId) {
      errs.rateMasterId = "Pick a rate master row — unit and rate come from it.";
    }
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) {
      setError("Please fix the highlighted fields before submitting.");
      setStep(1);
      return;
    }

    setIsSubmitting(true);
    try {
      const base: CreateResourceRequest = {
        code: common.code.trim() || undefined,
        name: common.name.trim(),
        description: common.description.trim() || undefined,
        roleId,
        resourceTypeId: typeId,
        availability: toNumberOrUndef(common.availability) ?? null,
        costPerUnit: toNumberOrUndef(common.costPerUnit) ?? null,
        unit: common.unit.trim() || null,
        rateMasterId: common.rateMasterId || null,
        status: common.status,
        calendarId: common.calendarId || null,
        parentId: common.parentId || null,
      };

      if (kind === "EQUIPMENT") {
        base.equipment = equipment;
      } else if (kind === "MATERIAL") {
        // alternateUnits is JSONB — validate the textarea before sending.
        if (material.alternateUnits) {
          parseJsonStringOrThrow(
            material.alternateUnits,
            "Alternate Units (JSON)"
          );
        }
        base.material = material;
      } else if (kind === "MANPOWER") {
        // Skills now use the MultiSelect component (already JSON-encoded internally), so the
        // textarea-validation step is no longer needed. Financial/Attendance/Allocation/Compliance
        // sections are hidden — their JSON fields aren't surfaced.
        base.manpower = manpower;
      }

      const result = await resourceApi.createResource(base);
      if (result.data) {
        toast.success("Resource created");
        router.push(`/resources/${result.data.id}`);
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create resource"));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      <div className="mb-4">
        <Breadcrumb
          items={[
            { label: "Resources", href: "/resources" },
            { label: "New Resource", href: "/resources/new", active: true },
          ]}
        />
      </div>
      <PageHeader
        title="New Resource"
        description="Create a manpower, equipment or material resource"
      />

      {/* Step indicator */}
      <div className="mb-6 flex items-center gap-2 text-sm">
        <span
          className={`inline-flex items-center gap-2 rounded-md px-3 py-1.5 ${
            step === 1
              ? "bg-accent text-text-primary"
              : "bg-surface-hover text-text-secondary"
          }`}
        >
          <span className="font-bold">1</span> Identity
        </span>
        <span className="text-text-muted">→</span>
        <span
          className={`inline-flex items-center gap-2 rounded-md px-3 py-1.5 ${
            step === 2
              ? "bg-accent text-text-primary"
              : "bg-surface-hover text-text-secondary"
          }`}
        >
          <span className="font-bold">2</span> {kind === "MANPOWER" ? "Manpower" : kind === "EQUIPMENT" ? "Equipment" : kind === "MATERIAL" ? "Material" : "Details"}
        </span>
      </div>

      {error && (
        <div className="mb-4 flex items-center justify-between rounded-md bg-danger/10 p-4 text-sm text-danger">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => setError("")}
            className="ml-3 text-danger hover:text-danger"
          >
            ×
          </button>
        </div>
      )}

      {step === 1 && (
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h2 className="mb-4 text-lg font-semibold text-text-primary">Resource Type</h2>
          <div className="mb-6 grid grid-cols-1 md:grid-cols-3 gap-4">
            {types.map((t) => {
              const k = classifyType(t.code);
              const isSelected = t.id === typeId;
              return (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => {
                    setTypeId(t.id);
                    setRoleId("");
                  }}
                  className={`rounded-lg border p-4 text-left transition-colors ${
                    isSelected
                      ? "border-accent bg-accent/10"
                      : "border-border bg-surface hover:bg-surface-hover/50"
                  }`}
                >
                  <div className="text-xs font-semibold uppercase tracking-wide text-text-muted">
                    {t.code}
                  </div>
                  <div className="mt-1 text-base font-semibold text-text-primary">
                    {t.name}
                  </div>
                  <div className="mt-1 text-xs text-text-muted">
                    {k === "MANPOWER" && "People — workers, supervisors, drivers."}
                    {k === "EQUIPMENT" && "Plant & machinery — owned, hired, sub-let."}
                    {k === "MATERIAL" && "Consumed or stocked materials."}
                    {k === "OTHER" && (t.description ?? "Custom resource type.")}
                  </div>
                </button>
              );
            })}
          </div>

          {typeId && (
            <>
              <h2 className="mb-3 text-lg font-semibold text-text-primary">Role *</h2>
              <div className="mb-6">
                {kind === "MANPOWER" && common.rateMasterId ? (
                  // Manpower: rate master implies role; show read-only label.
                  <div className={`${inputCls} flex items-center justify-between !cursor-default`}>
                    <span>
                      {roles.find((r) => r.id === roleId)?.name ?? "—"}
                      {roles.find((r) => r.id === roleId)?.code && (
                        <span className="ml-1 text-text-muted">
                          ({roles.find((r) => r.id === roleId)?.code})
                        </span>
                      )}
                    </span>
                    <span className="text-xs uppercase tracking-wide text-text-muted">
                      from rate master
                    </span>
                  </div>
                ) : (
                  <SearchableSelect
                    options={roles.map((r) => ({
                      value: r.id,
                      label: `${r.name} (${r.code})`,
                    }))}
                    value={roleId}
                    onChange={(v) => {
                      setRoleId(v);
                      if (fieldErrors.roleId) setFieldErrors({ ...fieldErrors, roleId: undefined });
                    }}
                    placeholder={
                      rolesLoading
                        ? "Loading roles…"
                        : roles.length === 0
                          ? "No active roles for this type"
                          : "Pick a role"
                    }
                    disabled={rolesLoading || roles.length === 0}
                  />
                )}
                {fieldErrors.roleId && (
                  <p className="mt-1 text-xs text-danger">{fieldErrors.roleId}</p>
                )}
                <p className="mt-1 text-xs text-text-muted">
                  Required. Define roles in{" "}
                  <a href={withBasePath("/admin/resource-roles")} className="text-accent hover:underline">
                    Resource Roles
                  </a>
                  .
                  {kind === "MANPOWER" && (
                    <>
                      {" "}<strong className="text-text-secondary">For Manpower the role is set by the rate master pick below. Enter the person's actual name (e.g. <em>Rajesh Kumar</em>) — not the role label.</strong>
                    </>
                  )}
                </p>
              </div>

              <h2 className="mb-3 text-lg font-semibold text-text-primary">Rate Master</h2>
              <div className="mb-6">
                <SearchableSelect
                  options={rateMasterOptions.map((r) => ({ value: r.id, label: r.label }))}
                  value={common.rateMasterId}
                  onChange={(id) => {
                    const picked = rateMasterOptions.find((r) => r.id === id);
                    if (picked) {
                      // Snapshot unit + rate from the selected row, and (Manpower only) align role.
                      setCommon((prev) => ({
                        ...prev,
                        rateMasterId: picked.id,
                        unit: picked.unit,
                        costPerUnit: String(picked.rate),
                      }));
                      if (picked.roleId) setRoleId(picked.roleId);
                      if (fieldErrors.rateMasterId) setFieldErrors({ ...fieldErrors, rateMasterId: undefined });
                    } else {
                      setCommon((prev) => ({ ...prev, rateMasterId: "" }));
                    }
                  }}
                  placeholder={
                    rateMasterOptions.length === 0
                      ? "No rate master rows defined for this type"
                      : "— pick a rate master row —"
                  }
                />
                {fieldErrors.rateMasterId ? (
                  <p className="mt-1 text-xs text-danger">{fieldErrors.rateMasterId}</p>
                ) : (
                  <p className="mt-1 text-xs text-text-muted">
                    Required. Picking a rate master row auto-fills Unit and Default Rate, and
                    cascades any future rate revisions to this resource.
                  </p>
                )}
              </div>

              <h2 className="mb-3 text-lg font-semibold text-text-primary">Common Fields</h2>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className={labelCls}>Code</label>
                  <input
                    type="text"
                    value={common.code}
                    onChange={(e) => setCommon({ ...common, code: e.target.value })}
                    placeholder={`${codePrefixFor(kind)}001`}
                    className={inputCls}
                  />
                  <p className="mt-1 text-xs text-text-muted">
                    Auto-generated from prefix when blank.
                  </p>
                </div>
                <div>
                  <label className={labelCls}>Name *</label>
                  <input
                    type="text"
                    value={common.name}
                    onChange={(e) => {
                      setCommon({ ...common, name: e.target.value });
                      if (fieldErrors.name) setFieldErrors({ ...fieldErrors, name: undefined });
                    }}
                    placeholder={kind === "MANPOWER" ? "e.g. Rajesh Kumar" : kind === "EQUIPMENT" ? "e.g. JCB Excavator #2" : kind === "MATERIAL" ? "e.g. OPC 53 Cement" : "Resource name"}
                    className={inputCls}
                    required
                  />
                  {fieldErrors.name && (
                    <p className="mt-1 text-xs text-danger">{fieldErrors.name}</p>
                  )}
                </div>
                <div className="md:col-span-2">
                  <label className={labelCls}>Description</label>
                  <textarea
                    value={common.description}
                    onChange={(e) => setCommon({ ...common, description: e.target.value })}
                    rows={2}
                    className={inputCls}
                  />
                </div>
                <div>
                  <label className={labelCls}>
                    Default Rate
                    {common.rateMasterId && (
                      <span className="ml-2 text-xs uppercase tracking-wide text-text-muted">
                        from rate master
                      </span>
                    )}
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    value={common.costPerUnit}
                    onChange={(e) => {
                      setCommon({ ...common, costPerUnit: e.target.value });
                      if (fieldErrors.costPerUnit) setFieldErrors({ ...fieldErrors, costPerUnit: undefined });
                    }}
                    className={inputCls}
                    readOnly={!!common.rateMasterId}
                  />
                  {fieldErrors.costPerUnit ? (
                    <p className="mt-1 text-xs text-danger">{fieldErrors.costPerUnit}</p>
                  ) : (
                    <p className="mt-1 text-xs text-text-muted">
                      {common.rateMasterId
                        ? "Synced from the selected rate master. Edit the rate master row to change this value."
                        : "Rate used for project cost calculations. Enter the cost for ONE unit of this resource (e.g. ₹2,400 per Day for a mason, ₹4,500 per Hour for a JCB, ₹65,000 per MT for steel)."}
                    </p>
                  )}
                </div>
                <div>
                  <label className={labelCls}>
                    Unit
                    {common.rateMasterId && (
                      <span className="ml-2 text-xs uppercase tracking-wide text-text-muted">
                        from rate master
                      </span>
                    )}
                  </label>
                  <input
                    type="text"
                    value={common.unit}
                    onChange={(e) => {
                      setCommon({ ...common, unit: e.target.value });
                      if (fieldErrors.unit) setFieldErrors({ ...fieldErrors, unit: undefined });
                    }}
                    placeholder="Hour, Day, Cum, MT, Bags…"
                    className={inputCls}
                    readOnly={!!common.rateMasterId}
                  />
                  {fieldErrors.unit && (
                    <p className="mt-1 text-xs text-danger">{fieldErrors.unit}</p>
                  )}
                </div>
                <div>
                  <label className={labelCls}>Status</label>
                  <select
                    value={common.status}
                    onChange={(e) =>
                      setCommon({ ...common, status: e.target.value as ResourceStatus })
                    }
                    className={inputCls}
                  >
                    <option value="ACTIVE">Active</option>
                    <option value="INACTIVE">Inactive</option>
                  </select>
                </div>
                <div>
                  <label className={labelCls}>Calendar</label>
                  <select
                    value={common.calendarId}
                    onChange={(e) => setCommon({ ...common, calendarId: e.target.value })}
                    className={inputCls}
                    disabled={calendarsLoading}
                  >
                    {calendarsLoading && <option value="">Loading…</option>}
                    <option value="">— none —</option>
                    {resourceCalendars.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className={labelCls}>Parent Resource ID</label>
                  <input
                    type="text"
                    value={common.parentId}
                    onChange={(e) => setCommon({ ...common, parentId: e.target.value })}
                    placeholder="Optional — for hierarchical resources"
                    className={inputCls}
                  />
                </div>
                <div className="md:col-span-2">
                  <label className={labelCls}>Availability</label>
                  <input
                    type="number"
                    step="0.01"
                    value={common.availability}
                    onChange={(e) => setCommon({ ...common, availability: e.target.value })}
                    className={inputCls}
                    placeholder="e.g. 8 hours/day or 100 units (optional)"
                  />
                </div>
              </div>
            </>
          )}

          <div className="mt-6 flex gap-3">
            <button
              type="button"
              disabled={!canAdvanceToStep2}
              onClick={() => setStep(2)}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-text-primary hover:bg-accent-hover disabled:opacity-50"
            >
              Continue →
            </button>
            <button
              type="button"
              onClick={() => router.back()}
              className="rounded-md border border-border bg-surface/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          {kind === "EQUIPMENT" && (
            <EquipmentForm value={equipment} onChange={setEquipment} />
          )}
          {kind === "MATERIAL" && (
            <MaterialForm value={material} onChange={setMaterial} />
          )}
          {kind === "MANPOWER" && (
            <ManpowerForm
              value={manpower}
              onChange={setManpower}
              activeSection={activeManpowerSection}
              onActiveSectionChange={setActiveManpowerSection}
            />
          )}
          {kind === "OTHER" && (
            <div className="rounded-md border border-dashed border-border p-6 text-center text-sm text-text-muted">
              No type-specific fields for this resource type. Click Create to save.
            </div>
          )}

          <div className="mt-6 flex gap-3">
            <button
              type="button"
              onClick={() => setStep(1)}
              className="rounded-md border border-border bg-surface/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              ← Back
            </button>
            <button
              type="button"
              disabled={isSubmitting}
              onClick={handleSubmit}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-text-primary hover:bg-accent-hover disabled:opacity-50"
            >
              {isSubmitting ? "Creating…" : "Create Resource"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────────
// Equipment form
// ────────────────────────────────────────────────────────────────────────────

function EquipmentForm({
  value,
  onChange,
}: {
  value: EquipmentDetailsDto;
  onChange: (v: EquipmentDetailsDto) => void;
}) {
  const set = <K extends keyof EquipmentDetailsDto>(key: K, v: EquipmentDetailsDto[K]) =>
    onChange({ ...value, [key]: v });

  return (
    <div className="space-y-6">
      <Section title="Manufacturer & Model">
        <Field label="Make">
          <input
            className={inputCls}
            value={value.make ?? ""}
            onChange={(e) => set("make", e.target.value || null)}
          />
        </Field>
        <Field label="Model">
          <input
            className={inputCls}
            value={value.model ?? ""}
            onChange={(e) => set("model", e.target.value || null)}
          />
        </Field>
        <Field label="Variant">
          <input
            className={inputCls}
            value={value.variant ?? ""}
            onChange={(e) => set("variant", e.target.value || null)}
          />
        </Field>
        <Field label="Manufacturer Name">
          <input
            className={inputCls}
            value={value.manufacturerName ?? ""}
            onChange={(e) => set("manufacturerName", e.target.value || null)}
          />
        </Field>
        <Field label="Country of Origin">
          <input
            className={inputCls}
            value={value.countryOfOrigin ?? ""}
            onChange={(e) => set("countryOfOrigin", e.target.value || null)}
          />
        </Field>
        <Field label="Year of Manufacture">
          <input
            type="number"
            className={inputCls}
            value={value.yearOfManufacture ?? ""}
            onChange={(e) => set("yearOfManufacture", toIntOrUndef(e.target.value) ?? null)}
          />
        </Field>
      </Section>

      <Section title="Identification">
        <Field label="Serial Number">
          <input
            className={inputCls}
            value={value.serialNumber ?? ""}
            onChange={(e) => set("serialNumber", e.target.value || null)}
          />
        </Field>
        <Field label="Chassis Number">
          <input
            className={inputCls}
            value={value.chassisNumber ?? ""}
            onChange={(e) => set("chassisNumber", e.target.value || null)}
          />
        </Field>
        <Field label="Engine Number">
          <input
            className={inputCls}
            value={value.engineNumber ?? ""}
            onChange={(e) => set("engineNumber", e.target.value || null)}
          />
        </Field>
        <Field label="Registration Number">
          <input
            className={inputCls}
            value={value.registrationNumber ?? ""}
            onChange={(e) => set("registrationNumber", e.target.value || null)}
          />
        </Field>
      </Section>

      <Section title="Specification">
        <Field label="Capacity / Spec">
          <input
            className={inputCls}
            placeholder="1.0 Cum, 60 TPH, 5.5 m"
            value={value.capacitySpec ?? ""}
            onChange={(e) => set("capacitySpec", e.target.value || null)}
          />
        </Field>
        <Field label="Fuel L/Hour">
          <input
            type="number"
            step="0.01"
            className={inputCls}
            value={value.fuelLitresPerHour ?? ""}
            onChange={(e) => set("fuelLitresPerHour", toNumberOrUndef(e.target.value) ?? null)}
          />
        </Field>
        <Field label="Standard Output / Day">
          <input
            type="number"
            step="0.01"
            className={inputCls}
            value={value.standardOutputPerDay ?? ""}
            onChange={(e) => set("standardOutputPerDay", toNumberOrUndef(e.target.value) ?? null)}
          />
        </Field>
        <Field label="Output Unit">
          <input
            className={inputCls}
            value={value.standardOutputUnit ?? ""}
            onChange={(e) => set("standardOutputUnit", e.target.value || null)}
          />
        </Field>
      </Section>

      <Section title="Ownership">
        <Field label="Ownership Type">
          <select
            className={inputCls}
            value={value.ownershipType ?? ""}
            onChange={(e) =>
              set("ownershipType", (e.target.value || null) as ResourceOwnership | null)
            }
          >
            <option value="">—</option>
            <option value="OWNED">Owned</option>
            <option value="HIRED">Hired</option>
            <option value="SUB_CONTRACTOR_PROVIDED">Sub-contractor provided</option>
          </select>
        </Field>
        <Field label="Quantity Available">
          <input
            type="number"
            className={inputCls}
            value={value.quantityAvailable ?? ""}
            onChange={(e) => set("quantityAvailable", toIntOrUndef(e.target.value) ?? null)}
          />
        </Field>
      </Section>

      <Section title="Service & Insurance">
        <Field label="Insurance Expiry">
          <input
            type="date"
            className={inputCls}
            value={value.insuranceExpiry ?? ""}
            onChange={(e) => set("insuranceExpiry", e.target.value || null)}
          />
        </Field>
        <Field label="Last Service Date">
          <input
            type="date"
            className={inputCls}
            value={value.lastServiceDate ?? ""}
            onChange={(e) => set("lastServiceDate", e.target.value || null)}
          />
        </Field>
        <Field label="Next Service Date">
          <input
            type="date"
            className={inputCls}
            value={value.nextServiceDate ?? ""}
            onChange={(e) => set("nextServiceDate", e.target.value || null)}
          />
        </Field>
      </Section>
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────────
// Material form
// ────────────────────────────────────────────────────────────────────────────

function MaterialForm({
  value,
  onChange,
}: {
  value: MaterialDetailsDto;
  onChange: (v: MaterialDetailsDto) => void;
}) {
  const set = <K extends keyof MaterialDetailsDto>(key: K, v: MaterialDetailsDto[K]) =>
    onChange({ ...value, [key]: v });

  return (
    <div className="space-y-6">
      <Section title="Identification">
        <Field label="Material Type">
          <div className="mt-1 flex gap-4">
            {(["CONSUMABLE", "NON_CONSUMABLE"] as MaterialType[]).map((m) => (
              <label key={m} className="inline-flex items-center gap-2 text-sm text-text-secondary">
                <input
                  type="radio"
                  name="materialType"
                  checked={value.materialType === m}
                  onChange={() => set("materialType", m)}
                />
                {m === "CONSUMABLE" ? "Consumable" : "Non-Consumable"}
              </label>
            ))}
          </div>
        </Field>
        <Field label="Category">
          <input
            className={inputCls}
            value={value.category ?? ""}
            onChange={(e) => set("category", e.target.value || null)}
          />
        </Field>
        <Field label="Sub-Category">
          <input
            className={inputCls}
            value={value.subCategory ?? ""}
            onChange={(e) => set("subCategory", e.target.value || null)}
          />
        </Field>
        <Field label="Material Grade">
          <input
            className={inputCls}
            value={value.materialGrade ?? ""}
            onChange={(e) => set("materialGrade", e.target.value || null)}
          />
        </Field>
        <Field label="Specification" colSpan={2}>
          <input
            className={inputCls}
            value={value.specification ?? ""}
            onChange={(e) => set("specification", e.target.value || null)}
          />
        </Field>
      </Section>

      <Section title="Brand & Standards">
        <Field label="Brand">
          <input
            className={inputCls}
            value={value.brand ?? ""}
            onChange={(e) => set("brand", e.target.value || null)}
          />
        </Field>
        <Field label="Manufacturer Name">
          <input
            className={inputCls}
            value={value.manufacturerName ?? ""}
            onChange={(e) => set("manufacturerName", e.target.value || null)}
          />
        </Field>
        <Field label="Standard Code">
          <input
            className={inputCls}
            placeholder="IS / ASTM / EN…"
            value={value.standardCode ?? ""}
            onChange={(e) => set("standardCode", e.target.value || null)}
          />
        </Field>
        <Field label="Quality Class">
          <input
            className={inputCls}
            value={value.qualityClass ?? ""}
            onChange={(e) => set("qualityClass", e.target.value || null)}
          />
        </Field>
      </Section>

      <Section title="Units & Density">
        <Field label="Base Unit">
          <input
            className={inputCls}
            placeholder="MT, Cum, Bags, Litre…"
            value={value.baseUnit ?? ""}
            onChange={(e) => set("baseUnit", e.target.value || null)}
          />
        </Field>
        <Field label="Conversion Factor">
          <input
            type="number"
            step="0.001"
            className={inputCls}
            value={value.conversionFactor ?? ""}
            onChange={(e) => set("conversionFactor", toNumberOrUndef(e.target.value) ?? null)}
          />
        </Field>
        <Field label="Density">
          <input
            type="number"
            step="0.001"
            className={inputCls}
            value={value.density ?? ""}
            onChange={(e) => set("density", toNumberOrUndef(e.target.value) ?? null)}
          />
        </Field>
        <Field label="Alternate Units (JSON)" colSpan={2}>
          <textarea
            className={inputCls}
            rows={3}
            placeholder='{"bags": 50, "kg-per-bag": 1}'
            value={value.alternateUnits ?? ""}
            onChange={(e) => set("alternateUnits", e.target.value || null)}
          />
          <p className="mt-1 text-xs text-text-muted">
            Free-form JSON. Validated on submit.
          </p>
        </Field>
      </Section>
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────────────
// Manpower form
// ────────────────────────────────────────────────────────────────────────────

type ManpowerSection = "master" | "skills";

// Financial / Attendance / Allocation / Compliance sections are hidden for now per requirements.
// Their backend entities, columns, and DTOs are intact — only the UI surface is removed. To
// re-enable, restore the section keys + tab buttons + conditional renders, and recreate the
// corresponding ManpowerXxxFields components (see git history before this change).
function ManpowerForm({
  value,
  onChange,
  activeSection,
  onActiveSectionChange,
}: {
  value: ManpowerDto;
  onChange: (v: ManpowerDto) => void;
  activeSection: ManpowerSection;
  onActiveSectionChange: (s: ManpowerSection) => void;
}) {
  const sections: { key: ManpowerSection; label: string }[] = [
    { key: "master", label: "Master" },
    { key: "skills", label: "Skills" },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2 border-b border-border pb-3">
        {sections.map((s) => (
          <button
            key={s.key}
            type="button"
            onClick={() => onActiveSectionChange(s.key)}
            className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
              activeSection === s.key
                ? "bg-accent text-text-primary"
                : "border border-border text-text-secondary hover:bg-surface-hover/50"
            }`}
          >
            {s.label}
          </button>
        ))}
      </div>

      {activeSection === "master" && (
        <ManpowerMasterFields value={value} onChange={onChange} />
      )}
      {activeSection === "skills" && (
        <ManpowerSkillsFields value={value} onChange={onChange} />
      )}
    </div>
  );
}

/**
 * Build a SearchableSelect options list from master rows, optionally injecting the current
 * stored value as a synthetic "(legacy)" option when it doesn't match any master row. Lets
 * users see existing data that pre-dates a master entry instead of silently showing blank.
 */
function masterOptions(
  rows: Array<{ id?: string; name: string }>,
  currentValue: string | null | undefined,
): { value: string; label: string }[] {
  const opts = rows.map((r) => ({ value: r.name, label: r.name }));
  if (currentValue && !opts.some((o) => o.value === currentValue)) {
    opts.unshift({ value: currentValue, label: `${currentValue} (legacy)` });
  }
  return opts;
}

/** Tolerant parse for primary/secondary skills — JSON array, single legacy string, or empty. */
function parseSkillArray(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String) : [String(parsed)];
  } catch {
    return [raw];
  }
}

function ManpowerMasterFields({
  value,
  onChange,
}: {
  value: ManpowerDto;
  onChange: (v: ManpowerDto) => void;
}) {
  const m = value.master ?? {};
  const set = (patch: Partial<typeof m>) =>
    onChange({ ...value, master: { ...m, ...patch } });

  // Master data for dropdowns
  const { data: topCategoriesData } = useQuery({
    queryKey: ["manpower-categories", "top-level"],
    queryFn: () => manpowerCategoryMasterApi.listTopLevel(),
  });
  const topCategories = useMemo(() => topCategoriesData?.data ?? [], [topCategoriesData]);

  const selectedCategoryId = useMemo(
    () => topCategories.find((c) => c.name === m.category)?.id ?? null,
    [topCategories, m.category],
  );

  const { data: subCategoriesData } = useQuery({
    queryKey: ["manpower-categories", "by-parent", selectedCategoryId],
    queryFn: () => manpowerCategoryMasterApi.listByParent(selectedCategoryId!),
    enabled: !!selectedCategoryId,
  });
  const subCategories = useMemo(() => subCategoriesData?.data ?? [], [subCategoriesData]);

  const { data: employmentTypesData } = useQuery({
    queryKey: ["employment-types"],
    queryFn: () => employmentTypeMasterApi.list(),
  });
  const employmentTypes = useMemo(
    () => employmentTypesData?.data ?? [],
    [employmentTypesData],
  );

  const { data: nationalitiesData } = useQuery({
    queryKey: ["nationalities"],
    queryFn: () => nationalityMasterApi.list(),
  });
  const nationalities = useMemo(() => nationalitiesData?.data ?? [], [nationalitiesData]);

  const categoryOptions = masterOptions(topCategories, m.category);
  const subCategoryOptions = masterOptions(subCategories, m.subCategory);
  const employmentTypeOptions = masterOptions(employmentTypes, m.employmentType);

  return (
    <Section title="Personal & Employment">
      <Field label="Employee Code">
        <input
          className={inputCls}
          value={m.employeeCode ?? ""}
          onChange={(e) => set({ employeeCode: e.target.value || null })}
        />
      </Field>
      <Field label="Full Name (display)">
        <input
          className={inputCls}
          value={m.fullName ?? ""}
          onChange={(e) => set({ fullName: e.target.value || null })}
        />
      </Field>
      <Field label="First Name">
        <input
          className={inputCls}
          value={m.firstName ?? ""}
          onChange={(e) => set({ firstName: e.target.value || null })}
        />
      </Field>
      <Field label="Last Name">
        <input
          className={inputCls}
          value={m.lastName ?? ""}
          onChange={(e) => set({ lastName: e.target.value || null })}
        />
      </Field>
      <Field label="Category">
        <SearchableSelect
          options={categoryOptions}
          value={m.category ?? ""}
          onChange={(v) => {
            // Clear sub-category when category changes if it no longer fits.
            const newSet: Partial<typeof m> = { category: v || null };
            if (v !== m.category) newSet.subCategory = null;
            set(newSet);
          }}
          placeholder="— select category —"
        />
      </Field>
      <Field label="Sub-Category">
        <SearchableSelect
          options={subCategoryOptions}
          value={m.subCategory ?? ""}
          onChange={(v) => set({ subCategory: v || null })}
          placeholder={
            !selectedCategoryId
              ? "Pick Category first"
              : subCategoryOptions.length === 0
                ? "No sub-categories defined"
                : "— select sub-category —"
          }
          disabled={!selectedCategoryId}
        />
      </Field>
      <Field label="Date of Birth">
        <input
          type="date"
          className={inputCls}
          value={m.dateOfBirth ?? ""}
          onChange={(e) => set({ dateOfBirth: e.target.value || null })}
        />
      </Field>
      <Field label="Gender">
        <select
          className={inputCls}
          value={m.gender ?? ""}
          onChange={(e) => set({ gender: e.target.value || null })}
        >
          <option value="">—</option>
          <option value="Male">Male</option>
          <option value="Female">Female</option>
        </select>
      </Field>
      <Field label="Nationality">
        <input
          className={inputCls}
          list="nationalities-datalist"
          value={m.nationality ?? ""}
          onChange={(e) => set({ nationality: e.target.value || null })}
          placeholder="Type to search or pick a suggestion…"
        />
        <datalist id="nationalities-datalist">
          {nationalities.map((n) => (
            <option key={n.id} value={n.name} />
          ))}
        </datalist>
      </Field>
      <Field label="Contact Number">
        <input
          className={inputCls}
          value={m.contactNumber ?? ""}
          onChange={(e) => set({ contactNumber: e.target.value || null })}
        />
      </Field>
      <Field label="Email">
        <input
          type="email"
          className={inputCls}
          value={m.email ?? ""}
          onChange={(e) => set({ email: e.target.value || null })}
        />
      </Field>
      <Field label="Address" colSpan={2}>
        <input
          className={inputCls}
          value={m.address ?? ""}
          onChange={(e) => set({ address: e.target.value || null })}
        />
      </Field>
      <Field label="Emergency Contact">
        <input
          className={inputCls}
          value={m.emergencyContact ?? ""}
          onChange={(e) => set({ emergencyContact: e.target.value || null })}
        />
      </Field>
      <Field label="Photo URL">
        <input
          className={inputCls}
          value={m.photoUrl ?? ""}
          onChange={(e) => set({ photoUrl: e.target.value || null })}
        />
      </Field>
      <Field label="Employment Type">
        <SearchableSelect
          options={employmentTypeOptions}
          value={m.employmentType ?? ""}
          onChange={(v) => set({ employmentType: v || null })}
          placeholder="— select employment type —"
        />
      </Field>
      <Field label="Designation">
        <input
          className={inputCls}
          value={m.designation ?? ""}
          onChange={(e) => set({ designation: e.target.value || null })}
        />
      </Field>
      <Field label="Department">
        <input
          className={inputCls}
          value={m.department ?? ""}
          onChange={(e) => set({ department: e.target.value || null })}
        />
      </Field>
      <Field label="Joining Date">
        <input
          type="date"
          className={inputCls}
          value={m.joiningDate ?? ""}
          onChange={(e) => set({ joiningDate: e.target.value || null })}
        />
      </Field>
      <Field label="Exit Date">
        <input
          type="date"
          className={inputCls}
          value={m.exitDate ?? ""}
          onChange={(e) => set({ exitDate: e.target.value || null })}
        />
      </Field>
      <Field label="Reporting Manager (UUID)">
        <input
          className={inputCls}
          value={m.reportingManagerId ?? ""}
          onChange={(e) => set({ reportingManagerId: e.target.value || null })}
        />
      </Field>
      <Field label="Company Name">
        <input
          className={inputCls}
          value={m.companyName ?? ""}
          onChange={(e) => set({ companyName: e.target.value || null })}
        />
      </Field>
      <Field label="Work Location">
        <input
          className={inputCls}
          value={m.workLocation ?? ""}
          onChange={(e) => set({ workLocation: e.target.value || null })}
        />
      </Field>
    </Section>
  );
}

function ManpowerSkillsFields({
  value,
  onChange,
}: {
  value: ManpowerDto;
  onChange: (v: ManpowerDto) => void;
}) {
  const s = value.skills ?? {};
  const set = (patch: Partial<typeof s>) =>
    onChange({ ...value, skills: { ...s, ...patch } });

  const { data: skillsData } = useQuery({
    queryKey: ["skills"],
    queryFn: () => skillMasterApi.list(),
  });
  const skills = useMemo(() => skillsData?.data ?? [], [skillsData]);

  const { data: skillLevelsData } = useQuery({
    queryKey: ["skill-levels"],
    queryFn: () => skillLevelMasterApi.list(),
  });
  const skillLevels = useMemo(() => skillLevelsData?.data ?? [], [skillLevelsData]);

  const skillOptions = skills.map((sk) => ({ value: sk.name, label: sk.name }));
  const skillLevelOptions = masterOptions(skillLevels, s.skillLevel);

  const primarySelected = parseSkillArray(s.primarySkill);
  const secondarySelected = parseSkillArray(s.secondarySkills);

  return (
    <Section title="Skills & Certifications">
      <Field label="Primary Skills" colSpan={2}>
        <MultiSelect
          options={skillOptions}
          value={primarySelected}
          onChange={(arr) => set({ primarySkill: arr.length === 0 ? null : JSON.stringify(arr) })}
          placeholder="Pick one or more skills…"
        />
      </Field>
      <Field label="Skill Level">
        <SearchableSelect
          options={skillLevelOptions}
          value={s.skillLevel ?? ""}
          onChange={(v) => set({ skillLevel: v || null })}
          placeholder="— select skill level —"
        />
      </Field>
      <Field label="Experience (years)">
        <input
          type="number"
          className={inputCls}
          value={s.experienceYears ?? ""}
          onChange={(e) => set({ experienceYears: toIntOrUndef(e.target.value) ?? null })}
        />
      </Field>
      <Field label="License Details">
        <input
          className={inputCls}
          value={s.licenseDetails ?? ""}
          onChange={(e) => set({ licenseDetails: e.target.value || null })}
        />
      </Field>
      <Field label="Secondary Skills" colSpan={2}>
        <MultiSelect
          options={skillOptions}
          value={secondarySelected}
          onChange={(arr) =>
            set({ secondarySkills: arr.length === 0 ? null : JSON.stringify(arr) })
          }
          placeholder="Pick additional skills (if any)…"
        />
      </Field>
      <Field label="Certifications" colSpan={2}>
        <textarea
          className={inputCls}
          rows={2}
          value={s.certifications ?? ""}
          onChange={(e) => set({ certifications: e.target.value || null })}
        />
      </Field>
      <Field label="Training Records" colSpan={2}>
        <textarea
          className={inputCls}
          rows={2}
          value={s.trainingRecords ?? ""}
          onChange={(e) => set({ trainingRecords: e.target.value || null })}
        />
      </Field>
    </Section>
  );
}


// ────────────────────────────────────────────────────────────────────────────
// Layout helpers
// ────────────────────────────────────────────────────────────────────────────

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="mb-3 text-sm font-semibold uppercase tracking-wide text-text-secondary">
        {title}
      </h3>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">{children}</div>
    </div>
  );
}

function Field({
  label,
  children,
  colSpan,
}: {
  label: string;
  children: React.ReactNode;
  colSpan?: 1 | 2;
}) {
  return (
    <div className={colSpan === 2 ? "md:col-span-2" : ""}>
      <label className={labelCls}>{label}</label>
      {children}
    </div>
  );
}

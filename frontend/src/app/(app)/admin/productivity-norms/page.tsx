"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Info, Pencil, Trash2, X } from "lucide-react";
import {
  productivityNormApi,
  type ProductivityNormResponse,
  type ProductivityNormType,
  type CreateProductivityNormRequest,
} from "@/lib/api/productivityNormApi";
import { workActivityApi } from "@/lib/api/workActivityApi";
import { resourceTypeApi } from "@/lib/api/resourceTypeApi";
import { resourceApi } from "@/lib/api/resourceApi";
import { resourceRoleApi } from "@/lib/api/resourceRoleApi";
import { manpowerCategoryMasterApi } from "@/lib/api/manpowerCategoryMasterApi";
import { gradeMasterApi } from "@/lib/api/gradeMasterApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { unitOptionsWithFallback, STANDARD_UNITS } from "@/lib/constants/units";

type Scope = "VARIANT" | "ROLE" | "UNSCOPED";

interface NormForm {
  workActivityId: string;
  scope: Scope;
  /** Role-keyed scope inputs (new model). */
  roleId: string;
  categoryId: string;
  gradeId: string;
  make: string;
  model: string;
  equipmentSpec: string;
  unit: string;
  outputPerManPerDay: string;
  outputPerHour: string;
  crewSize: string;
  outputPerDay: string;
  workingHoursPerDay: string;
  fuelLitresPerHour: string;
  remarks: string;
}

const initialFormState: NormForm = {
  workActivityId: "",
  scope: "VARIANT",
  roleId: "",
  categoryId: "",
  gradeId: "",
  make: "",
  model: "",
  equipmentSpec: "",
  unit: "",
  outputPerManPerDay: "",
  outputPerHour: "",
  crewSize: "",
  outputPerDay: "",
  workingHoursPerDay: "",
  fuelLitresPerHour: "",
  remarks: "",
};

const toNumberOrUndefined = (value: string): number | undefined => {
  if (value === "" || value === null || value === undefined) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
};

const toIntOrUndefined = (value: string): number | undefined => {
  if (value === "" || value === null || value === undefined) return undefined;
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
};

function formatNumber(value: number | null): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString("en-IN");
}

/**
 * Bucket norms by scope. Priority order: role-keyed (variant or role) → legacy specific resource
 * → legacy resource type → unscoped. Legacy rows are tagged "(legacy)" so the admin can tell the
 * old type/resource-keyed rows from the new role-keyed ones at a glance.
 */
function groupNormsByScope(
  norms: ProductivityNormResponse[],
): Array<{ key: string; label: string; rows: ProductivityNormResponse[] }> {
  const map = new Map<string, { label: string; rows: ProductivityNormResponse[] }>();
  for (const n of norms) {
    let key: string;
    let label: string;
    if (n.roleId) {
      // Variant: role + (category/grade for manpower, make/model for equipment).
      const variantSig = [n.categoryId, n.gradeId, n.make, n.model].filter(Boolean).join("|");
      key = variantSig ? `role:${n.roleId}|var:${variantSig}` : `role:${n.roleId}`;
      const variantLabel = [
        n.make,
        n.model,
        // Category/grade ids are opaque — we don't have the names denormalised in the norm
        // response, so the UI falls back to a generic "variant" suffix.
      ]
        .filter((s): s is string => !!s && s.length > 0)
        .join(" ");
      label = `Role · ${n.roleId.slice(0, 8)}${variantLabel ? " — " + variantLabel : variantSig ? " — variant" : ""}`;
    } else if (n.resourceId) {
      key = `legacy:res:${n.resourceId}`;
      label = `${n.resourceCode ?? n.resourceName ?? "(resource)"} (legacy)`;
    } else if (n.resourceTypeId) {
      key = `legacy:type:${n.resourceTypeId}`;
      label = `${n.resourceTypeName ?? "(type)"} (legacy)`;
    } else {
      key = "_unscoped";
      label = "(unscoped)";
    }
    const bucket = map.get(key) ?? { label, rows: [] };
    bucket.rows.push(n);
    map.set(key, bucket);
  }
  return Array.from(map.entries())
    .sort(([, a], [, b]) => a.label.localeCompare(b.label))
    .map(([key, value]) => ({ key, ...value }));
}

function FieldHint({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-1.5 flex gap-2 rounded-md border-l-2 border-info/40 bg-info/5 px-2.5 py-1.5">
      <Info size={13} className="mt-0.5 flex-shrink-0 text-info/70" strokeWidth={1.75} />
      <p className="text-xs leading-relaxed text-text-secondary">{children}</p>
    </div>
  );
}

function ScopeBadge({ norm }: { norm: ProductivityNormResponse }) {
  if (norm.roleId) {
    const isVariant = !!(norm.categoryId || norm.gradeId || norm.make || norm.model);
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-success/10 text-success ring-1 ring-success/20">
        {isVariant ? "Variant" : "Role"}
      </span>
    );
  }
  if (norm.resourceId) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-accent/10 text-accent ring-1 ring-accent/20">
        {norm.resourceCode ?? norm.resourceName} <em className="text-text-muted">(legacy)</em>
      </span>
    );
  }
  if (norm.resourceTypeId) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-info/10 text-info ring-1 ring-info/20">
        {norm.resourceTypeName} <em className="text-text-muted">(legacy)</em>
      </span>
    );
  }
  return <span className="text-text-muted text-xs">Unscoped</span>;
}

export default function ProductivityNormsPage() {
  const [tab, setTab] = useState<ProductivityNormType>("MANPOWER");
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formData, setFormData] = useState<NormForm>(initialFormState);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const clearFieldError = useCallback(
    (field: string) => {
      if (!fieldErrors[field]) return;
      setFieldErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    },
    [fieldErrors],
  );

  const cancelForm = useCallback(() => {
    setShowForm(false);
    setEditingId(null);
    setFormData(initialFormState);
    setError(null);
    setFieldErrors({});
  }, []);

  useEffect(() => {
    if (!showForm) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") cancelForm();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [showForm, cancelForm]);

  const queryClient = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: ["productivity-norms", tab],
    queryFn: () => productivityNormApi.list(tab),
  });
  const norms: ProductivityNormResponse[] = data?.data ?? [];

  const { data: activitiesData } = useQuery({
    queryKey: ["work-activities", "active"],
    queryFn: () => workActivityApi.list(true),
  });
  const activities = activitiesData?.data ?? [];

  // Manpower tab matches MANPOWER or LABOR codes; Equipment tab matches EQUIPMENT or MACHINE.
  // Different DB seed generations used different spellings, so we accept both. Case-insensitive
  // so manual entries like "manpower" / "Labor" still match.
  const targetTypeCodes =
    tab === "MANPOWER" ? ["MANPOWER", "LABOR"] : ["EQUIPMENT", "MACHINE"];
  const matchesTargetCode = (code: string | null | undefined) =>
    !!code && targetTypeCodes.includes(code.toUpperCase());

  const { data: typesData } = useQuery({
    queryKey: ["resource-types"],
    queryFn: () => resourceTypeApi.list(),
  });
  const allTypes = typesData?.data ?? [];
  const typeDefs = allTypes.filter((t) => t.active && matchesTargetCode(t.code));

  const { data: resourcesData } = useQuery({
    queryKey: ["resources", "all"],
    queryFn: () => resourceApi.listResources(),
  });
  const allResources = (Array.isArray(resourcesData?.data) ? resourcesData?.data : []) ?? [];
  const filteredResources = allResources.filter((r) => matchesTargetCode(r.resourceTypeCode));

  // Role-keyed pickers. Roles are filtered to the current norm-type bucket by their parent
  // ResourceType.code (MANPOWER/LABOR for the Manpower tab, EQUIPMENT/MACHINE for the Equipment
  // tab). Categories/grades are manpower-only — they sit empty on the Equipment scope branch.
  const { data: rolesData } = useQuery({
    queryKey: ["resource-roles", "all"],
    queryFn: () => resourceRoleApi.list(),
  });
  const allRoles = rolesData?.data ?? [];
  const filteredRoles = allRoles.filter(
    (r) => r.active && matchesTargetCode(r.resourceTypeCode),
  );

  const { data: categoriesData } = useQuery({
    queryKey: ["manpower-categories", "active"],
    queryFn: () => manpowerCategoryMasterApi.list(),
    enabled: tab === "MANPOWER",
  });
  const allCategories = categoriesData?.data ?? [];
  // Top-level categories only (Skilled / Semi-Skilled / Unskilled / Staff) — sub-categories are
  // not represented as a separate scope axis on the productivity norm.
  const topCategories = allCategories.filter((c) => c.active && !c.parentId);

  const { data: gradesData } = useQuery({
    queryKey: ["grades", "active"],
    queryFn: () => gradeMasterApi.list(),
    enabled: tab === "MANPOWER",
  });
  const grades = (gradesData?.data ?? []).filter((g) => g.active);

  const handleTabChange = (nextTab: ProductivityNormType) => {
    setTab(nextTab);
    setShowForm(false);
    setEditingId(null);
    setFormData(initialFormState);
    setError(null);
    setFieldErrors({});
  };

  const handleEdit = useCallback((norm: ProductivityNormResponse) => {
    setEditingId(norm.id);
    setShowForm(true);
    setError(null);
    setFieldErrors({});
    // Detect scope: role-keyed wins over legacy. Legacy rows render as UNSCOPED so the user
    // can convert them to role-keyed without losing the row — the role-keyed fields stay null
    // until they pick a role explicitly.
    let editScope: Scope;
    if (norm.roleId) {
      const hasVariant = !!(norm.categoryId || norm.gradeId || norm.make || norm.model);
      editScope = hasVariant ? "VARIANT" : "ROLE";
    } else {
      editScope = "UNSCOPED";
    }
    setFormData({
      workActivityId: norm.workActivityId ?? "",
      scope: editScope,
      roleId: norm.roleId ?? "",
      categoryId: norm.categoryId ?? "",
      gradeId: norm.gradeId ?? "",
      make: norm.make ?? "",
      model: norm.model ?? "",
      equipmentSpec: norm.equipmentSpec ?? "",
      unit: norm.unit ?? "",
      outputPerManPerDay: norm.outputPerManPerDay?.toString() ?? "",
      outputPerHour: norm.outputPerHour?.toString() ?? "",
      crewSize: norm.crewSize?.toString() ?? "",
      outputPerDay: norm.outputPerDay?.toString() ?? "",
      workingHoursPerDay: norm.workingHoursPerDay?.toString() ?? "",
      fuelLitresPerHour: norm.fuelLitresPerHour?.toString() ?? "",
      remarks: norm.remarks ?? "",
    });
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const errors: Record<string, string> = {};
    if (!formData.workActivityId) {
      errors.workActivityId = "Pick a master Work Activity";
    }
    if (!formData.unit.trim()) {
      errors.unit = "Unit is required (e.g. Sqm, Cum, MT)";
    }
    if ((formData.scope === "VARIANT" || formData.scope === "ROLE") && !formData.roleId) {
      errors.roleId = "Pick a Role for the scope";
    }
    // UNSCOPED: applies to any resource on this work activity. No further validation needed.
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setError("Fix the highlighted fields and try again");
      return;
    }
    setFieldErrors({});

    try {
      // Variant carries role + variant fields; Role carries role only; Unscoped clears them all.
      const roleScopeActive = formData.scope === "VARIANT" || formData.scope === "ROLE";
      const variantActive = formData.scope === "VARIANT";
      const base: CreateProductivityNormRequest = {
        normType: tab,
        workActivityId: formData.workActivityId,
        // Legacy fields stay null on new rows from this form. Existing legacy rows keep their
        // values via the update endpoint (we only overwrite the role-keyed columns).
        resourceTypeId: null,
        resourceId: null,
        roleId: roleScopeActive ? formData.roleId || null : null,
        categoryId: variantActive && tab === "MANPOWER" ? formData.categoryId || null : null,
        gradeId: variantActive && tab === "MANPOWER" ? formData.gradeId || null : null,
        make: variantActive && tab === "EQUIPMENT" ? (formData.make.trim() || null) : null,
        model: variantActive && tab === "EQUIPMENT" ? (formData.model.trim() || null) : null,
        unit: formData.unit,
        remarks: formData.remarks || undefined,
        outputPerDay: toNumberOrUndefined(formData.outputPerDay),
      };

      const request: CreateProductivityNormRequest =
        tab === "MANPOWER"
          ? {
              ...base,
              outputPerManPerDay: toNumberOrUndefined(formData.outputPerManPerDay),
              crewSize: toIntOrUndefined(formData.crewSize),
            }
          : {
              ...base,
              equipmentSpec: formData.equipmentSpec || undefined,
              outputPerHour: toNumberOrUndefined(formData.outputPerHour),
              workingHoursPerDay: toNumberOrUndefined(formData.workingHoursPerDay),
              fuelLitresPerHour: toNumberOrUndefined(formData.fuelLitresPerHour),
            };

      if (editingId) {
        await productivityNormApi.update(editingId, request);
      } else {
        await productivityNormApi.create(request);
      }
      setFormData(initialFormState);
      setShowForm(false);
      setEditingId(null);
      setError(null);
      queryClient.invalidateQueries({ queryKey: ["productivity-norms", tab] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, editingId
          ? "Failed to update productivity norm"
          : "Failed to create productivity norm"));
    }
  };

  const handleDelete = useCallback(
    async (id: string) => {
      if (!window.confirm("Delete this productivity norm?")) return;
      try {
        await productivityNormApi.delete(id);
        queryClient.invalidateQueries({ queryKey: ["productivity-norms", tab] });
      } catch (err: unknown) {
        setError(getErrorMessage(err, "Failed to delete productivity norm"));
      }
    },
    [tab, queryClient]
  );

  const handleActivityPick = (id: string) => {
    const wa = activities.find((a) => a.id === id);
    setFormData({
      ...formData,
      workActivityId: id,
      unit: formData.unit || wa?.defaultUnit || "",
    });
  };

  const manpowerColumns: ColumnDef<ProductivityNormResponse>[] = useMemo(
    () => [
      {
        accessorKey: "workActivityName",
        header: "Activity",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return row.workActivityName ?? row.activityName ?? "—";
        },
      },
      {
        accessorKey: "scope",
        header: "Scope",
        enableSorting: false,
        cell: (info) => {
          const row = info.row.original;
          return <ScopeBadge norm={row} />;
        },
      },
      { accessorKey: "unit", header: "Unit", enableSorting: true },
      {
        accessorKey: "outputPerManPerDay",
        header: "Output / Man / Day",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.outputPerManPerDay);
        },
      },
      {
        accessorKey: "crewSize",
        header: "Crew Size",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.crewSize);
        },
      },
      {
        accessorKey: "outputPerDay",
        header: "Gang Output / Day",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.outputPerDay);
        },
      },
      {
        accessorKey: "remarks",
        header: "Remarks",
        enableSorting: false,
        cell: (info) => {
          const row = info.row.original;
          return row.remarks || "—";
        },
      },
      {
        accessorKey: "actions",
        header: "Actions",
        enableSorting: false,
        cell: (info) => {
          const row = info.row.original;
          return (
            <div className="flex items-center gap-2">
              <button
                onClick={() => handleEdit(row)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm text-info bg-info/10 ring-1 ring-info/20 rounded-lg hover:bg-info/20 transition-colors"
                title="Edit"
              >
                <Pencil size={14} />
                <span className="hidden sm:inline">Edit</span>
              </button>
              <button
                onClick={() => handleDelete(row.id)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm text-danger bg-danger/10 ring-1 ring-danger/20 rounded-lg hover:bg-danger/20 transition-colors"
                title="Delete"
              >
                <Trash2 size={14} />
                <span className="hidden sm:inline">Delete</span>
              </button>
            </div>
          );
        },
      },
    ],
    [handleDelete, handleEdit]
  );

  const equipmentColumns: ColumnDef<ProductivityNormResponse>[] = useMemo(
    () => [
      {
        accessorKey: "equipmentSpec",
        header: "Equipment Spec",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return row.equipmentSpec || "—";
        },
      },
      {
        accessorKey: "workActivityName",
        header: "Activity",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return row.workActivityName ?? row.activityName ?? "—";
        },
      },
      {
        accessorKey: "scope",
        header: "Scope",
        enableSorting: false,
        cell: (info) => {
          const row = info.row.original;
          return <ScopeBadge norm={row} />;
        },
      },
      { accessorKey: "unit", header: "Unit", enableSorting: true },
      {
        accessorKey: "outputPerHour",
        header: "Output / Hour",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.outputPerHour);
        },
      },
      {
        accessorKey: "workingHoursPerDay",
        header: "Working Hrs / Day",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.workingHoursPerDay);
        },
      },
      {
        accessorKey: "outputPerDay",
        header: "Output / Day",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.outputPerDay);
        },
      },
      {
        accessorKey: "fuelLitresPerHour",
        header: "Fuel L/Hr",
        enableSorting: true,
        cell: (info) => {
          const row = info.row.original;
          return formatNumber(row.fuelLitresPerHour);
        },
      },
      {
        accessorKey: "remarks",
        header: "Remarks",
        enableSorting: false,
        cell: (info) => {
          const row = info.row.original;
          return row.remarks || "—";
        },
      },
      {
        accessorKey: "actions",
        header: "Actions",
        enableSorting: false,
        cell: (info) => {
          const row = info.row.original;
          return (
            <div className="flex items-center gap-2">
              <button
                onClick={() => handleEdit(row)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm text-info bg-info/10 ring-1 ring-info/20 rounded-lg hover:bg-info/20 transition-colors"
                title="Edit"
              >
                <Pencil size={14} />
                <span className="hidden sm:inline">Edit</span>
              </button>
              <button
                onClick={() => handleDelete(row.id)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm text-danger bg-danger/10 ring-1 ring-danger/20 rounded-lg hover:bg-danger/20 transition-colors"
                title="Delete"
              >
                <Trash2 size={14} />
                <span className="hidden sm:inline">Delete</span>
              </button>
            </div>
          );
        },
      },
    ],
    [handleDelete, handleEdit]
  );

  if (isLoading && norms.length === 0) {
    return <div className="p-6 text-text-muted">Loading norms...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Productivity Norms"
        description="Activity-wise man-day and equipment-hour output rates; the seed for resource estimates and daily-report validation."
      />

      <div className="mb-8">
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
          <h1 className="text-3xl font-bold text-text-primary">Productivity Norms</h1>
          <button
            onClick={() => {
              if (showForm) {
                cancelForm();
              } else {
                setEditingId(null);
                setFormData(initialFormState);
                setShowForm(true);
              }
            }}
            className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors font-medium"
          >
            {showForm ? "Cancel" : "Add Norm"}
          </button>
        </div>

        {/* Tabs */}
        <div className="flex gap-2 mb-6 border-b border-border">
          <button
            onClick={() => handleTabChange("MANPOWER")}
            className={`px-4 py-2 rounded-t-lg text-sm font-medium transition-colors ${
              tab === "MANPOWER"
                ? "bg-accent text-accent-foreground"
                : "bg-surface-active/50 text-text-secondary hover:bg-border"
            }`}
          >
            Manpower
          </button>
          <button
            onClick={() => handleTabChange("EQUIPMENT")}
            className={`px-4 py-2 rounded-t-lg text-sm font-medium transition-colors ${
              tab === "EQUIPMENT"
                ? "bg-accent text-accent-foreground"
                : "bg-surface-active/50 text-text-secondary hover:bg-border"
            }`}
          >
            Equipment
          </button>
        </div>

        {error && !showForm && (
          <div className="mb-4 rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-sm text-danger">
            {error}
          </div>
        )}

        {showForm && (
          <>
            <div
              className="fixed inset-0 z-30 bg-black/50"
              onClick={cancelForm}
              aria-hidden="true"
            />
            <aside
              className="fixed right-0 top-0 z-40 flex h-screen w-full flex-col border-l border-border bg-surface shadow-xl md:w-[720px] lg:w-[880px]"
              role="dialog"
              aria-modal="true"
              aria-label={editingId ? "Edit Productivity Norm" : "New Productivity Norm"}
            >
              <header className="flex items-center justify-between border-b border-border px-5 py-3">
                <h2 className="text-base font-semibold text-text-primary">
                  {editingId ? "Edit Productivity Norm" : "New Productivity Norm"}
                </h2>
                <button
                  type="button"
                  onClick={cancelForm}
                  aria-label="Close"
                  className="rounded-md p-1 text-text-secondary hover:bg-surface-active hover:text-text-primary"
                >
                  <X size={16} />
                </button>
              </header>
              <form onSubmit={handleSubmit} className="flex flex-1 flex-col overflow-hidden">
                <div className="flex-1 overflow-y-auto px-5 py-4">
                  {error && (
                    <div className="mb-4 rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-sm text-danger">
                      {error}
                    </div>
                  )}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Work Activity <span className="text-danger">*</span>
                </label>
                <select
                  value={formData.workActivityId}
                  onChange={(e) => {
                    handleActivityPick(e.target.value);
                    clearFieldError("workActivityId");
                  }}
                  className={`w-full px-3 py-2 border bg-surface-hover text-text-primary rounded-lg ${
                    fieldErrors.workActivityId ? "border-danger" : "border-border"
                  }`}
                  aria-invalid={!!fieldErrors.workActivityId}
                >
                  <option value="">— select a master activity —</option>
                  {activities.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name}
                      {a.defaultUnit ? ` (${a.defaultUnit})` : ""}
                    </option>
                  ))}
                </select>
                {fieldErrors.workActivityId && (
                  <p className="mt-1 text-xs text-danger">{fieldErrors.workActivityId}</p>
                )}
                <FieldHint>
                  Pick from the master library at <em>Admin → Work Activities</em>. The same activity
                  can carry different norms per resource type or specific resource.
                </FieldHint>
                {activities.length === 0 && (
                  <FieldHint>
                    No activities yet — create one in <em>Admin → Work Activities</em> first.
                  </FieldHint>
                )}
              </div>

              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Scope <span className="text-danger">*</span>
                </label>
                <div className="flex flex-wrap gap-4 mb-2">
                  <label className="inline-flex items-center gap-2 text-text-secondary">
                    <input
                      type="radio"
                      name="scope"
                      checked={formData.scope === "VARIANT"}
                      onChange={() => setFormData({ ...formData, scope: "VARIANT" })}
                    />
                    Variant (role + skill/grade or make/model)
                  </label>
                  <label className="inline-flex items-center gap-2 text-text-secondary">
                    <input
                      type="radio"
                      name="scope"
                      checked={formData.scope === "ROLE"}
                      onChange={() =>
                        setFormData({
                          ...formData,
                          scope: "ROLE",
                          categoryId: "",
                          gradeId: "",
                          make: "",
                          model: "",
                        })
                      }
                    />
                    Role only
                  </label>
                  <label className="inline-flex items-center gap-2 text-text-secondary">
                    <input
                      type="radio"
                      name="scope"
                      checked={formData.scope === "UNSCOPED"}
                      onChange={() =>
                        setFormData({
                          ...formData,
                          scope: "UNSCOPED",
                          roleId: "",
                          categoryId: "",
                          gradeId: "",
                          make: "",
                          model: "",
                        })
                      }
                    />
                    Unscoped (any role on this activity)
                  </label>
                </div>
                <FieldHint>
                  <strong>Variant</strong> targets a specific role + (skill/grade for manpower
                  · make/model for equipment). <strong>Role</strong> applies to any variant of
                  that role. <strong>Unscoped</strong> falls through to any resource on the
                  activity. At runtime the lookup chain is{" "}
                  <code className="px-1 bg-surface/50 rounded">variant → role → unscoped</code>.
                </FieldHint>
                {(formData.scope === "VARIANT" || formData.scope === "ROLE") && (
                  <>
                    <select
                      value={formData.roleId}
                      onChange={(e) => {
                        setFormData({ ...formData, roleId: e.target.value });
                        clearFieldError("roleId");
                      }}
                      className={`w-full px-3 py-2 border bg-surface-hover text-text-primary rounded-lg ${
                        fieldErrors.roleId ? "border-danger" : "border-border"
                      }`}
                      aria-invalid={!!fieldErrors.roleId}
                    >
                      <option value="">— select a role —</option>
                      {filteredRoles.map((r) => (
                        <option key={r.id} value={r.id}>
                          {r.code} — {r.name}
                        </option>
                      ))}
                    </select>
                    {fieldErrors.roleId && (
                      <p className="mt-1 text-xs text-danger">{fieldErrors.roleId}</p>
                    )}
                  </>
                )}
                {formData.scope === "VARIANT" && tab === "MANPOWER" && (
                  <div className="grid grid-cols-2 gap-3 mt-3">
                    <div>
                      <label className="block text-xs font-medium mb-1 text-text-secondary">
                        Category (optional)
                      </label>
                      <select
                        value={formData.categoryId}
                        onChange={(e) => setFormData({ ...formData, categoryId: e.target.value })}
                        className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                      >
                        <option value="">— any category —</option>
                        {topCategories.map((c) => (
                          <option key={c.id} value={c.id}>
                            {c.name}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-medium mb-1 text-text-secondary">
                        Grade (optional)
                      </label>
                      <select
                        value={formData.gradeId}
                        onChange={(e) => setFormData({ ...formData, gradeId: e.target.value })}
                        className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                      >
                        <option value="">— any grade —</option>
                        {grades.map((g) => (
                          <option key={g.id} value={g.id}>
                            {g.code} — {g.name}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>
                )}
                {formData.scope === "VARIANT" && tab === "EQUIPMENT" && (
                  <div className="grid grid-cols-2 gap-3 mt-3">
                    <div>
                      <label className="block text-xs font-medium mb-1 text-text-secondary">
                        Make (optional)
                      </label>
                      <input
                        type="text"
                        value={formData.make}
                        onChange={(e) => setFormData({ ...formData, make: e.target.value })}
                        className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                        placeholder="e.g. CAT"
                      />
                    </div>
                    <div>
                      <label className="block text-xs font-medium mb-1 text-text-secondary">
                        Model (optional)
                      </label>
                      <input
                        type="text"
                        value={formData.model}
                        onChange={(e) => setFormData({ ...formData, model: e.target.value })}
                        className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                        placeholder="e.g. 320D"
                      />
                    </div>
                  </div>
                )}
                {formData.scope === "UNSCOPED" && (
                  <FieldHint>
                    <em>
                      No role binding — falls through as the final tier of the resolver chain. The
                      102 legacy norms imported from the seed workbook live in this bucket.
                    </em>
                  </FieldHint>
                )}
              </div>

              {tab === "EQUIPMENT" && (
                <div>
                  <label className="block text-sm font-medium mb-1 text-text-secondary">
                    Equipment Spec
                  </label>
                  <input
                    type="text"
                    value={formData.equipmentSpec}
                    onChange={(e) => setFormData({ ...formData, equipmentSpec: e.target.value })}
                    className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                    placeholder='e.g. "JCB 210 (1.0 Cum Bucket)"'
                  />
                  <FieldHint>
                    Free-text description of the make / model / capacity. Useful when multiple
                    equipment types share the same Resource Type.
                  </FieldHint>
                </div>
              )}
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Unit <span className="text-danger">*</span>
                </label>
                <select
                  value={formData.unit}
                  onChange={(e) => {
                    setFormData({ ...formData, unit: e.target.value });
                    clearFieldError("unit");
                  }}
                  className={`w-full px-3 py-2 border bg-surface-hover text-text-primary rounded-lg ${
                    fieldErrors.unit ? "border-danger" : "border-border"
                  }`}
                  aria-invalid={!!fieldErrors.unit}
                >
                  <option value="">— select a unit —</option>
                  {unitOptionsWithFallback(formData.unit).map((u) => (
                    <option key={u} value={u}>
                      {u}
                      {!(STANDARD_UNITS as readonly string[]).includes(u) ? " (legacy)" : ""}
                    </option>
                  ))}
                </select>
                {fieldErrors.unit && (
                  <p className="mt-1 text-xs text-danger">{fieldErrors.unit}</p>
                )}
                <FieldHint>
                  Auto-fills from the selected Work Activity. Same dropdown the DPR form uses, so
                  the values stay consistent. Override only if this norm uses a different unit
                  from the activity master.
                </FieldHint>
              </div>

              {tab === "MANPOWER" ? (
                <>
                  <div className="md:col-span-2 flex gap-2.5 p-3 rounded-lg bg-info/5 border border-info/20 text-xs text-text-secondary">
                    <Info size={15} className="mt-0.5 flex-shrink-0 text-info/70" strokeWidth={1.75} />
                    <p className="leading-relaxed">
                      Fill <strong>Output per Man per Day</strong> + <strong>Crew Size</strong> to
                      describe the standard gang. <strong>Output per Day</strong> is the gang&apos;s
                      combined output (= Output/Man × Crew Size). Leave it blank in the typical case;
                      fill it only when you want to pin a specific gang output that doesn&apos;t match
                      the multiplication.
                    </p>
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Output per Man per Day
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={formData.outputPerManPerDay}
                      onChange={(e) =>
                        setFormData({ ...formData, outputPerManPerDay: e.target.value })
                      }
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                    />
                    <FieldHint>
                      What ONE worker produces in a normal 8-hour day. e.g. 2.5 Cum/day for hand
                      excavation, 12 Sqm/day for 12 mm plastering. CPWD / IS-7272 lists baseline
                      values; calibrate against your own daily-output history once you have data —
                      Indian site studies show real productivity typically runs 55–77% of CPWD
                      figures.
                    </FieldHint>
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Crew Size
                    </label>
                    <input
                      type="number"
                      step="1"
                      value={formData.crewSize}
                      onChange={(e) => setFormData({ ...formData, crewSize: e.target.value })}
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                    />
                    <FieldHint>
                      Standard gang size for this activity. e.g. 4 (1 mason + 3 helpers) for brick
                      masonry, 2 (1 fitter + 1 helper) for bar bending.
                    </FieldHint>
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Output per Day (optional)
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={formData.outputPerDay}
                      onChange={(e) => setFormData({ ...formData, outputPerDay: e.target.value })}
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                    />
                    <FieldHint>
                      Leave blank to imply (Output per Man per Day) × (Crew Size). Fill only when
                      the actual gang output differs from that multiplication.
                    </FieldHint>
                  </div>
                </>
              ) : (
                <>
                  <div className="md:col-span-2 flex gap-2.5 p-3 rounded-lg bg-info/5 border border-info/20 text-xs text-text-secondary">
                    <Info size={15} className="mt-0.5 flex-shrink-0 text-info/70" strokeWidth={1.75} />
                    <p className="leading-relaxed">
                      Enter the daily norm directly (e.g. 4 000 Sqm/Day for a Bull Dozer). The
                      per-hour breakdown below is optional — the server uses{" "}
                      <code className="px-1 bg-surface/50 rounded">outputPerDay</code> when
                      supplied; otherwise it derives it from <em>per-hour × working hours</em>.
                    </p>
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Output per Day <span className="text-danger">*</span>
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={formData.outputPerDay}
                      onChange={(e) => setFormData({ ...formData, outputPerDay: e.target.value })}
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                      placeholder="e.g. 4000"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Output per Hour <span className="text-text-muted">(optional)</span>
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={formData.outputPerHour}
                      onChange={(e) => setFormData({ ...formData, outputPerHour: e.target.value })}
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Working Hours per Day <span className="text-text-muted">(optional)</span>
                    </label>
                    <input
                      type="number"
                      step="0.1"
                      value={formData.workingHoursPerDay}
                      onChange={(e) =>
                        setFormData({ ...formData, workingHoursPerDay: e.target.value })
                      }
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                      placeholder="default 8"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1 text-text-secondary">
                      Fuel Litres per Hour
                    </label>
                    <input
                      type="number"
                      step="0.01"
                      value={formData.fuelLitresPerHour}
                      onChange={(e) =>
                        setFormData({ ...formData, fuelLitresPerHour: e.target.value })
                      }
                      className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                    />
                  </div>
                </>
              )}

              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Remarks
                </label>
                <textarea
                  value={formData.remarks}
                  onChange={(e) => setFormData({ ...formData, remarks: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  rows={3}
                />
              </div>
            </div>
                </div>
                <div className="flex justify-end gap-2 border-t border-border bg-surface px-5 py-3">
                  <button
                    type="button"
                    onClick={cancelForm}
                    className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600"
                  >
                    {editingId ? "Update Norm" : "Save Norm"}
                  </button>
                </div>
              </form>
            </aside>
          </>
        )}

        {/* Norms — grouped by scope (Resource Type / Specific Resource) so the layout mirrors
            the spreadsheet's S.No. → equipment-section → activities pattern. */}
        <div className="space-y-6">
          {groupNormsByScope(norms).map((group, gIdx) => (
            <div key={group.key} className="border border-border rounded-lg overflow-hidden bg-surface/30">
              <div className="bg-accent/10 text-text-primary px-4 py-2 flex items-center gap-3 font-semibold">
                <span className="inline-flex items-center justify-center w-6 h-6 rounded-full bg-accent/20 text-accent text-xs font-bold">
                  {gIdx + 1}
                </span>
                <span className="uppercase tracking-wide">{group.label}</span>
                <span className="text-xs font-normal text-text-muted">
                  · {group.rows.length} {group.rows.length === 1 ? "norm" : "norms"}
                </span>
              </div>
              <VirtualDataTable
                columns={tab === "MANPOWER" ? manpowerColumns : equipmentColumns}
                data={group.rows}
                sortable
                resizable
              />
            </div>
          ))}
          {norms.length === 0 && (
            <div className="text-center text-text-muted py-12 border border-dashed border-border rounded-lg">
              No {tab.toLowerCase()} norms yet — click <strong>Add Norm</strong> above to start.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

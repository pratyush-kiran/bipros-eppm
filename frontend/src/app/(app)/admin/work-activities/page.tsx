"use client";

import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";

import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  workActivityApi,
  type CreateWorkActivityRequest,
  type NormCombination,
  type WorkActivityResponse,
} from "@/lib/api/workActivityApi";
import { TabTip } from "@/components/common/TabTip";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { getErrorMessage } from "@/lib/utils/error";
import { unitOptionsWithFallback, STANDARD_UNITS } from "@/lib/constants/units";

interface ActivityForm {
  code: string;
  name: string;
  defaultUnit: string;
  discipline: string;
  description: string;
  sortOrder: string;
  active: boolean;
  normCombination: NormCombination;
}

const initialFormState: ActivityForm = {
  code: "",
  name: "",
  defaultUnit: "",
  discipline: "",
  description: "",
  sortOrder: "",
  active: true,
  normCombination: "SERIES",
};

const toIntOrUndefined = (value: string): number | undefined => {
  if (value === "" || value === null || value === undefined) return undefined;
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
};

export default function WorkActivitiesPage() {
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formData, setFormData] = useState<ActivityForm>(initialFormState);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [confirmDialog, setConfirmDialog] = useState<{
    open: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
  }>({ open: false, title: "", message: "", onConfirm: () => {} });

  const queryClient = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: ["work-activities"],
    queryFn: () => workActivityApi.list(),
  });

  const activities: WorkActivityResponse[] = useMemo(() => data?.data ?? [], [data]);

  // Filter on code + name + discipline. Case-insensitive substring match — the dataset is small
  // enough (≤ a few thousand entries) that client-side filtering is fine without debouncing.
  const filteredActivities = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return activities;
    return activities.filter((a) => {
      const haystack = [a.code, a.name, a.discipline ?? "", a.defaultUnit ?? ""]
        .join(" ")
        .toLowerCase();
      return haystack.includes(q);
    });
  }, [activities, searchQuery]);

  const resetForm = () => {
    setFormData(initialFormState);
    setEditingId(null);
    setShowForm(false);
    setError(null);
  };

  const handleEdit = (activity: WorkActivityResponse) => {
    setEditingId(activity.id);
    setFormData({
      code: activity.code,
      name: activity.name,
      defaultUnit: activity.defaultUnit ?? "",
      discipline: activity.discipline ?? "",
      description: activity.description ?? "",
      sortOrder: activity.sortOrder?.toString() ?? "",
      active: activity.active,
      normCombination: activity.normCombination ?? "SERIES",
    });
    setShowForm(true);
    setError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const request: CreateWorkActivityRequest = {
        code: formData.code || undefined,
        name: formData.name,
        defaultUnit: formData.defaultUnit || undefined,
        discipline: formData.discipline || undefined,
        description: formData.description || undefined,
        sortOrder: toIntOrUndefined(formData.sortOrder),
        active: formData.active,
        normCombination: formData.normCombination,
      };
      if (editingId) {
        await workActivityApi.update(editingId, request);
      } else {
        await workActivityApi.create(request);
      }
      resetForm();
      queryClient.invalidateQueries({ queryKey: ["work-activities"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save work activity"));
    }
  };

  const handleDelete = (id: string) => {
    setConfirmDialog({
      open: true,
      title: "Delete Work Activity",
      message: "Are you sure you want to delete this work activity? This action cannot be undone.",
      onConfirm: async () => {
        setConfirmDialog((d) => ({ ...d, open: false }));
        try {
          await workActivityApi.delete(id);
          queryClient.invalidateQueries({ queryKey: ["work-activities"] });
        } catch (err: unknown) {
          setError(getErrorMessage(err, "Failed to delete work activity"));
        }
      },
    });
  };

  const handleDeleteAll = () => {
    setConfirmDialog({
      open: true,
      title: "Delete All Work Activities",
      message:
        "This will permanently remove all work activities that are not referenced by productivity norms. Activities linked to norms will be skipped.",
      onConfirm: async () => {
        setConfirmDialog((d) => ({ ...d, open: false }));
        try {
          await workActivityApi.deleteAll();
          queryClient.invalidateQueries({ queryKey: ["work-activities"] });
        } catch (err: unknown) {
          setError(getErrorMessage(err, "Failed to delete work activities"));
        }
      },
    });
  };

  const columns = useMemo<ColumnDef<WorkActivityResponse>[]>(() => [
    {
      accessorKey: "code",
      header: "Code",
      cell: ({ row }) => <span className="font-mono text-sm">{row.original.code}</span>,
    },
    {
      accessorKey: "name",
      header: "Name",
      cell: ({ row }) => <span>{row.original.name}</span>,
    },
    {
      accessorKey: "defaultUnit",
      header: "Default Unit",
      cell: ({ row }) => <span>{row.original.defaultUnit ?? "-"}</span>,
    },
    {
      accessorKey: "discipline",
      header: "Discipline",
      cell: ({ row }) => <span>{row.original.discipline ?? "-"}</span>,
    },
    {
      accessorKey: "sortOrder",
      header: "Sort",
      cell: ({ row }) => <span className="text-right block">{row.original.sortOrder ?? "-"}</span>,
    },
    {
      accessorKey: "active",
      header: "Active",
      cell: ({ row }) => <span className="text-center block">{row.original.active ? "✓" : "—"}</span>,
    },
    {
      id: "actions",
      header: "Actions",
      cell: ({ row }) => (
        <div className="flex gap-2">
          <button
            onClick={() => handleEdit(row.original)}
            className="px-3 py-1 bg-accent/10 text-accent ring-1 ring-accent/20 rounded hover:bg-accent/20"
          >
            Edit
          </button>
          <button
            onClick={() => handleDelete(row.original.id)}
            className="px-3 py-1 bg-danger/10 text-danger ring-1 ring-red-500/20 rounded hover:bg-danger/20"
          >
            Delete
          </button>
        </div>
      ),
    },
  ], [handleEdit, handleDelete]);

  if (isLoading && activities.length === 0) {
    return <div className="p-6 text-text-muted">Loading work activities...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Work Activities"
        description="Master library of unit-of-work definitions (Clearing & Grubbing, Subgrade Preparation, …). Productivity Norms attach to one of these and to a resource type or specific resource — same activity can have a different norm per resource."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Work Activities</h1>

        <div className="flex gap-3 mb-6">
          <button
            onClick={() => (showForm ? resetForm() : setShowForm(true))}
            className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
          >
            {showForm ? "Cancel" : "Add Activity"}
          </button>
          {activities.length > 0 && (
            <button
              onClick={handleDeleteAll}
              className="px-4 py-2 bg-danger text-text-primary rounded-lg hover:bg-red-600"
            >
              Delete All
            </button>
          )}
        </div>

        {error && <div className="text-danger mb-4">{error}</div>}

        {showForm && (
          <form
            onSubmit={handleSubmit}
            className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl"
          >
            <div className="mb-4 p-3 rounded-lg bg-info/5 border border-info/20 text-xs text-text-muted">
              This is a <strong>master library</strong> entry — a reusable definition of one
              <em> kind of work</em>. It feeds two places: (1) <strong>Productivity Norms</strong>
              {" "}attach to it (&quot;for this activity, X output per day&quot;), and
              (2) <strong>project Activities</strong> can reference it as their work-type, which
              lets the Capacity Utilization report find the matching norm. Add an entry once;
              reuse across every project.
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Code <span className="text-text-muted">(auto-generated from name when blank)</span>
                </label>
                <input
                  type="text"
                  value={formData.code}
                  onChange={(e) => setFormData({ ...formData, code: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  maxLength={50}
                />
                <p className="text-xs text-text-muted mt-1">
                  Short identifier shown in tables and exports (e.g. <em>WA_EXC</em>,{" "}
                  <em>WA_PNT</em>). Leave blank to auto-generate from the name.
                </p>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Name <span className="text-danger">*</span>
                </label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                  maxLength={150}
                />
                <p className="text-xs text-text-muted mt-1">
                  Human-friendly label. Use the same wording your site team uses (e.g.{" "}
                  <em>Brick Masonry</em>, <em>Plastering</em>) so reports stay recognisable.
                </p>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Default Unit
                </label>
                <select
                  value={formData.defaultUnit}
                  onChange={(e) => setFormData({ ...formData, defaultUnit: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="">— select a unit —</option>
                  {unitOptionsWithFallback(formData.defaultUnit).map((u) => (
                    <option key={u} value={u}>
                      {u}
                      {!(STANDARD_UNITS as readonly string[]).includes(u) ? " (legacy)" : ""}
                    </option>
                  ))}
                </select>
                <p className="text-xs text-text-muted mt-1">
                  Pre-fills the <em>Unit</em> field on the DPR form and the Productivity Norms
                  form when this activity is selected. Same dropdown the DPR form uses, so the
                  values stay consistent. Sqm for plastering, Cum for excavation, MT for steel.
                </p>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Discipline
                </label>
                <input
                  type="text"
                  value={formData.discipline}
                  onChange={(e) => setFormData({ ...formData, discipline: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  placeholder="earthwork / pavement / structures"
                  maxLength={50}
                />
                <p className="text-xs text-text-muted mt-1">
                  Free-text grouping label used in reports and dropdown ordering. Common values:{" "}
                  <em>earthwork</em>, <em>pavement</em>, <em>structures</em>, <em>finishing</em>,{" "}
                  <em>MEP</em>. No fixed list — pick what suits your project mix.
                </p>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Sort Order
                </label>
                <input
                  type="number"
                  step="1"
                  value={formData.sortOrder}
                  onChange={(e) => setFormData({ ...formData, sortOrder: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
                <p className="text-xs text-text-muted mt-1">
                  Lower numbers appear first in activity dropdowns. Leave blank to fall back to
                  alphabetical order.
                </p>
              </div>
              <div className="flex flex-col">
                <div className="flex items-end h-[42px]">
                  <label className="inline-flex items-center gap-2 text-text-secondary">
                    <input
                      type="checkbox"
                      checked={formData.active}
                      onChange={(e) => setFormData({ ...formData, active: e.target.checked })}
                      className="h-4 w-4"
                    />
                    Active
                  </label>
                </div>
                <p className="text-xs text-text-muted mt-1">
                  Inactive activities are hidden from <em>new</em> Productivity Norm forms but
                  existing norms and project Activities continue to work. Use this instead of
                  deleting when an activity is no longer used on new work.
                </p>
              </div>
              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Description
                </label>
                <textarea
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  rows={3}
                  maxLength={500}
                />
                <p className="text-xs text-text-muted mt-1">
                  Optional notes — scope inclusions / exclusions, measurement convention, contract
                  reference. Shows up only on this admin page.
                </p>
              </div>

              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Norm combination
                </label>
                <p className="text-xs text-text-muted mb-2">
                  How should the DPR preview combine expected output when this activity has{" "}
                  <em>both</em> Manpower and Equipment productivity norms? Ignored when only one
                  side has a norm — that single side drives the expected output regardless.
                </p>
                <div className="flex flex-col gap-2">
                  {(
                    [
                      {
                        value: "SERIES" as const,
                        title: "Series — bottleneck (default)",
                        body: (
                          <>
                            Manpower and equipment work on the <em>same</em> unit of output, in
                            sequence (e.g. excavator digs, mason cleans behind it). Expected ={" "}
                            <strong>min(Manpower, Equipment)</strong>. Slowest side caps output.
                            Use for excavation, concreting, paving, plastering — most gang work.
                          </>
                        ),
                      },
                      {
                        value: "PARALLEL" as const,
                        title: "Parallel — independent teams",
                        body: (
                          <>
                            Manpower team and equipment team work <em>independently</em> on
                            different stretches. Expected ={" "}
                            <strong>Manpower + Equipment</strong>. Outputs add. Use for road side
                            clearance, brush cutting, survey — where the two sides cover different
                            ground.
                          </>
                        ),
                      },
                      {
                        value: "SUBSTITUTE" as const,
                        title: "Substitute — either alone",
                        body: (
                          <>
                            Either side alone can finish the unit; the slower one is redundant.
                            Expected = <strong>max(Manpower, Equipment)</strong>. Rare — use only
                            for activities like demolition where hammer-and-JCB are
                            interchangeable. Default to Series if unsure.
                          </>
                        ),
                      },
                    ] as const
                  ).map((opt) => (
                    <label
                      key={opt.value}
                      className={`flex items-start gap-3 rounded-lg border px-3 py-2 cursor-pointer ${
                        formData.normCombination === opt.value
                          ? "border-accent bg-accent/10"
                          : "border-border bg-surface-hover"
                      }`}
                    >
                      <input
                        type="radio"
                        name="norm-combination"
                        value={opt.value}
                        checked={formData.normCombination === opt.value}
                        onChange={() =>
                          setFormData({ ...formData, normCombination: opt.value })
                        }
                        className="mt-1"
                      />
                      <div className="flex-1">
                        <div className="text-sm font-medium text-text-primary">{opt.title}</div>
                        <div className="text-xs text-text-muted mt-1">{opt.body}</div>
                      </div>
                    </label>
                  ))}
                </div>
              </div>
            </div>
            <div className="flex gap-2 mt-4">
              <button
                type="submit"
                className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600"
              >
                {editingId ? "Update Activity" : "Save Activity"}
              </button>
              <button
                type="button"
                onClick={resetForm}
                className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
              >
                Cancel
              </button>
            </div>
          </form>
        )}


        <div className="mb-3 flex items-center gap-3">
          <div className="relative flex-1 max-w-md">
            <input
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by code, name, unit, or discipline…"
              className="w-full px-3 py-2 pl-9 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
            <svg
              className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.3-4.3" />
            </svg>
          </div>
          <span className="text-xs text-text-muted">
            {searchQuery
              ? `${filteredActivities.length} of ${activities.length} matching`
              : `${activities.length} activities`}
          </span>
        </div>

        <VirtualDataTable columns={columns} data={filteredActivities} sortable resizable searchable={false} />
      </div>

      <ConfirmDialog
        open={confirmDialog.open}
        title={confirmDialog.title}
        message={confirmDialog.message}
        onConfirm={confirmDialog.onConfirm}
        onCancel={() => setConfirmDialog((d) => ({ ...d, open: false }))}
      />
    </div>
  );
}

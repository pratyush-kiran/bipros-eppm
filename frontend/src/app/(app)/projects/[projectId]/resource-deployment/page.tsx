"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  resourceDeploymentApi,
  type DailyResourceDeploymentResponse,
  type CreateDailyResourceDeploymentRequest,
  type DeploymentResourceType,
} from "@/lib/api/resourceDeploymentApi";
import { projectApi } from "@/lib/api/projectApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";

type TypeFilter = "ALL" | DeploymentResourceType;

interface ResourceDeploymentForm {
  logDate: string;
  resourceType: DeploymentResourceType;
  resourceDescription: string;
  /** Empty string is the "Auto" / "let the backend derive" signal. */
  nosPlanned: number | "";
  nosDeployed: number;
  hoursWorked: number;
  idleHours: number;
  remarks: string;
}

const today = () => new Date().toISOString().split("T")[0];

const initialFormState: ResourceDeploymentForm = {
  logDate: today(),
  resourceType: "MANPOWER",
  resourceDescription: "",
  nosPlanned: "",
  nosDeployed: 0,
  hoursWorked: 0,
  idleHours: 0,
  remarks: "",
};

const fmtNum = (v: number | null | undefined) =>
  v === null || v === undefined ? "—" : String(v);

export default function ResourceDeploymentPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data;

  const [from, setFrom] = useState<string>("");
  const [to, setTo] = useState<string>("");
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const [appliedFrom, setAppliedFrom] = useState<string>("");
  const [appliedTo, setAppliedTo] = useState<string>("");
  const [appliedType, setAppliedType] = useState<TypeFilter>(typeFilter);

  useEffect(() => {
    if (!project) return;
    if (appliedFrom === "" && project.plannedStartDate) {
      setFrom(project.plannedStartDate);
      setAppliedFrom(project.plannedStartDate);
    }
    if (appliedTo === "" && project.plannedFinishDate) {
      setTo(project.plannedFinishDate);
      setAppliedTo(project.plannedFinishDate);
    }
  }, [project, appliedFrom, appliedTo]);

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<ResourceDeploymentForm>(initialFormState);
  const [error, setError] = useState<string | null>(null);

  const {
    data: listResponse,
    isLoading,
    isError,
    error: queryError,
  } = useQuery({
    queryKey: ["resource-deployment", projectId, appliedFrom, appliedTo, appliedType],
    queryFn: () =>
      resourceDeploymentApi.list(projectId, {
        from: appliedFrom,
        to: appliedTo,
        resourceType: appliedType === "ALL" ? undefined : appliedType,
      }),
    enabled: !!projectId && !!appliedFrom && !!appliedTo,
  });

  const logs: DailyResourceDeploymentResponse[] = useMemo(
    () => (Array.isArray(listResponse?.data) ? (listResponse?.data ?? []) : []),
    [listResponse],
  );

  const handleApply = () => {
    setAppliedFrom(from);
    setAppliedTo(to);
    setAppliedType(typeFilter);
  };

  const invalidate = () => {
    queryClient.invalidateQueries({
      queryKey: ["resource-deployment", projectId, appliedFrom, appliedTo, appliedType],
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const payload: CreateDailyResourceDeploymentRequest = {
        logDate: formData.logDate,
        resourceType: formData.resourceType,
        resourceDescription: formData.resourceDescription,
        // Empty string → null (auto-derive on the backend); 0 also signals "not provided"
        // for back-compat. Any non-zero value is the user's explicit override.
        nosPlanned:
          formData.nosPlanned === "" || formData.nosPlanned === 0
            ? null
            : Number(formData.nosPlanned),
        nosDeployed: formData.nosDeployed,
        hoursWorked: formData.hoursWorked,
        idleHours: formData.idleHours,
        remarks: formData.remarks || null,
      };
      await resourceDeploymentApi.create(projectId, payload);
      setFormData(initialFormState);
      setShowForm(false);
      invalidate();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create resource deployment entry"));
    }
  };

  const handleRecalculateNosPlanned = async () => {
    setError(null);
    try {
      const response = await resourceDeploymentApi.suggestNosPlanned(projectId, {
        logDate: formData.logDate,
        resourceType: formData.resourceType,
      });
      const suggested = response.data ?? null;
      if (suggested == null) {
        setError(
          "No matching ResourceAssignment found for this date — Nos. Planned will stay blank (saved as auto-null).",
        );
        return;
      }
      setFormData({ ...formData, nosPlanned: suggested });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to recalculate planned headcount"));
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this deployment entry?")) return;
    try {
      await resourceDeploymentApi.delete(projectId, id);
      invalidate();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete entry"));
    }
  };

  const columns = useMemo<ColumnDef<DailyResourceDeploymentResponse>[]>(() => [
    { accessorKey: "logDate", header: "Date" },
    {
      accessorKey: "resourceType",
      header: "Type",
      cell: ({ row }) => (
        <span
          className={`px-2 py-1 rounded text-text-primary text-sm ${
            row.original.resourceType === "MANPOWER"
              ? "bg-success/10 text-success ring-1 ring-success/20"
              : "bg-accent/10 text-accent ring-1 ring-accent/20"
          }`}
        >
          {row.original.resourceType}
        </span>
      ),
    },
    { accessorKey: "resourceDescription", header: "Description" },
    {
      accessorKey: "nosPlanned",
      header: "Nos. Planned",
      cell: ({ row }) => (
        <span className="inline-flex items-center gap-1.5">
          {fmtNum(row.original.nosPlanned)}
          {row.original.nosPlannedAuto === true && (
            <span
              className="px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide bg-info/15 text-info ring-1 ring-info/30 rounded"
              title="Derived from ResourceAssignment.plannedUnits"
            >
              auto
            </span>
          )}
        </span>
      ),
    },
    {
      accessorKey: "nosDeployed",
      header: "Nos. Deployed",
      cell: ({ row }) => fmtNum(row.original.nosDeployed),
    },
    {
      accessorKey: "hoursWorked",
      header: "Hours Worked",
      cell: ({ row }) => fmtNum(row.original.hoursWorked),
    },
    {
      accessorKey: "idleHours",
      header: "Idle Hours",
      cell: ({ row }) => fmtNum(row.original.idleHours),
    },
    {
      accessorKey: "remarks",
      header: "Remarks",
      cell: ({ row }) => row.original.remarks ?? "—",
    },
    {
      id: "actions",
      header: "Actions",
      cell: ({ row }) => (
        <button
          onClick={() => handleDelete(row.original.id)}
          className="px-2 py-1 bg-danger/10 text-danger ring-1 ring-red-500/20 rounded text-sm hover:bg-danger/20"
        >
          Delete
        </button>
      ),
    },
  ], [handleDelete]);

  if (isLoading && logs.length === 0) {
    return <div className="p-6 text-text-muted">Loading resource deployment...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Daily Resource Deployment"
        description="Manpower & equipment — nos. planned vs deployed, hours worked, idle hours per day."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Daily Resource Deployment</h1>

        {/* Filter bar */}
        <div className="flex flex-wrap items-end gap-3 mb-6">
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">From</label>
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">To</label>
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">Type</label>
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value as TypeFilter)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            >
              <option value="ALL">All</option>
              <option value="MANPOWER">MANPOWER</option>
              <option value="EQUIPMENT">EQUIPMENT</option>
            </select>
          </div>
          <button
            onClick={handleApply}
            className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
          >
            Apply
          </button>
        </div>

        <button
          onClick={() => setShowForm(!showForm)}
          className="mb-6 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
        >
          {showForm ? "Cancel" : "Add Entry"}
        </button>

        {error && <div className="text-danger mb-4">{error}</div>}
        {isError && (
          <div className="text-danger mb-4">
            {getErrorMessage(queryError, "Failed to load resource deployment")}
          </div>
        )}

        {showForm && (
          <form
            onSubmit={handleSubmit}
            className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl"
          >
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Date</label>
                <input
                  type="date"
                  value={formData.logDate}
                  onChange={(e) => setFormData({ ...formData, logDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Type</label>
                <select
                  value={formData.resourceType}
                  onChange={(e) =>
                    setFormData({
                      ...formData,
                      resourceType: e.target.value as DeploymentResourceType,
                    })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                >
                  <option value="MANPOWER">MANPOWER</option>
                  <option value="EQUIPMENT">EQUIPMENT</option>
                </select>
              </div>
              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Description
                </label>
                <input
                  type="text"
                  value={formData.resourceDescription}
                  onChange={(e) =>
                    setFormData({ ...formData, resourceDescription: e.target.value })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Nos. Planned
                </label>
                <div className="flex gap-2">
                  <input
                    type="number"
                    min={0}
                    value={formData.nosPlanned}
                    placeholder="Auto"
                    onChange={(e) => {
                      const raw = e.target.value;
                      setFormData({
                        ...formData,
                        nosPlanned: raw === "" ? "" : parseInt(raw, 10) || 0,
                      });
                    }}
                    className="flex-1 px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  />
                  <button
                    type="button"
                    onClick={handleRecalculateNosPlanned}
                    className="px-3 py-2 bg-surface-hover text-text-primary border border-border rounded-lg text-sm hover:bg-surface-active"
                    title="Refetch the planned headcount from ResourceAssignment.plannedUnits"
                  >
                    Recalculate from plan
                  </button>
                </div>
                <p className="mt-1 text-xs text-text-muted">
                  Leave blank for "Auto" — the backend will derive nosPlanned from the matching
                  ResourceAssignment.
                </p>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Nos. Deployed
                </label>
                <input
                  type="number"
                  min={0}
                  value={formData.nosDeployed}
                  onChange={(e) =>
                    setFormData({ ...formData, nosDeployed: parseInt(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Hours Worked
                </label>
                <input
                  type="number"
                  min={0}
                  max={24}
                  step="0.1"
                  value={formData.hoursWorked}
                  onChange={(e) =>
                    setFormData({ ...formData, hoursWorked: parseFloat(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Idle Hours
                </label>
                <input
                  type="number"
                  min={0}
                  max={24}
                  step="0.1"
                  value={formData.idleHours}
                  onChange={(e) =>
                    setFormData({ ...formData, idleHours: parseFloat(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
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
            <div className="flex gap-2 mt-4">
              <button
                type="submit"
                className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600"
              >
                Save Entry
              </button>
              <button
                type="button"
                onClick={() => setShowForm(false)}
                className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        <VirtualDataTable
          columns={columns}
          data={logs}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No resource deployment entries for this date range."
        />
      </div>
    </div>
  );
}

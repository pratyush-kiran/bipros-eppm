"use client";

import { useState, useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from "recharts";
import { resourceApi, type ResourceAssignmentResponse } from "@/lib/api/resourceApi";
import { resourceHistogramApi } from "@/lib/api/resourceHistogramApi";
import { activityApi, type ActivityResponse } from "@/lib/api/activityApi";
import { projectResourceApi, type ProjectResourceResponse } from "@/lib/api/projectResourceApi";
import { DataTable, type ColumnDef } from "@/components/common/DataTable";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { Plus, SlidersHorizontal } from "lucide-react";
import { ResourceLevelingDialog } from "./ResourceLevelingDialog";
import { ResourceAssignmentForm } from "./ResourceAssignmentForm";
import { formatDefaultCurrency } from "@/lib/hooks/useCurrency";
import { UdfSection } from "@/components/udf/UdfSection";
import { ResourceAssignmentTree, ViewModeToggle, type AssignmentRow } from "./ResourceAssignmentTree";

interface ResourceAssignmentRow {
  id: string;
  activityId: string;
  resourceId: string | null;
  roleId: string | null;
  effectiveRoleId: string | null;
  effectiveRoleName: string | null;
  unit: string | null;
  projectId: string;
  resourceName: string;
  roleName: string | null;
  activityName: string;
  /** Phase 2: original committed units, frozen unless explicit Re-budget runs. */
  budgetedUnits: number | null;
  /** Phase 2: original committed cost, frozen unless explicit Re-budget runs. */
  budgetedCost: number | null;
  plannedUnits: number;
  actualUnits: number;
  remainingUnits: number;
  rateType: string;
  plannedCost: number;
  actualCost: number;
  remainingCost: number;
  staffed: boolean;
  /** Supervisor on the parent activity — populates the "By Supervisor" view. */
  activitySupervisorResourceId: string | null;
  activitySupervisorName: string | null;
}

export function ResourceAssignmentsTab({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [selectedResourceId, setSelectedResourceId] = useState<string>("");
  const [showLeveling, setShowLeveling] = useState(false);
  const [selectedAssignment, setSelectedAssignment] = useState<{ id: string; resourceName: string; activityName: string } | null>(null);
  const [viewMode, setViewMode] = useState<"flat" | "activity" | "resourceType" | "supervisor">("activity");

  const { data: assignmentsData, isLoading: isLoadingAssignments } = useQuery({
    queryKey: ["resource-assignments", projectId],
    queryFn: () => resourceApi.getProjectResourceAssignments(projectId, 0, 100),
  });

  const { data: poolData } = useQuery({
    queryKey: ["resource-pool", projectId],
    queryFn: () => projectResourceApi.listPool(projectId),
  });

  const { data: activitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 100),
  });

  const { data: histogramData, isLoading: isLoadingHistogram } = useQuery({
    queryKey: ["resource-histogram", projectId, selectedResourceId],
    queryFn: () =>
      selectedResourceId
        ? resourceHistogramApi.getHistogram(projectId, selectedResourceId)
        : Promise.resolve({ data: [], success: true } as unknown as ReturnType<typeof resourceHistogramApi.getHistogram>),
    enabled: !!selectedResourceId,
  });

  const recomputeCostsMutation = useMutation({
    mutationFn: () => resourceApi.recomputeProjectAssignmentCosts(projectId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", projectId] });
    },
  });

  // Re-budget: copy current planned values into budgeted_units / budgeted_cost. Only triggered
  // by an explicit user action (the button in the selected-assignment panel below).
  const rebudgetMutation = useMutation({
    mutationFn: (assignmentId: string) => resourceApi.rebudgetAssignment(projectId, assignmentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", projectId] });
    },
  });

  const assignments = useMemo<ResourceAssignmentResponse[]>(() => {
    const raw = assignmentsData?.data as unknown;
    return Array.isArray(raw)
      ? (raw as ResourceAssignmentResponse[])
      : ((raw as { content?: ResourceAssignmentResponse[] } | undefined)?.content ?? []);
  }, [assignmentsData]);

  const pool = useMemo<ProjectResourceResponse[]>(() => {
    const raw = poolData?.data as unknown;
    return Array.isArray(raw) ? (raw as ProjectResourceResponse[]) : [];
  }, [poolData]);

  const activities = useMemo<ActivityResponse[]>(() => {
    const raw = activitiesData?.data as unknown;
    return Array.isArray(raw)
      ? (raw as ActivityResponse[])
      : ((raw as { content?: ActivityResponse[] } | undefined)?.content ?? []);
  }, [activitiesData]);

  const histogramEntries = histogramData?.data ?? [];

  const assignmentRows: ResourceAssignmentRow[] = useMemo(() => {
    const poolMap = new Map(pool.map((p) => [p.resourceId, p]));
    const activityMap = new Map(activities.map((a) => [a.id, a]));

    return assignments.map((a) => {
      const poolEntry = a.resourceId ? poolMap.get(a.resourceId) : undefined;
      const activity = activityMap.get(a.activityId);
      const anyA = a as unknown as Record<string, unknown>;
      // Display rule, kept deliberately simple:
      //   Planned   = headcount (or quantity for material)   — what was entered
      //   Actual    = stored actualUnits                     — cumulative nos from DPRs
      //   Remaining = max(Planned − Actual, 0)               — plain subtraction
      // Hours never enter this math. Legacy rows (no headcount/quantity) keep stored values.
      const storedActual = a.actualUnits ?? 0;
      let plannedUnits: number;
      let actualUnits: number;
      let remainingUnits: number;
      if (a.headcount != null) {
        plannedUnits = a.headcount;
        actualUnits = storedActual;
        remainingUnits = Math.max(plannedUnits - actualUnits, 0);
      } else if (a.quantity != null) {
        plannedUnits = Number(a.quantity);
        actualUnits = storedActual;
        remainingUnits = Math.max(plannedUnits - actualUnits, 0);
      } else {
        plannedUnits = a.plannedUnits;
        actualUnits = storedActual;
        const storedRemaining = anyA.remainingUnits as number | null | undefined;
        remainingUnits = storedRemaining != null
          ? storedRemaining
          : Math.max(plannedUnits - actualUnits, 0);
      }
      const plannedCost = (anyA.plannedCost as number) ?? 0;
      const actualCost = (anyA.actualCost as number) ?? 0;
      const storedRemainingCost = anyA.remainingCost as number | null | undefined;
      const remainingCost = storedRemainingCost != null
        ? storedRemainingCost
        : Math.max(plannedCost - actualCost, 0);
      return {
        id: a.id,
        activityId: a.activityId,
        resourceId: a.resourceId ?? null,
        roleId: a.roleId ?? null,
        effectiveRoleId: a.effectiveRoleId ?? a.roleId ?? null,
        effectiveRoleName: a.effectiveRoleName ?? a.roleName ?? null,
        unit: a.unit ?? null,
        projectId: (anyA.projectId as string) ?? projectId,
        resourceName: poolEntry?.resourceName ?? a.resourceName ?? a.resourceId ?? "—",
        roleName: a.roleName ?? null,
        activityName: activity?.name ?? a.activityName ?? a.activityId,
        budgetedUnits: (anyA.budgetedUnits as number | null | undefined) ?? null,
        budgetedCost: (anyA.budgetedCost as number | null | undefined) ?? null,
        plannedUnits,
        actualUnits,
        remainingUnits,
        rateType: (anyA.rateType as string) ?? "STANDARD",
        plannedCost,
        actualCost,
        remainingCost,
        staffed: a.staffed ?? a.resourceId != null,
        activitySupervisorResourceId: activity?.responsibleResourceId ?? null,
        activitySupervisorName: activity?.responsibleResourceName ?? null,
      };
    });
  }, [assignments, pool, activities, projectId]);

  const columns: ColumnDef<ResourceAssignmentRow>[] = [
    { key: "resourceName", label: "Resource Name", sortable: true },
    { key: "roleName", label: "Role", sortable: true, render: (value: unknown) => (value as string | null) ?? "—" },
    { key: "activityName", label: "Activity Name", sortable: true },
    {
      key: "staffed",
      label: "Status",
      sortable: true,
      render: (value) =>
        value ? (
          <span className="inline-flex items-center rounded-md bg-green-50 px-2 py-1 text-xs font-medium text-green-700 ring-1 ring-inset ring-green-600/20">
            Staffed
          </span>
        ) : (
          <span className="inline-flex items-center rounded-md bg-amber-50 px-2 py-1 text-xs font-medium text-amber-700 ring-1 ring-inset ring-amber-600/20">
            Role-only
          </span>
        ),
    },
    {
      key: "budgetedUnits",
      label: "Budgeted Units",
      sortable: true,
      render: (value, row) => {
        if (value == null) return "—";
        const formatted = Number(value).toFixed(2);
        return row.unit ? `${formatted} ${row.unit}` : formatted;
      },
    },
    {
      key: "plannedUnits",
      label: "Planned Units",
      sortable: true,
      render: (value, row) => {
        const formatted = Number(value).toFixed(2);
        return row.unit ? `${formatted} ${row.unit}` : formatted;
      },
    },
    {
      key: "actualUnits",
      label: "Actual Units",
      sortable: true,
      render: (value, row) => {
        const formatted = Number(value).toFixed(2);
        return row.unit ? `${formatted} ${row.unit}` : formatted;
      },
    },
    {
      key: "remainingUnits",
      label: "Remaining Units",
      sortable: true,
      render: (value, row) => {
        const formatted = Number(value).toFixed(2);
        return row.unit ? `${formatted} ${row.unit}` : formatted;
      },
    },
    { key: "rateType", label: "Rate Type", sortable: true },
    {
      key: "budgetedCost",
      label: "Budgeted Cost",
      sortable: true,
      render: (value) => (value == null ? "—" : formatDefaultCurrency(Number(value))),
    },
    {
      key: "plannedCost",
      label: "Planned Cost",
      sortable: true,
      render: (value) => formatDefaultCurrency(Number(value)),
    },
    {
      key: "actualCost",
      label: "Actual Cost",
      sortable: true,
      render: (value) => formatDefaultCurrency(Number(value)),
    },
  ];

  const handleRowClick = (row: ResourceAssignmentRow | AssignmentRow) => {
    const r = row as ResourceAssignmentRow;
    setSelectedAssignment(
      selectedAssignment?.id === r.id
        ? null
        : { id: r.id, resourceName: r.resourceName, activityName: r.activityName }
    );
  };

  const resourceTypeInfos = useMemo(
    () =>
      pool.map((p) => ({
        id: p.resourceId,
        resourceTypeCode: p.resourceTypeName ?? "",
      })),
    [pool]
  );

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3 flex-wrap">
        <button
          onClick={() => setShowForm(!showForm)}
          className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
        >
          <Plus size={16} />
          Assign Resource
        </button>
        <button
          onClick={() => setShowLeveling(true)}
          className="inline-flex items-center gap-2 rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-active"
        >
          <SlidersHorizontal size={16} />
          Level Resources
        </button>
        <button
          onClick={() => recomputeCostsMutation.mutate()}
          disabled={recomputeCostsMutation.isPending}
          className="inline-flex items-center gap-2 rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-active disabled:opacity-50"
          title="Recalculate planned cost for every assignment in this project from current resource rates"
        >
          {recomputeCostsMutation.isPending ? "Recomputing…" : "Recompute Costs"}
        </button>
        <div className="ml-auto">
          <ViewModeToggle viewMode={viewMode} onChange={setViewMode} />
        </div>
      </div>

      {showForm && (
        <ResourceAssignmentForm
          projectId={projectId}
          onSuccess={() => setShowForm(false)}
          onCancel={() => setShowForm(false)}
        />
      )}

      <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-text-primary">Resource Assignments</h3>
        </div>
        {isLoadingAssignments ? (
          <div className="text-center text-text-secondary">Loading assignments...</div>
        ) : assignmentRows.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <h3 className="text-lg font-medium text-text-primary">No Assignments</h3>
            <p className="mt-2 text-text-secondary">No resource assignments yet. Create one to get started.</p>
          </div>
        ) : viewMode === "flat" ? (
          <DataTable
            columns={columns}
            data={assignmentRows}
            rowKey="id"
            searchable
            searchPlaceholder="Search resources..."
            onRowClick={handleRowClick}
          />
        ) : (
          <ResourceAssignmentTree
            assignments={assignmentRows}
            viewMode={viewMode}
            resources={resourceTypeInfos}
            onRowClick={handleRowClick}
            selectedId={selectedAssignment?.id ?? null}
          />
        )}
      </div>

      {selectedAssignment && (
        <div className="space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div className="text-sm text-text-secondary">
              Custom fields for: <span className="font-medium text-accent">{selectedAssignment.resourceName} &rarr; {selectedAssignment.activityName}</span>
            </div>
            <button
              type="button"
              onClick={() => rebudgetMutation.mutate(selectedAssignment.id)}
              disabled={rebudgetMutation.isPending}
              className="inline-flex items-center gap-1.5 rounded-md border border-warning/40 bg-warning/10 px-3 py-1.5 text-xs font-medium text-warning hover:bg-warning/20 disabled:opacity-60"
              title="Copy current planned units & cost into Budgeted, freezing them as the new commitment. Audit-logged."
            >
              {rebudgetMutation.isPending ? "Re-budgeting…" : "Re-budget"}
            </button>
          </div>
          <UdfSection entityId={selectedAssignment.id} subject="RESOURCE_ASSIGNMENT" projectId={projectId} />
        </div>
      )}

      <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
        <h3 className="mb-4 text-lg font-semibold text-text-primary">Resource Histogram</h3>
        <div className="mb-4">
          <label className="block text-sm font-medium text-text-secondary mb-2">Select Resource</label>
          <SearchableSelect
            value={selectedResourceId}
            onChange={(val) => setSelectedResourceId(val)}
            placeholder="Search pooled resources..."
            options={pool.map((p) => ({
              value: p.resourceId,
              label: `${p.resourceCode ?? p.resourceId} - ${p.resourceName ?? "Unknown"}`,
            }))}
          />
        </div>

        {!selectedResourceId ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <p className="text-text-secondary">Select a resource to view planned vs actual usage over time.</p>
          </div>
        ) : isLoadingHistogram ? (
          <div className="text-center text-text-secondary">Loading histogram data...</div>
        ) : histogramEntries.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <p className="text-text-secondary">No histogram data available for this resource.</p>
          </div>
        ) : (
          <div className="w-full h-96">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={histogramEntries}
                margin={{ top: 20, right: 30, left: 0, bottom: 20 }}
              >
                <CartesianGrid strokeDasharray="3 3" stroke="#475569" />
                <XAxis
                  dataKey="period"
                  label={{ value: "Time Period", position: "insideBottom", offset: -10 }}
                />
                <YAxis
                  label={{ value: "Units", angle: -90, position: "insideLeft" }}
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "#1e293b",
                    border: "1px solid #475569",
                    borderRadius: "0.375rem",
                    color: "#e2e8f0",
                  }}
                  formatter={(value) => (typeof value === "number" ? value.toFixed(2) : value)}
                />
                <Legend wrapperStyle={{ paddingTop: "16px" }} />
                <Bar dataKey="planned" fill="#3b82f6" name="Planned" />
                <Bar dataKey="actual" fill="#10b981" name="Actual" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>
      <ResourceLevelingDialog
        projectId={projectId}
        open={showLeveling}
        onClose={() => setShowLeveling(false)}
      />
    </div>
  );
}

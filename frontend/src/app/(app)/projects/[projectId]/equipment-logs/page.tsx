"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { equipmentApi, type EquipmentLogResponse, type CreateEquipmentLogRequest, type EquipmentUtilizationSummary } from "@/lib/api/equipmentApi";
import { resourceApi, type ResourceResponse } from "@/lib/api/resourceApi";
import { TabTip } from "@/components/common/TabTip";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { getErrorMessage } from "@/lib/utils/error";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import type { PagedResponse } from "@/lib/types";
import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "@/lib/state/store";

// Spring's native Page<T> serialises with these fields at the root of the
// response body (no `pagination` sub-object). The paged endpoints in
// EquipmentLogController / LabourReturnController return ApiResponse<Page<T>>.
interface SpringPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

interface EquipmentLogForm {
  resourceId: string;
  logDate: string;
  deploymentSite: string;
  operatingHours: number;
  idleHours: number;
  breakdownHours: number;
  fuelConsumed: number;
  operatorName: string;
  remarks: string;
  status: "WORKING" | "IDLE" | "UNDER_MAINTENANCE" | "BREAKDOWN";
}

const initialFormState: EquipmentLogForm = {
  resourceId: "",
  logDate: new Date().toISOString().split("T")[0],
  deploymentSite: "",
  operatingHours: 0,
  idleHours: 0,
  breakdownHours: 0,
  fuelConsumed: 0,
  operatorName: "",
  remarks: "",
  status: "WORKING",
};

export default function EquipmentLogsPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  // Equipment hours / breakdown logging is supervisor-tier write; DPR.UPDATE
  // captures the daily reporting role-set without overlapping resource pool edits.
  const canWrite = hasPermission("DPR.UPDATE") || hasPermission("RESOURCE.UPDATE");
  const params = useParams();
  const projectId = params.projectId as string;

  const [logs, setLogs] = useState<EquipmentLogResponse[]>([]);
  const [utilization, setUtilization] = useState<EquipmentUtilizationSummary[]>([]);
  const [resources, setResources] = useState<ResourceResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<EquipmentLogForm>(initialFormState);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const loadEquipmentLogs = async (pageNum = 0) => {
    try {
      setIsLoading(true);
      const response = await equipmentApi.getLogsByProject(projectId, pageNum, 20);
      if (response.data) {
        // Backend returns Spring Page<T>: { content, totalElements, ... } at the root.
        const pagedData = response.data as unknown as SpringPage<EquipmentLogResponse>;
        setLogs(pagedData.content ?? []);
        setTotalElements(pagedData.totalElements ?? 0);
        setPage(pageNum);
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to load equipment logs"));
    } finally {
      setIsLoading(false);
    }
  };

  const loadUtilization = async () => {
    try {
      const response = await equipmentApi.getUtilizationSummary(projectId);
      if (response.data) {
        setUtilization(response.data);
      }
    } catch (err: unknown) {
      console.error(getErrorMessage(err, "Failed to load utilization summary"));
    }
  };

  // Load resources via react-query so the equipment dropdown is populated and
  // the table can render friendly resource names. Enabled once logs are loaded
  // (so the name map can be joined on the log rows).
  const {
    data: resourcesQueryData,
  } = useQuery({
    queryKey: ["resources-for-equipment-logs"],
    queryFn: () => resourceApi.listResources(0, 500),
    enabled: !isLoading,
  });

  useEffect(() => {
    if (!resourcesQueryData) return;
    // Backend returns a flat array; fall back to paged envelope just in case.
    const raw = resourcesQueryData.data as unknown;
    if (Array.isArray(raw)) {
      setResources(raw as ResourceResponse[]);
    } else if (raw && typeof raw === "object" && "content" in raw) {
      setResources((raw as PagedResponse<ResourceResponse>).content);
    }
  }, [resourcesQueryData]);

  // Build a lookup so the Resource column can render `${code} — ${name}`.
  const resourceById = useMemo(() => {
    const map = new Map<string, { code: string; name: string }>();
    for (const r of resources) {
      map.set(r.id, { code: r.code, name: r.name });
    }
    return map;
  }, [resources]);

  useEffect(() => {
    loadEquipmentLogs();
    loadUtilization();
  }, [projectId]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const request: CreateEquipmentLogRequest = {
        resourceId: formData.resourceId,
        projectId,
        logDate: formData.logDate,
        deploymentSite: formData.deploymentSite || undefined,
        operatingHours: formData.operatingHours || undefined,
        idleHours: formData.idleHours || undefined,
        breakdownHours: formData.breakdownHours || undefined,
        fuelConsumed: formData.fuelConsumed || undefined,
        operatorName: formData.operatorName || undefined,
        remarks: formData.remarks || undefined,
        status: formData.status,
      };

      await equipmentApi.createLog(projectId, request);
      setFormData(initialFormState);
      setShowForm(false);
      loadEquipmentLogs();
      loadUtilization();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create equipment log"));
    }
  };

  const columns = useMemo<ColumnDef<EquipmentLogResponse>[]>(() => [
    {
      accessorKey: "logDate",
      header: "Date",
    },
    {
      accessorKey: "resourceId",
      header: "Resource",
      cell: ({ row }) => {
        const r = resourceById.get(row.original.resourceId);
        return r ? `${r.code} — ${r.name}` : row.original.resourceId;
      },
    },
    {
      accessorKey: "deploymentSite",
      header: "Deployment Site",
      cell: ({ row }) => row.original.deploymentSite ?? "—",
    },
    {
      accessorKey: "operatingHours",
      header: "Operating Hrs",
      cell: ({ row }) => row.original.operatingHours ?? "—",
    },
    {
      accessorKey: "idleHours",
      header: "Idle Hrs",
      cell: ({ row }) => row.original.idleHours ?? "—",
    },
    {
      accessorKey: "breakdownHours",
      header: "Breakdown Hrs",
      cell: ({ row }) => row.original.breakdownHours ?? "—",
    },
    {
      accessorKey: "fuelConsumed",
      header: "Fuel (L)",
      cell: ({ row }) => row.original.fuelConsumed ?? "—",
    },
    {
      accessorKey: "operatorName",
      header: "Operator",
      cell: ({ row }) => row.original.operatorName ?? "—",
    },
    {
      accessorKey: "status",
      header: "Status",
      cell: ({ row }) => {
        const status = row.original.status;
        const cls =
          status === "WORKING"
            ? "bg-success/10 text-success ring-1 ring-success/20"
            : status === "IDLE"
              ? "bg-warning/10 text-warning ring-1 ring-warning/20"
              : status === "BREAKDOWN"
                ? "bg-danger/10 text-danger ring-1 ring-danger/20"
                : "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
        return (
          <span className={`px-2 py-1 rounded text-sm ${cls}`}>
            {status.replace("_", " ")}
          </span>
        );
      },
    },
    {
      accessorKey: "remarks",
      header: "Remarks",
      cell: ({ row }) => row.original.remarks ?? "—",
    },
  ], [resourceById]);

  if (isLoading && logs.length === 0) {
    return <div className="p-6 text-text-muted">Loading equipment logs...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Equipment Logs"
        description="Track equipment deployed on site — utilization hours, breakdown incidents, and deployment location. Helps monitor equipment productivity."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Equipment Logs</h1>

        {/* Utilization Summary Cards */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
          <div className="bg-accent/10 p-4 rounded-lg border border-accent/20">
            <p className="text-sm text-text-secondary mb-1">Total Equipment</p>
            <p className="text-2xl font-bold text-accent">{utilization.length}</p>
          </div>
          <div className="bg-success/10 p-4 rounded-lg border border-success/20">
            <p className="text-sm text-text-secondary mb-1">Avg Utilization</p>
            <p className="text-2xl font-bold text-success">
              {utilization.length > 0
                ? (utilization.reduce((sum, u) => sum + u.utilizationPercentage, 0) / utilization.length).toFixed(1)
                : 0}
              %
            </p>
          </div>
          <div className="bg-danger/10 p-4 rounded-lg border border-danger/20">
            <p className="text-sm text-text-secondary mb-1">Total Breakdown Hours</p>
            <p className="text-2xl font-bold text-danger">
              {utilization.reduce((sum, u) => sum + u.totalBreakdownHours, 0).toFixed(1)}h
            </p>
          </div>
          <div className="bg-warning/10 p-4 rounded-lg border border-warning/20">
            <p className="text-sm text-text-secondary mb-1">Total Idle Hours</p>
            <p className="text-2xl font-bold text-warning">
              {utilization.reduce((sum, u) => sum + u.totalIdleHours, 0).toFixed(1)}h
            </p>
          </div>
        </div>

        {canWrite && (
          <button
            onClick={() => setShowForm(!showForm)}
            className="mb-6 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
          >
            {showForm ? "Cancel" : "Add Equipment Log"}
          </button>
        )}

        {error && <div className="text-danger mb-4">{error}</div>}

        {showForm && (
          <form onSubmit={handleSubmit} className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Resource</label>
                <SearchableSelect
                  value={formData.resourceId}
                  onChange={(val) => setFormData({ ...formData, resourceId: val })}
                  placeholder="Search equipment..."
                  options={resources.map((r) => ({
                    value: r.id,
                    label: `${r.code} - ${r.name}`,
                  }))}
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Log Date</label>
                <input
                  type="date"
                  value={formData.logDate}
                  onChange={(e) => setFormData({ ...formData, logDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Deployment Site</label>
                <input
                  type="text"
                  value={formData.deploymentSite}
                  onChange={(e) => setFormData({ ...formData, deploymentSite: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Operating Hours</label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.operatingHours}
                  onChange={(e) => setFormData({ ...formData, operatingHours: parseFloat(e.target.value) || 0 })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Idle Hours</label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.idleHours}
                  onChange={(e) => setFormData({ ...formData, idleHours: parseFloat(e.target.value) || 0 })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Breakdown Hours</label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.breakdownHours}
                  onChange={(e) => setFormData({ ...formData, breakdownHours: parseFloat(e.target.value) || 0 })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Fuel Consumed (L)</label>
                <input
                  type="number"
                  step="0.1"
                  value={formData.fuelConsumed}
                  onChange={(e) => setFormData({ ...formData, fuelConsumed: parseFloat(e.target.value) || 0 })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Operator Name</label>
                <input
                  type="text"
                  value={formData.operatorName}
                  onChange={(e) => setFormData({ ...formData, operatorName: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Status</label>
                <select
                  value={formData.status}
                  onChange={(e) => setFormData({ ...formData, status: e.target.value as any })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="WORKING">Working</option>
                  <option value="IDLE">Idle</option>
                  <option value="UNDER_MAINTENANCE">Under Maintenance</option>
                  <option value="BREAKDOWN">Breakdown</option>
                </select>
              </div>
              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">Remarks</label>
                <textarea
                  value={formData.remarks}
                  onChange={(e) => setFormData({ ...formData, remarks: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  rows={3}
                />
              </div>
            </div>
            <div className="flex gap-2 mt-4">
              <button type="submit" className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600">
                Save Log
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
          emptyMessage="No equipment logs for this project."
        />

        {/* Pagination */}
        {totalElements > 20 && (
          <div className="flex gap-2 mt-4">
            <button
              onClick={() => loadEquipmentLogs(Math.max(0, page - 1))}
              disabled={page === 0}
              className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded disabled:opacity-50"
            >
              Previous
            </button>
            <span className="px-4 py-2 text-text-secondary">{page + 1}</span>
            <button
              onClick={() => loadEquipmentLogs(page + 1)}
              disabled={(page + 1) * 20 >= totalElements}
              className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded disabled:opacity-50"
            >
              Next
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

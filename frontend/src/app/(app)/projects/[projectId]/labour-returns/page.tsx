"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { labourApi, type LabourReturnResponse, type CreateLabourReturnRequest, type DeploymentSummary } from "@/lib/api/labourApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { useAuthStore } from "@/lib/state/store";

// Spring's native Page<T> serialises with these fields at the root of the
// response body (no `pagination` sub-object). LabourReturnController returns
// ApiResponse<Page<LabourReturnResponse>>.
interface SpringPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

interface LabourReturnForm {
  contractorName: string;
  returnDate: string;
  skillCategory: "SKILLED" | "SEMI_SKILLED" | "UNSKILLED" | "SUPERVISOR" | "ENGINEER";
  headCount: number;
  manDays: number;
  siteLocation: string;
  remarks: string;
}

const initialFormState: LabourReturnForm = {
  contractorName: "",
  returnDate: new Date().toISOString().split("T")[0],
  skillCategory: "UNSKILLED",
  headCount: 1,
  manDays: 1,
  siteLocation: "",
  remarks: "",
};

const skillCategoryLabel = {
  SKILLED: "Skilled",
  SEMI_SKILLED: "Semi-Skilled",
  UNSKILLED: "Unskilled",
  SUPERVISOR: "Supervisor",
  ENGINEER: "Engineer",
};

export default function LabourReturnsPage() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canWrite = hasPermission("DPR.UPDATE") || hasPermission("RESOURCE.UPDATE");
  const params = useParams();
  const projectId = params.projectId as string;

  const [returns, setReturns] = useState<LabourReturnResponse[]>([]);
  const [summary, setSummary] = useState<DeploymentSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<LabourReturnForm>(initialFormState);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const loadLabourReturns = async (pageNum = 0) => {
    try {
      setIsLoading(true);
      const response = await labourApi.getReturnsByProject(projectId, pageNum, 20);
      if (response.data) {
        // Backend returns Spring Page<T>: { content, totalElements, ... } at the root.
        const pagedData = response.data as unknown as SpringPage<LabourReturnResponse>;
        setReturns(pagedData.content ?? []);
        setTotalElements(pagedData.totalElements ?? 0);
        setPage(pageNum);
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to load labour returns"));
    } finally {
      setIsLoading(false);
    }
  };

  const loadDeploymentSummary = async () => {
    try {
      const response = await labourApi.getDeploymentSummary(projectId);
      if (response.data) {
        setSummary(response.data);
      }
    } catch (err: unknown) {
      console.error(getErrorMessage(err, "Failed to load deployment summary"));
    }
  };

  useEffect(() => {
    loadLabourReturns();
    loadDeploymentSummary();
  }, [projectId]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const request: CreateLabourReturnRequest = {
        projectId,
        contractorName: formData.contractorName,
        returnDate: formData.returnDate,
        skillCategory: formData.skillCategory,
        headCount: formData.headCount,
        manDays: formData.manDays,
        siteLocation: formData.siteLocation || undefined,
        remarks: formData.remarks || undefined,
      };

      await labourApi.createReturn(projectId, request);
      setFormData(initialFormState);
      setShowForm(false);
      loadLabourReturns();
      loadDeploymentSummary();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create labour return"));
    }
  };

  const columns = useMemo<ColumnDef<LabourReturnResponse>[]>(() => [
    { accessorKey: "returnDate", header: "Date" },
    { accessorKey: "contractorName", header: "Contractor" },
    {
      accessorKey: "skillCategory",
      header: "Skill Category",
      cell: ({ row }) => (
        <span className="px-2 py-1 bg-accent/10 text-accent ring-1 ring-accent/20 rounded text-sm">
          {skillCategoryLabel[row.original.skillCategory]}
        </span>
      ),
    },
    {
      accessorKey: "headCount",
      header: "Headcount",
      cell: ({ row }) => <span className="font-semibold">{row.original.headCount}</span>,
    },
    {
      accessorKey: "manDays",
      header: "Man-Days",
      cell: ({ row }) => row.original.manDays.toFixed(1),
    },
    {
      accessorKey: "siteLocation",
      header: "Site Location",
      cell: ({ row }) => row.original.siteLocation || "-",
    },
  ], []);

  if (isLoading && returns.length === 0) {
    return <div className="p-6 text-text-muted">Loading labour returns...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Labour Returns"
        description="Daily labour count by contractor and skill category. Track workforce deployment across different work sites."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Labour Returns</h1>

        {/* Summary Cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <div className="bg-accent/10 p-4 rounded-lg border border-accent/20">
            <p className="text-sm text-text-secondary mb-1">Total Headcount</p>
            <p className="text-2xl font-bold text-accent">
              {summary.reduce((sum, s) => sum + s.totalHeadCount, 0)}
            </p>
          </div>
          <div className="bg-success/10 p-4 rounded-lg border border-success/20">
            <p className="text-sm text-text-secondary mb-1">Total Man-Days</p>
            <p className="text-2xl font-bold text-success">
              {summary.reduce((sum, s) => sum + s.totalManDays, 0).toFixed(1)}
            </p>
          </div>
          <div className="bg-purple-500/10 p-4 rounded-lg border border-purple-500/20">
            <p className="text-sm text-text-secondary mb-1">Returns Submitted</p>
            <p className="text-2xl font-bold text-purple-400">{totalElements}</p>
          </div>
        </div>

        {/* Skill Category Breakdown */}
        {summary.length > 0 && (
          <div className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl">
            <h3 className="font-bold text-lg mb-3 text-text-primary">Deployment by Skill Category</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-3">
              {summary.map((s) => (
                <div key={s.skillCategory} className="bg-surface-hover/50 p-3 rounded border border-border">
                  <p className="text-sm text-text-secondary">{skillCategoryLabel[s.skillCategory]}</p>
                  <p className="text-xl font-bold text-text-primary">{s.totalHeadCount}</p>
                  <p className="text-xs text-text-muted">{s.totalManDays.toFixed(1)} man-days</p>
                </div>
              ))}
            </div>
          </div>
        )}

        {canWrite && (
          <button
            onClick={() => setShowForm(!showForm)}
            className="mb-6 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
          >
            {showForm ? "Cancel" : "Add Labour Return"}
          </button>
        )}

        {error && <div className="text-danger mb-4">{error}</div>}

        {showForm && (
          <form onSubmit={handleSubmit} className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Contractor Name</label>
                <input
                  type="text"
                  value={formData.contractorName}
                  onChange={(e) => setFormData({ ...formData, contractorName: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Return Date</label>
                <input
                  type="date"
                  value={formData.returnDate}
                  onChange={(e) => setFormData({ ...formData, returnDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Skill Category</label>
                <select
                  value={formData.skillCategory}
                  onChange={(e) => setFormData({ ...formData, skillCategory: e.target.value as any })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                >
                  <option value="UNSKILLED">Unskilled</option>
                  <option value="SEMI_SKILLED">Semi-Skilled</option>
                  <option value="SKILLED">Skilled</option>
                  <option value="SUPERVISOR">Supervisor</option>
                  <option value="ENGINEER">Engineer</option>
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Headcount</label>
                <input
                  type="number"
                  min="1"
                  value={formData.headCount}
                  onChange={(e) => setFormData({ ...formData, headCount: parseInt(e.target.value) || 1 })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Man-Days</label>
                <input
                  type="number"
                  step="0.1"
                  min="0"
                  value={formData.manDays}
                  onChange={(e) => setFormData({ ...formData, manDays: parseFloat(e.target.value) || 1 })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Site Location</label>
                <input
                  type="text"
                  value={formData.siteLocation}
                  onChange={(e) => setFormData({ ...formData, siteLocation: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
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
                Save Return
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

        {/* Returns Table */}
        <VirtualDataTable
          columns={columns}
          data={returns}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No labour returns for this project."
        />

        {/* Pagination */}
        {totalElements > 20 && (
          <div className="flex gap-2 mt-4">
            <button
              onClick={() => loadLabourReturns(Math.max(0, page - 1))}
              disabled={page === 0}
              className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded disabled:opacity-50"
            >
              Previous
            </button>
            <span className="px-4 py-2 text-text-secondary">{page + 1}</span>
            <button
              onClick={() => loadLabourReturns(page + 1)}
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

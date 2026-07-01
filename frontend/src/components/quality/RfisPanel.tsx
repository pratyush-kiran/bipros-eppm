"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { getErrorMessage } from "@/lib/utils/error";
import { documentApi } from "@/lib/api/documentApi";
import { TabTip } from "@/components/common/TabTip";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import type { RfiRegister, UpdateRfiRequest } from "@/lib/api/documentApi";
import { buildRfiUpdatePayload } from "@/lib/quality/rfiPayload";

interface RfiFormData {
  rfiNumber: string;
  subject: string;
  description: string;
  priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  raisedBy: string;
  assignedTo: string;
  raisedDate: string;
  dueDate: string;
}

export function RfisPanel({ projectId }: { projectId: string }) {
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [filterStatus, setFilterStatus] = useState<string>("ALL");
  const [formData, setFormData] = useState<RfiFormData>({
    rfiNumber: "",
    subject: "",
    description: "",
    priority: "MEDIUM",
    raisedBy: "",
    assignedTo: "",
    raisedDate: new Date().toISOString().split("T")[0],
    dueDate: "",
  });
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<RfiRegister | null>(null);
  const queryClient = useQueryClient();

  const { data: rfis = [] } = useQuery({
    queryKey: ["rfis", projectId],
    queryFn: () => documentApi.listRfis(projectId),
    select: (response) => response.data || [],
  });

  const createRfiMutation = useMutation({
    mutationFn: (data: RfiFormData) => documentApi.createRfi(projectId, { projectId, ...data }),
    onSuccess: () => {
      toast.success("RFI created successfully");
      setShowCreateForm(false);
      setFormData({
        rfiNumber: "",
        subject: "",
        description: "",
        priority: "MEDIUM",
        raisedBy: "",
        assignedTo: "",
        raisedDate: new Date().toISOString().split("T")[0],
        dueDate: "",
      });
      setError("");
      queryClient.invalidateQueries({ queryKey: ["rfis", projectId] });
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to create RFI");
      setError(msg);
      toast.error(msg);
    },
  });

  const updateRfiMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateRfiRequest }) =>
      documentApi.updateRfi(projectId, id, payload),
    onSuccess: () => {
      toast.success("RFI updated");
      setEditing(null);
      queryClient.invalidateQueries({ queryKey: ["rfis", projectId] });
    },
    onError: (err: unknown) => toast.error(getErrorMessage(err, "Failed to update RFI")),
  });

  const filteredRfis = rfis.filter((rfi) =>
    filterStatus === "ALL" ? true : rfi.status === filterStatus
  );

  const handleFormChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: value,
    }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    if (!formData.rfiNumber || !formData.subject) {
      setError("RFI Number and Subject are required");
      return;
    }

    createRfiMutation.mutate(formData);
  };

  const isOverdue = (dueDate: string): boolean => {
    return new Date(dueDate) < new Date();
  };

  const daysUntilDue = (dueDate: string): number => {
    const today = new Date();
    const due = new Date(dueDate);
    const diff = Math.ceil((due.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
    return diff;
  };

  const rfiColumns: ColumnDef<RfiRegister>[] = [
      {
        accessorKey: "rfiNumber",
        header: "RFI Number",
        cell: (info) => (
          <span className="font-medium text-text-primary">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "subject",
        header: "Subject",
        cell: (info) => (
          <span className="max-w-xs truncate block text-text-primary">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "raisedBy",
        header: "Raised By",
        cell: (info) => (
          <span className="text-text-secondary text-sm">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "assignedTo",
        header: "Assigned To",
        cell: (info) => (
          <span className="text-text-secondary text-sm">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "priority",
        header: "Priority",
        cell: (info) => (
          <span
            className={`inline-block px-3 py-1 rounded-full text-xs font-medium ${getPriorityColor(
              info.getValue() as "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"
            )}`}
          >
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: (info) => (
          <span
            className={`inline-block px-3 py-1 rounded-full text-xs font-medium ${getStatusColor(
              info.getValue() as "OPEN" | "RESPONDED" | "CLOSED" | "OVERDUE"
            )}`}
          >
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "dueDate",
        header: "Due Date",
        cell: (info) => {
          const dueDate = info.getValue() as string;
          const status = info.row.original.status;
          return (
            <div className="flex flex-col gap-1">
              <span className="text-text-secondary">
                {new Date(dueDate).toLocaleDateString()}
              </span>
              {getDueDateAlert(dueDate, status) && (
                <span className="text-xs font-medium text-orange-400">
                  {getDueDateAlert(dueDate, status)}
                </span>
              )}
            </div>
          );
        },
      },
      {
        id: "actions",
        header: "Actions",
        cell: (info) => (
          <button
            type="button"
            onClick={() => setEditing(info.row.original)}
            className="text-accent hover:text-blue-300 text-xs font-medium"
          >
            Edit
          </button>
        ),
      },
  ];

  const getStatusColor = (status: "OPEN" | "RESPONDED" | "CLOSED" | "OVERDUE") => {
    switch (status) {
      case "OPEN":
        return "bg-danger/10 text-danger ring-1 ring-red-500/20";
      case "RESPONDED":
        return "bg-accent/10 text-accent ring-1 ring-accent/20";
      case "CLOSED":
        return "bg-success/10 text-success ring-1 ring-success/20";
      case "OVERDUE":
        return "bg-orange-500/10 text-orange-400 ring-1 ring-orange-500/20";
      default:
        return "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
    }
  };

  const getPriorityColor = (priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL") => {
    switch (priority) {
      case "LOW":
        return "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
      case "MEDIUM":
        return "bg-warning/10 text-warning ring-1 ring-amber-500/20";
      case "HIGH":
        return "bg-orange-500/10 text-orange-400 ring-1 ring-orange-500/20";
      case "CRITICAL":
        return "bg-danger/10 text-danger ring-1 ring-red-500/20";
      default:
        return "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
    }
  };

  const getDueDateAlert = (dueDate: string, status: string) => {
    if (status === "CLOSED") return null;

    const days = daysUntilDue(dueDate);
    if (days < 0) return "⚠️ Overdue";
    if (days === 0) return "📅 Due Today";
    if (days <= 3) return `📅 ${days}d remaining`;
    return null;
  };

  return (
    <div className="space-y-6">
      <TabTip
        title="Requests for Information (RFIs)"
        description="Track questions and clarifications between contractor and client. Each RFI has a response deadline and status to ensure timely resolution."
      />
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-text-primary">Request for Information</h1>
          <p className="text-sm text-text-secondary mt-1">
            {filteredRfis.length} RFI{filteredRfis.length !== 1 ? "s" : ""} found
          </p>
        </div>
        <button
          onClick={() => setShowCreateForm(!showCreateForm)}
          className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover transition-colors text-sm font-medium"
        >
          + Create RFI
        </button>
      </div>

      {/* Create Form */}
      {showCreateForm && (
        <div className="bg-surface/50 rounded-xl border border-border p-6 shadow-xl">
          {error && (
            <div className="mb-4 rounded-md bg-danger/10 p-3 text-sm text-danger">
              {error}
            </div>
          )}
          <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1">
                RFI Number
              </label>
              <input
                type="text"
                name="rfiNumber"
                value={formData.rfiNumber}
                onChange={handleFormChange}
                placeholder="e.g., RFI-001"
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1">Priority</label>
              <select
                name="priority"
                value={formData.priority}
                onChange={handleFormChange}
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              >
                <option value="LOW">LOW</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="HIGH">HIGH</option>
                <option value="CRITICAL">CRITICAL</option>
              </select>
            </div>
            <div className="col-span-2">
              <label className="block text-sm font-medium text-text-secondary mb-1">Subject</label>
              <input
                type="text"
                name="subject"
                value={formData.subject}
                onChange={handleFormChange}
                placeholder="Brief description of RFI"
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div className="col-span-2">
              <label className="block text-sm font-medium text-text-secondary mb-1">
                Description
              </label>
              <textarea
                name="description"
                value={formData.description}
                onChange={handleFormChange}
                placeholder="Detailed description"
                rows={3}
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1">
                Raised By
              </label>
              <input
                type="text"
                name="raisedBy"
                value={formData.raisedBy}
                onChange={handleFormChange}
                placeholder="Your name"
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1">
                Assigned To
              </label>
              <input
                type="text"
                name="assignedTo"
                value={formData.assignedTo}
                onChange={handleFormChange}
                placeholder="Assignee"
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary placeholder-text-muted focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1">Raised Date *</label>
              <input
                type="date"
                name="raisedDate"
                value={formData.raisedDate}
                onChange={handleFormChange}
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1">Due Date *</label>
              <input
                type="date"
                name="dueDate"
                value={formData.dueDate}
                onChange={handleFormChange}
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded-lg text-text-primary focus:border-accent focus:ring-2 focus:ring-accent focus:ring-opacity-50"
              />
            </div>
            <div className="col-span-2 flex gap-3">
              <button
                type="submit"
                disabled={createRfiMutation.isPending}
                className="flex-1 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover disabled:bg-border transition-colors font-medium"
              >
                {createRfiMutation.isPending ? "Creating..." : "Create RFI"}
              </button>
              <button
                type="button"
                onClick={() => setShowCreateForm(false)}
                className="flex-1 px-4 py-2 border border-border bg-surface-hover text-text-secondary rounded-lg hover:bg-surface-active transition-colors font-medium"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Filter Tabs */}
      <div className="flex gap-2 border-b border-border">
        {["ALL", "OPEN", "RESPONDED", "CLOSED", "OVERDUE"].map((status) => (
          <button
            key={status}
            onClick={() => setFilterStatus(status)}
            className={`px-4 py-2 text-sm font-medium transition-colors ${
              filterStatus === status
                ? "border-b-2 border-accent text-accent"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            {status}
          </button>
        ))}
      </div>

      {/* RFI List */}
      <VirtualDataTable
        columns={rfiColumns}
        data={filteredRfis}
        rowClassName={(row) =>
          isOverdue(row.dueDate) && row.status !== "CLOSED"
            ? "bg-orange-500/10"
            : ""
        }
        emptyMessage={
          filterStatus === "ALL" ? "No RFIs found" : `No ${filterStatus} RFIs found`
        }
      />

      {editing && (
        <div className="fixed inset-0 z-50 flex justify-end bg-black/30" onClick={() => setEditing(null)}>
          <div className="h-full w-full max-w-md overflow-y-auto bg-surface p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
            <h2 className="mb-4 text-lg font-bold text-text-primary">Edit {editing.rfiNumber}</h2>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const fd = new FormData(e.currentTarget as HTMLFormElement);
                updateRfiMutation.mutate({
                  id: editing.id,
                  payload: buildRfiUpdatePayload(editing, {
                    subject: String(fd.get("subject") ?? editing.subject),
                    description: String(fd.get("description") ?? ""),
                    priority: String(fd.get("priority") ?? editing.priority),
                    status: String(fd.get("status") ?? editing.status),
                    assignedTo: String(fd.get("assignedTo") ?? editing.assignedTo),
                    dueDate: String(fd.get("dueDate") ?? editing.dueDate),
                    response: String(fd.get("response") ?? ""),
                    closedDate: editing.closedDate,
                  }),
                });
              }}
              className="space-y-3"
            >
              <input name="subject" defaultValue={editing.subject} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
              <textarea name="description" defaultValue={editing.description} rows={3} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
              <input name="assignedTo" defaultValue={editing.assignedTo} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
              <input type="date" name="dueDate" defaultValue={editing.dueDate} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
              <select name="priority" defaultValue={editing.priority} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary">
                {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
              <select name="status" defaultValue={editing.status} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary">
                {["OPEN", "RESPONDED", "CLOSED", "OVERDUE"].map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
              <textarea name="response" defaultValue={editing.response} rows={3} placeholder="Response" className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
              <div className="flex gap-2">
                <button type="submit" disabled={updateRfiMutation.isPending} className="flex-1 rounded-lg bg-accent px-4 py-2 font-medium text-accent-foreground">Save</button>
                <button type="button" onClick={() => setEditing(null)} className="flex-1 rounded-lg border border-border bg-surface-hover px-4 py-2 text-text-secondary">Cancel</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

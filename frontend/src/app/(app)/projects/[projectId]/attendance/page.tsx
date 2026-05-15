"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import {
  attendanceApi,
  type AttendanceResponse,
  type CreateAttendanceRequest,
  type SkillCategory,
  type UpdateAttendanceRequest,
} from "@/lib/api/attendanceApi";
import { useAuthStore } from "@/lib/state/store";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import { PageHeader } from "@/components/common/PageHeader";
import { Drawer } from "@/components/common/Drawer";
import { getErrorMessage } from "@/lib/utils/error";

const SKILL_LABEL: Record<SkillCategory, string> = {
  SKILLED: "Skilled",
  SEMI_SKILLED: "Semi-Skilled",
  UNSKILLED: "Unskilled",
  SUPERVISOR: "Supervisor",
  ENGINEER: "Engineer",
};

const SKILL_OPTIONS: SkillCategory[] = [
  "SKILLED",
  "SEMI_SKILLED",
  "UNSKILLED",
  "SUPERVISOR",
  "ENGINEER",
];

interface AttendanceForm {
  date: string;
  contractorName: string;
  skillCategory: SkillCategory;
  plannedCount: number;
  actualCount: number;
  hoursWorked: number;
  notes: string;
}

const initialForm: AttendanceForm = {
  date: new Date().toISOString().split("T")[0],
  contractorName: "",
  skillCategory: "UNSKILLED",
  plannedCount: 0,
  actualCount: 0,
  hoursWorked: 0,
  notes: "",
};

export default function AttendancePage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  const canCreate = hasPermission("ATTENDANCE.CREATE");
  const canUpdate = hasPermission("ATTENDANCE.UPDATE");
  const canApprove = hasPermission("ATTENDANCE.APPROVE");

  const [from, setFrom] = useState<string>("");
  const [to, setTo] = useState<string>("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<AttendanceForm>(initialForm);
  const [formError, setFormError] = useState<string | null>(null);

  const filters = { from: from || undefined, to: to || undefined };

  const listQuery = useQuery({
    queryKey: ["attendance", projectId, from || null, to || null],
    queryFn: () => attendanceApi.list(projectId, filters),
  });

  const summaryQuery = useQuery({
    queryKey: ["attendanceSummary", projectId, from || null, to || null],
    queryFn: () => attendanceApi.summary(projectId, filters),
  });

  const rows = listQuery.data?.data ?? [];
  const summary = summaryQuery.data?.data ?? [];

  const createMutation = useMutation({
    mutationFn: (payload: CreateAttendanceRequest) =>
      attendanceApi.create(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["attendance", projectId] });
      queryClient.invalidateQueries({ queryKey: ["attendanceSummary", projectId] });
      closeDrawer();
    },
    onError: (err) => setFormError(getErrorMessage(err, "Failed to save")),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateAttendanceRequest }) =>
      attendanceApi.update(projectId, id, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["attendance", projectId] });
      queryClient.invalidateQueries({ queryKey: ["attendanceSummary", projectId] });
      closeDrawer();
    },
    onError: (err) => setFormError(getErrorMessage(err, "Failed to update")),
  });

  const approveMutation = useMutation({
    mutationFn: (id: string) => attendanceApi.approve(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["attendance", projectId] });
    },
  });

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditingId(null);
    setForm(initialForm);
    setFormError(null);
  };

  const openCreate = () => {
    setEditingId(null);
    setForm(initialForm);
    setFormError(null);
    setDrawerOpen(true);
  };

  const openEdit = (r: AttendanceResponse) => {
    setEditingId(r.id);
    setForm({
      date: r.date,
      contractorName: r.contractorName,
      skillCategory: r.skillCategory,
      plannedCount: r.plannedCount,
      actualCount: r.actualCount,
      hoursWorked: Number(r.hoursWorked),
      notes: r.notes ?? "",
    });
    setFormError(null);
    setDrawerOpen(true);
  };

  const columns = useMemo<ColumnDef<AttendanceResponse>[]>(
    () => [
      { accessorKey: "date", header: "Date" },
      { accessorKey: "contractorName", header: "Contractor" },
      {
        accessorKey: "skillCategory",
        header: "Category",
        cell: ({ row }) => (
          <span className="px-2 py-1 bg-accent/10 text-accent ring-1 ring-accent/20 rounded text-sm">
            {SKILL_LABEL[row.original.skillCategory]}
          </span>
        ),
      },
      {
        accessorKey: "plannedCount",
        header: "Planned",
        cell: ({ row }) => row.original.plannedCount,
      },
      {
        accessorKey: "actualCount",
        header: "Actual",
        cell: ({ row }) => (
          <span className="font-semibold">{row.original.actualCount}</span>
        ),
      },
      {
        accessorKey: "hoursWorked",
        header: "Man-Hours",
        cell: ({ row }) => Number(row.original.hoursWorked).toFixed(2),
      },
      {
        id: "approved",
        header: "Status",
        cell: ({ row }) =>
          row.original.approvedAt ? (
            <span className="text-success">✓ Approved</span>
          ) : (
            <span className="text-text-muted">Pending</span>
          ),
      },
      {
        id: "actions",
        header: "",
        cell: ({ row }) => {
          const r = row.original;
          const approved = r.approvedAt !== null;
          return (
            <div className="flex gap-2">
              {canUpdate && !approved && (
                <button
                  type="button"
                  onClick={() => openEdit(r)}
                  className="px-2 py-1 text-xs rounded border border-border bg-surface hover:bg-surface-hover text-text-primary"
                >
                  Edit
                </button>
              )}
              {canApprove && !approved && (
                <button
                  type="button"
                  onClick={() => approveMutation.mutate(r.id)}
                  disabled={approveMutation.isPending}
                  className="px-2 py-1 text-xs rounded bg-success text-text-primary hover:bg-success/80 disabled:opacity-50"
                >
                  Approve
                </button>
              )}
            </div>
          );
        },
      },
    ],
    [canUpdate, canApprove, approveMutation]
  );

  const submitForm = (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    if (editingId) {
      updateMutation.mutate({
        id: editingId,
        payload: {
          date: form.date,
          contractorName: form.contractorName,
          skillCategory: form.skillCategory,
          plannedCount: form.plannedCount,
          actualCount: form.actualCount,
          hoursWorked: form.hoursWorked,
          notes: form.notes || undefined,
        },
      });
    } else {
      createMutation.mutate({
        date: form.date,
        contractorName: form.contractorName,
        skillCategory: form.skillCategory,
        plannedCount: form.plannedCount,
        actualCount: form.actualCount,
        hoursWorked: form.hoursWorked,
        notes: form.notes || undefined,
      });
    }
  };

  return (
    <div className="p-6">
      <PageHeader
        title="Attendance"
        description="Daily contractor attendance and man-hours, with supervisor approval."
        actions={
          canCreate ? (
            <button
              type="button"
              onClick={openCreate}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
            >
              Log Attendance
            </button>
          ) : null
        }
      />

      {/* Summary Panel */}
      <div className="mb-6 grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-5">
        {SKILL_OPTIONS.map((cat) => {
          const s = summary.find((x) => x.skillCategory === cat);
          return (
            <div key={cat} className="bg-surface/50 p-3 rounded-lg border border-border">
              <p className="text-sm text-text-secondary">{SKILL_LABEL[cat]}</p>
              <p className="text-2xl font-bold text-text-primary">
                {s?.totalActual ?? 0}
                <span className="ml-2 text-xs font-normal text-text-muted">
                  / {s?.totalPlanned ?? 0} planned
                </span>
              </p>
              <p className="text-xs text-text-muted">
                {Number(s?.totalHoursWorked ?? 0).toFixed(1)} man-hours · {s?.rowCount ?? 0} rows
              </p>
            </div>
          );
        })}
      </div>

      {/* Date Range Filter */}
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div>
          <label className="block text-xs font-medium mb-1 text-text-secondary">From</label>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
          />
        </div>
        <div>
          <label className="block text-xs font-medium mb-1 text-text-secondary">To</label>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
          />
        </div>
        {(from || to) && (
          <button
            type="button"
            onClick={() => {
              setFrom("");
              setTo("");
            }}
            className="px-3 py-2 text-sm text-text-secondary hover:text-text-primary"
          >
            Clear
          </button>
        )}
      </div>

      {listQuery.error && (
        <div className="text-danger mb-4">
          {getErrorMessage(listQuery.error, "Failed to load attendance")}
        </div>
      )}

      <VirtualDataTable
        columns={columns}
        data={rows}
        sortable
        resizable
        isLoading={listQuery.isLoading}
        emptyMessage="No attendance recorded yet."
      />

      <Drawer
        open={drawerOpen}
        onClose={closeDrawer}
        title={editingId ? "Edit Attendance" : "Log Attendance"}
      >
        <form onSubmit={submitForm} className="flex h-full flex-col">
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {formError && <div className="text-danger text-sm">{formError}</div>}

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Date</label>
                <input
                  type="date"
                  required
                  value={form.date}
                  onChange={(e) => setForm({ ...form, date: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Skill Category
                </label>
                <select
                  required
                  value={form.skillCategory}
                  onChange={(e) =>
                    setForm({ ...form, skillCategory: e.target.value as SkillCategory })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  {SKILL_OPTIONS.map((opt) => (
                    <option key={opt} value={opt}>
                      {SKILL_LABEL[opt]}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Contractor Name
              </label>
              <input
                type="text"
                required
                value={form.contractorName}
                onChange={(e) => setForm({ ...form, contractorName: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>

            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Planned
                </label>
                <input
                  type="number"
                  min={0}
                  required
                  value={form.plannedCount}
                  onChange={(e) =>
                    setForm({ ...form, plannedCount: parseInt(e.target.value, 10) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Actual
                </label>
                <input
                  type="number"
                  min={0}
                  required
                  value={form.actualCount}
                  onChange={(e) =>
                    setForm({ ...form, actualCount: parseInt(e.target.value, 10) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Man-Hours
                </label>
                <input
                  type="number"
                  step="0.25"
                  min={0}
                  required
                  value={form.hoursWorked}
                  onChange={(e) =>
                    setForm({ ...form, hoursWorked: parseFloat(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">Notes</label>
              <textarea
                rows={3}
                value={form.notes}
                onChange={(e) => setForm({ ...form, notes: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
          </div>

          <div className="flex justify-end gap-2 border-t border-border px-5 py-3">
            <button
              type="button"
              onClick={closeDrawer}
              className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={createMutation.isPending || updateMutation.isPending}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover disabled:opacity-50"
            >
              {createMutation.isPending || updateMutation.isPending
                ? "Saving…"
                : editingId
                  ? "Save Changes"
                  : "Log Attendance"}
            </button>
          </div>
        </form>
      </Drawer>
    </div>
  );
}

"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import {
  shiftHandoverApi,
  type CreateShiftHandoverRequest,
  type Shift,
  type ShiftHandoverResponse,
} from "@/lib/api/shiftHandoverApi";
import { userApi } from "@/lib/api/userApi";
import { useAuthStore } from "@/lib/state/store";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import { PageHeader } from "@/components/common/PageHeader";
import { Drawer } from "@/components/common/Drawer";
import { getErrorMessage } from "@/lib/utils/error";

const SHIFT_LABEL: Record<Shift, string> = {
  DAY: "Day",
  NIGHT: "Night",
};

interface HandoverForm {
  shiftDate: string;
  shift: Shift;
  toUserId: string;
  summary: string;
  pendingItems: string;
}

const initialForm: HandoverForm = {
  shiftDate: new Date().toISOString().split("T")[0],
  shift: "DAY",
  toUserId: "",
  summary: "",
  pendingItems: "",
};

export default function HandoversPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  const canCreate = hasPermission("SHIFT_HANDOVER.CREATE");

  const [shiftDateFilter, setShiftDateFilter] = useState<string>("");
  const [shiftFilter, setShiftFilter] = useState<"" | Shift>("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [form, setForm] = useState<HandoverForm>(initialForm);
  const [formError, setFormError] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: [
      "shiftHandovers",
      projectId,
      shiftDateFilter || null,
      shiftFilter || null,
    ],
    queryFn: () =>
      shiftHandoverApi.list(projectId, {
        shiftDate: shiftDateFilter || undefined,
        shift: shiftFilter || undefined,
      }),
  });

  // Picker users: supervisors / foremen / site engineers (incoming candidates)
  const usersQuery = useQuery({
    queryKey: ["handoverPickerUsers"],
    queryFn: () => userApi.listUsers(0, 200, ["SUPERVISOR", "FOREMAN", "SITE_ENGINEER"]),
    enabled: drawerOpen,
  });

  const handovers = listQuery.data?.data ?? [];
  const userOptions = usersQuery.data?.data?.content ?? [];

  const userNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const u of userOptions) {
      const display = [u.firstName, u.lastName].filter(Boolean).join(" ").trim() || u.username;
      map.set(u.id, display);
    }
    return map;
  }, [userOptions]);

  const renderUser = (userId: string) => userNameById.get(userId) ?? userId.substring(0, 8);

  const createMutation = useMutation({
    mutationFn: (payload: CreateShiftHandoverRequest) =>
      shiftHandoverApi.create(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["shiftHandovers", projectId] });
      setDrawerOpen(false);
      setForm(initialForm);
      setFormError(null);
    },
    onError: (err: unknown) => {
      setFormError(getErrorMessage(err, "Failed to log handover"));
    },
  });

  const acknowledgeMutation = useMutation({
    mutationFn: (id: string) => shiftHandoverApi.acknowledge(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["shiftHandovers", projectId] });
    },
  });

  const columns = useMemo<ColumnDef<ShiftHandoverResponse>[]>(
    () => [
      { accessorKey: "shiftDate", header: "Date" },
      {
        accessorKey: "shift",
        header: "Shift",
        cell: ({ row }) => {
          const isDay = row.original.shift === "DAY";
          return (
            <span
              className={
                isDay
                  ? "px-2 py-1 rounded text-sm bg-amber-500/15 text-amber-700 ring-1 ring-amber-500/30"
                  : "px-2 py-1 rounded text-sm bg-indigo-500/15 text-indigo-700 ring-1 ring-indigo-500/30"
              }
            >
              {SHIFT_LABEL[row.original.shift]}
            </span>
          );
        },
      },
      {
        accessorKey: "fromUserId",
        header: "From",
        cell: ({ row }) => renderUser(row.original.fromUserId),
      },
      {
        accessorKey: "toUserId",
        header: "To",
        cell: ({ row }) => renderUser(row.original.toUserId),
      },
      {
        accessorKey: "summary",
        header: "Summary",
        cell: ({ row }) => (
          <span className="block max-w-md truncate" title={row.original.summary}>
            {row.original.summary}
          </span>
        ),
      },
      {
        id: "acknowledged",
        header: "Status",
        cell: ({ row }) =>
          row.original.acknowledgedAt ? (
            <span className="text-success" title={`Acknowledged at ${row.original.acknowledgedAt}`}>
              ✓ Ack
            </span>
          ) : (
            <span className="text-text-muted">⏳ Pending</span>
          ),
      },
      {
        id: "actions",
        header: "",
        cell: ({ row }) => {
          const h = row.original;
          if (h.acknowledgedAt) return null;
          if (!canCreate) return null;
          return (
            <button
              type="button"
              onClick={() => acknowledgeMutation.mutate(h.id)}
              className="px-3 py-1 text-xs rounded border border-border bg-surface hover:bg-surface-hover text-text-primary"
              disabled={acknowledgeMutation.isPending}
            >
              Acknowledge
            </button>
          );
        },
      },
    ],
    [userNameById, canCreate, acknowledgeMutation]
  );

  const submitForm = (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    if (!form.toUserId) {
      setFormError("Select the incoming supervisor");
      return;
    }
    createMutation.mutate({
      shiftDate: form.shiftDate,
      shift: form.shift,
      toUserId: form.toUserId,
      summary: form.summary,
      pendingItems: form.pendingItems || undefined,
    });
  };

  return (
    <div className="p-6">
      <PageHeader
        title="Shift Handovers"
        description="Log and track supervisor shift-to-shift handover notes for this project."
        actions={
          canCreate ? (
            <button
              type="button"
              onClick={() => {
                setForm(initialForm);
                setFormError(null);
                setDrawerOpen(true);
              }}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
            >
              Log Handover
            </button>
          ) : null
        }
      />

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div>
          <label className="block text-xs font-medium mb-1 text-text-secondary">Shift Date</label>
          <input
            type="date"
            value={shiftDateFilter}
            onChange={(e) => setShiftDateFilter(e.target.value)}
            className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
          />
        </div>
        <div>
          <label className="block text-xs font-medium mb-1 text-text-secondary">Shift</label>
          <select
            value={shiftFilter}
            onChange={(e) => setShiftFilter(e.target.value as "" | Shift)}
            className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
          >
            <option value="">All</option>
            <option value="DAY">Day</option>
            <option value="NIGHT">Night</option>
          </select>
        </div>
        {(shiftDateFilter || shiftFilter) && (
          <button
            type="button"
            onClick={() => {
              setShiftDateFilter("");
              setShiftFilter("");
            }}
            className="px-3 py-2 text-sm text-text-secondary hover:text-text-primary"
          >
            Clear filters
          </button>
        )}
      </div>

      {listQuery.error && (
        <div className="text-danger mb-4">
          {getErrorMessage(listQuery.error, "Failed to load handovers")}
        </div>
      )}

      <VirtualDataTable
        columns={columns}
        data={handovers}
        sortable
        resizable
        isLoading={listQuery.isLoading}
        emptyMessage="No shift handovers logged yet."
      />

      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title="Log Shift Handover"
      >
        <form onSubmit={submitForm} className="flex h-full flex-col">
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {formError && <div className="text-danger text-sm">{formError}</div>}

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Shift Date</label>
                <input
                  type="date"
                  required
                  value={form.shiftDate}
                  onChange={(e) => setForm({ ...form, shiftDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Shift</label>
                <select
                  required
                  value={form.shift}
                  onChange={(e) => setForm({ ...form, shift: e.target.value as Shift })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="DAY">Day</option>
                  <option value="NIGHT">Night</option>
                </select>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Incoming Supervisor
              </label>
              <select
                required
                value={form.toUserId}
                onChange={(e) => setForm({ ...form, toUserId: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              >
                <option value="">— select user —</option>
                {userOptions.map((u) => {
                  const display = [u.firstName, u.lastName].filter(Boolean).join(" ").trim() || u.username;
                  return (
                    <option key={u.id} value={u.id}>
                      {display} ({u.username})
                    </option>
                  );
                })}
              </select>
              {usersQuery.isLoading && (
                <p className="mt-1 text-xs text-text-muted">Loading users…</p>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">Summary</label>
              <textarea
                required
                rows={4}
                value={form.summary}
                onChange={(e) => setForm({ ...form, summary: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                placeholder="What happened this shift; key events, safety incidents, equipment status…"
              />
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Pending Items
              </label>
              <textarea
                rows={4}
                value={form.pendingItems}
                onChange={(e) => setForm({ ...form, pendingItems: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                placeholder="Tasks left for incoming shift…"
              />
            </div>
          </div>

          <div className="flex justify-end gap-2 border-t border-border px-5 py-3">
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={createMutation.isPending}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover disabled:opacity-50"
            >
              {createMutation.isPending ? "Saving…" : "Log Handover"}
            </button>
          </div>
        </form>
      </Drawer>
    </div>
  );
}

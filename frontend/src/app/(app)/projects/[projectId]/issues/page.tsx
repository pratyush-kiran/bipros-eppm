"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { LayoutDashboard, Pencil, Trash2 } from "lucide-react";
import toast from "react-hot-toast";
import { dprIssueApi, type DprIssueFilters, type UpdateDprIssueRequest } from "@/lib/api/dprIssueApi";
import type { DprIssueRow, IssueStatus, IssueSeverity, IssueCategory } from "@/lib/types/dpr";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import {
  SEVERITY_VARIANT,
  STATUS_VARIANT,
  SEVERITY_OPTIONS,
  STATUS_OPTIONS,
  CATEGORY_OPTIONS,
  categoryLabel,
} from "@/components/dpr/IssueBadges";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { getErrorMessage } from "@/lib/utils/error";

const inputCls =
  "rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm text-text-primary focus:border-accent focus:outline-none";

export default function ProjectIssuesPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const router = useRouter();
  const queryClient = useQueryClient();

  const [filters, setFilters] = useState<DprIssueFilters>({});
  const [statusMenu, setStatusMenu] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["dpr-issues", projectId, filters],
    queryFn: () => dprIssueApi.list(projectId, filters),
    enabled: !!projectId,
  });

  const patchMutation = useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateDprIssueRequest }) =>
      dprIssueApi.patch(projectId, id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["dpr-issues", projectId] });
      setStatusMenu(null);
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => dprIssueApi.remove(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["dpr-issues", projectId] });
      toast.success("Issue deleted");
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const rows: DprIssueRow[] = data?.data ?? [];

  const setFilter = <K extends keyof DprIssueFilters>(k: K, v: DprIssueFilters[K]) =>
    setFilters((f) => ({ ...f, [k]: v || undefined }));

  const clearFilters = () => setFilters({});
  const hasFilters = Object.values(filters).some(Boolean);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Project Issues"
        description="Field issues logged from DPR submissions or raised directly against this project."
        actions={
          <div className="flex items-center gap-2">
            <Link
              href={`/projects/${projectId}/issues/dashboard`}
              className="inline-flex items-center gap-1.5 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm font-medium text-text-secondary hover:bg-surface"
            >
              <LayoutDashboard className="h-4 w-4" />
              Dashboard
            </Link>
            <Link
              href={`/projects/${projectId}/issues/new`}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90"
            >
              + New Issue
            </Link>
          </div>
        }
      />

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-2">
        <SearchableSelect
          options={[{ value: "", label: "All statuses" }, ...STATUS_OPTIONS]}
          value={filters.status ?? ""}
          onChange={(v) => setFilter("status", v as IssueStatus || undefined)}
          placeholder="Status"
          className="w-40"
        />

        <SearchableSelect
          options={[{ value: "", label: "All severities" }, ...SEVERITY_OPTIONS]}
          value={filters.severity ?? ""}
          onChange={(v) => setFilter("severity", v as IssueSeverity || undefined)}
          placeholder="Severity"
          className="w-36"
        />

        <SearchableSelect
          options={[{ value: "", label: "All categories" }, ...CATEGORY_OPTIONS]}
          value={filters.category ?? ""}
          onChange={(v) => setFilter("category", v as IssueCategory || undefined)}
          placeholder="Category"
          className="w-48"
        />

        <input
          type="date"
          value={filters.dateFrom ?? ""}
          onChange={(e) => setFilter("dateFrom", e.target.value)}
          className={inputCls}
          title="From date"
        />
        <input
          type="date"
          value={filters.dateTo ?? ""}
          onChange={(e) => setFilter("dateTo", e.target.value)}
          className={inputCls}
          title="To date"
        />

        {hasFilters && (
          <button
            onClick={clearFilters}
            className="text-sm text-text-muted underline hover:text-text-primary"
          >
            Clear
          </button>
        )}
      </div>

      {isLoading ? (
        <div className="text-text-secondary text-sm">Loading…</div>
      ) : rows.length === 0 ? (
        <EmptyState
          title="No issues logged yet"
          description="Raise an issue directly or log one during DPR submission."
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="min-w-full divide-y divide-border text-sm">
            <thead className="bg-surface-hover">
              <tr>
                {["Title", "Category", "Severity", "Status", "Assigned To", "Date", "Activity", ""].map(
                  (h) => (
                    <th
                      key={h}
                      className="px-4 py-2.5 text-left text-xs font-semibold uppercase tracking-wide text-text-muted"
                    >
                      {h}
                    </th>
                  )
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-border bg-surface">
              {rows.map((row) => (
                <tr key={row.id} className="hover:bg-surface-hover">
                  <td className="px-4 py-3 font-medium text-text-primary max-w-xs truncate">
                    {row.title}
                  </td>
                  <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                    {categoryLabel(row.category)}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    <Badge variant={SEVERITY_VARIANT[row.severity]}>
                      {row.severity}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap relative">
                    <button
                      onClick={() =>
                        setStatusMenu((prev) => (prev === row.id ? null : row.id!))
                      }
                      className="focus:outline-none"
                      title="Click to change status"
                    >
                      <Badge variant={STATUS_VARIANT[row.status]} withDot>
                        {row.status.replace("_", " ")}
                      </Badge>
                    </button>
                    {statusMenu === row.id && (
                      <div className="absolute z-20 mt-1 w-40 rounded-md border border-border bg-surface shadow-lg">
                        {STATUS_OPTIONS.map((opt) => (
                          <button
                            key={opt.value}
                            onClick={() =>
                              patchMutation.mutate({
                                id: row.id!,
                                body: { status: opt.value },
                              })
                            }
                            className="block w-full px-3 py-2 text-left text-sm hover:bg-surface-hover text-text-primary"
                          >
                            {opt.label}
                          </button>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                    {row.assignedToName ?? "—"}
                  </td>
                  <td className="px-4 py-3 text-text-muted whitespace-nowrap">
                    {row.openedAt
                      ? new Date(row.openedAt).toLocaleDateString()
                      : "—"}
                  </td>
                  <td className="px-4 py-3 text-text-muted whitespace-nowrap max-w-[160px] truncate">
                    {row.activityName ?? "—"}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() =>
                          router.push(`/projects/${projectId}/issues/${row.id}/edit`)
                        }
                        className="text-text-muted hover:text-accent"
                        title="Edit issue"
                      >
                        <Pencil className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => {
                          if (confirm("Delete this issue?")) {
                            deleteMutation.mutate(row.id!);
                          }
                        }}
                        className="text-text-muted hover:text-danger"
                        title="Delete issue"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Close status dropdown on outside click */}
      {statusMenu && (
        <div
          className="fixed inset-0 z-10"
          onClick={() => setStatusMenu(null)}
        />
      )}
    </div>
  );
}

"use client";

import { useMemo, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import { activityApi } from "@/lib/api/activityApi";
import type { CreateDprIssueRequest } from "@/lib/types/dpr";
import { PageHeader } from "@/components/common/PageHeader";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { CATEGORY_OPTIONS, SEVERITY_OPTIONS, STATUS_OPTIONS } from "@/components/dpr/IssueBadges";
import type { IssueCategory, IssueSeverity, IssueStatus } from "@/lib/types/dpr";
import { getErrorMessage } from "@/lib/utils/error";

const inputCls =
  "mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none text-sm";

const today = new Date().toISOString().slice(0, 10);

export default function NewProjectIssuePage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const router = useRouter();
  const queryClient = useQueryClient();

  const [state, setState] = useState<CreateDprIssueRequest>({
    title: "",
    category: "OTHER",
    severity: "MEDIUM",
    status: "OPEN",
    reportDate: today,
  });
  const [error, setError] = useState<string | null>(null);

  const { data: activitiesData, isLoading: activitiesLoading } = useQuery({
    queryKey: ["activities", projectId, "all"],
    queryFn: () => activityApi.listActivities(projectId, 0, 500),
    enabled: !!projectId,
  });

  const activityOptions = useMemo(
    () =>
      (activitiesData?.data?.content ?? []).map((a) => ({
        value: a.id,
        label: `${a.code} — ${a.name}`,
      })),
    [activitiesData]
  );

  const set = <K extends keyof CreateDprIssueRequest>(k: K, v: CreateDprIssueRequest[K]) =>
    setState((s) => ({ ...s, [k]: v }));

  const handleActivityChange = (activityId: string) => {
    if (!activityId) {
      setState((s) => ({ ...s, activityId: null, activityName: null }));
      return;
    }
    const activity = (activitiesData?.data?.content ?? []).find((a) => a.id === activityId);
    setState((s) => ({
      ...s,
      activityId,
      activityName: activity?.name ?? null,
    }));
  };

  const mutation = useMutation({
    mutationFn: (body: CreateDprIssueRequest) => dprIssueApi.create(projectId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["dpr-issues", projectId] });
      router.push(`/projects/${projectId}/issues`);
    },
    onError: (err) => setError(getErrorMessage(err)),
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!state.title.trim()) return setError("Title is required");
    mutation.mutate(state);
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <PageHeader
        title="New Issue"
        description="Log a field issue directly against this project."
      />

      <form onSubmit={handleSubmit} className="space-y-5 rounded-lg border border-border bg-surface p-6">
        {error && (
          <div className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
            {error}
          </div>
        )}

        <div>
          <label className="block text-sm font-medium text-text-secondary">Title *</label>
          <input
            type="text"
            maxLength={150}
            value={state.title}
            onChange={(e) => set("title", e.target.value)}
            required
            placeholder="Brief summary of the issue"
            className={inputCls}
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Category *</label>
            <SearchableSelect
              options={CATEGORY_OPTIONS}
              value={state.category ?? ""}
              onChange={(v) => set("category", v as IssueCategory)}
              placeholder="Select category"
              className="mt-1"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Severity *</label>
            <SearchableSelect
              options={SEVERITY_OPTIONS}
              value={state.severity ?? ""}
              onChange={(v) => set("severity", v as IssueSeverity)}
              placeholder="Select severity"
              className="mt-1"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Status</label>
            <SearchableSelect
              options={STATUS_OPTIONS}
              value={state.status ?? "OPEN"}
              onChange={(v) => set("status", v as IssueStatus)}
              placeholder="Select status"
              className="mt-1"
            />
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">Description</label>
          <textarea
            maxLength={2000}
            value={state.description ?? ""}
            onChange={(e) => set("description", e.target.value || null)}
            rows={3}
            placeholder="Detailed description of the issue…"
            className={inputCls}
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Activity (optional)</label>
            <SearchableSelect
              options={activityOptions}
              value={state.activityId ?? ""}
              onChange={handleActivityChange}
              placeholder="Search activities…"
              loading={activitiesLoading}
              selectedLabel={
                state.activityId
                  ? activityOptions.find((o) => o.value === state.activityId)?.label
                  : undefined
              }
              className="mt-1"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Report Date</label>
            <input
              type="date"
              value={state.reportDate ?? today}
              onChange={(e) => set("reportDate", e.target.value)}
              className={inputCls}
            />
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">Assigned To</label>
          <input
            type="text"
            maxLength={150}
            value={state.assignedToName ?? ""}
            onChange={(e) => set("assignedToName", e.target.value || null)}
            placeholder="Name of person responsible"
            className={inputCls}
          />
        </div>

        <div className="flex justify-end gap-3 pt-2">
          <button
            type="button"
            onClick={() => router.push(`/projects/${projectId}/issues`)}
            className="rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={mutation.isPending}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-50"
          >
            {mutation.isPending ? "Saving…" : "Log Issue"}
          </button>
        </div>
      </form>
    </div>
  );
}

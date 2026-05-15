"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { Drawer } from "@/components/common/Drawer";
import { EmptyState } from "@/components/common/EmptyState";
import { PageHeader } from "@/components/common/PageHeader";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useAuthStore } from "@/lib/state/store";
import { getErrorMessage } from "@/lib/utils/error";
import {
  checklistApi,
  type AnswerValue,
  type ChecklistAnswerDto,
  type ChecklistInstanceResponse,
  type ChecklistTemplateResponse,
} from "@/lib/api/checklistApi";

export default function ChecklistsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  const canCreate = hasPermission("CHECKLIST.CREATE");
  const canUpdate = hasPermission("CHECKLIST.UPDATE");
  const canApprove = hasPermission("CHECKLIST.APPROVE");

  const [selectedTemplateId, setSelectedTemplateId] = useState<string>("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeInstanceId, setActiveInstanceId] = useState<string | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);

  const { data: templatesData } = useQuery({
    queryKey: ["checklist-templates"],
    queryFn: () => checklistApi.listTemplates(),
  });
  const templates = templatesData?.data ?? [];

  const { data: instancesData, isLoading } = useQuery({
    queryKey: ["checklists", projectId],
    queryFn: () => checklistApi.list(projectId),
    enabled: !!projectId,
  });
  const instances = instancesData?.data ?? [];

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ["checklists", projectId] });

  const approveMutation = useMutation({
    mutationFn: (id: string) => checklistApi.approve(projectId, id),
    onSuccess: invalidate,
    onError: (e) => setPageError(getErrorMessage(e, "Failed to approve checklist")),
  });
  const rejectMutation = useMutation({
    mutationFn: (id: string) => checklistApi.reject(projectId, id),
    onSuccess: invalidate,
    onError: (e) => setPageError(getErrorMessage(e, "Failed to reject checklist")),
  });

  const handleStart = async () => {
    if (!selectedTemplateId) return;
    setPageError(null);
    try {
      const res = await checklistApi.start(projectId, { templateId: selectedTemplateId });
      invalidate();
      if (res.data?.id) {
        setActiveInstanceId(res.data.id);
        setDrawerOpen(true);
      }
    } catch (err) {
      setPageError(getErrorMessage(err, "Failed to start checklist"));
    }
  };

  const openInstance = (instance: ChecklistInstanceResponse) => {
    setActiveInstanceId(instance.id);
    setPageError(null);
    setDrawerOpen(true);
  };

  return (
    <div className="p-6">
      <PageHeader
        title="Checklists"
        description="Quality and safety checklists for site activities — Pre-Concrete, Excavation, Shuttering and more."
      />

      {canCreate && (
        <div className="mb-5 flex flex-wrap items-end gap-3 rounded-lg border border-border bg-surface/50 p-3">
          <div className="flex-1 min-w-[240px]">
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
              Template
            </label>
            <select
              value={selectedTemplateId}
              onChange={(e) => setSelectedTemplateId(e.target.value)}
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
            >
              <option value="">Select a template…</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>
          <button
            type="button"
            onClick={handleStart}
            disabled={!selectedTemplateId}
            className="inline-flex items-center gap-1.5 rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
          >
            <Plus className="h-4 w-4" /> New Checklist
          </button>
        </div>
      )}

      {pageError && (
        <div className="mb-4 rounded-md border border-danger/20 bg-danger/10 px-4 py-2 text-sm text-danger">
          {pageError}
        </div>
      )}

      {isLoading ? (
        <div className="text-sm text-text-muted">Loading…</div>
      ) : instances.length === 0 ? (
        <EmptyState
          title="No checklists yet"
          description="Start a checklist by selecting a template above."
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface-hover text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
              <tr>
                <th className="px-4 py-2">Template</th>
                <th className="px-4 py-2">Activity</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Started By</th>
                <th className="px-4 py-2">Started At</th>
                <th className="px-4 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {instances.map((row) => (
                <tr
                  key={row.id}
                  className="border-t border-border hover:bg-surface-hover/50"
                >
                  <td className="px-4 py-2 font-medium text-text-primary">
                    <button
                      type="button"
                      className="text-accent hover:underline"
                      onClick={() => openInstance(row)}
                    >
                      {row.templateName ?? row.templateCode ?? "(unknown)"}
                    </button>
                  </td>
                  <td className="px-4 py-2 text-text-secondary">
                    {row.activityId ? row.activityId.substring(0, 8) : "—"}
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={row.status} />
                  </td>
                  <td className="px-4 py-2 text-text-secondary">
                    {row.startedBy ? row.startedBy.substring(0, 8) : "—"}
                  </td>
                  <td className="px-4 py-2 text-text-secondary">
                    {row.startedAt ? new Date(row.startedAt).toLocaleString() : "—"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {row.status === "SUBMITTED" && canApprove && (
                      <span className="flex justify-end gap-2">
                        <button
                          onClick={() => approveMutation.mutate(row.id)}
                          disabled={approveMutation.isPending}
                          className="rounded-md bg-success/10 px-3 py-1 text-xs font-medium text-success hover:bg-success/20"
                        >
                          Approve
                        </button>
                        <button
                          onClick={() => rejectMutation.mutate(row.id)}
                          disabled={rejectMutation.isPending}
                          className="rounded-md bg-danger/10 px-3 py-1 text-xs font-medium text-danger hover:bg-danger/20"
                        >
                          Reject
                        </button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title="Checklist"
        widthClass="max-w-3xl"
      >
        {activeInstanceId && (
          <ChecklistInstanceForm
            key={activeInstanceId}
            projectId={projectId}
            instanceId={activeInstanceId}
            templates={templates}
            canUpdate={canUpdate}
            onSaved={() => {
              invalidate();
              setDrawerOpen(false);
            }}
            onError={setPageError}
          />
        )}
      </Drawer>
    </div>
  );
}

// ─── Drawer: fill out a checklist instance ───────────────────────────────────

interface ChecklistFormProps {
  projectId: string;
  instanceId: string;
  templates: ChecklistTemplateResponse[];
  canUpdate: boolean;
  onSaved: () => void;
  onError: (msg: string) => void;
}

function ChecklistInstanceForm({
  projectId,
  instanceId,
  templates,
  canUpdate,
  onSaved,
  onError,
}: ChecklistFormProps) {
  const { data, isLoading } = useQuery({
    queryKey: ["checklist", projectId, instanceId],
    queryFn: () => checklistApi.get(projectId, instanceId),
  });
  const instance = data?.data ?? null;

  const template = useMemo(
    () => templates.find((t) => t.id === instance?.templateId) ?? null,
    [templates, instance]
  );

  const [answers, setAnswers] = useState<Record<string, ChecklistAnswerDto>>({});
  const [saving, setSaving] = useState(false);

  // Seed the local answer map once the instance + template are both loaded. We key by
  // template item id so the editor renders all items even when no answer exists yet.
  useMemo(() => {
    if (!template) return;
    const seeded: Record<string, ChecklistAnswerDto> = {};
    for (const item of template.items) {
      const existing = instance?.answers.find((a) => a.itemId === item.id);
      seeded[item.id] = {
        itemId: item.id,
        value: existing?.value ?? null,
        note: existing?.note ?? "",
        photoUrl: existing?.photoUrl ?? "",
      };
    }
    setAnswers(seeded);
  }, [template, instance]);

  if (isLoading || !instance || !template) {
    return <div className="p-5 text-sm text-text-muted">Loading…</div>;
  }

  const editable = instance.status === "IN_PROGRESS" && canUpdate;

  const setAnswer = (itemId: string, patch: Partial<ChecklistAnswerDto>) => {
    setAnswers((prev) => ({
      ...prev,
      [itemId]: { ...prev[itemId], ...patch, itemId },
    }));
  };

  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      await checklistApi.saveAnswers(projectId, instanceId, {
        answers: Object.values(answers),
      });
      onSaved();
    } catch (err) {
      onError(getErrorMessage(err, "Failed to save answers"));
    } finally {
      setSaving(false);
    }
  };

  const handleSubmit = async () => {
    setSaving(true);
    try {
      await checklistApi.saveAnswers(projectId, instanceId, {
        answers: Object.values(answers),
      });
      await checklistApi.submit(projectId, instanceId);
      onSaved();
    } catch (err) {
      onError(getErrorMessage(err, "Failed to submit checklist"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-5 p-5">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold text-text-primary">{template.name}</h3>
          <p className="text-xs text-text-secondary">{template.code}</p>
        </div>
        <StatusBadge status={instance.status} />
      </div>

      <div className="space-y-3">
        {template.items.map((item) => {
          const answer = answers[item.id];
          return (
            <div key={item.id} className="rounded-md border border-border bg-surface/50 p-3">
              <div className="mb-2 flex items-baseline justify-between gap-2">
                <p className="text-sm font-medium text-text-primary">
                  {item.sequence}. {item.label}
                  {item.mandatory && <span className="ml-1 text-danger">*</span>}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                {(["YES", "NO", "NA"] as AnswerValue[]).map((v) => (
                  <label
                    key={v}
                    className={`flex cursor-pointer items-center gap-1 rounded-md border px-3 py-1 text-xs font-medium transition ${
                      answer?.value === v
                        ? "border-accent bg-accent/10 text-accent"
                        : "border-border bg-surface text-text-secondary"
                    } ${!editable ? "opacity-60" : ""}`}
                  >
                    <input
                      type="radio"
                      name={`answer-${item.id}`}
                      checked={answer?.value === v}
                      onChange={() => setAnswer(item.id, { value: v })}
                      disabled={!editable}
                      className="hidden"
                    />
                    {v}
                  </label>
                ))}
              </div>
              {item.evidenceType !== "NONE" && (
                <div className="mt-2">
                  <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                    {item.evidenceType === "PHOTO" ? "Photo URL" : "Note"}
                  </label>
                  {item.evidenceType === "PHOTO" ? (
                    <input
                      type="url"
                      value={answer?.photoUrl ?? ""}
                      onChange={(e) => setAnswer(item.id, { photoUrl: e.target.value })}
                      disabled={!editable}
                      placeholder="https://…"
                      className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
                    />
                  ) : (
                    <textarea
                      value={answer?.note ?? ""}
                      onChange={(e) => setAnswer(item.id, { note: e.target.value })}
                      disabled={!editable}
                      rows={2}
                      className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
                    />
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="flex justify-end gap-2 border-t border-border pt-4">
        <button
          type="button"
          onClick={() => onSaved()}
          className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-hover"
        >
          Close
        </button>
        {editable && (
          <>
            <button
              type="button"
              onClick={handleSaveDraft}
              disabled={saving}
              className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-hover disabled:opacity-50"
            >
              Save Draft
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={saving}
              className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
            >
              {saving ? "Submitting…" : "Submit"}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

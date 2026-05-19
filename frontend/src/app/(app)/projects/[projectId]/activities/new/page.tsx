"use client";

import { useState } from "react";
import { useRouter, useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/common/PageHeader";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { projectApi } from "@/lib/api/projectApi";
import { activityApi } from "@/lib/api/activityApi";
import type { CreateActivityRequest } from "@/lib/api/activityApi";
import { workActivityApi } from "@/lib/api/workActivityApi";
import { calendarApi } from "@/lib/api/calendarApi";
import { userApi } from "@/lib/api/userApi";
import type { WbsNodeResponse } from "@/lib/types";
import { getErrorMessage } from "@/lib/utils/error";
import { activityNotifications, notificationHelpers } from "@/lib/notificationHelpers";
import { Breadcrumb } from "@/components/common/Breadcrumb";
import { LinkOrCreateWorkActivityDialog } from "@/components/activity/LinkOrCreateWorkActivityDialog";
import { WorkActivityCoverageChip } from "@/components/activity/WorkActivityCoverageChip";
import { Plus } from "lucide-react";

export default function NewActivityPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const params = useParams();
  const projectId = params.projectId as string;

  const [formData, setFormData] = useState<{
    code: string;
    name: string;
    activityType: string;
    durationType: string;
    percentCompleteType: "DURATION" | "UNITS" | "PHYSICAL";
    duration: number;
    wbsNodeId: string;
    plannedStartDate: string;
    plannedFinishDate: string;
    workActivityId: string;
    calendarId: string;
    supervisorUserId: string;
    supervisorUserName: string;
    preliminary: boolean;
  }>({
    code: "",
    name: "",
    activityType: "TASK_DEPENDENT",
    durationType: "FIXED_DURATION_AND_UNITS",
    percentCompleteType: "DURATION",
    duration: 0,
    wbsNodeId: "",
    plannedStartDate: "",
    plannedFinishDate: "",
    workActivityId: "",
    calendarId: "",
    supervisorUserId: "",
    supervisorUserName: "",
    preliminary: false,
  });

  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [masterDialogOpen, setMasterDialogOpen] = useState(false);

  const { data: wbsData, isLoading: isLoadingWbs } = useQuery({
    queryKey: ["wbs", projectId],
    queryFn: () => projectApi.getWbsTree(projectId),
  });

  const wbsNodes = wbsData?.data ?? [];

  const { data: workActivitiesData, isLoading: isLoadingWorkActivities } = useQuery({
    queryKey: ["work-activities", "active"],
    queryFn: () => workActivityApi.list(true),
  });
  const workActivities = workActivitiesData?.data ?? [];

  const { data: calendarsData, isLoading: isLoadingCalendars } = useQuery({
    queryKey: ["calendars", "all"],
    queryFn: () => calendarApi.listCalendars(),
  });
  const projectCalendars = calendarsData?.data ?? [];

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
  });
  const projectCalendarId = projectData?.data?.calendarId;

  // Phase 4.4 RBAC: supervisor candidates are Users carrying SUPERVISOR / FOREMAN /
  // SITE_ENGINEER / SITE_MANAGER. The picker source moved off the project resource pool —
  // see the SetSupervisorDialog component for the canonical pattern.
  const { data: supervisorUsers, isLoading: isLoadingPool } = useQuery({
    queryKey: ["users-by-role", "supervisor-pool"],
    queryFn: () =>
      userApi.listByRoles(["SUPERVISOR", "FOREMAN", "SITE_ENGINEER", "SITE_MANAGER"]),
  });
  const supervisorOptions = (supervisorUsers ?? []).map((u) => ({
    value: u.id,
    label: u.employeeCode ? `${u.employeeCode} — ${u.name}` : u.name,
  }));

  // Flatten WBS tree for dropdown
  const flattenedWbs = flattenWbsNodes(wbsNodes);

  const handleChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value } = e.target;
    if (error) setError("");
    if (fieldErrors[name]) setFieldErrors((prev) => { const next = { ...prev }; delete next[name]; return next; });
    setFormData((prev) => ({
      ...prev,
      [name]: name === "duration" ? (value === "" ? "" : parseInt(value, 10)) : value,
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    const errors: Record<string, string> = {};
    if (!formData.code.trim()) errors.code = "Activity code is required";
    if (!formData.name.trim()) errors.name = "Activity name is required";
    if (!formData.wbsNodeId) errors.wbsNodeId = "WBS Node is required";
    if (formData.plannedStartDate && formData.plannedFinishDate && formData.plannedStartDate > formData.plannedFinishDate) {
      errors.plannedFinishDate = "Finish date must be after start date";
    }
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setError("Please fix the highlighted fields");
      return;
    }

    setIsSubmitting(true);
    try {
      const createRequest: CreateActivityRequest = {
        code: formData.code,
        name: formData.name,
        projectId: projectId,
        wbsNodeId: formData.wbsNodeId,
        originalDuration: formData.duration || undefined,
        activityType: formData.activityType,
        durationType: formData.durationType,
        percentCompleteType: formData.percentCompleteType,
        plannedStartDate: formData.plannedStartDate || undefined,
        plannedFinishDate: formData.plannedFinishDate || undefined,
        workActivityId: formData.workActivityId || undefined,
        calendarId: formData.calendarId || undefined,
        supervisorUserId: formData.supervisorUserId || undefined,
        supervisorUserName: formData.supervisorUserName || undefined,
        // DBS-Phase-2 BOQ Section 1 (Preliminaries) flag. Only send when explicitly ticked so
        // the backend's null-means-default contract is preserved.
        preliminary: formData.preliminary || undefined,
      };

      const result = await activityApi.createActivity(projectId, createRequest);
      if (result.error) {
        setError(result.error?.message ?? "Failed to create activity");
        notificationHelpers.handleApiError(result.error, "Failed to create activity");
      } else {
        // POST /v1/projects/{id}/activities is part of the multi-supervisor
        // refactor (commit 182141eb) and silently DROPS the supervisorUserId
        // field on the request body. If the user picked a supervisor in the
        // form, immediately follow up with the dedicated supervisor PUT so the
        // selection actually persists. We do not roll back on failure — the
        // activity is created; only the supervisor link is missing.
        const createdId = result.data?.id;
        if (createdId && formData.supervisorUserId) {
          try {
            await activityApi.setSupervisor(projectId, createdId, {
              supervisorUserId: formData.supervisorUserId,
              supervisorName: formData.supervisorUserName || null,
            });
          } catch (supErr: unknown) {
            notificationHelpers.handleApiError(
              supErr,
              "Activity created, but failed to assign supervisor"
            );
          }
        }
        activityNotifications.created();
        queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
        router.push(`/projects/${projectId}/activities`);
      }
    } catch (err: unknown) {
      const msg = getErrorMessage(err, "Failed to create activity");
      setError(msg);
      notificationHelpers.handleApiError(err, "Failed to create activity");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      <div className="mb-4">
        <Breadcrumb items={[
          { label: "Projects", href: "/projects" },
          { label: "Project", href: `/projects/${projectId}` },
          { label: "Activities", href: `/projects/${projectId}/activities` },
          { label: "New Activity", href: `/projects/${projectId}/activities/new`, active: true },
        ]} />
      </div>
      <PageHeader
        title="New Activity"
        description="Create a new activity for this project"
      />

      <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
        <form onSubmit={handleSubmit} className="space-y-6">
          {error && (
            <div className="flex items-center justify-between rounded-md bg-danger/10 p-4 text-sm text-danger">
              <span>{error}</span>
              <button type="button" onClick={() => setError("")} className="ml-3 text-danger hover:text-danger">&times;</button>
            </div>
          )}

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Code *</label>
              <input
                type="text"
                name="code"
                value={formData.code}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.code ? "border-danger" : "border-border"} px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
                placeholder="e.g., ACT-001"
              />
              {fieldErrors.code && <p className="mt-1 text-xs text-danger">{fieldErrors.code}</p>}
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">Name *</label>
              <input
                type="text"
                name="name"
                value={formData.name}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.name ? "border-danger" : "border-border"} px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
                placeholder="e.g., Design Phase"
              />
              {fieldErrors.name && <p className="mt-1 text-xs text-danger">{fieldErrors.name}</p>}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Activity Type</label>
              <select
                name="activityType"
                value={formData.activityType}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="TASK_DEPENDENT">Task Dependent</option>
                <option value="RESOURCE_DEPENDENT">Resource Dependent</option>
                <option value="LEVEL_OF_EFFORT">Level of Effort</option>
                <option value="START_MILESTONE">Start Milestone</option>
                <option value="FINISH_MILESTONE">Finish Milestone</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">Duration Type</label>
              <select
                name="durationType"
                value={formData.durationType}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="FIXED_DURATION_AND_UNITS">Fixed Duration & Units</option>
                <option value="FIXED_DURATION_AND_UNITS_PER_TIME">Fixed Duration & Units/Time</option>
                <option value="FIXED_UNITS">Fixed Units</option>
                <option value="FIXED_UNITS_PER_TIME">Fixed Units/Time</option>
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">% Complete Type</label>
              <select
                name="percentCompleteType"
                value={formData.percentCompleteType}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="DURATION">Duration — auto from elapsed days</option>
                <option value="UNITS">Units — auto from Daily Output</option>
                <option value="PHYSICAL">Physical — manual / step-driven</option>
              </select>
              <p className="mt-1 text-xs text-text-muted">
                Drives how % Complete is calculated. Duration & Units update automatically; Physical is manually entered.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Duration (days) *
              </label>
              <input
                type="number"
                name="duration"
                value={formData.duration}
                onChange={handleChange}
                min="0"
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                placeholder="e.g., 5"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">WBS Node *</label>
              <SearchableSelect
                value={formData.wbsNodeId}
                onChange={(val) => { if (error) setError(""); if (fieldErrors.wbsNodeId) setFieldErrors((prev) => { const next = { ...prev }; delete next.wbsNodeId; return next; }); setFormData((prev) => ({ ...prev, wbsNodeId: val })); }}
                placeholder="Search WBS nodes..."
                options={flattenedWbs.map((node) => ({
                  value: node.id,
                  label: `${node.indent}${node.code} - ${node.name}`,
                }))}
                disabled={isLoadingWbs}
              />
              {fieldErrors.wbsNodeId && <p className="mt-1 text-xs text-danger">{fieldErrors.wbsNodeId}</p>}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Supervisor
              </label>
              <SearchableSelect
                value={formData.supervisorUserId}
                onChange={(val) => {
                  const picked = (supervisorUsers ?? []).find((u) => u.id === val);
                  setFormData((prev) => ({
                    ...prev,
                    supervisorUserId: val,
                    supervisorUserName: picked?.name ?? "",
                  }));
                }}
                placeholder={
                  isLoadingPool
                    ? "Loading users..."
                    : supervisorOptions.length
                      ? "Search supervisors..."
                      : "No users with supervisor roles"
                }
                options={[{ value: "", label: "— none —" }, ...supervisorOptions]}
                disabled={isLoadingPool || supervisorOptions.length === 0}
              />
              <p className="mt-1 text-xs text-text-muted">
                Field-accountable supervisor. Picker lists users carrying SUPERVISOR /
                FOREMAN / SITE_ENGINEER / SITE_MANAGER roles. Leave empty if no supervisor.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Planned Start Date
              </label>
              <input
                type="date"
                name="plannedStartDate"
                value={formData.plannedStartDate}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">
                Planned Finish Date
              </label>
              <input
                type="date"
                name="plannedFinishDate"
                value={formData.plannedFinishDate}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.plannedFinishDate ? "border-danger" : "border-border"} px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
              />
              {fieldErrors.plannedFinishDate && <p className="mt-1 text-xs text-danger">{fieldErrors.plannedFinishDate}</p>}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary">
              Work Activity (master)
            </label>
            <div className="mt-1 flex items-stretch gap-2">
              <div className="flex-1">
                <SearchableSelect
                  value={formData.workActivityId}
                  onChange={(val) => setFormData((prev) => ({ ...prev, workActivityId: val }))}
                  placeholder={
                    isLoadingWorkActivities
                      ? "Loading work activities..."
                      : "Search master library (optional)..."
                  }
                  options={[
                    { value: "", label: "— none —" },
                    ...workActivities.map((wa) => ({
                      value: wa.id,
                      label: wa.defaultUnit ? `${wa.name} (${wa.defaultUnit})` : wa.name,
                    })),
                  ]}
                  disabled={isLoadingWorkActivities}
                />
              </div>
              <button
                type="button"
                onClick={() => setMasterDialogOpen(true)}
                className="inline-flex items-center gap-1 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm font-medium text-text-secondary hover:bg-surface-active hover:text-text-primary"
                title="Create a new Master Work Activity from this activity's name/code"
              >
                <Plus size={14} /> Create new master
              </button>
            </div>
            <p className="mt-1 text-xs text-text-muted">
              Optional: link this activity to a master entry from{" "}
              <em>Admin → Work Activities</em>. Required to derive productivity-norm-driven
              capacity utilisation downstream.
            </p>
            <WorkActivityCoverageChip workActivityId={formData.workActivityId || null} />
          </div>

          <LinkOrCreateWorkActivityDialog
            open={masterDialogOpen}
            onClose={() => setMasterDialogOpen(false)}
            mode="LINK_OR_CREATE"
            defaultCode={formData.code}
            defaultName={formData.name}
            onMasterSelected={(masterId) =>
              setFormData((prev) => ({ ...prev, workActivityId: masterId }))
            }
          />

          <div>
            <label className="block text-sm font-medium text-text-secondary">
              Calendar
            </label>
            <select
              name="calendarId"
              value={formData.calendarId}
              onChange={handleChange}
              className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              disabled={isLoadingCalendars}
            >
              {isLoadingCalendars && <option value="">Loading…</option>}
              <option value="">
                {(() => {
                  const inherited = projectCalendars.find((c) => c.id === projectCalendarId);
                  return inherited
                    ? `— Inherit from project: ${inherited.name} —`
                    : "— Inherit from project —";
                })()}
              </option>
              {projectCalendars.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.standardWorkHoursPerDay}h / {c.standardWorkDaysPerWeek}d)
                </option>
              ))}
            </select>
            <p className="mt-1 text-xs text-text-muted">
              Leave empty to use the project’s default calendar. Select a different calendar to override.
            </p>
          </div>

          <div className="border-t border-border pt-6">
            <div className="flex items-start gap-3">
              <input
                type="checkbox"
                id="preliminary"
                checked={formData.preliminary}
                onChange={(e) =>
                  setFormData((prev) => ({ ...prev, preliminary: e.target.checked }))
                }
                className="mt-1 rounded border-border"
              />
              <label htmlFor="preliminary" className="text-sm text-text-secondary">
                <span className="font-medium text-text-primary">
                  Preliminary item (BOQ Section 1 — mobilization, site setup, diversions, etc.)
                </span>
                <p className="mt-1 text-xs text-text-muted">
                  When checked, this activity&rsquo;s cost is rolled up into the DBS{" "}
                  <em>Preliminaries</em> bucket instead of <em>Direct Cost</em>. Use for
                  mobilisation, site facilities, diversions, bonds, insurance and other Section 1
                  overhead items.
                </p>
              </label>
            </div>
          </div>

          <div className="flex gap-3 pt-6">
            <button
              type="submit"
              disabled={isSubmitting}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
            >
              {isSubmitting ? "Creating..." : "Create Activity"}
            </button>
            <button
              type="button"
              onClick={() => router.push(`/projects/${projectId}/activities`)}
              className="rounded-md bg-surface-active/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-active"
            >
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function flattenWbsNodes(
  nodes: WbsNodeResponse[],
  level = 0
): Array<{ id: string; code: string; name: string; indent: string }> {
  const result: Array<{ id: string; code: string; name: string; indent: string }> = [];
  for (const node of nodes) {
    result.push({
      id: node.id,
      code: node.code,
      name: node.name,
      indent: "  ".repeat(level),
    });
    if (node.children && node.children.length > 0) {
      result.push(...flattenWbsNodes(node.children, level + 1));
    }
  }
  return result;
}

"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import { PageHeader } from "@/components/common/PageHeader";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { projectApi } from "@/lib/api/projectApi";
import { obsApi } from "@/lib/api/obsApi";
import { projectCategoryApi } from "@/lib/api/projectCategoryApi";
import { calendarApi } from "@/lib/api/calendarApi";
import { getErrorMessage } from "@/lib/utils/error";
import { getPriorityInfo } from "@/lib/utils/format";
import { projectNotifications, notificationHelpers } from "@/lib/notificationHelpers";
import { Breadcrumb } from "@/components/common/Breadcrumb";
import type { CreateProjectRequest } from "@/lib/types";

export default function NewProjectPage() {
  const router = useRouter();
  const [formData, setFormData] = useState<CreateProjectRequest>({
    code: "",
    name: "",
    description: "",
    epsNodeId: "",
    plannedStartDate: "",
    plannedFinishDate: "",
    priority: 50,
    // Default to INR for back-compat with existing seed data; users on non-INR
    // projects (e.g. Oman OMR) MUST change this so EVM/cost cards label money
    // correctly and AI data-honesty gates don't mislabel an OMR audit as INR.
    budgetCurrency: "INR",
    calendarId: "",
  });

  // ISO-4217 currency choices we support in the create form. Keep this in
  // sync with the backend Project entity's accepted currencies (currently
  // free-form String defaulting to INR — see Project.java:177).
  const CURRENCY_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
    { code: "INR", label: "INR - Indian Rupee" },
    { code: "USD", label: "USD - US Dollar" },
    { code: "OMR", label: "OMR - Omani Rial" },
    { code: "AED", label: "AED - UAE Dirham" },
    { code: "SAR", label: "SAR - Saudi Riyal" },
    { code: "EUR", label: "EUR - Euro" },
    { code: "GBP", label: "GBP - British Pound" },
  ];

  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { data: epsData, isLoading: isLoadingEps } = useQuery({
    queryKey: ["eps"],
    queryFn: () => projectApi.getEpsTree(),
  });

  const { data: obsData, isLoading: isLoadingObs } = useQuery({
    queryKey: ["obs"],
    queryFn: () => obsApi.getObsTree(),
  });

  const { data: categoriesData, isLoading: isLoadingCategories } = useQuery({
    queryKey: ["project-categories"],
    queryFn: () => projectCategoryApi.listActive(),
  });

  const { data: calendarsData, isLoading: isLoadingCalendars } = useQuery({
    queryKey: ["calendars", "all"],
    queryFn: () => calendarApi.listCalendars(),
  });

  const epsNodes = epsData?.data ?? [];
  const obsNodes = obsData?.data ?? [];
  const categories = categoriesData?.data ?? [];
  const allCalendars = calendarsData?.data ?? [];

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    if (error) setError("");
    if (fieldErrors[name]) setFieldErrors((prev) => { const next = { ...prev }; delete next[name]; return next; });
    setFormData((prev) => ({
      ...prev,
      [name]: name === "priority" ? parseInt(value, 10) : value,
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    const errors: Record<string, string> = {};
    if (!formData.code.trim()) errors.code = "Project code is required";
    if (!formData.name.trim()) errors.name = "Project name is required";
    if (!formData.epsNodeId) errors.epsNodeId = "EPS Node is required";
    if (!formData.plannedStartDate) errors.plannedStartDate = "Planned start date is required";
    if (!formData.plannedFinishDate) errors.plannedFinishDate = "Planned finish date is required";
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
      const result = await projectApi.createProject(formData);
      if (result.data) {
        projectNotifications.created();
        router.push(`/projects/${result.data.id}`);
      }
    } catch (err: unknown) {
      const msg = getErrorMessage(err, "Failed to create project");
      const isDuplicate =
        (err instanceof Error && /already exists|duplicate|conflict/i.test(err.message)) ||
        (typeof err === "object" && err !== null && "status" in err && (err as { status: number }).status === 409);
      setError(isDuplicate ? `Project code "${formData.code}" already exists. Please use a different code.` : msg);
      notificationHelpers.handleApiError(err, isDuplicate ? "Duplicate project code" : "Failed to create project");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      <div className="mb-4">
        <Breadcrumb items={[
          { label: "Projects", href: "/projects" },
          { label: "New Project", href: "/projects/new", active: true },
        ]} />
      </div>
      <PageHeader title="New Project" description="Create a new project to get started" />

      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
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
                className={`mt-1 block w-full rounded-md border ${fieldErrors.code ? "border-danger" : "border-border"} bg-surface-hover px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
                placeholder="e.g., PROJ-001"
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
                className={`mt-1 block w-full rounded-md border ${fieldErrors.name ? "border-danger" : "border-border"} bg-surface-hover px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
                placeholder="e.g., Website Redesign"
              />
              {fieldErrors.name && <p className="mt-1 text-xs text-danger">{fieldErrors.name}</p>}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary">Description</label>
            <textarea
              name="description"
              value={formData.description || ""}
              onChange={handleChange}
              rows={4}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              placeholder="Project description"
            />
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">EPS Node *</label>
              <SearchableSelect
                value={formData.epsNodeId}
                onChange={(val) => { if (error) setError(""); if (fieldErrors.epsNodeId) setFieldErrors((prev) => { const next = { ...prev }; delete next.epsNodeId; return next; }); setFormData((prev) => ({ ...prev, epsNodeId: val })); }}
                placeholder="Search EPS nodes..."
                options={epsNodes.map((node) => ({
                  value: node.id,
                  label: `${node.code} - ${node.name}`,
                }))}
                disabled={isLoadingEps}
              />
              {fieldErrors.epsNodeId && <p className="mt-1 text-xs text-danger">{fieldErrors.epsNodeId}</p>}
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">OBS Node</label>
              <SearchableSelect
                value={formData.obsNodeId || ""}
                onChange={(val) => { if (error) setError(""); setFormData((prev) => ({ ...prev, obsNodeId: val })); }}
                placeholder="Search OBS nodes..."
                options={obsNodes.map((node) => ({
                  value: node.id,
                  label: `${node.code} - ${node.name}`,
                }))}
                disabled={isLoadingObs}
              />
            </div>
          </div>

          <div className="grid grid-cols-3 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Priority</label>
              <select
                name="priority"
                value={formData.priority}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                {[5, 20, 35, 50, 65, 80, 95].map((p) => (
                  <option key={p} value={p}>
                    {p} - {getPriorityInfo(p).label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Currency *</label>
              <select
                name="budgetCurrency"
                value={formData.budgetCurrency ?? "INR"}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                {CURRENCY_OPTIONS.map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.label}
                  </option>
                ))}
              </select>
              <p className="mt-1 text-xs text-text-muted">
                Budget currency for EVM, cost cards, and AI reports. Cannot be changed after creation.
              </p>
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Calendar</label>
              <select
                name="calendarId"
                value={formData.calendarId || ""}
                onChange={handleChange}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                disabled={isLoadingCalendars}
              >
                {isLoadingCalendars && <option value="">Loading…</option>}
                <option value="">— none —</option>
                {allCalendars.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} ({c.standardWorkHoursPerDay}h / {c.standardWorkDaysPerWeek}d)
                  </option>
                ))}
              </select>
              <p className="mt-1 text-xs text-text-muted">
                Default calendar for all activities in this project.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Planned Start Date *</label>
              <input
                type="date"
                name="plannedStartDate"
                value={formData.plannedStartDate}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.plannedStartDate ? "border-danger" : "border-border"} bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
              />
              {fieldErrors.plannedStartDate && <p className="mt-1 text-xs text-danger">{fieldErrors.plannedStartDate}</p>}
            </div>

            <div>
              <label className="block text-sm font-medium text-text-secondary">Planned Finish Date *</label>
              <input
                type="date"
                name="plannedFinishDate"
                value={formData.plannedFinishDate || ""}
                onChange={handleChange}
                className={`mt-1 block w-full rounded-md border ${fieldErrors.plannedFinishDate ? "border-danger" : "border-border"} bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent`}
              />
              {fieldErrors.plannedFinishDate && <p className="mt-1 text-xs text-danger">{fieldErrors.plannedFinishDate}</p>}
            </div>
          </div>

          {/* ── PMS MasterData Screen 01 fields ── */}
          <div className="border-t border-border pt-6">
            <h3 className="mb-4 text-sm font-semibold uppercase tracking-wide text-text-secondary">
              Project Category & Corridor (optional)
            </h3>
            <div className="grid grid-cols-2 gap-6">
              <div>
                <label className="block text-sm font-medium text-text-secondary">Category</label>
                <select
                  value={formData.category ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      category: (e.target.value || null) as CreateProjectRequest["category"],
                    }))
                  }
                  disabled={isLoadingCategories}
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
                >
                  <option value="">—</option>
                  {categories.map((cat) => (
                    <option key={cat.code} value={cat.code}>
                      {cat.name}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">MoRTH Code</label>
                <input
                  value={formData.morthCode ?? ""}
                  onChange={(e) => setFormData((f) => ({ ...f, morthCode: e.target.value || null }))}
                  placeholder="NH-48"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
            <div className="mt-6 grid grid-cols-2 gap-6">
              <div>
                <label className="block text-sm font-medium text-text-secondary">From Chainage (m)</label>
                <input
                  type="number"
                  value={formData.fromChainageM ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      fromChainageM: e.target.value ? Number(e.target.value) : null,
                    }))
                  }
                  placeholder="145000"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">To Chainage (m)</label>
                <input
                  type="number"
                  value={formData.toChainageM ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      toChainageM: e.target.value ? Number(e.target.value) : null,
                    }))
                  }
                  placeholder="165000"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
            <div className="mt-6 grid grid-cols-2 gap-6">
              <div>
                <label className="block text-sm font-medium text-text-secondary">From Location</label>
                <input
                  value={formData.fromLocation ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({ ...f, fromLocation: e.target.value || null }))
                  }
                  placeholder="Km 145+000"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">To Location</label>
                <input
                  value={formData.toLocation ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({ ...f, toLocation: e.target.value || null }))
                  }
                  placeholder="Km 165+000"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          </div>

          <div className="border-t border-border pt-6">
            <h3 className="mb-4 text-sm font-semibold uppercase tracking-wide text-text-secondary">
              Primary Contract (optional)
            </h3>
            <div className="grid grid-cols-2 gap-6">
              <div>
                <label className="block text-sm font-medium text-text-secondary">Contract Number</label>
                <input
                  value={formData.contract?.contractNumber ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      contract: { ...(f.contract ?? {}), contractNumber: e.target.value || null },
                    }))
                  }
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">Contract Type</label>
                <select
                  value={formData.contract?.contractType ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      contract: {
                        ...(f.contract ?? {}),
                        contractType: (e.target.value || null) as
                          | NonNullable<CreateProjectRequest["contract"]>["contractType"]
                          | null,
                      },
                    }))
                  }
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  <option value="">—</option>
                  <option value="EPC">EPC</option>
                  <option value="BOT">BOT</option>
                  <option value="HAM">HAM</option>
                  <option value="ITEM_RATE">Item Rate</option>
                  <option value="LUMP_SUM">Lump Sum</option>
                  <option value="ANNUITY">Annuity</option>
                </select>
              </div>
            </div>
            <div className="mt-6 grid grid-cols-3 gap-6">
              <div>
                <label className="block text-sm font-medium text-text-secondary">Contract Value (₹)</label>
                <input
                  type="number"
                  step="0.01"
                  value={formData.contract?.contractValue ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      contract: {
                        ...(f.contract ?? {}),
                        contractValue: e.target.value ? Number(e.target.value) : null,
                      },
                    }))
                  }
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">Revised Value (₹)</label>
                <input
                  type="number"
                  step="0.01"
                  value={formData.contract?.revisedValue ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      contract: {
                        ...(f.contract ?? {}),
                        revisedValue: e.target.value ? Number(e.target.value) : null,
                      },
                    }))
                  }
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary">DLP Months</label>
                <input
                  type="number"
                  value={formData.contract?.dlpMonths ?? ""}
                  onChange={(e) =>
                    setFormData((f) => ({
                      ...f,
                      contract: {
                        ...(f.contract ?? {}),
                        dlpMonths: e.target.value ? Number(e.target.value) : null,
                      },
                    }))
                  }
                  placeholder="60"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          </div>

          <div className="flex gap-3 pt-6">
            <button
              type="submit"
              disabled={isSubmitting}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
            >
              {isSubmitting ? "Creating..." : "Create Project"}
            </button>
            <button
              type="button"
              onClick={() => router.back()}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

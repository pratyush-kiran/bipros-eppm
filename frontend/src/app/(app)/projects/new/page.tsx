"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import { PageHeader } from "@/components/common/PageHeader";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { projectApi } from "@/lib/api/projectApi";
import { settingsApi } from "@/lib/api/settingsApi";
import { resolveCurrencyMeta } from "@/lib/currency/format";
import { obsApi } from "@/lib/api/obsApi";
import { projectCategoryApi } from "@/lib/api/projectCategoryApi";
import { calendarApi } from "@/lib/api/calendarApi";
import { getErrorMessage } from "@/lib/utils/error";
import { PRIORITY_CHOICES } from "@/lib/utils/format";
import { projectNotifications, notificationHelpers } from "@/lib/notificationHelpers";
import { Breadcrumb } from "@/components/common/Breadcrumb";
import type { CreateProjectRequest } from "@/lib/types";

// EPS and OBS are identical tree shapes (code / name / children). One helper
// flattens either tree depth-first (preserving display order) and renders
// indented dropdown options, so the New Project selectors offer every node — not
// just roots. P6 files a project under any EPS/OBS node at any level; `depth`
// drives the indentation.
type TreeNode<T> = { id: string; code: string; name: string; children?: T[] };

function flattenTree<T extends TreeNode<T>>(
  nodes: T[],
  depth = 0,
): { node: T; depth: number }[] {
  return nodes.flatMap((node) => [
    { node, depth },
    ...flattenTree(node.children ?? [], depth + 1),
  ]);
}

function buildNodeSelect<T extends TreeNode<T>>(
  nodes: T[],
  selectedId: string | null | undefined,
): { options: { value: string; label: string }[]; selectedLabel: string | undefined } {
  const flat = flattenTree(nodes);
  // Indent with NBSP so it doesn't collapse in the rendered <li>; search still
  // substring-matches code/name after the indent.
  const options = flat.map(({ node, depth }) => ({
    value: node.id,
    label:
      depth === 0
        ? `${node.code} - ${node.name}`
        : `${"\u00A0\u00A0\u00A0\u00A0".repeat(depth)}↳ ${node.code} - ${node.name}`,
  }));
  // Keep the closed-button label clean (no indent/connector) for child nodes.
  const sel = flat.find((f) => f.node.id === selectedId)?.node;
  return { options, selectedLabel: sel ? `${sel.code} - ${sel.name}` : undefined };
}

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

  // Currency choices come from Admin → Settings → Currencies (the single source
  // of truth), not a hardcoded list. retry:false so a 403 degrades fast to the
  // INR fallback below rather than blocking the form.
  const { data: currenciesData, isLoading: isLoadingCurrencies } = useQuery({
    queryKey: ["currencies"],
    queryFn: () => settingsApi.listCurrencies(),
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  const epsNodes = epsData?.data ?? [];
  const obsNodes = obsData?.data ?? [];
  const { options: epsOptions, selectedLabel: selectedEpsLabel } = buildNodeSelect(
    epsNodes,
    formData.epsNodeId,
  );
  const { options: obsOptions, selectedLabel: selectedObsLabel } = buildNodeSelect(
    obsNodes,
    formData.obsNodeId,
  );
  const categories = categoriesData?.data ?? [];
  const allCalendars = calendarsData?.data ?? [];
  const configuredCurrencies = currenciesData?.data ?? [];
  // Defensive fallback keeps the form usable if currencies can't be read.
  const currencyOptions = configuredCurrencies.length
    ? configuredCurrencies.map((c) => ({ code: c.code, label: `${c.code} - ${c.name}` }))
    : [{ code: "INR", label: "INR - Indian Rupee" }];
  const selectedCurrencySymbol = resolveCurrencyMeta(
    formData.budgetCurrency,
    configuredCurrencies,
  ).symbol;

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
                options={epsOptions}
                selectedLabel={selectedEpsLabel}
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
                options={obsOptions}
                selectedLabel={selectedObsLabel}
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
                {PRIORITY_CHOICES.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
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
                disabled={isLoadingCurrencies}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
              >
                {isLoadingCurrencies && <option value="">Loading…</option>}
                {currencyOptions.map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.label}
                  </option>
                ))}
              </select>
              <p className="mt-1 text-xs text-text-muted">
                Budget currency for EVM, cost cards, and AI reports. Manage the list under Admin → Settings → Currencies.
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
                <label className="block text-sm font-medium text-text-secondary">Contract Value ({selectedCurrencySymbol})</label>
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
                <label className="block text-sm font-medium text-text-secondary">Revised Value ({selectedCurrencySymbol})</label>
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

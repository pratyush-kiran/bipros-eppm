"use client";

import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";
import { SimpleTable } from "@/components/common/SimpleTable";

import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Plus,
  Search,
  Pencil,
  Trash2,
  Play,
  Calculator,
  ChevronDown,
  ChevronUp,
  Copy,
  Check,
} from "lucide-react";
import {
  formulaApi,
  type FormulaDto,
  type FormulaCategory,
  type CreateFormulaRequest,
  type FormulaOverrideDto,
  type CreateFormulaOverrideRequest,
} from "@/lib/api/formulaApi";
import { projectApi } from "@/lib/api/projectApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { cn } from "@/lib/utils/cn";

const CATEGORIES: FormulaCategory[] = [
  "EVM",
  "COST",
  "SCHEDULING",
  "RESOURCE",
  "REPORTING",
  "PORTFOLIO",
  "BASELINE",
  "PREDICTION",
  "BOQ",
  "GENERAL",
];

interface RowForm {
  code: string;
  name: string;
  category: FormulaCategory;
  description: string;
  defaultExpression: string;
  inputVariablesJson: string;
  outputType: string;
  scale: string;
  moduleSource: string;
  sortOrder: string;
}

const initialForm = (): RowForm => ({
  code: "",
  name: "",
  category: "EVM",
  description: "",
  defaultExpression: "",
  inputVariablesJson: "",
  outputType: "NUMBER",
  scale: "4",
  moduleSource: "",
  sortOrder: "",
});

const formFromRow = (r: FormulaDto): RowForm => ({
  code: r.code,
  name: r.name,
  category: r.category,
  description: r.description ?? "",
  defaultExpression: r.defaultExpression,
  inputVariablesJson: r.inputVariablesJson ?? "",
  outputType: r.outputType,
  scale: String(r.scale ?? 4),
  moduleSource: r.moduleSource ?? "",
  sortOrder: r.sortOrder == null ? "" : String(r.sortOrder),
});

const outputTypeBadge = (t: string) => {
  const map: Record<string, string> = {
    NUMBER: "bg-slate/10 text-slate",
    PERCENTAGE: "bg-gold/10 text-gold-deep",
    CURRENCY: "bg-emerald/10 text-emerald",
    BOOLEAN: "bg-burgundy/10 text-burgundy",
    INTEGER: "bg-bronze-warn/10 text-bronze-warn",
  };
  return map[t] || "bg-slate/10 text-slate";
};

export default function FormulasPage() {
  const queryClient = useQueryClient();
  const [activeCategory, setActiveCategory] = useState<FormulaCategory | "ALL">("ALL");
  const [searchQuery, setSearchQuery] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<RowForm>(initialForm());
  const [error, setError] = useState<string | null>(null);

  // Test panel state
  const [testFormula, setTestFormula] = useState<FormulaDto | null>(null);
  const [testVars, setTestVars] = useState<Record<string, string>>({});
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const [testLoading, setTestLoading] = useState(false);

  // Override state
  const [overrideFormula, setOverrideFormula] = useState<FormulaDto | null>(null);
  const [overrideProjectId, setOverrideProjectId] = useState<string>("");
  const [overrideExpression, setOverrideExpression] = useState("");
  const [overrideReason, setOverrideReason] = useState("");

  const { data, isLoading, isError, error: queryError, refetch, isFetching } = useQuery({
    queryKey: ["formulas"],
    queryFn: () => formulaApi.list(),
  });
  const rows = useMemo(() => data?.data ?? [], [data]);

  const { data: projectsData } = useQuery({
    queryKey: ["projects-all"],
    queryFn: () => projectApi.listProjects(0, 1000),
    staleTime: 1000 * 60 * 5,
  });
  const projects = useMemo(() => projectsData?.data?.content ?? [], [projectsData]);

  const { data: overridesData, refetch: refetchOverrides } = useQuery({
    queryKey: ["formula-overrides", overrideFormula?.code],
    queryFn: () =>
      overrideFormula
        ? formulaApi.listOverridesByFormula(overrideFormula.code)
        : Promise.resolve({ data: [], error: null, meta: { timestamp: new Date().toISOString(), version: "0.1.0" } } as import("@/lib/types").ApiResponse<FormulaOverrideDto[]>),
    enabled: !!overrideFormula,
  });
  const overrides = useMemo(() => overridesData?.data ?? [], [overridesData]);

  const filtered = useMemo(() => {
    let list = rows;
    if (activeCategory !== "ALL") {
      list = list.filter((r) => r.category === activeCategory);
    }
    if (!searchQuery.trim()) return list;
    const q = searchQuery.toLowerCase();
    return list.filter(
      (r) =>
        r.code.toLowerCase().includes(q) ||
        r.name.toLowerCase().includes(q) ||
        r.defaultExpression.toLowerCase().includes(q),
    );
  }, [rows, activeCategory, searchQuery]);

  const openCreate = () => {
    setEditingId(null);
    setForm(initialForm());
    setError(null);
    setShowForm(true);
  };
  const openEdit = (row: FormulaDto) => {
    setEditingId(row.id);
    setForm(formFromRow(row));
    setError(null);
    setShowForm(true);
  };
  const closeForm = () => {
    setShowForm(false);
    setEditingId(null);
    setForm(initialForm());
    setError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const payload: CreateFormulaRequest = {
        code: form.code.trim(),
        name: form.name.trim(),
        category: form.category,
        description: form.description.trim() || null,
        defaultExpression: form.defaultExpression.trim(),
        inputVariablesJson: form.inputVariablesJson.trim() || null,
        outputType: form.outputType as FormulaDto["outputType"],
        scale: form.scale.trim() ? parseInt(form.scale, 10) : 4,
        moduleSource: form.moduleSource.trim() || null,
        sortOrder: form.sortOrder.trim() ? parseInt(form.sortOrder, 10) : null,
      };
      if (editingId) await formulaApi.update(editingId, payload);
      else await formulaApi.create(payload);
      closeForm();
      queryClient.invalidateQueries({ queryKey: ["formulas"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save formula"));
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this formula?")) return;
    try {
      // Note: Formula API doesn't have a delete endpoint on the backend yet
      // await formulaApi.delete(id);
      if (editingId === id) closeForm();
      queryClient.invalidateQueries({ queryKey: ["formulas"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete formula"));
    }
  };

  const runTest = async () => {
    if (!testFormula) return;
    setTestLoading(true);
    setTestError(null);
    setTestResult(null);
    try {
      const res = await formulaApi.evaluate({
        formulaCode: testFormula.code,
        variables: testVars,
      });
      if (!res.data) {
        setTestError("No response data");
      } else if (res.data.error) {
        setTestError(res.data.errorMessage || "Evaluation error");
      } else {
        setTestResult(`${res.data.value} (expression: ${res.data.expressionUsed})`);
      }
    } catch (err: unknown) {
      setTestError(getErrorMessage(err, "Failed to evaluate formula"));
    } finally {
      setTestLoading(false);
    }
  };

  const openTest = (row: FormulaDto) => {
    setTestFormula(row);
    // Try to parse input variables JSON to seed the test form
    try {
      const vars = row.inputVariablesJson ? JSON.parse(row.inputVariablesJson) : {};
      const seeded: Record<string, string> = {};
      Object.keys(vars).forEach((k) => {
        seeded[k] = "";
      });
      setTestVars(seeded);
    } catch {
      setTestVars({});
    }
    setTestResult(null);
    setTestError(null);
  };

  const openOverride = (row: FormulaDto) => {
    setOverrideFormula(row);
    setOverrideExpression(row.defaultExpression);
    setOverrideReason("");
    setOverrideProjectId("");
  };

  const saveOverride = async () => {
    if (!overrideFormula || !overrideProjectId) return;
    try {
      const payload: CreateFormulaOverrideRequest = {
        formulaCode: overrideFormula.code,
        projectId: overrideProjectId,
        overrideExpression: overrideExpression.trim(),
        overrideReason: overrideReason.trim() || null,
      };
      await formulaApi.createOverride(payload);
      refetchOverrides();
      setOverrideProjectId("");
      setOverrideExpression(overrideFormula.defaultExpression);
      setOverrideReason("");
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save override"));
    }
  };

  const deleteOverride = async (id: string) => {
    if (!window.confirm("Delete this override?")) return;
    try {
      await formulaApi.deleteOverride(id);
      refetchOverrides();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete override"));
    }
  };

  const copyExpression = async (expr: string) => {
    await navigator.clipboard.writeText(expr);
  };

  const columns = useMemo<ColumnDef<FormulaDto>[]>(() => [
    {
      accessorKey: "code",
      header: "Code",
      cell: ({ row }) => (
        <span className="font-mono text-[12px] font-medium text-gold-deep">
          {row.original.code}
        </span>
      ),
    },
    {
      accessorKey: "name",
      header: "Name",
      cell: ({ row }) => (
        <span className="font-semibold text-charcoal">{row.original.name}</span>
      ),
    },
    {
      accessorKey: "category",
      header: "Category",
      cell: ({ row }) => (
        <span className="inline-flex rounded-md px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider bg-ivory text-slate">
          {row.original.category}
        </span>
      ),
    },
    {
      accessorKey: "defaultExpression",
      header: "Expression",
      cell: ({ row }) => (
        <div className="flex items-center gap-2 max-w-[360px]">
          <code className="truncate font-mono text-[11px] text-slate bg-parchment px-1.5 py-0.5 rounded">
            {row.original.defaultExpression}
          </code>
          <button
            onClick={() => copyExpression(row.original.defaultExpression)}
            className="shrink-0 rounded p-1 text-ash hover:text-gold-deep hover:bg-parchment transition-colors"
            title="Copy expression"
          >
            <Copy size={12} />
          </button>
        </div>
      ),
    },
    {
      accessorKey: "outputType",
      header: "Output",
      cell: ({ row }) => (
        <span
          className={cn(
            "inline-flex rounded-md px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider",
            outputTypeBadge(row.original.outputType)
          )}
        >
          {row.original.outputType}
        </span>
      ),
    },
    {
      id: "actions",
      header: "",
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => openTest(row.original)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-emerald"
            title="Test formula"
          >
            <Play size={14} strokeWidth={1.5} />
          </button>
          <button
            onClick={() => openOverride(row.original)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-gold-deep"
            title="Project overrides"
          >
            <Calculator size={14} strokeWidth={1.5} />
          </button>
          <button
            onClick={() => openEdit(row.original)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-gold-deep"
            title="Edit"
          >
            <Pencil size={14} strokeWidth={1.5} />
          </button>
        </div>
      ),
    },
  ], []);

  return (
    <div>
      <TabTip
        title="Formula Configuration"
        description="Central registry for all system formulas. Override expressions per project, test formulas with sample inputs, and view version history. Changes take effect immediately for EVM, Cost, and Scheduling calculations."
      />

      {/* Header */}
      <div className="mb-6 flex items-start justify-between gap-6">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            {rows.length} formula{rows.length !== 1 ? "s" : ""}
          </div>
          <h1
            className="font-display text-[38px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Formulas
          </h1>
          <p className="mt-2 max-w-[640px] text-sm text-slate leading-relaxed">
            Browse, edit, and override the mathematical formulas that power EVM indices,
            cost calculations, schedule health scores, and more. Each formula can be customized
            per-project without touching code.
          </p>
        </div>
        <button
          onClick={() => (showForm ? closeForm() : openCreate())}
          className="inline-flex h-10 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(0,88,202,0.3)] hover:-translate-y-px"
        >
          <Plus size={14} strokeWidth={2.5} />
          {showForm ? "Cancel" : "Add Formula"}
        </button>
      </div>

      {/* Category tabs */}
      <div className="mb-5 flex flex-wrap gap-1.5">
        <button
          onClick={() => setActiveCategory("ALL")}
          className={cn(
            "rounded-lg px-3 py-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] transition-colors",
            activeCategory === "ALL"
              ? "bg-gold text-paper"
              : "bg-ivory text-slate hover:bg-parchment hover:text-charcoal"
          )}
        >
          All
        </button>
        {CATEGORIES.map((cat) => (
          <button
            key={cat}
            onClick={() => setActiveCategory(cat)}
            className={cn(
              "rounded-lg px-3 py-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] transition-colors",
              activeCategory === cat
                ? "bg-gold text-paper"
                : "bg-ivory text-slate hover:bg-parchment hover:text-charcoal"
            )}
          >
            {cat.replace("_", " ")}
          </button>
        ))}
      </div>

      {/* Search */}
      <div className="mb-5 flex items-center">
        <div className="ml-auto flex h-10 max-w-[340px] flex-1 items-center gap-2 rounded-[10px] border border-hairline bg-paper px-3">
          <Search size={15} className="text-ash" strokeWidth={1.5} />
          <input
            type="text"
            placeholder="Search by code, name, or expression…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="flex-1 border-none bg-transparent text-sm text-charcoal placeholder:text-ash outline-none"
          />
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm text-burgundy">
          {error}
        </div>
      )}

      {/* Create/Edit Form */}
      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="mb-6 rounded-xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]"
        >
          <h2 className="text-lg font-semibold text-charcoal mb-4">
            {editingId ? "Edit Formula" : "New Formula"}
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <FormField label="Code *">
              <input
                type="text"
                value={form.code}
                onChange={(e) => setForm({ ...form, code: e.target.value })}
                className={inputCls}
                required
                disabled={!!editingId}
              />
            </FormField>
            <FormField label="Name *">
              <input
                type="text"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                className={inputCls}
                required
              />
            </FormField>
            <FormField label="Category *">
              <select
                value={form.category}
                onChange={(e) => setForm({ ...form, category: e.target.value as FormulaCategory })}
                className={inputCls}
              >
                {CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField label="Output Type *">
              <select
                value={form.outputType}
                onChange={(e) => setForm({ ...form, outputType: e.target.value })}
                className={inputCls}
              >
                {["NUMBER", "PERCENTAGE", "CURRENCY", "BOOLEAN", "INTEGER"].map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField label="Scale">
              <input
                type="number"
                value={form.scale}
                onChange={(e) => setForm({ ...form, scale: e.target.value })}
                className={inputCls}
              />
            </FormField>
            <FormField label="Sort Order">
              <input
                type="number"
                value={form.sortOrder}
                onChange={(e) => setForm({ ...form, sortOrder: e.target.value })}
                className={inputCls}
              />
            </FormField>
            <div className="md:col-span-3">
              <FormField label="Expression *">
                <textarea
                  value={form.defaultExpression}
                  onChange={(e) => setForm({ ...form, defaultExpression: e.target.value })}
                  className={cn(inputCls, "font-mono min-h-[80px]")}
                  placeholder="e.g. IF($AC = 0, 0, $EV / $AC)"
                  required
                />
              </FormField>
            </div>
            <div className="md:col-span-2">
              <FormField label="Description">
                <input
                  type="text"
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  className={inputCls}
                />
              </FormField>
            </div>
            <FormField label="Module Source">
              <input
                type="text"
                value={form.moduleSource}
                onChange={(e) => setForm({ ...form, moduleSource: e.target.value })}
                className={inputCls}
                placeholder="evm, cost, scheduling..."
              />
            </FormField>
            <div className="md:col-span-3">
              <FormField label="Input Variables (JSON)">
                <textarea
                  value={form.inputVariablesJson}
                  onChange={(e) => setForm({ ...form, inputVariablesJson: e.target.value })}
                  className={cn(inputCls, "font-mono min-h-[60px]")}
                  placeholder='{"EV": "BigDecimal", "AC": "BigDecimal"}'
                />
              </FormField>
            </div>
          </div>
          <div className="flex gap-2 mt-4">
            <button
              type="submit"
              className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep"
            >
              {editingId ? "Save Changes" : "Create"}
            </button>
            <button
              type="button"
              onClick={closeForm}
              className="inline-flex h-9 items-center gap-1.5 rounded-[10px] border border-hairline bg-paper px-4 text-sm font-semibold text-slate hover:border-gold hover:text-gold-deep"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      {/* Test Panel */}
      {testFormula && (
        <div className="mb-6 rounded-xl border border-gold/30 bg-gold/5 p-5">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-charcoal flex items-center gap-2">
              <Play size={18} className="text-gold-deep" />
              Test: {testFormula.code}
            </h2>
            <button
              onClick={() => setTestFormula(null)}
              className="rounded-md p-1.5 text-slate hover:bg-parchment hover:text-charcoal"
            >
              <ChevronUp size={16} />
            </button>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
            {Object.keys(testVars).map((key) => (
              <FormField key={key} label={`$${key}`}>
                <input
                  type="text"
                  value={testVars[key]}
                  onChange={(e) =>
                    setTestVars((prev) => ({ ...prev, [key]: e.target.value }))
                  }
                  className={inputCls}
                  placeholder="0"
                />
              </FormField>
            ))}
            {Object.keys(testVars).length === 0 && (
              <p className="text-sm text-slate col-span-full">
                No input variables defined for this formula. You can still test with an empty context.
              </p>
            )}
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={runTest}
              disabled={testLoading}
              className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep disabled:opacity-50"
            >
              <Calculator size={14} />
              {testLoading ? "Evaluating…" : "Evaluate"}
            </button>
            {testResult && (
              <span className="text-sm font-mono text-emerald bg-emerald/10 px-3 py-1.5 rounded-lg">
                Result: {testResult}
              </span>
            )}
            {testError && (
              <span className="text-sm text-burgundy bg-burgundy/10 px-3 py-1.5 rounded-lg">
                {testError}
              </span>
            )}
          </div>
        </div>
      )}

      {/* Override Panel */}
      {overrideFormula && (
        <div className="mb-6 rounded-xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-charcoal">
              Overrides for {overrideFormula.code}
            </h2>
            <button
              onClick={() => setOverrideFormula(null)}
              className="rounded-md p-1.5 text-slate hover:bg-parchment hover:text-charcoal"
            >
              <ChevronUp size={16} />
            </button>
          </div>

          {/* Create override */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-5 p-4 rounded-lg bg-ivory/60">
            <FormField label="Project *">
              <select
                value={overrideProjectId}
                onChange={(e) => setOverrideProjectId(e.target.value)}
                className={inputCls}
              >
                <option value="">Select project…</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </FormField>
            <div className="md:col-span-2">
              <FormField label="Override Expression *">
                <textarea
                  value={overrideExpression}
                  onChange={(e) => setOverrideExpression(e.target.value)}
                  className={cn(inputCls, "font-mono min-h-[60px]")}
                />
              </FormField>
            </div>
            <div className="md:col-span-2">
              <FormField label="Reason">
                <input
                  type="text"
                  value={overrideReason}
                  onChange={(e) => setOverrideReason(e.target.value)}
                  className={inputCls}
                  placeholder="Why is this override needed?"
                />
              </FormField>
            </div>
            <div className="flex items-end">
              <button
                onClick={saveOverride}
                disabled={!overrideProjectId || !overrideExpression.trim()}
                className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep disabled:opacity-50"
              >
                <Plus size={14} />
                Add Override
              </button>
            </div>
          </div>

          {/* Existing overrides */}
          {overrides.length === 0 ? (
            <p className="text-sm text-slate">No overrides for this formula yet.</p>
          ) : (
            <SimpleTable
              columns={[
                {
                  accessorKey: "projectId",
                  header: "Project",
                  cell: ({ row }) => (
                    <span className="text-charcoal font-medium">
                      {projects.find((p) => p.id === row.original.projectId)?.name || row.original.projectId}
                    </span>
                  ),
                },
                {
                  accessorKey: "overrideExpression",
                  header: "Expression",
                  cell: ({ row }) => (
                    <span className="font-mono text-[11px] text-slate max-w-[300px] truncate">{row.original.overrideExpression}</span>
                  ),
                },
                {
                  accessorKey: "overrideReason",
                  header: "Reason",
                  cell: ({ row }) => <span className="text-slate">{row.original.overrideReason || "—"}</span>,
                },
                {
                  id: "actions",
                  header: "",
                  cell: ({ row }) => (
                    <button
                      onClick={() => deleteOverride(row.original.id)}
                      className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-burgundy"
                    >
                      <Trash2 size={14} strokeWidth={1.5} />
                    </button>
                  ),
                },
              ]}
              data={overrides}
            />
          )}
        </div>
      )}

      {isError && (
        <div className="mb-4 rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm">
          <div className="font-medium text-burgundy">Failed to load formulas</div>
          <div className="text-slate mt-1">{getErrorMessage(queryError, "Unknown error")}</div>
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="mt-3 inline-flex h-8 items-center gap-1.5 rounded-[10px] bg-gold px-3 text-xs font-semibold text-paper hover:bg-gold-deep disabled:opacity-50"
          >
            {isFetching ? "Retrying…" : "Retry"}
          </button>
        </div>
      )}

      {isLoading && (
        <div className="space-y-3">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-14 animate-pulse rounded-lg bg-parchment" />
          ))}
        </div>
      )}

      {!isLoading && filtered.length === 0 && (
        <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
          <p className="text-sm text-slate">
            {rows.length === 0
              ? "No formulas yet. Add your first one to get started."
              : "No formulas match your search."}
          </p>
        </div>
      )}


      {!isLoading && filtered.length > 0 && (
        <VirtualDataTable columns={columns} data={filtered} sortable resizable searchable={false} />
      )}
    </div>
  );
}

const inputCls =
  "w-full rounded-[10px] border border-hairline bg-paper px-3 py-2 text-sm text-charcoal placeholder:text-ash focus:border-gold focus:outline-none focus:shadow-[0_0_0_3px_rgba(0,88,202,0.18)]";

function FormField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-sm font-medium mb-1 text-text-secondary">{label}</label>
      {children}
    </div>
  );
}

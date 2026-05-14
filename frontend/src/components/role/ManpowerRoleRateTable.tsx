"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2, Plus } from "lucide-react";
import {
  roleRateApi,
  type ManpowerRoleRate,
  type ManpowerRoleRateRequest,
} from "@/lib/api/roleRateApi";
import { manpowerCategoryMasterApi } from "@/lib/api/manpowerCategoryMasterApi";
import { gradeMasterApi } from "@/lib/api/gradeMasterApi";

interface Props {
  roleId: string;
}

const UNITS = ["Day", "Hour"] as const;

export function ManpowerRoleRateTable({ roleId }: Props) {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<ManpowerRoleRateRequest>({
    categoryId: "",
    gradeId: "",
    unit: "Day",
    rate: 0,
    active: true,
  });
  const [error, setError] = useState<string | null>(null);

  const { data: ratesResp } = useQuery({
    queryKey: ["manpower-role-rates", roleId],
    queryFn: () => roleRateApi.listManpowerForRole(roleId),
  });
  const { data: categoriesResp } = useQuery({
    queryKey: ["manpower-categories"],
    queryFn: () => manpowerCategoryMasterApi.list(),
  });
  const { data: gradesResp } = useQuery({
    queryKey: ["grades"],
    queryFn: () => gradeMasterApi.list(),
  });

  const rates = useMemo<ManpowerRoleRate[]>(
    () => (Array.isArray(ratesResp?.data) ? ratesResp.data : []),
    [ratesResp],
  );
  const categories = useMemo(
    () => (Array.isArray(categoriesResp?.data) ? categoriesResp.data : []),
    [categoriesResp],
  );
  const grades = useMemo(
    () => (Array.isArray(gradesResp?.data) ? gradesResp.data : []),
    [gradesResp],
  );

  const create = useMutation({
    mutationFn: (req: ManpowerRoleRateRequest) =>
      roleRateApi.createManpowerRate(roleId, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["manpower-role-rates", roleId] });
      setShowForm(false);
      setForm({ categoryId: "", gradeId: "", unit: "Day", rate: 0, active: true });
      setError(null);
    },
    onError: (e: unknown) =>
      setError(e instanceof Error ? e.message : "Failed to create rate"),
  });
  const remove = useMutation({
    mutationFn: (id: string) => roleRateApi.deleteManpowerRate(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["manpower-role-rates", roleId] }),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">Manpower Rates</h3>
        <button
          onClick={() => setShowForm(true)}
          className="flex items-center gap-1 rounded-md bg-amber-500 px-3 py-1.5 text-sm text-black hover:bg-amber-400"
        >
          <Plus className="h-4 w-4" /> Add Rate
        </button>
      </div>

      {showForm && (
        <div className="rounded-md border border-neutral-700 p-4">
          {error && <div className="mb-2 text-sm text-red-400">{error}</div>}
          <div className="grid grid-cols-2 gap-3">
            <label className="block text-sm">
              <span className="text-neutral-400">Category *</span>
              <select
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.categoryId}
                onChange={(e) => setForm({ ...form, categoryId: e.target.value })}
              >
                <option value="">— pick a category —</option>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">Grade *</span>
              <select
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.gradeId}
                onChange={(e) => setForm({ ...form, gradeId: e.target.value })}
              >
                <option value="">— pick a grade —</option>
                {grades.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">Unit *</span>
              <select
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.unit}
                onChange={(e) => setForm({ ...form, unit: e.target.value })}
              >
                {UNITS.map((u) => (
                  <option key={u} value={u}>
                    {u}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">Rate *</span>
              <input
                type="number"
                step="0.01"
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.rate}
                onChange={(e) =>
                  setForm({ ...form, rate: parseFloat(e.target.value) || 0 })
                }
              />
            </label>
          </div>
          <div className="mt-3 flex gap-2">
            <button
              disabled={!form.categoryId || !form.gradeId || !form.rate || create.isPending}
              onClick={() => create.mutate(form)}
              className="rounded-md bg-green-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
            >
              Create
            </button>
            <button
              onClick={() => setShowForm(false)}
              className="rounded-md border border-neutral-700 px-3 py-1.5 text-sm"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      <table className="w-full text-sm">
        <thead className="border-b border-neutral-700 text-neutral-400">
          <tr>
            <th className="py-2 text-left">Category</th>
            <th className="py-2 text-left">Grade</th>
            <th className="py-2 text-left">Unit</th>
            <th className="py-2 text-right">Rate</th>
            <th className="py-2 text-center">Active</th>
            <th className="py-2 text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {rates.length === 0 && (
            <tr>
              <td colSpan={6} className="py-6 text-center text-neutral-500">
                No rates defined yet
              </td>
            </tr>
          )}
          {rates.map((r: ManpowerRoleRate) => (
            <tr key={r.id} className="border-b border-neutral-800">
              <td className="py-2">{r.categoryName}</td>
              <td className="py-2">{r.gradeName}</td>
              <td className="py-2">{r.unit}</td>
              <td className="py-2 text-right">{r.rate}</td>
              <td className="py-2 text-center">{r.active ? "✓" : "—"}</td>
              <td className="py-2 text-right">
                <button
                  onClick={() => remove.mutate(r.id)}
                  className="text-red-400 hover:text-red-300"
                  title="Delete"
                >
                  <Trash2 className="inline h-4 w-4" />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

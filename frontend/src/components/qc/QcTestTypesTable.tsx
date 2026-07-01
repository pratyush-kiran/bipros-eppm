"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { qcApi } from "@/lib/api/qcApi";
import type { QcTestType } from "@/lib/types/qc";
import { EmptyState } from "@/components/common/EmptyState";
import { TableSkeleton } from "@/components/common/Skeleton";
import { getErrorMessage } from "@/lib/utils/error";

interface Props {
  projectId: string;
}

export function QcTestTypesTable({ projectId }: Props) {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<QcTestType | null>(null);
  const [name, setName] = useState("");
  const [unit, setUnit] = useState("");
  const [ircThreshold, setIrcThreshold] = useState<string>("");
  const [formError, setFormError] = useState<string | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["qc-test-types", projectId],
    queryFn: () => qcApi.listTestTypes(projectId),
  });

  const types = data?.data ?? [];

  const openNew = () => {
    setEditing(null);
    setName("");
    setUnit("");
    setIrcThreshold("");
    setFormError(null);
    setShowForm(true);
  };

  const openEdit = (t: QcTestType) => {
    setEditing(t);
    setName(t.name);
    setUnit(t.unit ?? "");
    setIrcThreshold(t.ircThreshold?.toString() ?? "");
    setFormError(null);
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditing(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) {
      setFormError("Name is required.");
      return;
    }
    try {
      const req = {
        name: name.trim(),
        unit: unit.trim() || null,
        ircThreshold: ircThreshold ? Number(ircThreshold) : null,
      };
      if (editing) {
        await qcApi.updateTestType(projectId, editing.id, req);
      } else {
        await qcApi.createTestType(projectId, req);
      }
      queryClient.invalidateQueries({ queryKey: ["qc-test-types", projectId] });
      closeForm();
    } catch (err: unknown) {
      setFormError(getErrorMessage(err, "Failed to save test type."));
    }
  };

  const handleDelete = async (t: QcTestType) => {
    if (!confirm(`Delete test type "${t.name}"?`)) return;
    try {
      await qcApi.deleteTestType(projectId, t.id);
      queryClient.invalidateQueries({ queryKey: ["qc-test-types", projectId] });
    } catch (err: unknown) {
      setPageError(getErrorMessage(err, "Failed to delete test type."));
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-slate">Test Types</h3>
        <button
          onClick={openNew}
          className="inline-flex items-center gap-1.5 rounded-md bg-gold px-3 py-1.5 text-sm font-semibold text-gold-ink hover:bg-gold-deep transition"
        >
          <Plus className="h-4 w-4" /> Add Type
        </button>
      </div>
      {showForm && (
        <div className="rounded-lg border border-hairline bg-paper p-4">
          <h4 className="mb-3 text-sm font-semibold text-charcoal">
            {editing ? "Edit Test Type" : "New Test Type"}
          </h4>
          {formError && <div className="mb-3 text-sm text-burgundy">{formError}</div>}
          <form onSubmit={handleSubmit} className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">Name *</label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">Unit</label>
              <input
                type="text"
                value={unit}
                onChange={(e) => setUnit(e.target.value)}
                placeholder="e.g. %, g/cc"
                className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">OHDS Threshold</label>
              <input
                type="number"
                step="0.0001"
                value={ircThreshold}
                onChange={(e) => setIrcThreshold(e.target.value)}
                className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
              />
            </div>
            <div className="flex items-center justify-end gap-2 sm:col-span-3">
              <button
                type="button"
                onClick={closeForm}
                className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-semibold text-charcoal hover:bg-ivory"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep"
              >
                {editing ? "Update" : "Save"}
              </button>
            </div>
          </form>
        </div>
      )}

      {pageError && <div className="text-sm text-burgundy">{pageError}</div>}

      {isLoading ? (
        <TableSkeleton rows={5} />
      ) : types.length === 0 ? (
        <EmptyState title="No test types" description="Configure QC test types in the master data section." />
      ) : (
        <div className="overflow-x-auto rounded-md border border-hairline">
          <table className="w-full text-sm">
            <thead className="bg-ivory/60">
              <tr>
                <th className="px-4 py-2 text-left font-semibold text-slate">Name</th>
                <th className="px-4 py-2 text-left font-semibold text-slate">Unit</th>
                <th className="px-4 py-2 text-left font-semibold text-slate">OHDS Threshold</th>
                <th className="px-4 py-2 text-right font-semibold text-slate">Actions</th>
              </tr>
            </thead>
            <tbody>
              {types.map((t) => (
                <tr key={t.id} className="border-t border-hairline">
                  <td className="px-4 py-2 text-charcoal">{t.name}</td>
                  <td className="px-4 py-2 text-charcoal">{t.unit ?? "—"}</td>
                  <td className="px-4 py-2 text-charcoal tabular-nums">{t.ircThreshold ?? "—"}</td>
                  <td className="px-4 py-2 text-right">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        onClick={() => openEdit(t)}
                        className="rounded-md p-1.5 text-slate hover:bg-ivory hover:text-charcoal"
                        aria-label="Edit"
                      >
                        <Pencil className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(t)}
                        className="rounded-md p-1.5 text-slate hover:bg-burgundy/10 hover:text-burgundy"
                        aria-label="Delete"
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
    </div>
  );
}

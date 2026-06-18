"use client";

import { useState } from "react";
import type { RiskResponse, UpdateRiskRequest } from "@/lib/api/riskApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

interface Props {
  risk: RiskResponse;
  onUpdate: (data: UpdateRiskRequest) => void;
}

const STATUS_OPTIONS = [
  "IDENTIFIED", "ANALYZING", "MITIGATING", "RESOLVED", "CLOSED", "ACCEPTED",
  "REJECTED", "REALISED",
  "OPEN_ESCALATED", "OPEN_UNDER_ACTIVE_MANAGEMENT", "OPEN_BEING_MANAGED",
  "OPEN_MONITOR", "OPEN_WATCH", "OPEN_TARGET", "OPEN_ASI_REVIEW", "REALISED_PARTIALLY",
];

export function RiskGeneralTab({ risk, onUpdate }: Props) {
  const { money } = useProjectCurrency();
  const [editing, setEditing] = useState<string | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string>>({});

  const startEdit = (field: string, currentValue: string) => {
    setEditing(field);
    setFormValues({ [field]: currentValue });
  };

  const saveField = (field: string) => {
    const value = formValues[field];
    if (value !== undefined) {
      onUpdate({ [field]: value } as UpdateRiskRequest);
    }
    setEditing(null);
  };

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return "—";
    return new Date(dateStr).toLocaleDateString();
  };

  return (
    <div className="space-y-6">
      {/* Identification Section */}
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <h3 className="text-lg font-semibold text-text-primary mb-4">Identification</h3>
        <div className="grid grid-cols-2 gap-4">
          <FieldDisplay label="Risk ID" value={risk.code} />
          <FieldDisplay
            label="Risk Name"
            value={risk.title}
            editable
            onEdit={() => startEdit("title", risk.title)}
            editing={editing === "title"}
            editInput={
              <input
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded text-sm text-text-primary"
                value={formValues.title || ""}
                onChange={(e) => setFormValues({ ...formValues, title: e.target.value })}
                onBlur={() => saveField("title")}
                onKeyDown={(e) => e.key === "Enter" && saveField("title")}
                autoFocus
              />
            }
          />
          <FieldDisplay
            label="Category"
            value={
              risk.category
                ? `${risk.category.code} — ${risk.category.name}`
                : "—"
            }
          />
          <FieldDisplay
            label="Type"
            value={risk.riskType === "OPPORTUNITY" ? "Opportunity" : "Threat"}
          />
          <FieldDisplay
            label="Status"
            value={risk.status?.replace(/_/g, " ") || "—"}
            editable
            onEdit={() => startEdit("status", risk.status || "IDENTIFIED")}
            editing={editing === "status"}
            editInput={
              <select
                className="w-full px-3 py-2 bg-surface-hover border border-border rounded text-sm text-text-primary"
                value={formValues.status || risk.status}
                onChange={(e) => {
                  onUpdate({ status: e.target.value as UpdateRiskRequest["status"] });
                  setEditing(null);
                }}
                autoFocus
              >
                {STATUS_OPTIONS.map((s) => (
                  <option key={s} value={s}>{s.replace(/_/g, " ")}</option>
                ))}
              </select>
            }
          />
          <FieldDisplay label="Owner" value={risk.ownerId || "—"} />
          <FieldDisplay label="Identified On" value={formatDate(risk.identifiedDate)} />
          <FieldDisplay label="Identified By" value={risk.identifiedById || "—"} />
        </div>
      </div>

      {/* Exposure Section */}
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <h3 className="text-lg font-semibold text-text-primary mb-4">Exposure</h3>
        <div className="grid grid-cols-2 gap-4">
          <FieldDisplay
            label="Exposure Start"
            value={formatDate(risk.exposureStartDate)}
            hint="Auto-derived from assigned activities"
          />
          <FieldDisplay
            label="Exposure Finish"
            value={formatDate(risk.exposureFinishDate)}
            hint="Auto-derived from assigned activities"
          />
          <FieldDisplay
            label="Pre-Response Exposure Cost"
            value={money(risk.preResponseExposureCost)}
            hint="Auto-calculated from activity costs"
          />
          <FieldDisplay
            label="Post-Response Exposure Cost"
            value={money(risk.postResponseExposureCost)}
            hint="Auto-calculated from activity costs"
          />
        </div>
      </div>

      {/* Scores Section */}
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <h3 className="text-lg font-semibold text-text-primary mb-4">Scores</h3>
        <div className="grid grid-cols-2 gap-4">
          <FieldDisplay
            label="Pre-Response Score"
            value={risk.riskScore != null ? String(risk.riskScore) : "—"}
            hint="Auto-calculated from matrix"
          />
          <FieldDisplay
            label="Post-Response Score"
            value={risk.postResponseRiskScore != null ? String(risk.postResponseRiskScore) : "—"}
            hint="Auto-calculated from matrix"
          />
          <FieldDisplay
            label="RAG"
            value={risk.rag || "—"}
            badge={risk.rag ? {
              CRIMSON: "bg-fuchsia-100 text-fuchsia-900 dark:bg-fuchsia-900 dark:text-fuchsia-200",
              RED: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-200",
              AMBER: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-200",
              GREEN: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-200",
              OPPORTUNITY: "bg-blue-100 text-blue-900 dark:bg-blue-900 dark:text-blue-200",
            }[risk.rag] : undefined}
          />
          <FieldDisplay label="Trend" value={risk.trend || "—"} />
        </div>
      </div>
    </div>
  );
}

function FieldDisplay({
  label,
  value,
  hint,
  badge,
  editable,
  onEdit,
  editing,
  editInput,
}: {
  label: string;
  value: string;
  hint?: string;
  badge?: string;
  editable?: boolean;
  onEdit?: () => void;
  editing?: boolean;
  editInput?: React.ReactNode;
}) {
  return (
    <div className="space-y-1">
      <label className="block text-xs font-medium text-text-secondary uppercase tracking-wide">
        {label}
      </label>
      {editing && editInput ? (
        editInput
      ) : (
        <div
          className={`flex items-center gap-2 ${editable ? "cursor-pointer hover:bg-surface-hover/50 rounded px-2 py-1 -mx-2" : ""}`}
          onClick={editable ? onEdit : undefined}
        >
          {badge ? (
            <span className={`px-2 py-0.5 rounded text-xs font-bold ${badge}`}>{value}</span>
          ) : (
            <span className="text-sm text-text-primary">{value}</span>
          )}
          {editable && <span className="text-xs text-text-muted">(click to edit)</span>}
        </div>
      )}
      {hint && <p className="text-[10px] text-text-muted">{hint}</p>}
    </div>
  );
}

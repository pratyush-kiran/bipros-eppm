import React from "react";

/**
 * Visual indicator for an Activity's edit lifecycle.
 *
 * - {@code DRAFT}: amber pill, deliberately attention-grabbing — DPRs can't be submitted
 *   yet, so the user typically needs to act (lock the activity once inputs are finalised).
 * - {@code LOCKED}: subdued gray pill — terminal "ready" state where DPRs flow.
 *
 * Styling follows {@link import("@/components/common/StatusBadge").StatusBadge} so the
 * two badges sit side-by-side without visual mismatch.
 */
interface ActivityEditStatusBadgeProps {
  editStatus: "DRAFT" | "LOCKED";
  className?: string;
}

const styles: Record<"DRAFT" | "LOCKED", string> = {
  DRAFT: "bg-warning/10 text-warning ring-1 ring-warning/20",
  LOCKED: "bg-surface-hover text-text-secondary ring-1 ring-border",
};

const labels: Record<"DRAFT" | "LOCKED", string> = {
  DRAFT: "Draft",
  LOCKED: "Locked",
};

const icons: Record<"DRAFT" | "LOCKED", string> = {
  DRAFT: "📝",
  LOCKED: "🔒",
};

export function ActivityEditStatusBadge({ editStatus, className }: ActivityEditStatusBadgeProps) {
  const style = styles[editStatus];
  const label = labels[editStatus];
  const icon = icons[editStatus];

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ${style} ${className ?? ""}`}
      title={
        editStatus === "DRAFT"
          ? "Draft — DPRs can't be submitted until this activity is locked."
          : "Locked — inputs are read-only; DPRs can be submitted."
      }
    >
      <span aria-hidden="true">{icon}</span>
      {label}
    </span>
  );
}

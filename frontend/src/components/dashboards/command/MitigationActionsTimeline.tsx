import React from "react";
import { cn } from "@/lib/utils/cn";

export interface MitigationAction {
  id: string;
  title: string;
  due: Date | string;
  tone?: "danger" | "warning" | "info" | "success";
}

export interface MitigationActionsTimelineProps {
  actions: MitigationAction[];
  className?: string;
}

const toneColor: Record<NonNullable<MitigationAction["tone"]>, string> = {
  danger: "var(--burgundy)",
  warning: "var(--bronze-warn)",
  info: "var(--steel)",
  success: "var(--emerald)",
};

function formatDate(value: Date | string): string {
  const d = value instanceof Date ? value : new Date(value);
  return d.toLocaleDateString("en-US", { day: "numeric", month: "short" });
}

export function MitigationActionsTimeline({ actions, className }: MitigationActionsTimelineProps) {
  if (actions.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-6 text-center text-sm text-text-secondary", className)}>
        No mitigation actions scheduled.
      </div>
    );
  }
  return (
    <ul className={cn("space-y-3", className)}>
      {actions.map((action) => (
        <li key={action.id} className="flex items-center gap-3">
          <span
            className="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
            style={{ background: toneColor[action.tone ?? "info"] }}
            aria-hidden
          />
          <span className="min-w-0 flex-1 truncate text-sm text-text-primary">{action.title}</span>
          <span
            className="shrink-0 text-xs font-semibold tabular-nums"
            style={{ color: toneColor[action.tone ?? "info"] }}
          >
            {formatDate(action.due)}
          </span>
        </li>
      ))}
    </ul>
  );
}

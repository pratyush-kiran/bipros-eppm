import React from "react";
import { cn } from "@/lib/utils/cn";
import { StatusBadge } from "@/components/ui/status-badge";

export type AlertSeverity = "CRITICAL" | "WARNING" | "INFO" | "RESOLVED";

export interface AlertItem {
  id: string;
  severity: AlertSeverity;
  message: string;
  context?: string;
}

const railColor: Record<AlertSeverity, string> = {
  CRITICAL: "bg-burgundy",
  WARNING: "bg-bronze-warn",
  INFO: "bg-steel",
  RESOLVED: "bg-emerald",
};

export interface ActiveAlertsPanelProps {
  alerts: AlertItem[];
  onAlertClick?: (alert: AlertItem) => void;
  className?: string;
}

export function ActiveAlertsPanel({ alerts, onAlertClick, className }: ActiveAlertsPanelProps) {
  if (alerts.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-6 text-center text-sm text-text-secondary", className)}>
        No active alerts.
      </div>
    );
  }
  return (
    <ul className={cn("space-y-2.5", className)}>
      {alerts.map((alert) => (
        <li
          key={alert.id}
          className={cn(
            "relative overflow-hidden rounded-lg border border-hairline bg-parchment/40 py-2.5 pl-4 pr-3",
            onAlertClick && "cursor-pointer transition-colors hover:bg-parchment/70"
          )}
          onClick={onAlertClick ? () => onAlertClick(alert) : undefined}
        >
          <span
            className={cn("absolute left-0 top-0 h-full w-[3px]", railColor[alert.severity])}
            aria-hidden
          />
          <div className="flex items-center gap-3">
            <StatusBadge status={alert.severity} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm text-text-primary">{alert.message}</p>
              {alert.context && (
                <p className="truncate text-xs text-text-secondary">{alert.context}</p>
              )}
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}

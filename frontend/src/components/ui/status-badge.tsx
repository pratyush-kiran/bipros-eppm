import React from "react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";

/** Status tokens used across the Project Command dashboards.
 *  Pass the raw token (case-insensitive); the badge picks the right variant. */
export type StatusToken =
  | "DONE"
  | "ON_TRACK"
  | "ON TRACK"
  | "IN_PROGRESS"
  | "IN PROGRESS"
  | "PLANNED"
  | "DELAYED"
  | "AT_RISK"
  | "AT RISK"
  | "CRITICAL"
  | "HIGH"
  | "MEDIUM"
  | "LOW"
  | "INFO"
  | "WARNING"
  | "RESOLVED"
  | "PAID"
  | "PENDING"
  | "RAISED"
  | "ACTIVE"
  | "IDLE"
  | "REPAIR"
  | "GOOD"
  | "FAIR"
  | "POOR"
  | "ACHIEVED"
  | "PROJECTED_DELAY";

const variantMap: Record<string, BadgeVariant> = {
  DONE: "success",
  ON_TRACK: "success",
  RESOLVED: "success",
  PAID: "success",
  ACTIVE: "success",
  GOOD: "success",
  ACHIEVED: "success",

  IN_PROGRESS: "warning",
  PENDING: "warning",
  IDLE: "warning",
  FAIR: "warning",
  MEDIUM: "warning",
  WARNING: "warning",

  DELAYED: "danger",
  AT_RISK: "danger",
  CRITICAL: "danger",
  HIGH: "danger",
  REPAIR: "danger",
  POOR: "danger",
  PROJECTED_DELAY: "danger",

  PLANNED: "info",
  RAISED: "info",
  LOW: "info",
  INFO: "info",
};

function normalize(token: string): string {
  return token.toUpperCase().replace(/\s+/g, "_");
}

export function statusToVariant(token: string): BadgeVariant {
  return variantMap[normalize(token)] ?? "neutral";
}

export interface StatusBadgeProps {
  status: string;
  label?: string;
  withDot?: boolean;
  className?: string;
}

export function StatusBadge({ status, label, withDot, className }: StatusBadgeProps) {
  return (
    <Badge variant={statusToVariant(status)} withDot={withDot} className={className}>
      {label ?? status.replace(/_/g, " ")}
    </Badge>
  );
}

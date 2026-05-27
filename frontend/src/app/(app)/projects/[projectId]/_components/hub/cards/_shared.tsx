"use client";
/**
 * UI primitives shared by the bento group cards (status pill, skeleton, query
 * options). Pure data helpers (todayIso, unwrapArray, etc.) live in
 * `_data/projectHubHelpers.ts` so the data layer can use them without
 * pulling in JSX.
 */
import { type ReactNode } from "react";

// Re-export pure helpers so existing card imports keep working without churn.
export {
  todayIso,
  isoDaysAgo,
  currentYearMonth,
  unwrapArray,
  rawToMajorScale,
  formatBudgetAmount,
} from "../../../_data/projectHubHelpers";

export type PillTone = "neutral" | "success" | "warn" | "danger" | "info";

const TONE_CLASS: Record<PillTone, string> = {
  neutral: "bg-white/5 text-text-secondary",
  success: "bg-secondary-container/20 text-secondary",
  warn: "bg-tertiary-container/20 text-tertiary",
  danger: "bg-error-container/20 text-error",
  info: "bg-primary-container/20 text-primary",
};

/** Small inline M3 chip — used in card headers as a status indicator. */
export function StatusPill({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: PillTone;
}) {
  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] ${TONE_CLASS[tone]}`}
    >
      {children}
    </span>
  );
}

/** Skeleton bar; uses the parchment token so it blends with the card body. */
export function Skeleton({ className = "" }: { className?: string }) {
  return (
    <div
      className={`rounded bg-parchment motion-safe:animate-pulse ${className}`}
    />
  );
}

/** Shared base query options for every card-level React Query call. */
export const cardQueryOpts = {
  staleTime: 30_000,
  gcTime: 5 * 60_000,
  refetchOnWindowFocus: false,
} as const;

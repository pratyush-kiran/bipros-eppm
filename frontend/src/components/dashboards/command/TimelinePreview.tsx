import React from "react";
import { cn } from "@/lib/utils/cn";

export interface TimelinePhase {
  name: string;
  /** ISO date or millis */
  start: Date | string | number;
  end: Date | string | number;
  /** Tailwind bg color class. Falls back to the orange accent. */
  color?: string;
}

export interface TimelinePreviewProps {
  phases: TimelinePhase[];
  /** Visible range. If omitted, uses min(start)–max(end) padded by 15%. */
  rangeStart?: Date | string | number;
  rangeEnd?: Date | string | number;
  /** Defaults to now. */
  today?: Date | string | number;
  className?: string;
}

function toDate(d: Date | string | number): Date {
  return d instanceof Date ? d : new Date(d);
}

function pct(time: number, start: number, end: number): number {
  return ((time - start) / (end - start)) * 100;
}

function monthLabels(start: number, end: number): { label: string; pct: number }[] {
  const labels: { label: string; pct: number }[] = [];
  const cursor = new Date(start);
  cursor.setDate(1);
  while (cursor.getTime() < end) {
    labels.push({
      label: cursor.toLocaleString("en-US", { month: "short" }),
      pct: pct(cursor.getTime(), start, end),
    });
    cursor.setMonth(cursor.getMonth() + 1);
  }
  return labels.filter((l) => l.pct >= 0 && l.pct <= 100);
}

const defaultColors = [
  "bg-emerald",
  "bg-steel",
  "bg-bronze-warn",
  "bg-[var(--amber-flame)]",
  "bg-[var(--cmd-violet)]",
  "bg-[var(--cmd-cyan)]",
];

export function TimelinePreview({
  phases,
  rangeStart,
  rangeEnd,
  today,
  className,
}: TimelinePreviewProps) {
  if (phases.length === 0) {
    return (
      <div className={cn("text-sm text-text-secondary", className)}>
        No timeline data available.
      </div>
    );
  }

  const starts = phases.map((p) => toDate(p.start).getTime());
  const ends = phases.map((p) => toDate(p.end).getTime());
  const minStart = rangeStart ? toDate(rangeStart).getTime() : Math.min(...starts);
  const maxEnd = rangeEnd ? toDate(rangeEnd).getTime() : Math.max(...ends);
  const padded = (maxEnd - minStart) * 0.05;
  const start = minStart - padded;
  const end = maxEnd + padded;
  const todayMs = (today ? toDate(today) : new Date()).getTime();
  const showToday = todayMs >= start && todayMs <= end;
  const todayPct = showToday ? pct(todayMs, start, end) : 0;
  const months = monthLabels(start, end);

  return (
    <div className={cn("relative", className)}>
      {/* month labels */}
      <div className="relative mb-3 h-4 text-[10px] font-medium uppercase tracking-wider text-text-secondary">
        {months.map((m, i) => (
          <span
            key={`${m.label}-${i}`}
            className="absolute -translate-x-1/2"
            style={{ left: `${m.pct}%` }}
          >
            {m.label}
          </span>
        ))}
      </div>

      {/* phase rows */}
      <div className="relative space-y-3">
        {/* today marker */}
        {showToday && (
          <>
            <span
              className="absolute -top-3 z-10 -translate-x-1/2 rounded-md bg-gold px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-[var(--accent-foreground)] shadow"
              style={{ left: `${todayPct}%` }}
            >
              Today
            </span>
            <span
              className="absolute top-0 z-0 h-full w-px border-l border-dashed border-gold/70"
              style={{ left: `${todayPct}%` }}
              aria-hidden
            />
          </>
        )}
        {phases.map((phase, idx) => {
          const s = pct(toDate(phase.start).getTime(), start, end);
          const e = pct(toDate(phase.end).getTime(), start, end);
          const color = phase.color ?? defaultColors[idx % defaultColors.length];
          return (
            <div key={`${phase.name}-${idx}`} className="grid grid-cols-[120px_1fr] items-center gap-3">
              <span className="truncate text-xs text-text-secondary">{phase.name}</span>
              <div className="relative h-5 rounded-full bg-parchment">
                <span
                  className={cn("absolute top-0 h-5 rounded-full", color)}
                  style={{ left: `${s}%`, width: `${Math.max(1, e - s)}%` }}
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

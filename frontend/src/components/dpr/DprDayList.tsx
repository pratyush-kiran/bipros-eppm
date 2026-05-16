"use client";

import { CalendarDays, CloudSun, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DailyProgressReportResponse } from "@/lib/types/dpr";
import { DprActivityGroup, type ActivityGroup } from "./DprActivityGroup";

interface Props {
  rows: DailyProgressReportResponse[];
  onEdit: (row: DailyProgressReportResponse) => void;
  onDelete: (row: DailyProgressReportResponse) => void;
  /**
   * Pixel offset for sticky day headers, so they park beneath the page's sticky filter bar.
   * Falls back to 0 (sticky still works at top of viewport) when caller doesn't measure.
   */
  stickyOffset?: number;
}

interface DayGroup {
  date: string;
  weather: string | null;
  supervisorCount: number;
  totalActivities: number; // total DPR rows that day (sum across activity groups)
  activityGroups: ActivityGroup[];
}

/** Stable key for a (boqItemNo, activityName) pair so two rows with the same BOQ collapse. */
const activityKey = (row: DailyProgressReportResponse): string =>
  row.boqItemNo
    ? `boq:${row.boqItemNo}`
    : row.activityId
      ? `aid:${row.activityId}`
      : `name:${(row.activityName ?? "").toLowerCase()}`;

/** Chainage-ascending sort for work fronts within an activity; nulls sort last. */
const compareByChainage = (
  a: DailyProgressReportResponse,
  b: DailyProgressReportResponse,
): number => {
  const ax = a.chainageFromM ?? Number.POSITIVE_INFINITY;
  const bx = b.chainageFromM ?? Number.POSITIVE_INFINITY;
  if (ax !== bx) return ax - bx;
  return (a.supervisorName ?? "").localeCompare(b.supervisorName ?? "");
};

const groupByDayThenActivity = (rows: DailyProgressReportResponse[]): DayGroup[] => {
  const byDay = new Map<string, DailyProgressReportResponse[]>();
  for (const r of rows) {
    if (!r.reportDate) continue;
    const list = byDay.get(r.reportDate) ?? [];
    list.push(r);
    byDay.set(r.reportDate, list);
  }

  const days: DayGroup[] = [];
  for (const [date, dayRows] of byDay.entries()) {
    const byActivity = new Map<string, DailyProgressReportResponse[]>();
    for (const r of dayRows) {
      const key = activityKey(r);
      const list = byActivity.get(key) ?? [];
      list.push(r);
      byActivity.set(key, list);
    }

    const activityGroups: ActivityGroup[] = [];
    for (const [key, list] of byActivity.entries()) {
      const sorted = [...list].sort(compareByChainage);
      const first = sorted[0];
      const totalQty = sorted.reduce(
        (acc, r) => acc + (typeof r.qtyExecuted === "number" ? r.qtyExecuted : 0),
        0,
      );
      // Deduplicate supervisors while preserving discovery order.
      const seen = new Set<string>();
      const uniqueSupervisors: Array<{ id: string; name: string }> = [];
      for (const r of sorted) {
        const name = (r.supervisorName ?? "").trim();
        if (!name) continue;
        const dedupKey = (r.supervisorUserId ?? `name:${name.toLowerCase()}`).toString();
        if (seen.has(dedupKey)) continue;
        seen.add(dedupKey);
        uniqueSupervisors.push({ id: dedupKey, name });
      }
      activityGroups.push({
        key,
        boqItemNo: first.boqItemNo ?? null,
        activityName: first.activityName,
        unit: first.unit,
        totalQty,
        uniqueSupervisors,
        rows: sorted,
      });
    }

    // Sort activity groups by BOQ natural order (fallback to name).
    activityGroups.sort((a, b) => {
      const ka = a.boqItemNo ?? a.activityName;
      const kb = b.boqItemNo ?? b.activityName;
      return ka.localeCompare(kb, undefined, { numeric: true, sensitivity: "base" });
    });

    const supervisorNames = new Set<string>();
    for (const g of activityGroups) {
      for (const s of g.uniqueSupervisors) supervisorNames.add(s.name);
    }
    const weather = dayRows.find((r) => r.weatherCondition)?.weatherCondition ?? null;

    days.push({
      date,
      weather,
      supervisorCount: supervisorNames.size,
      totalActivities: dayRows.length,
      activityGroups,
    });
  }

  // Most recent day first.
  days.sort((a, b) => b.date.localeCompare(a.date));
  return days;
};

const fmtDate = (iso: string): string => {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString(undefined, {
    weekday: "short",
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
};

export function DprDayList({ rows, onEdit, onDelete, stickyOffset = 0 }: Props) {
  const days = groupByDayThenActivity(rows);

  if (days.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-hairline bg-ivory/30 px-6 py-12 text-center">
        <CalendarDays className="mx-auto h-8 w-8 text-slate" />
        <p className="mt-2 text-sm text-slate">
          No daily progress logged in this range. Tap{" "}
          <span className="font-semibold text-gold-ink">Add Activity</span> to start.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {days.map((day) => (
        <section key={day.date} className="space-y-3">
          <header
            className="sticky z-10 flex flex-wrap items-center gap-3 border-b border-b-gold/30 bg-ivory/85 px-4 py-2.5 backdrop-blur-sm"
            style={{ top: stickyOffset }}
          >
            <div className="flex items-center gap-2 font-display text-base font-semibold tracking-tight text-charcoal">
              <CalendarDays className="h-4 w-4 text-gold-deep" />
              {fmtDate(day.date)}
            </div>
            <Badge variant="gold">
              {day.activityGroups.length}{" "}
              {day.activityGroups.length === 1 ? "activity" : "activities"}
            </Badge>
            {day.totalActivities !== day.activityGroups.length && (
              <span className="text-xs text-slate">
                · {day.totalActivities}{" "}
                {day.totalActivities === 1 ? "front" : "fronts"}
              </span>
            )}
            {day.supervisorCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full bg-paper/80 px-2 py-0.5 text-xs text-slate">
                <Users className="h-3 w-3 text-gold-deep" />
                {day.supervisorCount}{" "}
                {day.supervisorCount === 1 ? "supervisor" : "supervisors"}
              </span>
            )}
            {day.weather && (
              <span className="inline-flex items-center gap-1 text-xs text-slate">
                <CloudSun className="h-3 w-3" /> {day.weather}
              </span>
            )}
          </header>
          <div className="space-y-2">
            {day.activityGroups.map((group) => (
              <DprActivityGroup
                key={group.key}
                group={group}
                onEditRow={onEdit}
                onDeleteRow={onDelete}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

export function DprDaySkeleton() {
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-3 border-b border-b-gold/20 bg-ivory/60 px-4 py-2.5">
        <div className="h-4 w-4 animate-pulse rounded bg-parchment" />
        <div className="h-4 w-40 animate-pulse rounded bg-parchment" />
        <div className="h-5 w-20 animate-pulse rounded-full bg-parchment" />
      </div>
      <div className="space-y-2">
        <div className="h-16 animate-pulse rounded-lg bg-parchment/60" />
        <div className="h-16 animate-pulse rounded-lg bg-parchment/60" />
      </div>
    </section>
  );
}

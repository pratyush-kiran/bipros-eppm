"use client";

import { useMemo } from "react";
import { CalendarDays, CloudSun, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DprSummaryRow } from "@/lib/types/dpr";
import { DprActivityGroup } from "./DprActivityGroup";
import { groupByDayThenActivity } from "./groupByDayThenActivity";

interface Props {
  rows: DprSummaryRow[];
  projectId: string;
  onEdit: (row: DprSummaryRow) => void;
  onDelete: (row: DprSummaryRow) => void;
  stickyOffset?: number;
}

const fmtDate = (iso: string): string => {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString(undefined, {
    weekday: "short",
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
};

export function DprDayList({ rows, projectId, onEdit, onDelete, stickyOffset = 0 }: Props) {
  const days = useMemo(() => groupByDayThenActivity(rows), [rows]);

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
        <section
          key={day.date}
          className="space-y-3"
          style={{ contentVisibility: "auto", containIntrinsicSize: "0 480px" }}
        >
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
                projectId={projectId}
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

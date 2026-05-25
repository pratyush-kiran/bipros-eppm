"use client";

import { useMemo } from "react";
import { addMonths, differenceInDays, format, startOfMonth } from "date-fns";
import { Calendar } from "lucide-react";
import {
  SectionCard,
  EmptyBlock,
} from "@/components/common/dashboard/primitives";
import type { ActivityStatusRow } from "@/lib/api/projectInsightsApi";

interface ProjectTimelinePreviewProps {
  activities: ActivityStatusRow[];
  windowMonths?: number;
  maxRows?: number;
}

const BAR_HEIGHT = 18;
const ROW_HEIGHT = 34;

const TONE_BY_STATUS: Record<string, string> = {
  COMPLETED: "bg-emerald",
  DONE: "bg-emerald",
  IN_PROGRESS: "bg-gold-deep",
  ACTIVE: "bg-gold-deep",
  DELAYED: "bg-burgundy",
  NOT_STARTED: "bg-slate-400",
  PLANNED: "bg-slate-400",
};

function toneClassFor(status: string) {
  return TONE_BY_STATUS[status] ?? TONE_BY_STATUS.PLANNED;
}

function parseISO(s: string | null): Date | null {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function ProjectTimelinePreview({
  activities,
  windowMonths = 6,
  maxRows = 6,
}: ProjectTimelinePreviewProps) {
  const today = useMemo(() => new Date(), []);
  const windowStart = useMemo(
    () => startOfMonth(addMonths(today, -1)),
    [today],
  );
  const windowEnd = useMemo(
    () => addMonths(windowStart, windowMonths),
    [windowStart, windowMonths],
  );
  const totalDays = useMemo(
    () => Math.max(1, differenceInDays(windowEnd, windowStart)),
    [windowStart, windowEnd],
  );

  const rows = useMemo(() => {
    return activities
      .filter((a) => a.plannedStart && a.plannedFinish)
      .slice(0, maxRows);
  }, [activities, maxRows]);

  const monthMarks = useMemo(() => {
    const marks: { date: Date; pct: number }[] = [];
    for (let i = 0; i <= windowMonths; i++) {
      const d = addMonths(windowStart, i);
      const pct = (differenceInDays(d, windowStart) / totalDays) * 100;
      marks.push({ date: d, pct });
    }
    return marks;
  }, [windowStart, totalDays, windowMonths]);

  const todayPct = useMemo(() => {
    const d = differenceInDays(today, windowStart);
    return (d / totalDays) * 100;
  }, [today, windowStart, totalDays]);
  const todayVisible = todayPct >= 0 && todayPct <= 100;

  return (
    <SectionCard
      title="Project Timeline Preview"
      subtitle={`Top WBS phases · ${windowMonths}-month window`}
      icon={<Calendar size={16} />}
      accent
    >
      {rows.length === 0 ? (
        <EmptyBlock label="No scheduled phases in this window" />
      ) : (
        <div className="rounded-xl border border-hairline bg-ivory/40">
          <div className="flex">
            {/* Left sidebar: activity names */}
            <div className="w-44 shrink-0 border-r border-hairline bg-paper">
              <div className="h-9 border-b border-hairline" />
              <ul>
                {rows.map((row) => (
                  <li
                    key={row.activityId}
                    className="flex items-center px-3 text-[12px] font-medium text-charcoal"
                    style={{ height: `${ROW_HEIGHT}px` }}
                    title={row.name}
                  >
                    <span className="truncate">{row.name}</span>
                  </li>
                ))}
              </ul>
            </div>

            {/* Right pane: timescale + bars (relative) */}
            <div className="relative grow overflow-hidden">
              {/* Header */}
              <div className="relative h-9 border-b border-hairline bg-paper">
                {monthMarks.map((m, idx) => (
                  <div
                    key={idx}
                    className="absolute top-0 h-full -translate-x-1/2"
                    style={{ left: `${m.pct}%` }}
                  >
                    <div className="flex h-full flex-col items-center justify-center text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                      {format(m.date, "MMM")}
                    </div>
                  </div>
                ))}
                {todayVisible && (
                  <div
                    className="absolute top-1 -translate-x-1/2 rounded-md bg-burgundy px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-paper shadow-sm z-10"
                    style={{ left: `${todayPct}%` }}
                  >
                    Today
                  </div>
                )}
              </div>

              {/* Bars area */}
              <div
                className="relative"
                style={{ height: `${rows.length * ROW_HEIGHT}px` }}
              >
                {/* Month gridlines */}
                {monthMarks.map((m, idx) => (
                  <div
                    key={idx}
                    className="absolute inset-y-0 w-px bg-hairline/60"
                    style={{ left: `${m.pct}%` }}
                    aria-hidden
                  />
                ))}
                {/* TODAY line */}
                {todayVisible && (
                  <div
                    className="absolute inset-y-0 w-[2px] bg-burgundy/70"
                    style={{ left: `${todayPct}%` }}
                    aria-hidden
                  />
                )}

                {rows.map((row, i) => {
                  const start = parseISO(row.plannedStart);
                  const finish = parseISO(row.plannedFinish);
                  if (!start || !finish) return null;

                  const startOffset = Math.max(
                    0,
                    differenceInDays(start, windowStart),
                  );
                  const finishOffset = Math.min(
                    totalDays,
                    differenceInDays(finish, windowStart),
                  );
                  const widthDays = Math.max(1, finishOffset - startOffset);
                  const leftPct = (startOffset / totalDays) * 100;
                  const widthPct = (widthDays / totalDays) * 100;
                  if (widthPct <= 0) return null;

                  return (
                    <div
                      key={row.activityId}
                      className="absolute"
                      style={{
                        top: `${i * ROW_HEIGHT + (ROW_HEIGHT - BAR_HEIGHT) / 2}px`,
                        left: `${leftPct}%`,
                        width: `${widthPct}%`,
                        height: `${BAR_HEIGHT}px`,
                      }}
                    >
                      <div
                        className={`relative h-full overflow-hidden rounded-md shadow-[0_1px_2px_rgba(28,28,28,0.10)] ring-1 ring-black/5 ${toneClassFor(row.status)}`}
                        title={`${row.name} · ${format(start, "d MMM")} → ${format(finish, "d MMM")}`}
                      >
                        {row.pctComplete > 0 && row.pctComplete < 100 && (
                          <div
                            className="absolute inset-y-0 left-0 bg-black/15"
                            style={{ width: `${Math.min(100, row.pctComplete)}%` }}
                          />
                        )}
                        {widthPct > 6 && (
                          <div className="absolute inset-0 flex items-center justify-end pr-1.5 text-[9px] font-semibold text-paper drop-shadow">
                            {row.pctComplete >= 10
                              ? `${Math.round(row.pctComplete)}%`
                              : ""}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      )}
    </SectionCard>
  );
}

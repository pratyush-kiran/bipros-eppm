"use client";

import React, { useRef, useEffect, useState, useMemo } from "react";
import {
  differenceInDays,
  min as getMin,
  max as getMax,
  addDays,
  startOfDay,
  eachMonthOfInterval,
  eachQuarterOfInterval,
} from "date-fns";
import { AlertTriangle, Play } from "lucide-react";
import type { ActivityResponse, WbsNodeResponse } from "@/lib/types";
import { GanttTimescale } from "./GanttTimescale";
import { GanttSidebar } from "./GanttSidebar";
import { GanttTaskRow } from "./GanttTaskRow";
import { buildGanttRows, type GanttRow } from "./ganttGrouping";
import { buildWbsNameMap } from "@/lib/utils/wbs";
import { getActivityBarRect } from "@/lib/utils/ganttGeometry";
import { getGanttStatus, getGanttStatusToken } from "@/lib/utils/ganttStatus";
import { StatusBadge } from "@/components/common/StatusBadge";

interface ActivityRelationship {
  predecessorActivityId: string;
  successorActivityId: string;
  relationshipType: string;
}

interface BaselineActivityData {
  activityId: string;
  baselineStartDate: string | null;
  baselineFinishDate: string | null;
}

interface GanttChartProps {
  activities: ActivityResponse[];
  wbsNodes?: WbsNodeResponse[];
  relationships?: ActivityRelationship[];
  baselineActivities?: BaselineActivityData[];
  onActivityClick?: (id: string) => void;
  onActivityContextMenu?: (id: string, x: number, y: number) => void;
  onActivityReschedule?: (id: string, newStart: string, newEnd: string) => void;
  spotlightStartDate?: string | null;
  spotlightEndDate?: string | null;
  isStale?: boolean;
  onRunSchedule?: () => void;
  isRunningSchedule?: boolean;
}

interface DateRange {
  start: Date;
  end: Date;
  days: number;
}

const QUARTER_MODE_THRESHOLD = 4;
const BADGE_LANE_PX = 140;

export function GanttChart({
  activities,
  wbsNodes = [],
  relationships = [],
  baselineActivities = [],
  onActivityClick,
  onActivityContextMenu,
  onActivityReschedule,
  spotlightStartDate,
  spotlightEndDate,
  isStale = false,
  onRunSchedule,
  isRunningSchedule = false,
}: GanttChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const sidebarRef = useRef<HTMLDivElement>(null);
  const [pixelsPerDay, setPixelsPerDay] = useState(20);
  const [startDateFilter, setStartDateFilter] = useState(
    spotlightStartDate || ""
  );
  const [endDateFilter, setEndDateFilter] = useState(spotlightEndDate || "");

  // Group activities by their parent WBS node.
  const wbsNameById = useMemo(() => buildWbsNameMap(wbsNodes), [wbsNodes]);
  const rows: GanttRow[] = useMemo(
    () => buildGanttRows(activities, wbsNameById),
    [activities, wbsNameById]
  );

  // Calculate date range from all activities
  const dateRange = calculateDateRange(activities);

  // Auto-scroll the timeline to the first activity on initial mount so the
  // bars are visible without the user having to manually scroll horizontally.
  const hasAutoScrolledRef = useRef(false);
  useEffect(() => {
    if (hasAutoScrolledRef.current) return;
    if (!dateRange || activities.length === 0) return;
    const topRows = activities.slice(0, 10);
    const firstDate = topRows
      .map((a) => a.plannedStartDate || a.earlyStartDate)
      .filter((d): d is string => d != null)
      .map((d) => startOfDay(new Date(d)))
      .reduce<Date | null>((acc, d) => (acc == null || d < acc ? d : acc), null);
    if (!firstDate) return;
    const offsetDays = Math.max(0, differenceInDays(firstDate, dateRange.start));
    const targetScrollLeft = Math.max(0, (offsetDays - 2) * pixelsPerDay);
    const raf = requestAnimationFrame(() => {
      if (chartContainerRef.current) {
        chartContainerRef.current.scrollLeft = targetScrollLeft;
        hasAutoScrolledRef.current = true;
      }
    });
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activities.length, dateRange?.start.getTime(), pixelsPerDay]);

  if (!dateRange || activities.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border py-12 text-center">
        <p className="text-text-secondary">No activities to display</p>
        <p className="mt-2 text-sm text-text-muted">
          Create activities first, then run the scheduler to see them on the
          Gantt chart.
        </p>
      </div>
    );
  }

  const handleSidebarScroll = (e: React.UIEvent<HTMLDivElement>) => {
    if (chartContainerRef.current) {
      chartContainerRef.current.scrollTop = (e.target as HTMLDivElement).scrollTop;
    }
  };

  const handleChartScroll = (e: React.UIEvent<HTMLDivElement>) => {
    if (sidebarRef.current) {
      sidebarRef.current.scrollTop = (e.target as HTMLDivElement).scrollTop;
    }
  };

  const totalWidth = dateRange.days * pixelsPerDay;
  const wrapperWidth = totalWidth + BADGE_LANE_PX;
  const rowHeight = 32;
  const timelineStartY = 0;
  const chartHeight = rows.length * rowHeight + timelineStartY;
  const mode: "quarter-month" | "month-week" =
    pixelsPerDay < QUARTER_MODE_THRESHOLD ? "quarter-month" : "month-week";

  const getActivityOpacity = (activity: ActivityResponse): number => {
    if (!startDateFilter && !endDateFilter) return 1;

    const actStart = activity.plannedStartDate || activity.earlyStartDate
      ? new Date((activity.plannedStartDate || activity.earlyStartDate)!)
      : null;
    const actEnd = activity.plannedFinishDate || activity.earlyFinishDate
      ? new Date((activity.plannedFinishDate || activity.earlyFinishDate)!)
      : null;
    const filterStart = startDateFilter ? new Date(startDateFilter) : null;
    const filterEnd = endDateFilter ? new Date(endDateFilter) : null;

    if (!actStart || !actEnd) return 0.3;

    const isInRange =
      (!filterStart || !filterEnd || actStart <= filterEnd || actEnd >= filterStart) &&
      (!filterEnd || !filterStart || actEnd >= filterStart || actStart <= filterEnd);

    return isInRange ? 1 : 0.3;
  };

  return (
    <div className="space-y-4">
      {isStale && (
        <div className="flex items-center justify-between gap-4 rounded-md border border-warning/40 bg-warning/10 px-4 py-3">
          <div className="flex items-center gap-2 text-sm text-warning">
            <AlertTriangle size={16} className="shrink-0" />
            <span>
              Schedule is out of date — dates and critical path shown may not
              reflect recent changes.
            </span>
          </div>
          {onRunSchedule && (
            <button
              onClick={onRunSchedule}
              disabled={isRunningSchedule}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-warning px-3 py-1.5 text-xs font-medium text-text-primary hover:bg-warning/80 disabled:opacity-50"
            >
              <Play size={12} />
              {isRunningSchedule ? "Running..." : "Run now"}
            </button>
          )}
        </div>
      )}

      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text-primary">Gantt Chart</h2>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2 text-sm text-text-secondary">
            <label>Progress Spotlight:</label>
            <input
              type="date"
              value={startDateFilter}
              onChange={(e) => setStartDateFilter(e.target.value)}
              className="rounded border border-border px-2 py-1 bg-surface/50 text-text-primary"
              placeholder="Start"
            />
            <span>to</span>
            <input
              type="date"
              value={endDateFilter}
              onChange={(e) => setEndDateFilter(e.target.value)}
              className="rounded border border-border px-2 py-1 bg-surface/50 text-text-primary"
              placeholder="End"
            />
            {(startDateFilter || endDateFilter) && (
              <button
                onClick={() => {
                  setStartDateFilter("");
                  setEndDateFilter("");
                }}
                className="text-xs text-accent hover:underline"
              >
                Clear
              </button>
            )}
          </div>

          <label className="flex items-center gap-2 text-sm text-text-secondary">
            Zoom:
            <input
              type="range"
              min="2"
              max="50"
              value={pixelsPerDay}
              onChange={(e) => setPixelsPerDay(Number(e.target.value))}
              className="w-24"
            />
            {pixelsPerDay}px
          </label>
        </div>
      </div>

      <div className="flex gap-0 border border-border rounded-lg overflow-hidden bg-surface/50">
        {/* Sidebar */}
        <div
          ref={sidebarRef}
          className="w-[480px] shrink-0 overflow-y-auto border-r border-border"
          onScroll={handleSidebarScroll}
        >
          <GanttSidebar
            rows={rows}
            rowHeight={rowHeight}
            onActivityClick={onActivityClick}
            onActivityContextMenu={onActivityContextMenu}
          />
        </div>

        {/* Chart */}
        <div
          ref={chartContainerRef}
          className="flex-1 overflow-auto"
          onScroll={handleChartScroll}
        >
          <div className="inline-block min-w-full">
            <GanttTimescale dateRange={dateRange} pixelsPerDay={pixelsPerDay} />

            <div
              className="relative"
              style={{ width: wrapperWidth, height: chartHeight }}
            >
              <svg
                width={totalWidth}
                height={chartHeight}
                className="bg-surface/50 block"
              >
                <defs>
                  <marker
                    id="arrowhead"
                    markerWidth="10"
                    markerHeight="10"
                    refX="9"
                    refY="3"
                    orient="auto"
                  >
                    <polygon points="0 0, 10 3, 0 6" fill="var(--text-muted)" />
                  </marker>
                  <marker
                    id="arrowhead-critical"
                    markerWidth="10"
                    markerHeight="10"
                    refX="9"
                    refY="3"
                    orient="auto"
                  >
                    <polygon points="0 0, 10 3, 0 6" fill="var(--danger)" />
                  </marker>
                </defs>

                {/* Vertical grid lines (mode-aware) */}
                {renderGridLines(dateRange, pixelsPerDay, chartHeight, mode)}

                {/* Group row banding */}
                {rows.map((row, idx) =>
                  row.kind === "group" ? (
                    <g key={`gb-${row.groupId}`}>
                      <rect
                        x={0}
                        y={idx * rowHeight + timelineStartY}
                        width={totalWidth}
                        height={rowHeight}
                        fill="var(--surface-active)"
                        opacity={0.4}
                      />
                      <line
                        x1={0}
                        y1={(idx + 1) * rowHeight + timelineStartY}
                        x2={totalWidth}
                        y2={(idx + 1) * rowHeight + timelineStartY}
                        stroke="var(--border)"
                        strokeWidth="1"
                      />
                    </g>
                  ) : null
                )}

                {/* Today line */}
                {renderTodayLine(dateRange, pixelsPerDay, chartHeight)}

                {/* Relationship lines */}
                {relationships.length > 0 &&
                  renderRelationshipLines(
                    relationships,
                    rows,
                    dateRange,
                    pixelsPerDay,
                    rowHeight,
                    timelineStartY
                  )}

                {/* Activity bars */}
                {rows.map((row, idx) => {
                  if (row.kind !== "activity") return null;
                  const activity = row.activity;
                  const baselineData = baselineActivities.find(
                    (b) => b.activityId === activity.id
                  );
                  const opacity = getActivityOpacity(activity);
                  return (
                    <g key={activity.id} opacity={opacity}>
                      <GanttTaskRow
                        activity={activity}
                        dateRange={dateRange}
                        pixelsPerDay={pixelsPerDay}
                        rowIndex={idx}
                        rowHeight={rowHeight}
                        timelineStartY={timelineStartY}
                        baselineData={baselineData}
                        onActivityClick={onActivityClick}
                        onActivityContextMenu={onActivityContextMenu}
                        onActivityReschedule={onActivityReschedule}
                      />
                    </g>
                  );
                })}
              </svg>

              {/* Status pill badges — HTML layer above the SVG */}
              <div className="absolute inset-0 pointer-events-none">
                {rows.map((row, idx) => {
                  if (row.kind !== "activity") return null;
                  const rect = getActivityBarRect(
                    row.activity,
                    dateRange,
                    pixelsPerDay
                  );
                  if (!rect) return null;
                  const opacity = getActivityOpacity(row.activity);
                  const status = getGanttStatus(row.activity);
                  const token = getGanttStatusToken(status);
                  return (
                    <div
                      key={`badge-${row.activity.id}`}
                      className="absolute flex items-center"
                      style={{
                        left: rect.x + rect.width + 8,
                        top: idx * rowHeight + timelineStartY,
                        height: rowHeight,
                        opacity,
                      }}
                    >
                      <StatusBadge
                        status={token.badgeStatus}
                        variant="gantt"
                      />
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Legend */}
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-surface/80 p-4">
        <StatusBadge status="DONE" variant="gantt" />
        <StatusBadge status="IN_PROGRESS_NOW" variant="gantt" />
        <StatusBadge status="DELAYED" variant="gantt" />
        <StatusBadge status="PLANNED" variant="gantt" />
        <span className="mx-2 h-4 w-px bg-border" />
        <div className="flex items-center gap-2">
          <div className="h-3 w-5 rounded bg-danger" />
          <span className="text-xs text-text-secondary">Critical Path</span>
        </div>
        {baselineActivities.length > 0 && (
          <div className="flex items-center gap-2">
            <div
              className="h-1.5 w-5 rounded bg-text-muted"
              style={{ opacity: 0.5 }}
            />
            <span className="text-xs text-text-secondary">Baseline</span>
          </div>
        )}
        {relationships.length > 0 && (
          <div className="flex items-center gap-2">
            <svg width="24" height="10" viewBox="0 0 24 10">
              <path
                d="M 0 5 L 22 5"
                stroke="var(--text-muted)"
                strokeWidth="1.5"
                fill="none"
              />
              <polygon points="24,5 18,2 18,8" fill="var(--text-muted)" />
            </svg>
            <span className="text-xs text-text-secondary">Relationships</span>
          </div>
        )}
        {(startDateFilter || endDateFilter) && (
          <div className="flex items-center gap-2">
            <div className="h-3 w-5 rounded bg-warning" />
            <span className="text-xs text-text-secondary">In Spotlight Range</span>
          </div>
        )}
      </div>
    </div>
  );
}

function calculateDateRange(activities: ActivityResponse[]): DateRange | null {
  const validDates = activities
    .flatMap((a) => [
      a.plannedStartDate || a.earlyStartDate,
      a.plannedFinishDate || a.earlyFinishDate,
    ])
    .filter((d): d is string => d != null)
    .map((d) => startOfDay(new Date(d)));

  if (validDates.length === 0) {
    return null;
  }

  const start = getMin(validDates);
  const end = getMax(validDates);
  const days = Math.max(differenceInDays(end, start) + 1, 7);

  return { start, end, days };
}

function renderGridLines(
  dateRange: DateRange,
  pixelsPerDay: number,
  height: number,
  mode: "quarter-month" | "month-week"
): React.ReactNode {
  const lines: React.ReactNode[] = [];
  const rangeEnd = addDays(dateRange.start, dateRange.days - 1);

  if (mode === "quarter-month") {
    const months = eachMonthOfInterval({ start: dateRange.start, end: rangeEnd });
    const quarters = eachQuarterOfInterval({
      start: dateRange.start,
      end: rangeEnd,
    });
    months.forEach((m, idx) => {
      const monthStart = new Date(m.getFullYear(), m.getMonth(), 1);
      const days = Math.max(0, differenceInDays(monthStart, dateRange.start));
      const x = days * pixelsPerDay;
      lines.push(
        <line
          key={`gm-${idx}`}
          x1={x}
          y1="0"
          x2={x}
          y2={height}
          stroke="var(--grid-color)"
          strokeWidth="0.5"
        />
      );
    });
    quarters.forEach((q, idx) => {
      const qStart = new Date(q.getFullYear(), q.getMonth(), 1);
      const days = Math.max(0, differenceInDays(qStart, dateRange.start));
      const x = days * pixelsPerDay;
      lines.push(
        <line
          key={`gq-${idx}`}
          x1={x}
          y1="0"
          x2={x}
          y2={height}
          stroke="var(--border)"
          strokeWidth="1"
          opacity="0.7"
        />
      );
    });
  } else {
    const weekInDays = 7;
    for (let i = 0; i <= dateRange.days; i++) {
      if (i % weekInDays === 0) {
        const x = i * pixelsPerDay;
        lines.push(
          <line
            key={`gw-${i}`}
            x1={x}
            y1="0"
            x2={x}
            y2={height}
            stroke="var(--border)"
            strokeWidth="1"
            strokeDasharray="2,2"
          />
        );
      }
    }
  }

  return lines;
}

function renderTodayLine(
  dateRange: DateRange,
  pixelsPerDay: number,
  height: number
): React.ReactNode | null {
  const today = startOfDay(new Date());

  if (today < dateRange.start || today > addDays(dateRange.start, dateRange.days)) {
    return null;
  }

  const daysFromStart = differenceInDays(today, dateRange.start);
  const x = daysFromStart * pixelsPerDay;
  const pillW = 56;
  const pillH = 18;

  return (
    <g key="today-line">
      <line
        x1={x}
        y1={pillH + 2}
        x2={x}
        y2={height}
        stroke="var(--accent)"
        strokeWidth="2"
        strokeDasharray="6,4"
      />
      <rect
        x={x - pillW / 2}
        y={2}
        width={pillW}
        height={pillH}
        rx={9}
        fill="var(--accent)"
      />
      <text
        x={x}
        y={pillH - 3}
        textAnchor="middle"
        fontSize="10"
        fontWeight="700"
        fill="var(--accent-foreground)"
        letterSpacing="0.05em"
      >
        TODAY
      </text>
    </g>
  );
}

function renderRelationshipLines(
  relationships: ActivityRelationship[],
  rows: GanttRow[],
  dateRange: DateRange,
  pixelsPerDay: number,
  rowHeight: number,
  timelineStartY: number
): React.ReactNode[] {
  const lines: React.ReactNode[] = [];
  // Activity row index within the flat `rows` array — preserves vertical
  // alignment when group rows are interleaved between activity rows.
  const activityRowIndex = new Map<string, number>();
  rows.forEach((row, idx) => {
    if (row.kind === "activity") {
      activityRowIndex.set(row.activity.id, idx);
    }
  });

  relationships.forEach((rel, idx) => {
    const predIdx = activityRowIndex.get(rel.predecessorActivityId);
    const succIdx = activityRowIndex.get(rel.successorActivityId);

    if (predIdx === undefined || succIdx === undefined) return;

    const predRow = rows[predIdx];
    const succRow = rows[succIdx];
    if (predRow.kind !== "activity" || succRow.kind !== "activity") return;
    const predActivity = predRow.activity;
    const succActivity = succRow.activity;

    const predEndStr =
      predActivity.plannedFinishDate || predActivity.earlyFinishDate;
    const succStartStr =
      succActivity.plannedStartDate || succActivity.earlyStartDate;

    const predEnd = predEndStr ? startOfDay(new Date(predEndStr)) : null;
    const succStart = succStartStr ? startOfDay(new Date(succStartStr)) : null;

    if (!predEnd || !succStart) return;

    const predEndOffset = differenceInDays(predEnd, dateRange.start);
    const succStartOffset = Math.max(
      0,
      differenceInDays(succStart, dateRange.start)
    );

    const predX = predEndOffset * pixelsPerDay;
    const predY = predIdx * rowHeight + timelineStartY + rowHeight / 2;

    const succX = succStartOffset * pixelsPerDay;
    const succY = succIdx * rowHeight + timelineStartY + rowHeight / 2;

    const isCritical =
      predActivity.isCritical === true && succActivity.isCritical === true;

    const midX = (predX + succX) / 2;

    lines.push(
      <g key={`rel-${idx}`} opacity="0.6">
        <path
          d={`M ${predX} ${predY} L ${midX} ${predY} L ${midX} ${succY} L ${succX} ${succY}`}
          stroke={isCritical ? "var(--danger)" : "var(--text-muted)"}
          strokeWidth="1.5"
          fill="none"
          markerEnd={
            isCritical ? "url(#arrowhead-critical)" : "url(#arrowhead)"
          }
        />
      </g>
    );
  });

  return lines;
}

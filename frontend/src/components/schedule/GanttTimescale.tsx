"use client";

import React from "react";
import {
  format,
  eachMonthOfInterval,
  eachQuarterOfInterval,
  startOfQuarter,
  endOfQuarter,
  addDays,
  differenceInDays,
} from "date-fns";

interface DateRange {
  start: Date;
  end: Date;
  days: number;
}

interface GanttTimescaleProps {
  dateRange: DateRange;
  pixelsPerDay: number;
}

const QUARTER_MODE_THRESHOLD = 4;

export function GanttTimescale({
  dateRange,
  pixelsPerDay,
}: GanttTimescaleProps) {
  const totalWidth = dateRange.days * pixelsPerDay;
  const headerHeight = 80;
  const rangeEnd = addDays(dateRange.start, dateRange.days - 1);
  const mode: "quarter-month" | "month-week" =
    pixelsPerDay < QUARTER_MODE_THRESHOLD ? "quarter-month" : "month-week";

  if (mode === "quarter-month") {
    const quarters = eachQuarterOfInterval({
      start: dateRange.start,
      end: rangeEnd,
    });
    const months = eachMonthOfInterval({
      start: dateRange.start,
      end: rangeEnd,
    });

    return (
      <svg
        width={totalWidth}
        height={headerHeight}
        className="sticky top-0 bg-surface/50 border-b border-border z-10"
      >
        {/* Quarter row (top) */}
        <g>
          {quarters.map((q, idx) => {
            const qStart = startOfQuarter(q);
            const qEnd = endOfQuarter(q);
            const adjStart = qStart < dateRange.start ? dateRange.start : qStart;
            const adjEnd = qEnd > rangeEnd ? rangeEnd : qEnd;
            const startOffset = differenceInDays(adjStart, dateRange.start);
            const endOffset = differenceInDays(adjEnd, dateRange.start);
            const width = (endOffset - startOffset + 1) * pixelsPerDay;
            const x = startOffset * pixelsPerDay;

            return (
              <g key={`q-${idx}`}>
                <rect
                  x={x}
                  y="0"
                  width={width}
                  height="40"
                  fill="var(--surface-active)"
                  stroke="var(--border)"
                  strokeWidth="1"
                />
                <text
                  x={x + width / 2}
                  y="26"
                  textAnchor="middle"
                  fontSize="13"
                  fontWeight="700"
                  fill="var(--text-primary)"
                >
                  {format(qStart, "QQQ yyyy")}
                </text>
              </g>
            );
          })}
        </g>

        {/* Month row (bottom) */}
        <g>
          {months.map((m, idx) => {
            const mStart = new Date(m.getFullYear(), m.getMonth(), 1);
            const mEnd = new Date(m.getFullYear(), m.getMonth() + 1, 0);
            const adjStart = mStart < dateRange.start ? dateRange.start : mStart;
            const adjEnd = mEnd > rangeEnd ? rangeEnd : mEnd;
            const startOffset = differenceInDays(adjStart, dateRange.start);
            const endOffset = differenceInDays(adjEnd, dateRange.start);
            const width = (endOffset - startOffset + 1) * pixelsPerDay;
            const x = startOffset * pixelsPerDay;
            const isQuarterBoundary = mStart.getMonth() % 3 === 0;

            return (
              <g key={`m-${idx}`}>
                <rect
                  x={x}
                  y="40"
                  width={width}
                  height="40"
                  fill="var(--surface-hover)"
                />
                {/* faint month boundary tick on the left edge */}
                <line
                  x1={x}
                  y1="40"
                  x2={x}
                  y2="80"
                  stroke={
                    isQuarterBoundary ? "var(--border)" : "var(--grid-color)"
                  }
                  strokeWidth={isQuarterBoundary ? "1.5" : "0.5"}
                />
                {width > 18 && (
                  <text
                    x={x + width / 2}
                    y="64"
                    textAnchor="middle"
                    fontSize="11"
                    fontWeight="600"
                    fill="var(--text-muted)"
                  >
                    {format(mStart, "MMM")}
                  </text>
                )}
              </g>
            );
          })}
        </g>
      </svg>
    );
  }

  // Default: Month + Week
  const months = eachMonthOfInterval({
    start: dateRange.start,
    end: rangeEnd,
  });

  return (
    <svg
      width={totalWidth}
      height={headerHeight}
      className="sticky top-0 bg-surface/50 border-b border-border z-10"
    >
      {/* Month headers */}
      <g>
        {months.map((month, idx) => {
          const monthStart = new Date(month.getFullYear(), month.getMonth(), 1);
          const monthEnd = new Date(
            month.getFullYear(),
            month.getMonth() + 1,
            0
          );

          const adjustedStart =
            monthStart < dateRange.start ? dateRange.start : monthStart;
          const adjustedEnd = monthEnd > rangeEnd ? rangeEnd : monthEnd;

          const startOffset = differenceInDays(adjustedStart, dateRange.start);
          const endOffset = differenceInDays(adjustedEnd, dateRange.start);
          const width = (endOffset - startOffset + 1) * pixelsPerDay;
          const x = startOffset * pixelsPerDay;

          return (
            <g key={`month-${idx}`}>
              <rect
                x={x}
                y="0"
                width={width}
                height="40"
                fill="var(--surface-hover)"
                stroke="var(--border)"
                strokeWidth="1"
              />
              <text
                x={x + width / 2}
                y="28"
                textAnchor="middle"
                fontSize="13"
                fontWeight="bold"
                fill="var(--text-secondary)"
              >
                {format(month, "MMM yyyy")}
              </text>
            </g>
          );
        })}
      </g>

      {/* Week/Day markers */}
      <g>
        {Array.from({ length: dateRange.days }).map((_, i) => {
          const date = addDays(dateRange.start, i);
          const x = i * pixelsPerDay;
          const dayOfWeek = date.getDay();
          const isMonday = dayOfWeek === 1;

          return (
            <g key={`day-${i}`}>
              {isMonday && (
                <text
                  x={x + 5}
                  y="72"
                  fontSize="11"
                  fill="var(--text-muted)"
                  fontWeight="600"
                >
                  W{Math.ceil((i + 1) / 7)}
                </text>
              )}
              <line
                x1={x}
                y1="40"
                x2={x}
                y2="80"
                stroke={isMonday ? "var(--text-muted)" : "var(--border)"}
                strokeWidth={isMonday ? "1" : "0.5"}
              />
            </g>
          );
        })}
      </g>
    </svg>
  );
}

import { differenceInDays, startOfDay } from "date-fns";
import type { ActivityResponse } from "@/lib/types";

export interface GanttDateRange {
  start: Date;
  end: Date;
  days: number;
}

export interface BarRect {
  x: number;
  width: number;
}

/**
 * Horizontal geometry of an activity bar within the chart's date range.
 * Returns null when the activity has no usable dates.
 */
export function getActivityBarRect(
  activity: ActivityResponse,
  dateRange: GanttDateRange,
  pixelsPerDay: number
): BarRect | null {
  const startStr = activity.plannedStartDate || activity.earlyStartDate;
  const endStr = activity.plannedFinishDate || activity.earlyFinishDate;
  if (!startStr || !endStr) return null;

  const start = startOfDay(new Date(startStr));
  const end = startOfDay(new Date(endStr));
  const startOffset = Math.max(0, differenceInDays(start, dateRange.start));
  const endOffset = differenceInDays(end, dateRange.start);
  const duration = Math.max(1, endOffset - startOffset + 1);

  return {
    x: startOffset * pixelsPerDay,
    width: duration * pixelsPerDay,
  };
}

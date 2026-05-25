"use client";

import React, { useState, useCallback, useRef } from "react";
import { differenceInDays, startOfDay, addDays, format } from "date-fns";
import type { ActivityResponse } from "@/lib/types";
import { getGanttStatus, getGanttStatusToken } from "@/lib/utils/ganttStatus";

interface DateRange {
  start: Date;
  end: Date;
  days: number;
}

interface BaselineActivityData {
  activityId: string;
  baselineStartDate: string | null;
  baselineFinishDate: string | null;
}

interface GanttTaskRowProps {
  activity: ActivityResponse;
  dateRange: DateRange;
  pixelsPerDay: number;
  rowIndex: number;
  rowHeight: number;
  timelineStartY: number;
  baselineData?: BaselineActivityData;
  onActivityClick?: (id: string) => void;
  onActivityContextMenu?: (id: string, x: number, y: number) => void;
  onActivityReschedule?: (
    id: string,
    newStart: string,
    newEnd: string
  ) => void;
}

export function GanttTaskRow({
  activity,
  dateRange,
  pixelsPerDay,
  rowIndex,
  rowHeight,
  timelineStartY,
  baselineData,
  onActivityClick,
  onActivityContextMenu,
  onActivityReschedule,
}: GanttTaskRowProps) {
  const [dragState, setDragState] = useState<{
    dragging: boolean;
    startMouseX: number;
    originalStartOffset: number;
    currentDeltaDays: number;
  } | null>(null);

  const dragRef = useRef(dragState);

  // Sync ref with state in useEffect to avoid mutation during render
  React.useEffect(() => {
    dragRef.current = dragState;
  }, [dragState]);

  const startStr = activity.plannedStartDate || activity.earlyStartDate;
  const endStr = activity.plannedFinishDate || activity.earlyFinishDate;
  const actStartDate = startStr ? startOfDay(new Date(startStr)) : null;
  const actEndDate = endStr ? startOfDay(new Date(endStr)) : null;

  const startOffset = actStartDate
    ? Math.max(0, differenceInDays(actStartDate, dateRange.start))
    : 0;

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      if (!onActivityReschedule || !actStartDate || !actEndDate) return;
      e.preventDefault();
      e.stopPropagation();

      const state = {
        dragging: true,
        startMouseX: e.clientX,
        originalStartOffset: startOffset,
        currentDeltaDays: 0,
      };
      setDragState(state);
      dragRef.current = state;

      const handleMouseMove = (moveEvent: MouseEvent) => {
        const current = dragRef.current;
        if (!current?.dragging) return;
        const dx = moveEvent.clientX - current.startMouseX;
        const daysDelta = Math.round(dx / pixelsPerDay);
        const newState = { ...current, currentDeltaDays: daysDelta };
        setDragState(newState);
        dragRef.current = newState;
      };

      const handleMouseUp = () => {
        const current = dragRef.current;
        if (current?.dragging && current.currentDeltaDays !== 0) {
          const newStart = addDays(actStartDate, current.currentDeltaDays);
          const newEnd = addDays(actEndDate, current.currentDeltaDays);
          onActivityReschedule(
            activity.id,
            format(newStart, "yyyy-MM-dd"),
            format(newEnd, "yyyy-MM-dd")
          );
        }
        setDragState(null);
        dragRef.current = null;
        document.removeEventListener("mousemove", handleMouseMove);
        document.removeEventListener("mouseup", handleMouseUp);
      };

      document.addEventListener("mousemove", handleMouseMove);
      document.addEventListener("mouseup", handleMouseUp);
    },
    [
      onActivityReschedule,
      pixelsPerDay,
      startOffset,
      actStartDate,
      actEndDate,
      activity.id,
    ]
  );

  if (!actStartDate || !actEndDate) {
    return null;
  }

  const endOffset = differenceInDays(actEndDate, dateRange.start);
  const duration = Math.max(1, endOffset - startOffset + 1);

  const deltaDays = dragState?.currentDeltaDays ?? 0;
  const x = (startOffset + deltaDays) * pixelsPerDay;
  const width = duration * pixelsPerDay;
  const y = rowIndex * rowHeight + timelineStartY;

  // Bar color: status-driven, with critical-path override for non-completed bars.
  const displayStatus = getGanttStatus(activity);
  const token = getGanttStatusToken(displayStatus);
  const isCriticalOverride =
    activity.totalFloat === 0 && activity.status !== "COMPLETED";
  const fillVar = isCriticalOverride ? "--danger" : token.fillVar;
  const fillColor = `var(${fillVar})`;

  const barHeight = rowHeight - 4;

  const percentComplete = Math.max(
    0,
    Math.min(100, activity.percentComplete || 0)
  );
  const completeWidth = (width * percentComplete) / 100;

  // Render baseline bar if available
  let baselineBarElements: React.ReactNode = null;
  if (baselineData?.baselineStartDate && baselineData?.baselineFinishDate) {
    const baselineStartDate = startOfDay(
      new Date(baselineData.baselineStartDate)
    );
    const baselineEndDate = startOfDay(
      new Date(baselineData.baselineFinishDate)
    );
    const baselineStartOffset = Math.max(
      0,
      differenceInDays(baselineStartDate, dateRange.start)
    );
    const baselineEndOffset = differenceInDays(
      baselineEndDate,
      dateRange.start
    );
    const baselineDuration = Math.max(
      1,
      baselineEndOffset - baselineStartOffset + 1
    );
    const baselineX = baselineStartOffset * pixelsPerDay;
    const baselineWidth = baselineDuration * pixelsPerDay;

    baselineBarElements = (
      <>
        <rect
          x={baselineX}
          y={y + barHeight + 4}
          width={baselineWidth}
          height={6}
          fill="var(--text-muted)"
          opacity="0.5"
          rx="1"
        />
        <title>
          Baseline: {baselineData.baselineStartDate} to{" "}
          {baselineData.baselineFinishDate}
        </title>
      </>
    );
  }

  const isDragging = dragState?.dragging ?? false;
  const cursorClass = onActivityReschedule
    ? isDragging
      ? "cursor-grabbing"
      : "cursor-grab"
    : "cursor-pointer";

  return (
    <g key={`task-${activity.id}`}>
      {/* Baseline bar */}
      {baselineBarElements}

      {/* Translucent base bar (full planned duration) — also the click/drag surface */}
      <rect
        x={x}
        y={y + 2}
        width={width}
        height={barHeight}
        fill={fillColor}
        opacity={isDragging ? 0.18 : 0.25}
        rx="3"
        className={`${cursorClass} hover:opacity-40 transition-opacity`}
        onMouseDown={handleMouseDown}
        onClick={() => {
          if (!isDragging) {
            onActivityClick?.(activity.id);
          }
        }}
        onContextMenu={(e) => {
          e.preventDefault();
          onActivityContextMenu?.(activity.id, e.clientX, e.clientY);
        }}
      />

      {/* Saturated overlay sized to percentComplete */}
      {percentComplete > 0 && (
        <rect
          x={x}
          y={y + 2}
          width={completeWidth}
          height={barHeight}
          fill={fillColor}
          opacity={isDragging ? 0.7 : 1}
          rx="3"
          className="pointer-events-none"
        />
      )}

      {/* Activity label */}
      {width > 50 && (
        <text
          x={x + 4}
          y={y + rowHeight / 2 + 4}
          fontSize="11"
          fill="white"
          fontWeight="600"
          className="pointer-events-none select-none"
        >
          {activity.code}
        </text>
      )}

      {/* Drag indicator - show new dates while dragging */}
      {isDragging && deltaDays !== 0 && (
        <text
          x={x + width + 6}
          y={y + rowHeight / 2 + 4}
          fontSize="10"
          fill="var(--text-muted)"
          className="pointer-events-none select-none"
        >
          {deltaDays > 0 ? "+" : ""}
          {deltaDays}d
        </text>
      )}

      {/* Tooltip on hover */}
      <title>
        {activity.name} | {activity.code} | {percentComplete}% complete | Float:{" "}
        {activity.totalFloat}d
        {onActivityReschedule ? " | Drag to reschedule" : ""}
      </title>
    </g>
  );
}

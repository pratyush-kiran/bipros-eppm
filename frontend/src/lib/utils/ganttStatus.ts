import { startOfDay } from "date-fns";
import type { ActivityResponse } from "@/lib/types";

export type GanttDisplayStatus =
  | "DONE"
  | "IN_PROGRESS_NOW"
  | "DELAYED"
  | "PLANNED";

export function getGanttStatus(
  activity: ActivityResponse,
  today: Date = startOfDay(new Date())
): GanttDisplayStatus {
  if (activity.status === "COMPLETED") return "DONE";
  if (activity.status === "IN_PROGRESS") {
    const finishStr =
      activity.plannedFinishDate ?? activity.earlyFinishDate ?? null;
    if (finishStr && startOfDay(new Date(finishStr)) < today) {
      return "DELAYED";
    }
    return "IN_PROGRESS_NOW";
  }
  return "PLANNED";
}

export interface GanttStatusToken {
  /** CSS var name (with leading "--") used as `var(--…)` for bar fills. */
  fillVar: string;
  /** StatusBadge `status` key — must exist in StatusBadge's statusStyles map. */
  badgeStatus: "DONE" | "IN_PROGRESS_NOW" | "DELAYED" | "PLANNED";
  /** Pill label, already uppercase. */
  label: string;
}

export function getGanttStatusToken(
  status: GanttDisplayStatus
): GanttStatusToken {
  switch (status) {
    case "DONE":
      return { fillVar: "--success", badgeStatus: "DONE", label: "DONE" };
    case "IN_PROGRESS_NOW":
      return {
        fillVar: "--accent",
        badgeStatus: "IN_PROGRESS_NOW",
        label: "IN PROGRESS",
      };
    case "DELAYED":
      return { fillVar: "--danger", badgeStatus: "DELAYED", label: "DELAYED" };
    case "PLANNED":
      return { fillVar: "--info", badgeStatus: "PLANNED", label: "PLANNED" };
  }
}

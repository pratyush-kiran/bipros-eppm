import type { ActivityResponse } from "@/lib/types";
import type { WbsNodeInfo } from "@/lib/utils/wbs";

export type GanttRow =
  | { kind: "group"; groupId: string; label: string; ordinal: number }
  | { kind: "activity"; activity: ActivityResponse; groupId: string };

const UNGROUPED_ID = "__ungrouped__";
const UNGROUPED_LABEL = "Ungrouped";

interface Bucket {
  groupId: string;
  label: string;
  sortOrder: number;
  activities: ActivityResponse[];
}

export function buildGanttRows(
  activities: ActivityResponse[],
  wbsNameById: Map<string, WbsNodeInfo>
): GanttRow[] {
  const buckets = new Map<string, Bucket>();

  for (const activity of activities) {
    const wbsId = activity.wbsNodeId || UNGROUPED_ID;
    const info = wbsNameById.get(wbsId);
    let bucket = buckets.get(wbsId);
    if (!bucket) {
      bucket = {
        groupId: wbsId,
        label:
          info?.name ?? (wbsId === UNGROUPED_ID ? UNGROUPED_LABEL : wbsId),
        sortOrder:
          info?.sortOrder ??
          (wbsId === UNGROUPED_ID ? Number.MAX_SAFE_INTEGER : 0),
        activities: [],
      };
      buckets.set(wbsId, bucket);
    }
    bucket.activities.push(activity);
  }

  const sorted = [...buckets.values()].sort((a, b) => {
    if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
    return a.label.localeCompare(b.label);
  });

  const rows: GanttRow[] = [];
  let ordinal = 0;
  for (const bucket of sorted) {
    ordinal += 1;
    rows.push({
      kind: "group",
      groupId: bucket.groupId,
      label: bucket.label,
      ordinal,
    });
    for (const activity of bucket.activities) {
      rows.push({
        kind: "activity",
        activity,
        groupId: bucket.groupId,
      });
    }
  }
  return rows;
}

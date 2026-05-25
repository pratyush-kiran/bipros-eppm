import { describe, expect, it } from "vitest";
import { groupByDayThenActivity } from "./groupByDayThenActivity";
import type { DprSummaryRow } from "@/lib/types/dpr";

const row = (over: Partial<DprSummaryRow>): DprSummaryRow => ({
  id: Math.random().toString(36).slice(2),
  projectId: "p",
  reportDate: "2026-03-10",
  supervisorName: "Ravi",
  activityName: "Earthworks",
  unit: "Cum",
  qtyExecuted: 0,
  manpowerNos: 0,
  equipmentNos: 0,
  materialCount: 0,
  photoCount: 0,
  issueCount: 0,
  openIssueCount: 0,
  hasCriticalOpen: false,
  ...over,
});

describe("groupByDayThenActivity", () => {
  it("groups by day (newest first) then activity, summing qty and sorting fronts by chainage", () => {
    const days = groupByDayThenActivity([
      row({ reportDate: "2026-03-09", boqItemNo: "1.1", chainageFromM: 200, qtyExecuted: 5 }),
      row({ reportDate: "2026-03-10", boqItemNo: "1.1", chainageFromM: 100, qtyExecuted: 10 }),
      row({ reportDate: "2026-03-10", boqItemNo: "1.1", chainageFromM: 50, qtyExecuted: 20 }),
    ]);

    expect(days.map((d) => d.date)).toEqual(["2026-03-10", "2026-03-09"]);
    const mar10 = days[0].activityGroups[0];
    expect(mar10.totalQty).toBe(30);
    expect(mar10.rows.map((r) => r.chainageFromM)).toEqual([50, 100]);
  });

  it("keeps distinct activities on the same day as separate groups", () => {
    const days = groupByDayThenActivity([
      row({ boqItemNo: "1.1", activityName: "Earthworks" }),
      row({ boqItemNo: "2.3", activityName: "Paving" }),
    ]);
    expect(days[0].activityGroups).toHaveLength(2);
  });
});

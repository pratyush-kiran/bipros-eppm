import type { DprSummaryRow } from "@/lib/types/dpr";

export interface ActivityGroup {
  key: string;
  boqItemNo: string | null;
  activityName: string;
  unit: string;
  totalQty: number;
  uniqueSupervisors: Array<{ id: string; name: string }>;
  rows: DprSummaryRow[]; // one per work-front, sorted by chainage asc
}

export interface DayGroup {
  date: string;
  weather: string | null;
  supervisorCount: number;
  totalActivities: number;
  activityGroups: ActivityGroup[];
}

const activityKey = (row: DprSummaryRow): string =>
  row.boqItemNo
    ? `boq:${row.boqItemNo}`
    : row.activityId
      ? `aid:${row.activityId}`
      : `name:${(row.activityName ?? "").toLowerCase()}`;

const compareByChainage = (a: DprSummaryRow, b: DprSummaryRow): number => {
  const ax = a.chainageFromM ?? Number.POSITIVE_INFINITY;
  const bx = b.chainageFromM ?? Number.POSITIVE_INFINITY;
  if (ax !== bx) return ax - bx;
  return (a.supervisorName ?? "").localeCompare(b.supervisorName ?? "");
};

export const groupByDayThenActivity = (rows: DprSummaryRow[]): DayGroup[] => {
  const byDay = new Map<string, DprSummaryRow[]>();
  for (const r of rows) {
    if (!r.reportDate) continue;
    const list = byDay.get(r.reportDate) ?? [];
    list.push(r);
    byDay.set(r.reportDate, list);
  }

  const days: DayGroup[] = [];
  for (const [date, dayRows] of byDay.entries()) {
    const byActivity = new Map<string, DprSummaryRow[]>();
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

  days.sort((a, b) => b.date.localeCompare(a.date));
  return days;
};

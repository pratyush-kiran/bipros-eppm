"use client";
import { useQuery } from "@tanstack/react-query";
import { dprApi } from "@/lib/api/dprApi";
import { dailyWeatherApi } from "@/lib/api/dailyWeatherApi";
import { CardShell } from "./CardShell";
import { Sparkline } from "@/components/hub/mission-control/primitives/Sparkline";
import { Skeleton, StatusPill, cardQueryOpts, todayIso, isoDaysAgo } from "./_shared";

/**
 * Execute card — what's happening on the ground today. Three mini-tiles:
 *  1. DPRs logged in the last 30 days (rolling)
 *  2. Manpower headcount on today's DPR (if logged)
 *  3. Today's weather snapshot (if a row exists)
 *
 * Status pill flips to {@code Today logged} / {@code Pending today} based on
 * whether a DPR exists for {@link todayIso}. Shares the today-DPR query key
 * with {@code useHubBadges}.
 */
export function ExecuteCard({ projectId }: { projectId: string }) {
  const today = todayIso();
  const thirtyDaysAgo = isoDaysAgo(30);

  // Same key as useHubBadges → cache shared
  const dprTodayQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "dpr", today],
    queryFn: () => dprApi.list(projectId, { from: today, to: today, days: 1 }),
    ...cardQueryOpts,
  });

  // Card-scoped key — separate window
  const dpr30dQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "execute", "dpr-30d", thirtyDaysAgo, today],
    queryFn: () =>
      dprApi.list(projectId, { from: thirtyDaysAgo, to: today, days: 31 }),
    ...cardQueryOpts,
  });

  const weatherQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "execute", "weather-latest"],
    queryFn: () => dailyWeatherApi.list(projectId),
    ...cardQueryOpts,
  });

  const dprTodayItems = dprTodayQuery.data?.data?.items ?? [];
  const loggedToday = dprTodayItems.some((it) => it.reportDate === today);

  // Sum manpower from today's DPR rows (one row per activity → sum across rows).
  const manpowerToday = dprTodayItems
    .filter((it) => it.reportDate === today)
    .reduce((acc, it) => acc + (it.manpowerNos ?? 0), 0);

  // 30-day distinct days with at least one DPR
  const dpr30Items = dpr30dQuery.data?.data?.items ?? [];
  const dprDays30: number | undefined = dpr30dQuery.data
    ? new Set(dpr30Items.map((it) => it.reportDate)).size
    : undefined;

  // Per-day manpower series — sum manpowerNos per reportDate, sorted ascending.
  const manpowerSeries: number[] = (() => {
    const byDay = new Map<string, number>();
    for (const it of dpr30Items) {
      if (!it.reportDate) continue;
      byDay.set(it.reportDate, (byDay.get(it.reportDate) ?? 0) + (it.manpowerNos ?? 0));
    }
    return [...byDay.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([, sum]) => sum);
  })();

  // Latest weather entry — sort by logDate desc.
  const weatherRows = weatherQuery.data?.data ?? [];
  const latestWeather = [...weatherRows].sort((a, b) =>
    (b.logDate ?? "").localeCompare(a.logDate ?? ""),
  )[0];
  const weatherIsToday = latestWeather?.logDate === today;

  const isLoading =
    dprTodayQuery.isLoading || dpr30dQuery.isLoading || weatherQuery.isLoading;

  const statusPill = dprTodayQuery.data ? (
    loggedToday ? (
      <StatusPill tone="success">Today logged</StatusPill>
    ) : (
      <StatusPill tone="warn">Pending today</StatusPill>
    )
  ) : undefined;

  return (
    <CardShell
      groupId="execute"
      title="Execute"
      subtitle="Day-by-day field execution"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={isLoading}
    >
      {isLoading ? (
        <div className="grid grid-cols-3 gap-2">
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
        </div>
      ) : (
        <div className="space-y-2.5">
        <div className="grid grid-cols-3 gap-2">
          {dprDays30 !== undefined ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                DPRs · 30d
              </div>
              <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                {dprDays30}
              </div>
            </div>
          ) : null}

          {loggedToday && manpowerToday > 0 ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Manpower today
              </div>
              <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                {manpowerToday}
              </div>
            </div>
          ) : null}

          {latestWeather ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                {weatherIsToday ? "Weather today" : "Last weather"}
              </div>
              <div className="mt-1 text-sm text-text-primary">
                {latestWeather.tempMaxC != null ? (
                  <span className="font-mono tabular-nums">
                    {Math.round(latestWeather.tempMaxC)}°C
                  </span>
                ) : null}
                {latestWeather.weatherCondition ? (
                  <span className="ml-1 text-text-secondary text-xs">
                    {latestWeather.weatherCondition}
                  </span>
                ) : null}
              </div>
            </div>
          ) : null}
        </div>

          {manpowerSeries.length >= 2 ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="flex items-center justify-between">
                <span className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                  Manpower · 30d
                </span>
                <span className="font-mono text-[10px] text-text-secondary tabular-nums">
                  peak {Math.max(...manpowerSeries)}
                </span>
              </div>
              <div className="mt-1 w-full [&>svg]:h-10 [&>svg]:w-full">
                <Sparkline
                  values={manpowerSeries}
                  width={300}
                  height={40}
                  stroke="var(--amber-flame)"
                  fill="rgba(224,122,31,0.12)"
                />
              </div>
            </div>
          ) : null}
        </div>
      )}
    </CardShell>
  );
}

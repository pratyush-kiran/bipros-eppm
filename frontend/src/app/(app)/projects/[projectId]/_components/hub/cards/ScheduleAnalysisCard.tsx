"use client";
import { useQuery } from "@tanstack/react-query";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { MetricNumber } from "@/components/hub/mission-control/primitives/MetricNumber";
import { CardShell } from "./CardShell";
import { Skeleton, StatusPill, cardQueryOpts } from "./_shared";

/**
 * Schedule Analysis card — three mini-tiles fed by the schedule-quality
 * endpoint: overall health %, missing-logic count, hard-constraint count.
 *
 * If the endpoint returns nothing the body collapses to chips only — we never
 * fabricate.
 */
export function ScheduleAnalysisCard({ projectId }: { projectId: string }) {
  const qualityQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "schedule", "quality"],
    queryFn: () => projectInsightsApi.getScheduleQuality(projectId),
    ...cardQueryOpts,
  });

  const quality = qualityQuery.data;
  const healthPct = quality?.overallHealthPct;

  // Health ring geometry — 96px box, 8px stroke.
  const RING = 96;
  const STROKE = 8;
  const R = (RING - STROKE) / 2;
  const C = 2 * Math.PI * R;
  const ringPct = healthPct != null ? Math.max(0, Math.min(100, healthPct)) : 0;

  // BEI comparison — normalise both bars against the larger value so the
  // required marker reads as a fair scale.
  const beiActual = quality?.beiActual;
  const beiRequired = quality?.beiRequired;
  const beiMax =
    beiActual != null && beiRequired != null
      ? Math.max(beiActual, beiRequired, 0.0001)
      : null;

  const statusPill =
    healthPct != null ? (
      <StatusPill
        tone={healthPct >= 85 ? "success" : healthPct >= 70 ? "warn" : "danger"}
      >
        Health {Math.round(healthPct)}%
      </StatusPill>
    ) : undefined;

  return (
    <CardShell
      groupId="schedule"
      title="Schedule Analysis"
      subtitle="How the network holds together"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={qualityQuery.isLoading}
    >
      {qualityQuery.isLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Skeleton className="h-24" />
          <div className="grid grid-cols-3 gap-2">
            <Skeleton className="h-14" />
            <Skeleton className="h-14" />
            <Skeleton className="h-14" />
          </div>
        </div>
      ) : quality ? (
        <div className="grid grid-cols-1 items-center gap-4 sm:grid-cols-2">
          {/* Left — schedule-health ring */}
          {healthPct != null ? (
            <div className="flex items-center justify-center">
              <div className="relative" style={{ width: RING, height: RING }}>
                <svg
                  width={RING}
                  height={RING}
                  viewBox={`0 0 ${RING} ${RING}`}
                  aria-hidden
                  className="-rotate-90"
                >
                  <circle
                    cx={RING / 2}
                    cy={RING / 2}
                    r={R}
                    fill="none"
                    stroke="var(--parchment)"
                    strokeWidth={STROKE}
                  />
                  <circle
                    cx={RING / 2}
                    cy={RING / 2}
                    r={R}
                    fill="none"
                    stroke="#8b5cf6"
                    strokeWidth={STROKE}
                    strokeLinecap="round"
                    strokeDasharray={C}
                    strokeDashoffset={C * (1 - ringPct / 100)}
                    className="motion-safe:transition-[stroke-dashoffset] motion-safe:duration-700"
                  />
                </svg>
                <div className="absolute inset-0 flex flex-col items-center justify-center">
                  <MetricNumber
                    value={Math.round(healthPct)}
                    format={(n) => `${Math.round(n)}%`}
                    className="font-mono text-2xl text-text-primary tabular-nums leading-none"
                  />
                  <span className="mt-0.5 text-[10px] uppercase tracking-[0.1em] text-text-muted">
                    health
                  </span>
                </div>
              </div>
            </div>
          ) : null}

          {/* Right — stat tiles + BEI bar */}
          <div className="space-y-2.5">
            <div className="grid grid-cols-3 gap-2">
              {quality.missingLogicCount != null ? (
                <div className="rounded-lg border border-border bg-ivory p-2.5">
                  <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                    Logic gaps
                  </div>
                  <div
                    className={`mt-1 font-mono text-lg tabular-nums ${
                      quality.missingLogicCount > 0 ? "text-burgundy" : "text-emerald"
                    }`}
                  >
                    {quality.missingLogicCount}
                  </div>
                </div>
              ) : null}
              {quality.hardConstraintsCount != null ? (
                <div className="rounded-lg border border-border bg-ivory p-2.5">
                  <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                    Hard constr.
                  </div>
                  <div
                    className={`mt-1 font-mono text-lg tabular-nums ${
                      quality.hardConstraintsCount > 0 ? "text-burgundy" : "text-emerald"
                    }`}
                  >
                    {quality.hardConstraintsCount}
                  </div>
                </div>
              ) : null}
              {quality.criticalPathLength != null ? (
                <div className="rounded-lg border border-border bg-ivory p-2.5">
                  <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                    Crit. path
                  </div>
                  <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                    {quality.criticalPathLength}
                  </div>
                </div>
              ) : null}
            </div>

            {beiActual != null && beiRequired != null && beiMax != null ? (
              <div className="rounded-lg border border-border bg-ivory p-2.5">
                <div className="flex items-center justify-between text-[10px] uppercase tracking-[0.1em] text-text-muted">
                  <span>BEI</span>
                  <span className="font-mono tabular-nums">
                    <span
                      className={
                        beiActual >= beiRequired ? "text-emerald" : "text-burgundy"
                      }
                    >
                      {beiActual.toFixed(2)}
                    </span>
                    <span className="text-text-muted"> / {beiRequired.toFixed(2)}</span>
                  </span>
                </div>
                <div className="relative mt-2 h-2 rounded-full bg-parchment dark:bg-white/10 overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      beiActual >= beiRequired ? "bg-emerald" : "bg-burgundy"
                    } motion-safe:transition-[width] motion-safe:duration-700`}
                    style={{ width: `${Math.max(0, Math.min(100, (beiActual / beiMax) * 100))}%` }}
                  />
                  {/* required threshold marker */}
                  <div
                    aria-hidden
                    className="absolute inset-y-0 w-0.5 bg-text-secondary"
                    style={{ left: `${Math.max(0, Math.min(100, (beiRequired / beiMax) * 100))}%` }}
                  />
                </div>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </CardShell>
  );
}

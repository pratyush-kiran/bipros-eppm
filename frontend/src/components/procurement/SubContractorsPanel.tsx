"use client";

import { Fragment, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";
import { activityApi } from "@/lib/api/activityApi";
import { procurementApi } from "@/lib/api/procurementApi";
import { EmptyState } from "@/components/common/EmptyState";
import { PageHeader } from "@/components/common/PageHeader";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { cn } from "@/lib/utils/cn";

export function buildActivityNameMap(
  activities: { id: string; name: string }[] | undefined,
): Map<string, string> {
  const map = new Map<string, string>();
  for (const a of activities ?? []) map.set(a.id, a.name);
  return map;
}

export function SubContractorsPanel({ projectId }: { projectId: string }) {
  const { money } = useProjectCurrency();
  const [expanded, setExpanded] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["procurement-subcontractors", projectId],
    queryFn: () => procurementApi.subContractors(projectId),
    enabled: !!projectId,
  });
  const rows = data?.data ?? [];

  const { data: activitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 1000),
    enabled: !!projectId,
  });
  const activityNames = buildActivityNameMap(activitiesData?.data?.content);

  return (
    <>
      <PageHeader
        title="Sub-contractors"
        description="Planned vs actual cost by sub-contractor across this project's activity assignments."
      />

      {isLoading ? (
        <div className="text-sm text-text-muted">Loading…</div>
      ) : rows.length === 0 ? (
        <EmptyState
          title="No sub-contractors engaged"
          description="Sub-contractor assignments on this project's activities will appear here."
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface-hover text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
              <tr>
                <th className="px-4 py-2">Sub-contractor</th>
                <th className="px-4 py-2 text-right">Assignments</th>
                <th className="px-4 py-2 text-right">Planned</th>
                <th className="px-4 py-2 text-right">Actual</th>
                <th className="px-4 py-2 text-right">Variance</th>
                <th className="px-4 py-2 text-right">% done</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const isOpen = expanded === row.subContractorMasterId;
                return (
                  <Fragment key={row.subContractorMasterId}>
                    <tr
                      className="cursor-pointer border-t border-border hover:bg-surface-hover/50"
                      onClick={() =>
                        setExpanded(isOpen ? null : row.subContractorMasterId)
                      }
                    >
                      <td className="px-4 py-2 font-medium text-text-primary">
                        <span className="flex items-center gap-1.5">
                          {isOpen ? (
                            <ChevronDown className="h-4 w-4 text-text-muted" />
                          ) : (
                            <ChevronRight className="h-4 w-4 text-text-muted" />
                          )}
                          <span>
                            {row.name}
                            <span className="ml-2 text-xs text-text-secondary">
                              {row.code}
                            </span>
                          </span>
                        </span>
                      </td>
                      <td className="px-4 py-2 text-right text-text-secondary">
                        {row.assignmentCount}
                      </td>
                      <td className="px-4 py-2 text-right text-text-primary">
                        {money(row.plannedCost)}
                      </td>
                      <td className="px-4 py-2 text-right text-text-primary">
                        {money(row.actualCost)}
                      </td>
                      <td
                        className={cn(
                          "px-4 py-2 text-right font-medium",
                          row.costVariance >= 0 ? "text-success" : "text-danger",
                        )}
                      >
                        {money(row.costVariance)}
                      </td>
                      <td className="px-4 py-2 text-right text-text-secondary">
                        {row.percentComplete.toFixed(1)}%
                      </td>
                    </tr>
                    {isOpen && (
                      <tr className="border-t border-border bg-surface/40">
                        <td colSpan={6} className="px-4 py-3">
                          {row.lines.length === 0 ? (
                            <p className="text-sm text-text-muted">
                              No assignment lines.
                            </p>
                          ) : (
                            <table className="w-full text-xs">
                              <thead className="text-left uppercase tracking-wide text-text-muted">
                                <tr>
                                  <th className="px-2 py-1">Activity</th>
                                  <th className="px-2 py-1">Work Type</th>
                                  <th className="px-2 py-1">Unit</th>
                                  <th className="px-2 py-1 text-right">
                                    Planned Units
                                  </th>
                                  <th className="px-2 py-1 text-right">
                                    Planned Cost
                                  </th>
                                  <th className="px-2 py-1 text-right">
                                    Actual Units
                                  </th>
                                  <th className="px-2 py-1 text-right">
                                    Actual Cost
                                  </th>
                                </tr>
                              </thead>
                              <tbody>
                                {row.lines.map((line, i) => (
                                  <tr
                                    key={`${line.activityId}-${i}`}
                                    className="border-t border-hairline"
                                  >
                                    <td className="px-2 py-1 text-text-primary">
                                      {activityNames.get(line.activityId) ?? "—"}
                                    </td>
                                    <td className="px-2 py-1 text-text-secondary">
                                      {line.workTypeName}
                                    </td>
                                    <td className="px-2 py-1 text-text-secondary">
                                      {line.unit}
                                    </td>
                                    <td className="px-2 py-1 text-right text-text-secondary">
                                      {line.plannedUnits}
                                    </td>
                                    <td className="px-2 py-1 text-right text-text-primary">
                                      {money(line.plannedCost)}
                                    </td>
                                    <td className="px-2 py-1 text-right text-text-secondary">
                                      {line.actualUnits}
                                    </td>
                                    <td className="px-2 py-1 text-right text-text-primary">
                                      {money(line.actualCost)}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

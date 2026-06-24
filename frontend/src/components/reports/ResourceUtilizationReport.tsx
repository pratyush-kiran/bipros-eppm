"use client";

import { useMemo } from "react";
import { Users } from "lucide-react";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { Progress } from "@/components/ui/progress";
import type { ResourceUtilizationData, ResourceUtilRow } from "@/lib/api/reportDataApi";

interface ResourceUtilizationReportProps {
  data: ResourceUtilizationData;
}

export function ResourceUtilizationReport({ data }: ResourceUtilizationReportProps) {
  const utilizationStatus = useMemo(() => {
    if (data.avgUtilization >= 80) return { status: "Optimal", color: "bg-success/10 text-success" };
    if (data.avgUtilization >= 60) return { status: "Good", color: "bg-accent/10 text-accent" };
    if (data.avgUtilization >= 40) return { status: "Fair", color: "bg-warning/10 text-warning" };
    return { status: "Low", color: "bg-danger/10 text-danger" };
  }, [data.avgUtilization]);

  const resourcesByType = useMemo(() => {
    const types = new Map<string, number>();
    data.resources.forEach((r) => {
      types.set(r.type, (types.get(r.type) || 0) + 1);
    });
    return Array.from(types.entries()).map(([type, count]) => ({ type, count }));
  }, [data.resources]);

  const columns = useMemo<ColumnDef<ResourceUtilRow>[]>(
    () => [
      {
        accessorKey: "code",
        header: "Code",
        cell: (info) => (
          <span className="font-mono text-text-primary">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "name",
        header: "Name",
        cell: (info) => (
          <span className="text-text-secondary">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "type",
        header: "Type",
        cell: (info) => (
          <span className="text-text-secondary">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "plannedHours",
        header: "Planned Hours",
        cell: (info) => (
          <span className="block text-right text-text-secondary">
            {Number(info.getValue()).toFixed(1)}
          </span>
        ),
      },
      {
        accessorKey: "actualHours",
        header: "Actual Hours",
        cell: (info) => (
          <span className="block text-right text-text-secondary">
            {Number(info.getValue()).toFixed(1)}
          </span>
        ),
      },
      {
        accessorKey: "utilPct",
        header: "Utilization",
        cell: (info) => {
          const val = Number(info.getValue());
          return (
            <div className="flex items-center justify-center gap-2">
              <Progress value={val} className="h-2 w-24" />
              <span
                className={`text-xs font-semibold ${
                  val >= 80
                    ? "text-success"
                    : val >= 60
                    ? "text-accent"
                    : "text-orange-600"
                }`}
              >
                {val.toFixed(0)}%
              </span>
            </div>
          );
        },
      },
    ],
    []
  );

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="flex justify-between items-start">
          <div>
            <h3 className="font-semibold text-lg text-text-primary">{data.projectName}</h3>
            <p className="text-sm text-text-secondary">Resource Utilization Analysis</p>
          </div>
          <div className={`px-3 py-1 rounded-full text-sm font-semibold ${utilizationStatus.color}`}>
            {utilizationStatus.status}
          </div>
        </div>
      </div>

      {/* Overall Utilization Gauge */}
      <div className="bg-surface/50 border border-border rounded-lg p-6">
        <div className="text-center mb-6">
          <p className="text-text-secondary mb-2">Average Utilization</p>
          <p className="text-5xl font-bold text-purple-600">{data.avgUtilization.toFixed(1)}%</p>
        </div>
        <Progress value={data.avgUtilization} className="h-4" />
        <div className="flex justify-between text-xs text-text-muted mt-2">
          <span>0%</span>
          <span>50%</span>
          <span>100%</span>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">Total Resources</p>
          <p className="text-3xl font-bold text-text-primary">{data.totalResources}</p>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">Fully Utilized</p>
          <p className="text-3xl font-bold text-success">
            {data.resources.filter((r) => r.utilPct >= 80).length}
          </p>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">Underutilized</p>
          <p className="text-3xl font-bold text-orange-600">
            {data.resources.filter((r) => r.utilPct < 60).length}
          </p>
        </div>
      </div>

      {/* Resources by Type */}
      {resourcesByType.length > 0 && (
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-4 flex items-center gap-2">
            <Users size={20} />
            Resources by Type
          </h4>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            {resourcesByType.map((rt, idx) => (
              <div key={idx} className="bg-surface/80 rounded-lg p-3 text-center">
                <p className="text-sm text-text-secondary">{rt.type}</p>
                <p className="text-2xl font-bold text-text-primary">{rt.count}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Resource Utilization Details */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-4">Individual Resource Utilization</h4>
        <VirtualDataTable
          columns={columns}
          data={data.resources}
          sortable
          resizable
          searchable={false}
        />
      </div>

      {/* Recommendations */}
      <div className="bg-accent/10 border border-accent/20 rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-3">Insights & Recommendations</h4>
        <ul className="space-y-2 text-sm text-text-secondary">
          {data.avgUtilization < 60 && (
            <li>• Average utilization is below optimal levels. Consider reallocating resources or reviewing project scope.</li>
          )}
          {data.resources.filter((r) => r.utilPct > 100).length > 0 && (
            <li>• {data.resources.filter((r) => r.utilPct > 100).length} resources are over-allocated. Consider hiring additional resources or extending timelines.</li>
          )}
          {data.resources.filter((r) => r.utilPct < 40).length > 0 && (
            <li>• {data.resources.filter((r) => r.utilPct < 40).length} resources are significantly underutilized. Consider redistributing work or releasing resources.</li>
          )}
          {data.avgUtilization >= 80 && (
            <li>• Resource utilization is at optimal levels. Maintain current staffing and allocation strategy.</li>
          )}
        </ul>
      </div>
    </div>
  );
}

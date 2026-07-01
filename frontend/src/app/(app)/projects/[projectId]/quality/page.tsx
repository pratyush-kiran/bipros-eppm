"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { activityApi } from "@/lib/api/activityApi";
import { qcApi } from "@/lib/api/qcApi";
import { TabTip } from "@/components/common/TabTip";
import { QcTestRecordList } from "@/components/qc/QcTestRecordList";
import { QcTestTypesTable } from "@/components/qc/QcTestTypesTable";
import { QcDashboard } from "@/components/qc/QcDashboard";
import { NcrsPanel } from "@/components/quality/NcrsPanel";
import { RfisPanel } from "@/components/quality/RfisPanel";
import { ChecklistsPanel } from "@/components/quality/ChecklistsPanel";
import { SnagsPanel } from "@/components/quality/SnagsPanel";
import { cn } from "@/lib/utils/cn";

type QualityTab = "test-reports" | "ncrs" | "rfis" | "inspections" | "punch-list";

export default function QualityPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const [tab, setTab] = useState<QualityTab>("test-reports");

  const { data: activitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 1000),
    enabled: !!projectId,
  });
  const activityOptions =
    activitiesData?.data?.content?.map((a) => ({ value: a.id, label: a.name })) ?? [];

  const { data: typesData } = useQuery({
    queryKey: ["qc-test-types", projectId],
    queryFn: () => qcApi.listTestTypes(projectId),
    enabled: !!projectId,
  });
  const testTypeOptions = typesData?.data ?? [];

  const [qcView, setQcView] = useState<"records" | "types" | "dashboard">("records");

  return (
    <div className="p-6">
      <TabTip
        title="Quality"
        description="Test reports, non-conformances, RFIs, inspections and the punch list for this project."
      />
      <h1 className="mb-4 font-display text-3xl font-bold text-charcoal">Quality</h1>

      <div className="mb-6 flex flex-wrap gap-2 border-b border-hairline">
        {([
          { id: "test-reports", label: "Test Reports" },
          { id: "ncrs", label: "NCRs" },
          { id: "rfis", label: "RFIs" },
          { id: "inspections", label: "Inspections" },
          { id: "punch-list", label: "Punch List" },
        ] as const).map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={cn(
              "cursor-pointer border-b-2 px-3 py-2 text-sm font-medium transition-colors",
              tab === t.id
                ? "border-accent text-accent"
                : "border-transparent text-text-secondary hover:border-border hover:text-text-primary"
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "test-reports" && (
        <div>
          <div className="mb-4 flex gap-2 border-b border-hairline">
            {([
              { id: "records", label: "Test Records" },
              { id: "types", label: "Test Types (Master)" },
              { id: "dashboard", label: "Dashboard" },
            ] as const).map((v) => (
              <button
                key={v.id}
                onClick={() => setQcView(v.id)}
                className={cn(
                  "cursor-pointer border-b-2 px-3 py-2 text-sm font-medium transition-colors",
                  qcView === v.id
                    ? "border-accent text-accent"
                    : "border-transparent text-text-secondary hover:border-border hover:text-text-primary"
                )}
              >
                {v.label}
              </button>
            ))}
          </div>
          {qcView === "records" && (
            <QcTestRecordList projectId={projectId} activityOptions={activityOptions} testTypeOptions={testTypeOptions} />
          )}
          {qcView === "types" && <QcTestTypesTable projectId={projectId} />}
          {qcView === "dashboard" && <QcDashboard projectId={projectId} activityOptions={activityOptions} />}
        </div>
      )}

      {tab === "ncrs" && <NcrsPanel projectId={projectId} />}
      {tab === "rfis" && <RfisPanel projectId={projectId} />}
      {tab === "inspections" && <ChecklistsPanel projectId={projectId} />}
      {tab === "punch-list" && <SnagsPanel projectId={projectId} />}
    </div>
  );
}

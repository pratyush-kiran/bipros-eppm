"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ChevronLeft } from "lucide-react";
import { activityApi } from "@/lib/api/activityApi";
import { qcApi } from "@/lib/api/qcApi";
import { TabTip } from "@/components/common/TabTip";
import { QcTestRecordList } from "@/components/qc/QcTestRecordList";
import { QcTestTypesTable } from "@/components/qc/QcTestTypesTable";
import { QcDashboard } from "@/components/qc/QcDashboard";
import { cn } from "@/lib/utils/cn";

export default function QcPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const [activeTab, setActiveTab] = useState<"records" | "types" | "dashboard">("records");

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

  return (
    <div className="p-6">
      <Link
        href="/qc"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate hover:text-charcoal"
      >
        <ChevronLeft className="h-3.5 w-3.5" />
        All projects
      </Link>

      <TabTip
        title="Quality Control"
        description="Log field and lab test results against activities. Compare against OHDS/AASHTO thresholds and track pass/fail/repeat outcomes."
      />
      <div className="mb-6">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h1 className="font-display text-3xl font-bold text-charcoal">Quality Control</h1>
        </div>
        <div className="mt-4 flex gap-2 border-b border-hairline">
          {([
            { id: "records", label: "Test Records" },
            { id: "types", label: "Test Types (Master)" },
            { id: "dashboard", label: "Dashboard" },
          ] as const).map((t) => (
            <button
              key={t.id}
              onClick={() => setActiveTab(t.id)}
              className={cn(
                "px-3 py-2 text-sm font-medium border-b-2 transition-colors cursor-pointer",
                activeTab === t.id
                  ? "border-accent text-accent"
                  : "border-transparent text-text-secondary hover:text-text-primary hover:border-border"
              )}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {activeTab === "records" && (
        <QcTestRecordList
          projectId={projectId}
          activityOptions={activityOptions}
          testTypeOptions={testTypeOptions}
        />
      )}
      {activeTab === "types" && <QcTestTypesTable projectId={projectId} />}
      {activeTab === "dashboard" && (
        <QcDashboard projectId={projectId} activityOptions={activityOptions} />
      )}
    </div>
  );
}

"use client";

import { Suspense, useState, useEffect } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { ArrowLeft, Trash2 } from "lucide-react";
import { riskApi, type UpdateRiskRequest } from "@/lib/api/riskApi";
import { getErrorMessage } from "@/lib/utils/error";
import { stripBasePath } from "@/lib/basePath";
import { RiskGeneralTab } from "@/components/risk/RiskGeneralTab";
import { RiskImpactTab } from "@/components/risk/RiskImpactTab";
import { RiskActivitiesTab } from "@/components/risk/RiskActivitiesTab";
import { RiskDescriptionTab } from "@/components/risk/RiskDescriptionTab";

type TabId = "general" | "impact" | "activities" | "description" | "cause" | "effect" | "notes";

const TABS: { id: TabId; label: string }[] = [
  { id: "general", label: "General" },
  { id: "impact", label: "Impact" },
  { id: "activities", label: "Activities" },
  { id: "description", label: "Description" },
  { id: "cause", label: "Cause" },
  { id: "effect", label: "Effect" },
  { id: "notes", label: "Notes" },
];
const TAB_IDS: TabId[] = TABS.map((t) => t.id);

function isTabId(value: string | null): value is TabId {
  return value !== null && (TAB_IDS as readonly string[]).includes(value);
}

export default function RiskDetailPage() {
  return (
    <Suspense fallback={<div className="p-6 text-center text-text-muted">Loading…</div>}>
      <RiskDetailPageInner />
    </Suspense>
  );
}

function RiskDetailPageInner() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const projectId = params.projectId as string;
  const riskId = params.riskId as string;
  const queryClient = useQueryClient();

  const tabFromUrl = searchParams.get("tab");
  const [activeTab, setActiveTab] = useState<TabId>(isTabId(tabFromUrl) ? tabFromUrl : "general");

  // Keep tab in URL so back/forward and reload restore state.
  useEffect(() => {
    const next = isTabId(tabFromUrl) ? tabFromUrl : "general";
    if (next !== activeTab) setActiveTab(next);
  }, [tabFromUrl]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleTabChange = (id: TabId) => {
    setActiveTab(id);
    const url = new URL(window.location.href);
    if (id === "general") url.searchParams.delete("tab");
    else url.searchParams.set("tab", id);
    // url.pathname includes the basePath (from window.location); strip it so
    // router.replace (which re-adds the basePath) doesn't double it.
    router.replace(stripBasePath(url.pathname) + url.search, { scroll: false });
  };

  const { data: riskData, isLoading } = useQuery({
    queryKey: ["risk", projectId, riskId],
    queryFn: () => riskApi.getRisk(projectId, riskId),
  });

  const updateMutation = useMutation({
    mutationFn: (data: UpdateRiskRequest) => riskApi.updateRisk(projectId, riskId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["risk", projectId, riskId] });
      queryClient.invalidateQueries({ queryKey: ["risks", projectId] });
      toast.success("Risk updated");
    },
    onError: (err: unknown) => toast.error(getErrorMessage(err, "Failed to update risk")),
  });

  const deleteMutation = useMutation({
    mutationFn: () => riskApi.deleteRisk(projectId, riskId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["risks", projectId] });
      router.push(`/projects/${projectId}/risks`);
      toast.success("Risk deleted");
    },
    onError: (err: unknown) => toast.error(getErrorMessage(err, "Failed to delete risk")),
  });

  const risk = riskData?.data;

  if (isLoading) {
    return <div className="p-6 text-center text-text-muted">Loading risk...</div>;
  }

  if (!risk) {
    return <div className="p-6 text-center text-danger">Risk not found</div>;
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <button
            onClick={() => router.push(`/projects/${projectId}/risks`)}
            className="inline-flex items-center gap-1 text-sm text-text-secondary hover:text-text-primary"
          >
            <ArrowLeft size={16} />
            Back to Risk Register
          </button>
        </div>
        <button
          onClick={() => {
            if (window.confirm("Are you sure you want to delete this risk?")) {
              deleteMutation.mutate();
            }
          }}
          disabled={deleteMutation.isPending}
          className="inline-flex items-center gap-2 rounded-md border border-danger/30 bg-danger/10 px-3 py-1.5 text-sm font-medium text-danger hover:bg-danger/20 disabled:opacity-50"
        >
          <Trash2 size={14} />
          Delete Risk
        </button>
      </div>

      <div>
        <h1 className="text-2xl font-bold text-text-primary">{risk.code} — {risk.title}</h1>
        <p className="text-sm text-text-secondary mt-1">
          {risk.riskType === "OPPORTUNITY" ? "Opportunity" : "Threat"} · {risk.status?.replace(/_/g, " ")}
        </p>
      </div>

      {/* Tabs */}
      <div className="border-b border-border">
        <nav
          className="flex items-center gap-6"
          aria-label="Risk detail sections"
          role="tablist"
        >
          {TABS.map((tab) => {
            const selected = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                id={`risk-tab-${tab.id}`}
                role="tab"
                aria-selected={selected}
                aria-controls={`risk-tabpanel-${tab.id}`}
                tabIndex={selected ? 0 : -1}
                onClick={() => handleTabChange(tab.id)}
                className={`px-1 py-3 text-sm font-medium border-b-2 transition-colors cursor-pointer ${
                  selected
                    ? "border-accent text-accent"
                    : "border-transparent text-text-secondary hover:text-text-primary hover:border-border"
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </nav>
      </div>

      {/* Tab Content */}
      <div
        className="mt-4"
        role="tabpanel"
        id={`risk-tabpanel-${activeTab}`}
        aria-labelledby={`risk-tab-${activeTab}`}
      >
        {activeTab === "general" && (
          <RiskGeneralTab risk={risk} onUpdate={(data) => updateMutation.mutate(data)} />
        )}
        {activeTab === "impact" && (
          <RiskImpactTab
            risk={risk}
            projectId={projectId}
            onUpdate={(data) => updateMutation.mutate(data)}
          />
        )}
        {activeTab === "activities" && (
          <RiskActivitiesTab risk={risk} projectId={projectId} riskId={riskId} />
        )}
        {activeTab === "description" && (
          <RiskDescriptionTab
            label="Description"
            field="description"
            value={risk.description || ""}
            onSave={(value) => updateMutation.mutate({ description: value })}
          />
        )}
        {activeTab === "cause" && (
          <RiskDescriptionTab
            label="Cause"
            field="cause"
            value={risk.cause || ""}
            onSave={(value) => updateMutation.mutate({ cause: value })}
          />
        )}
        {activeTab === "effect" && (
          <RiskDescriptionTab
            label="Effect"
            field="effect"
            value={risk.effect || ""}
            onSave={(value) => updateMutation.mutate({ effect: value })}
          />
        )}
        {activeTab === "notes" && (
          <RiskDescriptionTab
            label="Notes"
            field="notes"
            value={risk.notes || ""}
            onSave={(value) => updateMutation.mutate({ notes: value })}
          />
        )}
      </div>
    </div>
  );
}

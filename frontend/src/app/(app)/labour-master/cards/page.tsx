"use client";

import { Suspense, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  labourMasterApi,
  type LabourCategory,
  type LabourDesignationResponse,
  type LabourGrade,
} from "@/lib/api/labourMasterApi";
import {
  CategoryFilterBar,
  CategoryCardsSection,
  WorkerDetailModal,
  ProjectPickerEmpty,
  useLabourMasterProject,
} from "@/components/labour-master";

export default function CardsPage() {
  return (
    <Suspense>
      <CardsPageContent />
    </Suspense>
  );
}

function CardsPageContent() {
  const { projectId } = useLabourMasterProject();

  const [selectedCategory, setCategory] = useState<LabourCategory | null>(null);
  const [selectedGrade, setGrade] = useState<LabourGrade | null>(null);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<LabourDesignationResponse | null>(null);

  const cats = useQuery({
    queryKey: ["labour-categories"],
    queryFn: () => labourMasterApi.designations.listCategories(),
  });
  const dashboard = useQuery({
    queryKey: ["labour-deployments-dashboard", projectId],
    queryFn: () => labourMasterApi.deployments.getDashboard(projectId!),
    enabled: !!projectId,
  });
  const deployments = useQuery({
    queryKey: ["labour-deployments", projectId],
    queryFn: () => labourMasterApi.deployments.listForProject(projectId!),
    enabled: !!projectId,
  });

  const designations: LabourDesignationResponse[] = useMemo(() => {
    const rows = (deployments.data?.data ?? []).map((d) => ({
      ...d.designation,
      deployment: {
        id: d.id,
        workerCount: d.workerCount,
        actualDailyRate: d.actualDailyRate,
        effectiveRate: d.effectiveRate,
        dailyCost: d.dailyCost,
        notes: d.notes,
      },
    }));
    return rows
      .filter((r) => (selectedCategory ? r.category === selectedCategory : true))
      .filter((r) => (selectedGrade ? r.grade === selectedGrade : true))
      .filter((r) => {
        if (!query) return true;
        const q = query.toLowerCase();
        return (
          r.code.toLowerCase().includes(q) ||
          r.designation.toLowerCase().includes(q) ||
          r.trade.toLowerCase().includes(q)
        );
      });
  }, [deployments.data, selectedCategory, selectedGrade, query]);

  if (!projectId) return <ProjectPickerEmpty title="Select a project to browse Labour Cards" redirectBasePath="/labour-master/cards" />;
  if (deployments.isLoading || cats.isLoading || dashboard.isLoading) {
    return <p>Loading…</p>;
  }
  if (deployments.isError || cats.isError || dashboard.isError) {
    return <p className="text-red-700">Failed to load labour data.</p>;
  }

  return (
    <div className="space-y-4">
      <CategoryFilterBar
        categories={cats.data?.data ?? []}
        selectedCategory={selectedCategory}
        onCategoryChange={setCategory}
        selectedGrade={selectedGrade}
        onGradeChange={setGrade}
        query={query}
        onQueryChange={setQuery}
      />
      {(dashboard.data?.data?.byCategory ?? []).map((sum) => {
        const rows = designations.filter((d) => d.category === sum.category);
        if (rows.length === 0) return null;
        return (
          <CategoryCardsSection
            key={sum.category}
            summary={sum}
            designations={rows}
            onCardClick={setOpen}
          />
        );
      })}
      <WorkerDetailModal designation={open} onClose={() => setOpen(null)} />
    </div>
  );
}

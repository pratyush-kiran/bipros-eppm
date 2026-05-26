"use client";
import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { projectApi } from "@/lib/api/projectApi";
import { useRecentProjects } from "@/hooks/useRecentProjects";
import { HeroPulse } from "./HeroPulse";
import { QuickAccessLauncher } from "./QuickAccessLauncher";
import { BentoGrid } from "./BentoGrid";

export function ProjectHub({ projectId }: { projectId: string }) {
  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
  });
  const project = projectData?.data;

  // record visit (moved here from layout — visit is recorded once project is loaded into hub)
  const { recordVisit } = useRecentProjects();
  useEffect(() => {
    if (project?.id && project?.code && project?.name) {
      recordVisit({ id: project.id, code: project.code, name: project.name });
    }
  }, [project?.id, project?.code, project?.name, recordVisit]);

  if (!project) {
    return <div className="p-6 text-center text-text-muted">Loading project…</div>;
  }

  return (
    // Extra px-4 sm:px-6 of horizontal breathing room ON TOP of AppShell's
    // own px-4/6/8. This matches the inset that the SectionContextBar's
    // inner row and the section page wrapper (in layout.tsx) both apply, so
    // the hub and any section page share the same comfortable margins.
    <div className="px-4 sm:px-6 pt-6 pb-12">
      <HeroPulse projectId={projectId} />
      <QuickAccessLauncher projectId={projectId} />
      <BentoGrid projectId={projectId} />
    </div>
  );
}

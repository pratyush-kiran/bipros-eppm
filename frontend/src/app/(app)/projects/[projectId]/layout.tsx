"use client";

import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams, useSearchParams, usePathname } from "next/navigation";
import { projectApi } from "@/lib/api/projectApi";
import { SectionContextBar } from "./_components/nav/SectionContextBar";
import { CommandPaletteProvider } from "./_components/palette/CommandPaletteProvider";

function ProjectDetailLayoutInner({
  children,
}: {
  children: React.ReactNode;
}) {
  const params = useParams();
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const projectId = params.projectId as string;

  const { data: projectData, isLoading, error } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    // 403/404 is a final state — retrying just delays the explanatory message.
    retry: (failureCount, err) => {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 403 || status === 404) return false;
      return failureCount < 2;
    },
  });

  const project = projectData?.data;
  const status = (error as { response?: { status?: number } } | null)?.response?.status;

  if (isLoading) {
    return <div className="p-6 text-center text-text-muted">Loading project…</div>;
  }

  if (status === 403) {
    return (
      <div className="p-6 text-center">
        <p className="text-sm font-semibold uppercase tracking-widest text-accent">No access</p>
        <p className="mt-2 text-text-primary">
          You&apos;re not a member of this project.
        </p>
        <p className="mt-1 text-sm text-text-muted">
          Ask the project manager to add you under <em>Project &rsaquo; Members</em>.
        </p>
      </div>
    );
  }

  if (!project) {
    return <div className="p-6 text-center text-danger">Project not found</div>;
  }

  const projectBase = `/projects/${projectId}`;
  const onHub =
    (pathname === projectBase || pathname === `${projectBase}/`) &&
    !searchParams.get("tab");

  return (
    <CommandPaletteProvider>
      <div className="min-w-0">
        {!onHub && <SectionContextBar />}
        {/* Section pages get an extra px-4 sm:px-6 wrapper so their content
            (breadcrumb, tip card, tree, toolbar) picks up the same breathing
            room as the SectionContextBar's inner row above. The hub page
            does its own padding inside ProjectHub. */}
        <div className={onHub ? "" : "px-4 sm:px-6 mt-0"}>{children}</div>
      </div>
    </CommandPaletteProvider>
  );
}

export default function ProjectDetailLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <Suspense fallback={<div className="p-6 text-center text-text-muted">Loading…</div>}>
      <ProjectDetailLayoutInner>{children}</ProjectDetailLayoutInner>
    </Suspense>
  );
}

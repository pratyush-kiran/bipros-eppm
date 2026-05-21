"use client";

import React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ChevronLeft } from "lucide-react";

/** Top-of-module breadcrumb that links back to the central Dashboard hub.
 *  Renders inside per-project module surfaces (Resources, Finance, Risks,
 *  Reports) so users always know how to return to the hub. */
export function DashboardBreadcrumb({ label }: { label: string }) {
  const params = useParams();
  const projectId = params.projectId as string;
  return (
    <div className="flex items-center gap-1 text-xs text-text-secondary">
      <Link
        href={`/projects/${projectId}/dashboard`}
        className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 transition-colors hover:bg-parchment hover:text-text-primary"
      >
        <ChevronLeft size={12} strokeWidth={2} />
        Dashboard
      </Link>
      <span className="text-text-muted">/</span>
      <span className="text-text-primary">{label}</span>
    </div>
  );
}

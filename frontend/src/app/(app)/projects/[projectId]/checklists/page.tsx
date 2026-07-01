"use client";

import { useParams } from "next/navigation";
import { ChecklistsPanel } from "@/components/quality/ChecklistsPanel";

export default function ChecklistsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  return (
    <div className="p-6">
      <ChecklistsPanel projectId={projectId} />
    </div>
  );
}

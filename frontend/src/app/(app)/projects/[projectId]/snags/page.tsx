"use client";

import { useParams } from "next/navigation";
import { SnagsPanel } from "@/components/quality/SnagsPanel";

export default function SnagsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  return (
    <div className="p-6">
      <SnagsPanel projectId={projectId} />
    </div>
  );
}

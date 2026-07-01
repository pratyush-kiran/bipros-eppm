"use client";

import { useParams } from "next/navigation";
import { NcrsPanel } from "@/components/quality/NcrsPanel";

export default function NcrsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  return (
    <div className="p-6">
      <NcrsPanel projectId={projectId} />
    </div>
  );
}

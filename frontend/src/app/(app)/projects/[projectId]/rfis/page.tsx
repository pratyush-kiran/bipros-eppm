"use client";

import { useParams } from "next/navigation";
import { RfisPanel } from "@/components/quality/RfisPanel";

export default function RfisPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  return (
    <div className="p-6">
      <RfisPanel projectId={projectId} />
    </div>
  );
}

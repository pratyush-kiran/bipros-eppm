"use client";

import { useParams } from "next/navigation";
import { PnlView } from "@/components/financials/PnlView";

export default function PnlBoqPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  return (
    <PnlView
      projectId={projectId}
      scope="boq"
      title="P&L vs BOQ Rates"
      description="Revenue is priced at the contract BOQ rate — the rate the client pays; cost is total expense computed daily by the DBS module. Period margin equals the DBS contribution rolled up to weekly/monthly buckets."
      revenueLabel="BOQ Revenue"
    />
  );
}

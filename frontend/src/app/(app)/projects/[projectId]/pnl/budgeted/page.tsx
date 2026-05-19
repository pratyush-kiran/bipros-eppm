"use client";

import { useParams } from "next/navigation";
import { PnlView } from "@/components/financials/PnlView";

export default function PnlBudgetedPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  return (
    <PnlView
      projectId={projectId}
      scope="budgeted"
      title="P&L vs Budgeted Unit Rates"
      description="Revenue is priced at the project team's internal budgeted unit rate (BOQ.budgetedRate); cost is the actual cost rolled up from DPR rows via the Daily Cost Report. Margin tells you whether site execution is beating the internal plan."
      revenueLabel="Budgeted Revenue"
    />
  );
}

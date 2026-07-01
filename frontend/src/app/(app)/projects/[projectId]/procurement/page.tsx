"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { TabTip } from "@/components/common/TabTip";
import { SubContractorsPanel } from "@/components/procurement/SubContractorsPanel";
import { MaterialVendorsPanel } from "@/components/procurement/MaterialVendorsPanel";
import { cn } from "@/lib/utils/cn";

type ProcurementTab = "sub-contractors" | "material-vendors";

export default function ProcurementPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const [tab, setTab] = useState<ProcurementTab>("sub-contractors");

  return (
    <div className="p-6">
      <TabTip
        title="Procurement"
        description="Sub-contractor and material-vendor cost roll-ups for this project — a read-only reconciliation lens over existing assignments and goods receipts."
      />
      <h1 className="mb-4 font-display text-3xl font-bold text-charcoal">
        Procurement
      </h1>

      <div className="mb-6 flex flex-wrap gap-2 border-b border-hairline">
        {([
          { id: "sub-contractors", label: "Sub-contractors" },
          { id: "material-vendors", label: "Material Vendors" },
        ] as const).map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={cn(
              "cursor-pointer border-b-2 px-3 py-2 text-sm font-medium transition-colors",
              tab === t.id
                ? "border-accent text-accent"
                : "border-transparent text-text-secondary hover:border-border hover:text-text-primary",
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "sub-contractors" && <SubContractorsPanel projectId={projectId} />}
      {tab === "material-vendors" && <MaterialVendorsPanel projectId={projectId} />}
    </div>
  );
}

"use client";

import { ActiveProjectsTile } from "./tiles/ActiveProjectsTile";
import { AtRiskTile } from "./tiles/AtRiskTile";
import { CashFlowTile } from "./tiles/CashFlowTile";
import { OpenRisksTile } from "./tiles/OpenRisksTile";
import { PermitsTile } from "./tiles/PermitsTile";
import { SiteActivityTile } from "./tiles/SiteActivityTile";
import type { MissionControlData } from "./hooks/useMissionControlData";

interface Props {
  data: MissionControlData;
}

export function MetricGrid({ data }: Props) {
  return (
    <section
      data-testid="mc-metric-grid"
      className="mt-7 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3"
    >
      <ActiveProjectsTile scorecard={data.scorecard} />
      <AtRiskTile scorecard={data.scorecard} delayed={data.delayed} />
      <CashFlowTile points={data.cashFlow} />
      <OpenRisksTile scorecard={data.scorecard} heatmap={data.risks} />
      <PermitsTile summary={data.permits} />
      <SiteActivityTile summary={data.permits} />
    </section>
  );
}

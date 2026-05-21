import React from "react";
import { cn } from "@/lib/utils/cn";

export interface SiteConditionsStripProps {
  temperature?: number | null;
  windKmh?: number | null;
  rainfallMm?: number | null;
  aqi?: number | null;
  className?: string;
}

function tile(label: string, value: React.ReactNode, accent: string) {
  return (
    <div className="flex flex-col rounded-lg border border-hairline bg-parchment/30 px-3 py-2">
      <span className="text-[10px] font-semibold uppercase tracking-[0.14em]" style={{ color: accent }}>
        {label}
      </span>
      <span className="mt-1 font-display text-lg font-semibold tabular-nums text-text-primary" style={{ color: accent }}>
        {value}
      </span>
    </div>
  );
}

export function SiteConditionsStrip({
  temperature,
  windKmh,
  rainfallMm,
  aqi,
  className,
}: SiteConditionsStripProps) {
  return (
    <div className={cn("grid grid-cols-4 gap-2", className)}>
      {tile(
        "Temp",
        temperature == null ? "—" : `${Math.round(temperature)}°C`,
        "var(--bronze-warn)"
      )}
      {tile(
        "Wind",
        windKmh == null ? "—" : `${Math.round(windKmh)} km/h`,
        "var(--steel)"
      )}
      {tile(
        "Rain",
        rainfallMm == null ? "—" : `${rainfallMm} mm`,
        "var(--emerald)"
      )}
      {tile(
        "AQI",
        aqi == null ? "—" : aqi,
        "var(--burgundy)"
      )}
    </div>
  );
}

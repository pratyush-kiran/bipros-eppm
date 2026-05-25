"use client";

import { CloudSun, Wind, Droplets, Gauge } from "lucide-react";
import {
  SectionCard,
} from "@/components/common/dashboard/primitives";
import type { DailyWeatherResponse } from "@/lib/api/dailyWeatherApi";

interface SiteConditionsPanelProps {
  weather: DailyWeatherResponse | null;
}

function fmt(value: number | null | undefined, suffix: string): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${value.toFixed(0)}${suffix}`;
}

export function SiteConditionsPanel({ weather }: SiteConditionsPanelProps) {
  const temp = weather?.tempMaxC ?? null;
  const wind = weather?.windKmh ?? null;
  const rain = weather?.rainfallMm ?? null;

  const tiles: {
    label: string;
    value: string;
    caption?: string;
    icon: React.ReactNode;
    accent: string;
  }[] = [
    {
      label: "Temp",
      value: fmt(temp, "°C"),
      icon: <CloudSun size={14} />,
      accent: "from-amber-flame/15 to-paper border-amber-flame/30 text-amber-flame",
    },
    {
      label: "Wind",
      value: fmt(wind, " km/h"),
      icon: <Wind size={14} />,
      accent: "from-steel/15 to-paper border-steel/30 text-steel",
    },
    {
      label: "Rain",
      value: fmt(rain, " mm"),
      icon: <Droplets size={14} />,
      accent: "from-emerald/15 to-paper border-emerald/30 text-emerald",
    },
    {
      label: "AQI",
      value: "—",
      caption: "Not available",
      icon: <Gauge size={14} />,
      accent: "from-burgundy/10 to-paper border-burgundy/30 text-burgundy",
    },
  ];

  return (
    <SectionCard
      title="Site Conditions"
      subtitle={weather ? `As of ${weather.logDate}` : "Latest reading unavailable"}
      icon={<CloudSun size={16} />}
      accent
    >
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {tiles.map((t) => (
          <div
            key={t.label}
            className={`relative overflow-hidden rounded-xl border bg-gradient-to-br p-3 ${t.accent}`}
          >
            <div className="flex items-center justify-between">
              <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                {t.label}
              </div>
              <div className="flex h-6 w-6 items-center justify-center rounded-md bg-paper/60">
                {t.icon}
              </div>
            </div>
            <div
              className="mt-2 font-display text-2xl font-semibold leading-none tracking-tight text-charcoal"
              data-testid={t.label === "AQI" ? "site-conditions-aqi" : undefined}
            >
              {t.value}
            </div>
            {t.caption && (
              <div className="mt-1 text-[10px] font-medium text-slate">
                {t.caption}
              </div>
            )}
          </div>
        ))}
      </div>
    </SectionCard>
  );
}

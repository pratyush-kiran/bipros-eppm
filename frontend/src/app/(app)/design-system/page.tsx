"use client";

import React from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { KpiCard } from "@/components/ui/kpi-card";
import { MetricDelta } from "@/components/ui/metric-delta";
import { Tabs } from "@/components/ui/tabs";
import { GaugeCard } from "@/components/ui/gauge-card";
import { StatusBadge } from "@/components/ui/status-badge";

const commandPalette: Array<{ name: string; hex: string; varName: string }> = [
  { name: "Primary Accent", hex: "#FF6B35", varName: "--gold" },
  { name: "Amber / Warning", hex: "#F59E0B", varName: "--bronze-warn" },
  { name: "Success / On-Track", hex: "#10B981", varName: "--emerald" },
  { name: "Danger / Critical", hex: "#EF4444", varName: "--burgundy" },
  { name: "Info / Active", hex: "#3B82F6", varName: "--steel" },
  { name: "Secondary", hex: "#8B5CF6", varName: "--cmd-violet" },
  { name: "Tertiary", hex: "#06B6D4", varName: "--cmd-cyan" },
  { name: "Background Dark", hex: "#070D1A", varName: "--paper" },
  { name: "Card Surface", hex: "#0C1929", varName: "--ivory" },
  { name: "Surface", hex: "#0F2237", varName: "--parchment" },
  { name: "Text Primary", hex: "#F1F5F9", varName: "--charcoal" },
  { name: "Text Secondary", hex: "#94A3B8", varName: "--slate" },
  { name: "Text Muted", hex: "#475569", varName: "--ash" },
];

const goldPalette: Array<{ name: string; hex: string; varName: string }> = [
  { name: "Primary Accent · Gold", hex: "#D4AF37", varName: "--gold" },
  { name: "Gold Deep", hex: "#B8962E", varName: "--gold-deep" },
  { name: "Emerald", hex: "#2E7D5B", varName: "--emerald" },
  { name: "Bronze Warn", hex: "#C7882E", varName: "--bronze-warn" },
  { name: "Burgundy", hex: "#9B2C2C", varName: "--burgundy" },
  { name: "Steel", hex: "#475569", varName: "--steel" },
  { name: "Paper", hex: "#FFFFFF", varName: "--paper" },
  { name: "Ivory", hex: "#FAF9F6", varName: "--ivory" },
  { name: "Parchment", hex: "#F5F2E8", varName: "--parchment" },
  { name: "Charcoal", hex: "#1C1C1C", varName: "--charcoal" },
  { name: "Slate", hex: "#6B7280", varName: "--slate" },
  { name: "Ash", hex: "#9CA3AF", varName: "--ash" },
];

export default function DesignSystemPage() {
  return (
    <div className="space-y-10">
      <header>
        <h1 className="font-display text-3xl font-semibold text-text-primary">Design System</h1>
        <p className="mt-1 text-sm text-text-secondary">
          Tokens, primitives, and the Project Command palette. Apply{" "}
          <code className="rounded bg-parchment px-1.5 py-0.5 text-xs">.theme-command</code> on a
          wrapper to opt into the navy / orange identity. The class is auto-applied to the per-project
          workspace at <code className="text-xs">/projects/[id]/…</code>.
        </p>
      </header>

      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-[0.16em] text-gold-deep">
          Default · White &amp; Gold
        </h2>
        <div className="rounded-2xl border border-hairline bg-paper p-6">
          <PaletteSwatches palette={goldPalette} />
          <div className="my-8 h-px bg-hairline" />
          <DemoBlock />
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-[0.16em] text-gold-deep">
          Project Command · Navy &amp; Orange
        </h2>
        <div className="theme-command rounded-2xl border border-hairline bg-paper p-6">
          <PaletteSwatches palette={commandPalette} />
          <div className="my-8 h-px bg-hairline" />
          <DemoBlock />
        </div>
      </section>
    </div>
  );
}

function PaletteSwatches({
  palette,
}: {
  palette: Array<{ name: string; hex: string; varName: string }>;
}) {
  return (
    <div>
      <h3 className="mb-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
        Color palette
      </h3>
      <ul className="grid grid-cols-2 gap-2 md:grid-cols-3 lg:grid-cols-4">
        {palette.map((swatch) => (
          <li
            key={swatch.name}
            className="flex items-center gap-3 rounded-lg border border-hairline bg-ivory px-3 py-2.5"
          >
            <span
              className="h-7 w-10 shrink-0 rounded-md border border-hairline"
              style={{ background: swatch.hex }}
              aria-hidden
            />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold text-text-primary">{swatch.name}</div>
              <div className="flex items-center gap-2 text-[10px] text-text-secondary tabular-nums">
                <code>{swatch.hex}</code>
                <span className="text-text-muted">{swatch.varName}</span>
              </div>
            </div>
          </li>
        ))}
      </ul>
      <h3 className="mt-6 mb-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
        Typography
      </h3>
      <div className="space-y-3 rounded-lg border border-hairline bg-ivory px-5 py-4">
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            Display / Heading
          </p>
          <p className="font-display text-2xl font-semibold tracking-tight text-text-primary">
            Project Command Center
          </p>
        </div>
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            Section Header
          </p>
          <p className="text-base font-semibold text-text-primary">Resource Management</p>
        </div>
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            Body
          </p>
          <p className="text-sm text-text-primary">NH-48 Corridor Expansion Project</p>
        </div>
      </div>
    </div>
  );
}

function DemoBlock() {
  const [tab, setTab] = React.useState<"weekly" | "monthly" | "financial">("weekly");
  return (
    <div className="space-y-8">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          label="Overall progress"
          value="67"
          unit="%"
          accent="success"
          delta={<MetricDelta tone="positive">+4%</MetricDelta>}
        />
        <KpiCard
          label="Budget utilised"
          value="₹84.2"
          unit="Cr"
          accent="warning"
          delta={<MetricDelta tone="positive">+₹3.1</MetricDelta>}
        />
        <KpiCard
          label="Tasks completed"
          value="142"
          unit="/210"
          accent="info"
          delta={<MetricDelta tone="positive">+12</MetricDelta>}
        />
        <KpiCard
          label="Open issues"
          value="7"
          unit="critical"
          accent="danger"
          delta={<MetricDelta tone="negative">-3</MetricDelta>}
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <GaugeCard label="Total fleet" value="38" unit="units" percent={70} accent="info" />
        <GaugeCard label="On-site active" value="26" unit="running" percent={68} accent="success" />
        <GaugeCard label="Under repair" value="4" unit="units" percent={10} accent="danger" />
        <GaugeCard label="Avg utilisation" value="72" unit="%" percent={72} accent="primary" />
      </div>

      <div className="space-y-2">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
          Tabs
        </div>
        <Tabs
          items={[
            { id: "weekly", label: "Weekly Progress" },
            { id: "monthly", label: "Monthly Summary" },
            { id: "financial", label: "Financial" },
          ]}
          value={tab}
          onChange={setTab}
        />
      </div>

      <div className="space-y-2">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
          Status badges
        </div>
        <div className="flex flex-wrap gap-2">
          {(
            [
              "ON_TRACK",
              "DONE",
              "RESOLVED",
              "PAID",
              "ACTIVE",
              "IN_PROGRESS",
              "PLANNED",
              "RAISED",
              "PENDING",
              "DELAYED",
              "AT_RISK",
              "CRITICAL",
              "HIGH",
              "MEDIUM",
              "LOW",
            ] as const
          ).map((s) => (
            <StatusBadge key={s} status={s} />
          ))}
        </div>
      </div>

      <div className="space-y-2">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
          Buttons
        </div>
        <div className="flex flex-wrap gap-2">
          <Button>Primary action</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="ghost">Ghost</Button>
          <Button variant="danger">Danger</Button>
        </div>
      </div>

      <div className="space-y-2">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
          Badges (raw variants)
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant="neutral">Neutral</Badge>
          <Badge variant="gold">Accent</Badge>
          <Badge variant="success" withDot>
            Success
          </Badge>
          <Badge variant="warning" withDot>
            Warning
          </Badge>
          <Badge variant="danger" withDot>
            Danger
          </Badge>
          <Badge variant="info" withDot>
            Info
          </Badge>
        </div>
      </div>
    </div>
  );
}

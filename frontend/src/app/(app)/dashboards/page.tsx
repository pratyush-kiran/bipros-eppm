"use client";

import Link from "next/link";
import {
  ArrowUpRight,
  BarChart3,
  Briefcase,
  Sparkles,
  Wallet,
} from "lucide-react";

interface DashboardTier {
  id: string;
  title: string;
  kicker: string;
  description: string;
  highlights: string[];
  icon: React.ComponentType<{ size?: number; strokeWidth?: number }>;
  emphasis?: boolean;
}

const dashboardTiers: DashboardTier[] = [
  {
    id: "EXECUTIVE",
    title: "Executive",
    kicker: "C-suite · corridor view",
    description: "Strategic posture across the corridor — top risks, project health, and budget utilisation rolled up.",
    highlights: ["Project portfolio", "Top 5 risks", "Budget utilisation"],
    icon: Sparkles,
    emphasis: true,
  },
  {
    id: "PROGRAMME",
    title: "Programme",
    kicker: "Programme office",
    description: "Earned-value performance, milestone slippage and contractor scorecards across active programmes.",
    highlights: ["EVM metrics", "Milestone tracker", "Contractor scorecards"],
    icon: BarChart3,
  },
  {
    id: "PORTFOLIO",
    title: "Portfolio",
    kicker: "Cross-project scorecard",
    description: "Cross-project performance — schedule, cost, cash-flow, funding, risks and compliance — at a glance.",
    highlights: ["EVM rollup", "Cost overruns", "Risk heatmap"],
    icon: Briefcase,
  },
  {
    id: "FINANCIAL",
    title: "Financial",
    kicker: "Project · cost lens",
    description: "Budget tracking, cost analysis and billing for a single project — contract value, expenditure, billing raised, S-curve and category breakdown.",
    highlights: ["Cost S-curve", "Invoice summary", "Budget breakdown"],
    icon: Wallet,
  },
];

export default function DashboardsPage() {
  return (
    <div>
      {/* Page head */}
      <div className="mb-8 flex items-start justify-between gap-6">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            Command centre
          </div>
          <h1
            className="font-display text-[38px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Dashboards
          </h1>
          <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
            Cross-programme lenses. For per-project performance views (Operational, Field), open a project and use the <span className="font-semibold text-charcoal">Insights</span> tab.
          </p>
        </div>
      </div>

      {/* Tier grid */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {dashboardTiers.map((tier) => {
          const Icon = tier.icon;
          const href = `/dashboards/${tier.id.toLowerCase()}`;
          return (
            <Link
              key={tier.id}
              href={href}
              className={`group relative flex flex-col overflow-hidden rounded-2xl border bg-paper p-6 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_10px_30px_rgba(28,28,28,0.08)] ${
                tier.emphasis
                  ? "border-gold/45 ring-1 ring-gold/15"
                  : "border-hairline hover:border-gold/40"
              }`}
            >
              {/* Decorative gold sweep */}
              <div
                aria-hidden
                className="pointer-events-none absolute -right-12 -top-12 h-40 w-40 rounded-full bg-gold-tint opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-70"
              />

              <div className="mb-4 flex items-start justify-between">
                <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gold-tint text-gold-deep ring-1 ring-gold/30 transition-all duration-200 group-hover:bg-gold group-hover:text-paper group-hover:ring-gold">
                  <Icon size={20} strokeWidth={1.75} />
                </div>
                <span className="flex h-8 w-8 items-center justify-center rounded-full border border-hairline text-slate transition-all duration-200 group-hover:border-gold group-hover:text-gold-deep group-hover:translate-x-0.5">
                  <ArrowUpRight size={14} strokeWidth={2} />
                </span>
              </div>

              <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                {tier.kicker}
              </div>
              <h2
                className="mt-1 font-display text-2xl font-semibold leading-tight tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {tier.title} dashboard
              </h2>
              <p className="mt-2 text-sm leading-relaxed text-slate">{tier.description}</p>

              <div className="mt-5 flex flex-wrap gap-1.5">
                {tier.highlights.map((h) => (
                  <span
                    key={h}
                    className="inline-flex items-center rounded-md border border-hairline bg-ivory px-2 py-0.5 text-[11px] font-medium text-slate"
                  >
                    {h}
                  </span>
                ))}
              </div>
            </Link>
          );
        })}
      </div>
    </div>
  );
}

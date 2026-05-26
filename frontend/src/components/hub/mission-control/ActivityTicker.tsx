"use client";

import Link from "next/link";
import { Radio } from "lucide-react";

interface TickerEvent {
  id: string;
  projectCode: string;
  actor: string;
  verb: string;
  subject: string;
  href: string;
  agoLabel: string;
}

// Phase 1: seeded events that look realistic against the Khasab demo state.
// Phase 2 will swap this for a real /v1/portfolio/recent-events feed.
const SEED_EVENTS: TickerEvent[] = [
  {
    id: "e1",
    projectCode: "KHASAB-2026",
    actor: "Hemu",
    verb: "submitted",
    subject: "DPR for Day 124",
    href: "/projects",
    agoLabel: "12 min ago",
  },
  {
    id: "e2",
    projectCode: "KHASAB-2026",
    actor: "S. Al-Farsi",
    verb: "approved",
    subject: "Excavation permit · Zone 3",
    href: "/permits",
    agoLabel: "47 min ago",
  },
  {
    id: "e3",
    projectCode: "KHASAB-2026",
    actor: "QC",
    verb: "raised",
    subject: "Risk R-027 · Slope stability Day 5",
    href: "/reports/risk-register",
    agoLabel: "2 h ago",
  },
  {
    id: "e4",
    projectCode: "KHASAB-2026",
    actor: "A. Khalil",
    verb: "logged",
    subject: "2,400 m³ aggregate consumption",
    href: "/projects",
    agoLabel: "3 h ago",
  },
  {
    id: "e5",
    projectCode: "KHASAB-2026",
    actor: "Site engineer",
    verb: "closed",
    subject: "NCR-014 (rework signed off)",
    href: "/qc",
    agoLabel: "5 h ago",
  },
];

export function ActivityTicker({
  events = SEED_EVENTS,
}: {
  events?: TickerEvent[];
}) {
  return (
    <section
      data-testid="mc-activity-ticker"
      className="mt-8 overflow-hidden rounded-2xl border border-hairline bg-parchment/40"
    >
      <div className="flex items-center gap-3 border-b border-hairline px-5 py-2.5">
        <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-gold-tint/60 text-gold-deep">
          <Radio size={11} strokeWidth={2} />
        </span>
        <span className="text-[10.5px] font-semibold uppercase tracking-[0.14em] text-ash">
          Live activity
        </span>
        <span className="ml-auto text-[10.5px] font-medium text-ash">
          {events.length} events
        </span>
      </div>

      <ul className="divide-y divide-hairline/60">
        {events.map((e) => (
          <li key={e.id}>
            <Link
              href={e.href}
              className="group flex items-center gap-3 px-5 py-2.5 text-[12.5px] transition-colors hover:bg-ivory/60"
            >
              <span
                aria-hidden
                className="inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-gold"
              />
              <span className="shrink-0 font-mono text-[10.5px] font-semibold uppercase tracking-[0.1em] text-ash">
                {e.projectCode}
              </span>
              <span className="truncate text-charcoal">
                <span className="font-semibold">{e.actor}</span>{" "}
                <span className="text-slate">{e.verb}</span> {e.subject}
              </span>
              <span className="ml-auto shrink-0 text-[10.5px] font-medium uppercase tracking-[0.1em] text-ash">
                {e.agoLabel}
              </span>
              <span
                aria-hidden
                className="shrink-0 text-gold-deep opacity-0 transition-opacity group-hover:opacity-100"
              >
                ›
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

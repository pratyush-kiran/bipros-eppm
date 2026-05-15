"use client";

import Link from "next/link";
import { ArrowUpRight, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";
import { useAuthStore } from "@/lib/state/store";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";

// Programme dashboard is a cross-portfolio aggregate — gate the header CTA the
// same way the Hub tile is gated in hubConfig.ts.
const PROGRAMME_DASHBOARD_PERM = "PORTFOLIO.READ";

function partOfDay(d: Date): "morning" | "afternoon" | "evening" {
  const h = d.getHours();
  if (h < 12) return "morning";
  if (h < 17) return "afternoon";
  return "evening";
}

export function HubGreeting() {
  const user = useAuthStore((s) => s.user);
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { label: roleLabel } = useMostSeniorRole();

  // Render a stable placeholder during SSR/hydration so the time-aware greeting
  // doesn't flash a server "morning" against a client "evening" mismatch.
  // The auth store is also empty on the server, so any permission-derived UI
  // has to wait for hydration too, or React's hydration check will diff the
  // server's "no perms" markup against the client's authenticated markup.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);
  const canSeeProgrammeDashboard = hydrated && hasPermission(PROGRAMME_DASHBOARD_PERM);

  const now = hydrated ? new Date() : null;
  const greeting = now ? `Good ${partOfDay(now)}` : "Welcome";
  const displayName = user?.firstName?.trim() || user?.username || "there";

  const today = now
    ? now.toLocaleDateString("en-IN", {
        weekday: "long",
        day: "numeric",
        month: "long",
        year: "numeric",
      })
    : "";

  return (
    <header
      data-testid="hub-greeting"
      className="relative mb-7 overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 px-7 py-6 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_20px_50px_-30px_rgba(28,28,28,0.18)]"
    >
      <div className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gold/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-24 left-1/3 h-56 w-56 rounded-full bg-gold-tint/40 blur-3xl" />

      <div className="relative flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div className="min-w-0">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
              <Sparkles size={11} />
              {roleLabel}
            </span>
            {today && (
              <span className="text-[11px] font-medium text-slate">{today}</span>
            )}
          </div>
          <h1
            className="font-display text-[34px] font-semibold leading-[1.05] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            <span suppressHydrationWarning>{greeting}, {displayName}</span>
          </h1>
          <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
            {canSeeProgrammeDashboard
              ? "Pick up where you left off. The full programme dashboard is one click away."
              : "Pick up where you left off. Your projects and daily reports are below."}
          </p>
        </div>

        {canSeeProgrammeDashboard && (
          <Link
            href="/dashboard"
            data-testid="hub-dashboard-link"
            className="group inline-flex items-center gap-2 self-start rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
          >
            View programme dashboard
            <ArrowUpRight
              size={14}
              strokeWidth={1.75}
              className="transition-transform duration-200 group-hover:translate-x-0.5 group-hover:-translate-y-0.5"
            />
          </Link>
        )}
      </div>
    </header>
  );
}

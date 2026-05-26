"use client";

import Link from "next/link";
import { ArrowUpRight, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";
import { useAuthStore } from "@/lib/state/store";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";

const PROGRAMME_DASHBOARD_PERM = "PORTFOLIO.READ";

function partOfDay(d: Date): "morning" | "afternoon" | "evening" {
  const h = d.getHours();
  if (h < 12) return "morning";
  if (h < 17) return "afternoon";
  return "evening";
}

export function HubBanner() {
  const user = useAuthStore((s) => s.user);
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { label: roleLabel } = useMostSeniorRole();

  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);
  const canSeeProgrammeDashboard =
    hydrated && hasPermission(PROGRAMME_DASHBOARD_PERM);

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
    <section
      data-testid="mc-hub-banner"
      className="relative mb-8 overflow-hidden rounded-3xl border border-hairline px-6 py-7 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_28px_60px_-34px_rgba(28,28,28,0.22)] sm:px-10 sm:py-9"
      style={{
        background:
          "linear-gradient(125deg, color-mix(in srgb, #6366F1 9%, var(--paper)) 0%, color-mix(in srgb, #8B5CF6 5%, var(--paper)) 30%, var(--paper) 55%, color-mix(in srgb, #D4AF37 12%, var(--paper)) 100%)",
      }}
    >
      {/* layered ambient blurs — cool top-left, warm bottom-right */}
      <div
        aria-hidden
        className="pointer-events-none absolute -right-32 -top-32 h-80 w-80 rounded-full blur-3xl"
        style={{ background: "color-mix(in srgb, #D4AF37 22%, transparent)" }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -left-32 -bottom-32 h-80 w-80 rounded-full blur-3xl"
        style={{ background: "color-mix(in srgb, #6366F1 18%, transparent)" }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute right-1/3 top-1/2 h-44 w-44 -translate-y-1/2 rounded-full blur-3xl"
        style={{ background: "color-mix(in srgb, #8B5CF6 10%, transparent)" }}
      />

      <div className="relative flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
        <div className="min-w-0">
          <div className="mb-3 flex flex-wrap items-center gap-2.5">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/50 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-ink">
              <Sparkles size={11} />
              {roleLabel}
            </span>
            {today && (
              <span className="text-[11px] font-medium uppercase tracking-[0.12em] text-slate">
                {today}
              </span>
            )}
          </div>
          <h1
            className="font-display text-[32px] font-semibold leading-[1.02] tracking-tight text-charcoal sm:text-[38px]"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            <span suppressHydrationWarning>
              {greeting}, {displayName}.
            </span>
          </h1>
          <p className="mt-2.5 max-w-[640px] text-[14px] leading-relaxed text-slate">
            Where would you like to go? Pick a module below, or press{" "}
            <kbd className="inline-flex items-center rounded-md border border-hairline bg-paper/80 px-1.5 py-0.5 font-mono text-[10.5px] text-charcoal align-baseline shadow-sm">
              ⌘K
            </kbd>{" "}
            to search anywhere in Bipros.
          </p>
        </div>

        {canSeeProgrammeDashboard && (
          <Link
            href="/dashboard"
            data-testid="mc-banner-dashboard-link"
            className="group inline-flex items-center gap-2 self-start rounded-xl border border-hairline bg-paper/95 px-4 py-2.5 text-[12.5px] font-semibold text-charcoal shadow-sm backdrop-blur transition-all hover:-translate-y-0.5 hover:border-gold/50 hover:text-gold-deep hover:shadow-[0_12px_28px_-14px_rgba(212,175,55,0.45)]"
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
    </section>
  );
}

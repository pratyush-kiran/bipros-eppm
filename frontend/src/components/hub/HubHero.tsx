"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";
import { useHubSummary } from "@/hooks/useHubSummary";
import { useAuthStore } from "@/lib/state/store";
import { heroForRole, type HeroTile } from "./hubConfig";

export function HubHero() {
  const { role } = useMostSeniorRole();
  const { data: summary } = useHubSummary();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  // Auth store is empty on the server (zustand rehydrates from localStorage on
  // the client), so any permission-derived rendering must wait for hydration —
  // otherwise the server emits "no tiles" while the client emits a tile list,
  // and the resulting structural diff breaks Hub's sibling section ordering.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);
  if (!hydrated) return null;

  const tiles = heroForRole(role).filter((t) => !t.permission || hasPermission(t.permission));
  if (tiles.length === 0) return null;

  return (
    <section data-testid="hub-hero" className="mb-8">
      <div className="mb-4 px-1">
        <h2 className="font-display text-[22px] font-semibold tracking-tight text-charcoal">
          What would you like to do?
        </h2>
        <p className="mt-1 text-[13px] text-slate">
          Jump straight in. Everything else lives in the navigation on the left.
        </p>
      </div>
      <div
        // 2-up bento on desktop regardless of tile count — bigger cards feel like
        // a launchpad, not a tile-soup. Falls to 1 col on mobile.
        className="grid grid-cols-1 gap-4 sm:grid-cols-2"
      >
        {tiles.map((tile) => (
          <HeroCard key={tile.title} tile={tile} count={tile.badgeKey ? summary[tile.badgeKey] : 0} />
        ))}
      </div>
    </section>
  );
}

function HeroCard({ tile, count }: { tile: HeroTile; count: number }) {
  const Icon = tile.icon;
  const showBadge = !!tile.badgeKey && count > 0;
  return (
    <Link
      href={tile.href}
      data-testid="hub-hero-card"
      data-hero-title={tile.title}
      className="group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04)] transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_18px_42px_-18px_rgba(212,175,55,0.3)] sm:p-7"
    >
      <div className="pointer-events-none absolute -right-16 -top-16 h-36 w-36 rounded-full bg-gold/8 opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-100" />

      <div className="flex items-start justify-between gap-3">
        <div className="flex h-12 w-12 items-center justify-center rounded-xl border border-gold/25 bg-gradient-to-br from-gold-tint/60 to-paper text-gold-deep shadow-sm">
          <Icon size={22} strokeWidth={1.5} />
        </div>
        {showBadge && (
          <span
            data-testid="hub-hero-badge"
            className="inline-flex min-w-[1.75rem] items-center justify-center rounded-full border border-burgundy/30 bg-burgundy/10 px-2 py-0.5 text-[12px] font-semibold text-burgundy"
          >
            {count}
          </span>
        )}
      </div>

      <div
        className="mt-5 font-display text-[22px] font-semibold leading-tight tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {tile.title}
      </div>
      <p className="mt-1.5 max-w-[34ch] text-[13px] leading-relaxed text-slate">
        {tile.description}
      </p>

      <span
        aria-hidden
        className="absolute right-6 bottom-6 inline-flex h-7 w-7 items-center justify-center rounded-full border border-hairline bg-paper text-gold-deep opacity-0 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-100"
      >
        →
      </span>
    </Link>
  );
}

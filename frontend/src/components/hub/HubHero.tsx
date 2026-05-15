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
      <h2 className="mb-3 px-1 text-[11px] font-semibold uppercase tracking-[0.14em] text-ash">
        For you
      </h2>
      <div
        className={
          // 1 col on mobile, 2 on tablet, then a column per tile (max 4) on large screens
          "grid grid-cols-1 gap-3 sm:grid-cols-2 " +
          (tiles.length >= 4 ? "lg:grid-cols-4" : tiles.length === 3 ? "lg:grid-cols-3" : "lg:grid-cols-2")
        }
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
      className="group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.04)] transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_12px_32px_-14px_rgba(212,175,55,0.25)]"
    >
      <div className="pointer-events-none absolute -right-12 -top-12 h-28 w-28 rounded-full bg-gold/8 opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-100" />

      <div className="flex items-start justify-between gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-gold/25 bg-gradient-to-br from-gold-tint/60 to-paper text-gold-deep shadow-sm">
          <Icon size={18} strokeWidth={1.5} />
        </div>
        {showBadge && (
          <span
            data-testid="hub-hero-badge"
            className="inline-flex min-w-[1.5rem] items-center justify-center rounded-full border border-burgundy/30 bg-burgundy/10 px-2 py-0.5 text-[11px] font-semibold text-burgundy"
          >
            {count}
          </span>
        )}
      </div>

      <div
        className="mt-3.5 font-display text-lg font-semibold leading-tight tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {tile.title}
      </div>
      <p className="mt-1 text-xs leading-relaxed text-slate">{tile.description}</p>

      <span
        aria-hidden
        className="absolute right-5 top-5 text-sm text-gold-deep opacity-0 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-100"
      >
        →
      </span>
    </Link>
  );
}

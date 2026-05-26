"use client";

import Link from "next/link";
import { ArrowUpRight } from "lucide-react";
import { useState, type CSSProperties } from "react";
import {
  COLOR_PALETTES,
  type ModuleTileDef,
  type ModuleVariant,
} from "./modulesConfig";

interface Props {
  tile: ModuleTileDef;
  variant: ModuleVariant;
  /** Fires alongside navigation — used by hosts (e.g. AppSwitcher) that need
      to close themselves after the user picks a destination. */
  onClick?: () => void;
}

export function ModuleTile({ tile, variant, onClick }: Props) {
  const palette = COLOR_PALETTES[tile.color];
  const Icon = tile.icon;
  const [hover, setHover] = useState(false);

  // Inline styles drive the per-tile color so we don't need to register N color
  // tokens in globals.css. color-mix() blends with the live --paper value so the
  // tints adapt automatically when dark mode flips that variable. A soft 3%
  // resting tint keeps the per-module identity visible without shouting; hover
  // bumps to ~9% with a colored border + halo.
  const heroBackground = hover
    ? `linear-gradient(135deg, color-mix(in srgb, ${palette.accent} 9%, var(--paper)) 0%, var(--paper) 75%)`
    : `linear-gradient(135deg, color-mix(in srgb, ${palette.accent} 3%, var(--paper)) 0%, var(--paper) 80%)`;
  const compactBackground = hover
    ? `color-mix(in srgb, ${palette.accent} 5%, var(--paper))`
    : "var(--paper)";

  // Icon-square gets a Tailwind-100-level tint via color-mix so it adapts to
  // dark mode automatically rather than relying on the precomputed hex iconBg
  // which was calibrated for light surfaces.
  const iconBgColor = `color-mix(in srgb, ${palette.accent} 16%, var(--paper))`;

  const commonStyle: CSSProperties = {
    borderColor: hover
      ? `color-mix(in srgb, ${palette.accent} 45%, var(--hairline))`
      : "var(--hairline)",
    boxShadow: hover
      ? `0 14px 34px -18px ${palette.accent}45`
      : "0 1px 2px rgba(28,28,28,0.04)",
  };

  if (variant === "compact") {
    return (
      <Link
        href={tile.href}
        onClick={onClick}
        data-testid={`mc-module-${tile.key}`}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        className="group flex items-center gap-3 rounded-xl border p-3 transition-all duration-200 hover:-translate-y-0.5"
        style={{ ...commonStyle, background: compactBackground }}
      >
        <span
          aria-hidden
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border transition-transform duration-200 group-hover:scale-105"
          style={{
            backgroundColor: iconBgColor,
            color: palette.iconFg,
            borderColor: `color-mix(in srgb, ${palette.accent} 18%, var(--hairline))`,
          }}
        >
          <Icon size={15} strokeWidth={1.75} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-semibold text-charcoal">
            {tile.title}
          </span>
          {tile.description && (
            <span className="block truncate text-[11px] text-slate">
              {tile.description}
            </span>
          )}
        </span>
        <span
          aria-hidden
          className="shrink-0 opacity-0 transition-opacity duration-200 group-hover:opacity-100"
          style={{ color: palette.accent }}
        >
          ›
        </span>
      </Link>
    );
  }

  return (
    <Link
      href={tile.href}
      onClick={onClick}
      data-testid={`mc-module-${tile.key}`}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      className="group relative block overflow-hidden rounded-2xl border p-5 transition-all duration-200 hover:-translate-y-1 sm:p-6"
      style={{ ...commonStyle, background: heroBackground }}
    >
      {/* Soft accent halo — barely there at rest, intensifies on hover. */}
      <span
        aria-hidden
        className="pointer-events-none absolute -right-16 -top-16 h-44 w-44 rounded-full blur-3xl transition-opacity duration-300"
        style={{
          background: palette.accent,
          opacity: hover ? 0.12 : 0.04,
        }}
      />

      <div className="relative flex items-start justify-between gap-3">
        <span
          aria-hidden
          className="flex h-12 w-12 items-center justify-center rounded-xl border transition-transform duration-200 group-hover:scale-105"
          style={{
            backgroundColor: iconBgColor,
            color: palette.iconFg,
            borderColor: `color-mix(in srgb, ${palette.accent} 22%, var(--hairline))`,
          }}
        >
          <Icon size={22} strokeWidth={1.5} />
        </span>
        <span
          aria-hidden
          className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-paper opacity-0 transition-opacity duration-200 group-hover:opacity-100"
          style={{
            color: palette.accent,
            borderWidth: 1,
            borderColor: `color-mix(in srgb, ${palette.accent} 35%, var(--paper))`,
            borderStyle: "solid",
          }}
        >
          <ArrowUpRight size={14} strokeWidth={1.75} />
        </span>
      </div>

      <h3
        className="relative mt-5 font-display text-[22px] font-semibold leading-tight tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {tile.title}
      </h3>
      <p className="relative mt-1 max-w-[34ch] text-[12.5px] leading-relaxed text-slate">
        {tile.description}
      </p>
    </Link>
  );
}

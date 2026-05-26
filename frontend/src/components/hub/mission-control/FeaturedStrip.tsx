"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";
import { useModuleAccess } from "./hooks/useModuleAccess";
import { featuredForRole, type FeaturedCardDef } from "./featuredConfig";

export function FeaturedStrip() {
  const { role } = useMostSeniorRole();
  const { canSee } = useModuleAccess();
  const cards = featuredForRole(role).filter(canSee);

  // If the user has zero viewable featured cards, render nothing so the module
  // grid below sits right under the banner without a phantom gap.
  if (cards.length === 0) return null;

  return (
    <section
      data-testid="mc-featured-strip"
      className="mb-10 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
    >
      {cards.map((card) => (
        <FeaturedCardItem key={card.key} card={card} />
      ))}
    </section>
  );
}

function FeaturedCardItem({ card }: { card: FeaturedCardDef }) {
  const Icon = card.icon;
  return (
    <Link
      href={card.href}
      data-testid={`mc-featured-${card.key}`}
      className="group relative block overflow-hidden rounded-3xl px-6 pt-6 pb-6 shadow-[0_10px_30px_-18px_rgba(28,28,28,0.45)] transition-all duration-300 hover:-translate-y-1 hover:shadow-[0_24px_48px_-24px_rgba(28,28,28,0.5)] sm:px-7 sm:pt-7 sm:pb-7"
      style={{
        background: `linear-gradient(140deg, ${card.accent} 0%, ${card.accentDeep} 100%)`,
        minHeight: 200,
      }}
    >
      {/* Decorative concentric rings in the upper-right corner */}
      <span
        aria-hidden
        className="pointer-events-none absolute -right-20 -top-20 h-56 w-56 rounded-full border border-white/12"
      />
      <span
        aria-hidden
        className="pointer-events-none absolute -right-8 -top-8 h-36 w-36 rounded-full border border-white/16"
      />
      <span
        aria-hidden
        className="pointer-events-none absolute right-6 top-6 h-16 w-16 rounded-full border border-white/20"
      />

      {/* Large illustrative icon — sits behind the text as a soft mark */}
      <Icon
        size={132}
        strokeWidth={1.1}
        aria-hidden
        className="pointer-events-none absolute -right-3 -top-3 text-white opacity-[0.16] transition-transform duration-500 group-hover:rotate-[4deg] group-hover:scale-110"
      />

      {/* Soft inner glow on hover */}
      <span
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
        style={{
          background:
            "radial-gradient(120% 80% at 50% 0%, rgba(255,255,255,0.18), transparent 60%)",
        }}
      />

      <div className="relative flex h-full flex-col">
        <div>
          <span className="inline-flex items-center gap-1.5 rounded-full border border-white/25 bg-white/12 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] text-white/95 backdrop-blur">
            {card.eyebrow}
          </span>
          <h3
            className="mt-3 font-display text-[26px] font-semibold leading-[1.08] tracking-tight text-white sm:text-[28px]"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            {card.title}
          </h3>
          <p className="mt-2 max-w-[28ch] text-[13px] leading-relaxed text-white/80">
            {card.description}
          </p>
        </div>
        <div className="mt-6 flex items-center justify-between gap-3">
          <span
            className="inline-flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12px] font-semibold transition-transform duration-200 group-hover:translate-x-0.5"
            style={{ backgroundColor: card.pillBg, color: card.pillFg }}
          >
            {card.cta}
            <ArrowRight size={13} strokeWidth={2} />
          </span>
        </div>
      </div>
    </Link>
  );
}

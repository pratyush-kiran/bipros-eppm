"use client";

import { ModuleTile } from "./ModuleTile";
import type { ModuleSectionDef, ModuleTileDef } from "./modulesConfig";

interface Props {
  section: ModuleSectionDef;
  tiles: ModuleTileDef[];
}

export function ModuleSection({ section, tiles }: Props) {
  if (tiles.length === 0) return null;

  return (
    <section
      data-testid={`mc-section-${section.label.toLowerCase().replace(/\s+/g, "-")}`}
      className="mt-10 first:mt-0"
    >
      <div className="mb-4 flex items-baseline gap-3 px-1">
        <h2 className="relative font-display text-[11px] font-semibold uppercase tracking-[0.2em] text-charcoal/70">
          <span
            aria-hidden
            className="absolute -left-3 top-1/2 hidden h-px w-2 -translate-y-1/2 bg-gold/60 sm:block"
          />
          {section.label}
        </h2>
        {section.intro && (
          <span className="hidden text-[12px] text-slate sm:inline">
            {section.intro}
          </span>
        )}
      </div>
      <div className={section.gridClass}>
        {tiles.map((tile) => (
          <ModuleTile key={tile.key} tile={tile} variant={section.variant} />
        ))}
      </div>
    </section>
  );
}

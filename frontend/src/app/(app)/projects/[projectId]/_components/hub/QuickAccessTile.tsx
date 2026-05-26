"use client";

import Link from "next/link";
import { findGroupById, type SectionDef } from "../../_data/projectNav";
import type { HubBadge } from "../../_data/useHubBadges";

interface Props {
  section: SectionDef;
  projectId: string;
  isActive: boolean;
  badge?: HubBadge;
  loading?: boolean;
}

/** Dot styling for tone — small inline span before the badge text. */
const TONE_DOT_CLASSES: Record<string, string> = {
  success: "bg-emerald-500",
  warn: "bg-amber-500",
  danger: "bg-red-500 motion-safe:animate-pulse",
  info: "bg-blue-500",
};

export function QuickAccessTile({
  section,
  projectId,
  isActive,
  badge,
  loading = false,
}: Props) {
  const Icon = section.icon;
  const chipClass =
    findGroupById(section.group)?.iconChipClass ?? "bg-parchment text-text-secondary";

  // Fixed height (h-[116px]) — all tiles uniform regardless of label length.
  // Sized so the 3-row layout (icon · label up to 2 lines · badge) never
  // overflows or clips. Labels longer than 2 lines truncate via line-clamp-2.
  const baseClass =
    "group relative flex h-[116px] w-full flex-col gap-1.5 rounded-xl border bg-surface p-3 text-left transition-shadow transition-colors overflow-hidden";
  const activeClass = isActive
    ? "border-gold border-2 bg-gradient-to-br from-gold-tint via-gold-tint/60 to-surface shadow-[0_4px_16px_rgba(212,175,55,0.25)] ring-1 ring-gold/30"
    : "border-border hover:border-gold hover:shadow-md motion-safe:hover:-translate-y-0.5 motion-safe:transition-transform";

  return (
    <Link
      href={section.href(projectId)}
      aria-current={isActive ? "page" : undefined}
      title={section.description}
      className={`${baseClass} ${activeClass}`}
    >
      {/* Icon chip */}
      <span
        className={`inline-flex h-[30px] w-[30px] items-center justify-center rounded-lg shrink-0 ${chipClass}`}
        aria-hidden="true"
      >
        <Icon className="h-4 w-4" strokeWidth={2.25} />
      </span>

      {/* Label — clamps to 2 lines so long labels never push tile taller */}
      <div className={`font-semibold text-[0.83rem] leading-tight line-clamp-2 ${isActive ? "text-gold-deep" : "text-text-primary"}`}>
        {section.label}
      </div>

      {/* Badge — reserved row at the bottom; absent badge becomes empty space */}
      <div className="mt-auto min-h-[1rem]">
        {loading && !badge ? (
          <div className="h-2.5 w-20 rounded bg-parchment motion-safe:animate-pulse" />
        ) : badge ? (
          <div className="flex items-center gap-1 text-[0.68rem] text-text-muted truncate">
            {badge.tone ? (
              <span
                className={`inline-block h-1.5 w-1.5 rounded-full shrink-0 ${
                  TONE_DOT_CLASSES[badge.tone] ?? "bg-text-muted"
                }`}
                aria-hidden="true"
              />
            ) : null}
            <span className="truncate">{badge.text}</span>
          </div>
        ) : null}
      </div>

      {/* Active accent bar at bottom-inner */}
      {isActive ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-x-3 bottom-1 h-0.5 rounded-full bg-gradient-to-r from-gold to-gold-deep"
        />
      ) : null}
    </Link>
  );
}

"use client";

import Link from "next/link";
import { type SectionDef } from "../../_data/projectNav";
import type { HubBadge } from "../../_data/useHubBadges";
import { MIcon } from "./MIcon";
import { roleClassesFor, sectionMsIcon } from "./hubM3";

interface Props {
  section: SectionDef;
  projectId: string;
  isActive: boolean;
  badge?: HubBadge;
  loading?: boolean;
}

/** Badge tone → coloured dot token (success/warn/danger/info). */
const TONE_DOT: Record<NonNullable<HubBadge["tone"]>, string> = {
  success: "bg-secondary",
  warn: "bg-tertiary",
  danger: "bg-error",
  info: "bg-primary",
};

export function QuickAccessTile({
  section,
  projectId,
  isActive,
  badge,
  loading = false,
}: Props) {
  // Roomy tile (matches the mockup): a dark surface-container base with a clean
  // role-colour glow in the top-left corner (fades to transparent — never
  // muddy), a large icon chip, and label + sub at the bottom. `.vibrant-glass`
  // supplies the hover lift + border; tokens flip with light/dark.
  const role = roleClassesFor(section.group);

  return (
    <Link
      href={section.href(projectId)}
      aria-current={isActive ? "page" : undefined}
      title={section.description}
      className={`vibrant-glass group relative flex min-h-[150px] flex-col justify-between overflow-hidden rounded-2xl bg-surface-container p-4 ring-1 ring-white/5 hover:ring-white/10 ${
        isActive ? "ring-2 ring-primary" : ""
      }`}
    >
      {/* Clean role glow — bright accent at low opacity over the dark base */}
      <span
        aria-hidden="true"
        className={`pointer-events-none absolute inset-0 bg-gradient-to-br ${role.tileTint} to-transparent`}
      />

      <span
        className={`relative flex h-14 w-14 items-center justify-center rounded-2xl ${role.iconBg}`}
      >
        <MIcon name={sectionMsIcon(section.id)} className="text-[28px]" />
      </span>

      <div className="relative">
        <p className="text-[0.95rem] font-semibold leading-tight text-on-surface line-clamp-2">
          {section.label}
        </p>
        {badge ? (
          <p className="mt-1 flex items-center gap-1 truncate text-xs text-text-secondary">
            {badge.tone ? (
              <span
                aria-hidden="true"
                className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${TONE_DOT[badge.tone]}`}
              />
            ) : null}
            {badge.text}
          </p>
        ) : loading ? (
          <div className="mt-1.5 h-2.5 w-16 animate-pulse rounded bg-white/10" />
        ) : null}
      </div>
    </Link>
  );
}

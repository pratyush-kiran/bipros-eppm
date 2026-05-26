"use client";
import Link from "next/link";
import { type SectionDef } from "../../_data/projectNav";

interface Props {
  section: SectionDef;
  projectId: string;
  /** small inline badge after the label, e.g. "39" or "2 pending" */
  badgeText?: string;
}

/**
 * Footer chip used inside bento group cards. Renders a link to the section's
 * route with its icon + label and, optionally, a tiny trailing badge.
 *
 * Two visual variants:
 *  - `primary`   sections get a solid ivory chip
 *  - `secondary` sections get a dashed border + ✦ marker so the user can tell
 *                "this is a less-used / deep-dive section" at a glance.
 */
export function SectionChip({ section, projectId, badgeText }: Props) {
  const Icon = section.icon;
  const isSecondary = section.priority === "secondary";

  const base =
    "inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-[0.72rem] font-medium transition-colors";
  const primary =
    "bg-ivory text-text-primary border border-border hover:bg-gold-tint hover:border-gold/55";
  const secondary =
    "bg-surface text-text-secondary border border-dashed border-border hover:bg-gold-tint hover:text-text-primary hover:border-gold/55 hover:border-solid";

  return (
    <Link
      href={section.href(projectId)}
      title={section.description}
      className={`${base} ${isSecondary ? secondary : primary}`}
    >
      {isSecondary ? (
        <span className="text-gold-deep text-[0.55rem]" aria-hidden="true">✦</span>
      ) : null}
      <Icon className="h-3.5 w-3.5" strokeWidth={2.25} aria-hidden="true" />
      <span>{section.label}</span>
      {badgeText ? (
        <span className="text-text-muted text-[0.62rem] ml-0.5">{badgeText}</span>
      ) : null}
    </Link>
  );
}

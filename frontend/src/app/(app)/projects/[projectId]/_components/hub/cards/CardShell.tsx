"use client";
import { type ReactNode } from "react";
import {
  findGroupById,
  getSectionsByGroup,
  filterByPermission,
  type SectionDef,
  type SectionGroupId,
} from "../../../_data/projectNav";
import { GroupChip } from "../../nav/GroupChip";
import { SectionChip } from "../../nav/SectionChip";
import { useAuthStore } from "@/lib/state/store";

interface Props {
  groupId: SectionGroupId;
  title: string;
  subtitle: string;
  projectId: string;
  /** small right-aligned pill in the header (e.g. "Today logged") */
  statusPill?: ReactNode;
  /** body content (charts, lists, etc.) */
  children: ReactNode;
  /**
   * Optional override: by default the footer shows every section in the group.
   * Pass to override (e.g. to filter to a subset).
   */
  footerSections?: SectionDef[];
  /** Optional per-section badge text map shown on the footer chips. */
  chipBadges?: Partial<Record<string, string>>;
  /** When true, the body region is announced as busy to assistive tech. */
  isLoading?: boolean;
}

/**
 * Shared structural shell for every bento group card. Keeps the accent bar,
 * header (group chip + title + subtitle + status pill), body slot and
 * permission-filtered section footer in one place so individual cards only
 * worry about their own data + body visuals.
 */
export function CardShell({
  groupId,
  title,
  subtitle,
  projectId,
  statusPill,
  children,
  footerSections,
  chipBadges,
  isLoading = false,
}: Props) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const group = findGroupById(groupId);
  const sections = filterByPermission(
    footerSections ?? getSectionsByGroup(groupId),
    hasPermission,
  );

  return (
    <article className="relative overflow-hidden rounded-2xl border border-border bg-surface p-5 shadow-sm transition-shadow hover:shadow-md">
      {/* Accent bar on left side */}
      {group ? (
        <span
          className={`absolute left-0 top-4 bottom-4 w-1 rounded-r-md ${group.accentClass}`}
          aria-hidden="true"
        />
      ) : null}

      <header className="flex items-start justify-between gap-3 mb-4 pl-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <GroupChip groupId={groupId} />
            {statusPill}
          </div>
          <h3 className="font-serif text-xl text-text-primary mt-2">{title}</h3>
          <p className="text-sm text-text-secondary mt-0.5">{subtitle}</p>
        </div>
        <span className="text-text-muted text-xl" aria-hidden="true">↗</span>
      </header>

      <div className="pl-2" aria-busy={isLoading}>{children}</div>

      {sections.length > 0 ? (
        <footer className="mt-4 border-t border-border pt-3 pl-2">
          <div className="flex flex-wrap gap-1.5">
            {sections.map((s) => (
              <SectionChip
                key={s.id}
                section={s}
                projectId={projectId}
                badgeText={chipBadges?.[s.id]}
              />
            ))}
          </div>
        </footer>
      ) : null}
    </article>
  );
}

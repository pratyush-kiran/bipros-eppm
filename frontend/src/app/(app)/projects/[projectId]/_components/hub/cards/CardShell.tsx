"use client";
import { type ReactNode } from "react";
import Link from "next/link";
import {
  getSectionsByGroup,
  filterByPermission,
  type SectionDef,
  type SectionGroupId,
} from "../../../_data/projectNav";
import { useAuthStore } from "@/lib/state/store";
import { MIcon } from "../MIcon";
import { roleClassesFor, sectionMsIcon } from "../hubM3";

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
  /** Optional per-section badge text map shown on the footer links. */
  chipBadges?: Partial<Record<string, string>>;
  /** When true, the body region is announced as busy to assistive tech. */
  isLoading?: boolean;
}

/**
 * Shared structural shell for every bento group card. A Material-3 dark glass
 * card with a role-coloured top accent, status chip + title header, body slot,
 * and a permission-filtered footer of real section navigation links. Individual
 * cards only worry about their own data + body visuals.
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
  const sections = filterByPermission(
    footerSections ?? getSectionsByGroup(groupId),
    hasPermission,
  );

  const role = roleClassesFor(groupId);

  return (
    <article
      className={`glass-card flex h-full flex-col overflow-hidden rounded-2xl border-t-4 transition-all hover:-translate-y-0.5 ${role.border}`}
    >
      <div className="flex items-start justify-between p-5">
        <div className="min-w-0">
          {statusPill}
          <h4 className="mt-2 font-sans text-[1.35rem] font-semibold leading-tight text-on-surface">
            {title}
          </h4>
          <p className="mt-0.5 text-sm text-text-secondary">{subtitle}</p>
        </div>
        <button
          type="button"
          aria-label="Open"
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/5 text-on-surface-variant transition-colors hover:bg-white/10"
        >
          <MIcon name="open_in_full" className="text-sm" />
        </button>
      </div>

      <div className="flex-1 px-5 pb-5" aria-busy={isLoading}>
        {children}
      </div>

      {sections.length > 0 ? (
        <footer className="mt-auto flex flex-wrap gap-4 border-t border-white/5 bg-surface-container/50 px-5 py-3">
          {sections.map((s) => (
            <Link
              key={s.id}
              href={s.href(projectId)}
              className="flex items-center gap-2 text-xs text-text-secondary transition-colors hover:text-on-surface"
            >
              <MIcon name={sectionMsIcon(s.id)} className="text-sm" />
              <span>{s.label}</span>
              {chipBadges?.[s.id] ? (
                <span className="text-[10px] text-text-muted">
                  {chipBadges[s.id]}
                </span>
              ) : null}
            </Link>
          ))}
        </footer>
      ) : null}
    </article>
  );
}

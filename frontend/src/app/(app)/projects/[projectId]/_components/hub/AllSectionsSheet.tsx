"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogBody,
} from "@/components/ui/dialog";
import { useAuthStore } from "@/lib/state/store";
import {
  SECTION_GROUPS,
  getSectionsByGroup,
  filterByPermission,
  type SectionDef,
} from "../../_data/projectNav";
import { useHubBadges } from "../../_data/useHubBadges";
import { GroupChip } from "../nav/GroupChip";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: string;
}

export function AllSectionsSheet({ open, onOpenChange, projectId }: Props) {
  const router = useRouter();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { badges } = useHubBadges(projectId);
  const [query, setQuery] = useState("");

  // Pre-filter every group by permission, then by search query.
  const groupedSections = useMemo(() => {
    const q = query.trim().toLowerCase();
    return SECTION_GROUPS.map((g) => {
      const visible = filterByPermission(getSectionsByGroup(g.id), hasPermission);
      const filtered = q
        ? visible.filter((s) => {
            const hay = `${s.label} ${s.description} ${s.searchKeywords ?? ""}`.toLowerCase();
            return hay.includes(q);
          })
        : visible;
      return { group: g, sections: filtered };
    }).filter((entry) => entry.sections.length > 0);
  }, [query, hasPermission]);

  const visibleCount = groupedSections.reduce((sum, e) => sum + e.sections.length, 0);
  const totalCount = SECTION_GROUPS.reduce(
    (sum, g) => sum + filterByPermission(getSectionsByGroup(g.id), hasPermission).length,
    0,
  );

  const go = (s: SectionDef) => {
    router.push(s.href(projectId));
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="p-0 flex flex-col bg-paper max-h-[88vh]"
        style={{ width: "min(1080px, 96vw)", maxWidth: "min(1080px, 96vw)" }}
      >
        <DialogHeader className="border-b border-border px-6 py-4 flex items-start justify-between gap-4 shrink-0 pt-4">
          <div>
            <div className="text-[10px] tracking-[0.18em] uppercase text-text-muted font-bold">
              Project · All Sections
            </div>
            <DialogTitle className="font-serif text-2xl text-text-primary mt-1">
              Every section in this project
            </DialogTitle>
            <div className="text-xs text-text-secondary mt-1" aria-live="polite">
              {totalCount} total
              {query.length > 0 ? ` · ${visibleCount} match` : ""}
              {" · "}grouped by purpose
            </div>
          </div>
          <div className="relative shrink-0 mr-8">
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Filter sections…"
              aria-label="Filter sections by name or description"
              className="w-56 rounded-lg border border-border bg-surface pl-8 pr-3 py-1.5 text-sm placeholder:text-text-muted focus:border-gold focus:outline-none"
            />
            <span
              className="absolute left-2.5 top-1/2 -translate-y-1/2 text-text-muted text-xs"
              aria-hidden="true"
            >
              🔍
            </span>
          </div>
        </DialogHeader>

        <DialogBody className="flex-1 overflow-y-auto px-6 py-5 min-h-0">
          {groupedSections.length === 0 ? (
            <div className="py-10 text-center text-text-muted">
              <p>No sections match &ldquo;{query}&rdquo;.</p>
            </div>
          ) : (
            groupedSections.map(({ group, sections }) => (
              <section key={group.id} className="mt-5 first:mt-0">
                <header className="mb-2.5 flex items-center gap-2">
                  <GroupChip groupId={group.id} />
                  <span className="text-xs text-text-muted">
                    {sections.length} section{sections.length === 1 ? "" : "s"} · {group.blurb}
                  </span>
                </header>
                <ul
                  role="list"
                  className="grid grid-cols-3 gap-2 max-[900px]:grid-cols-2 max-[560px]:grid-cols-1"
                >
                  {sections.map((s) => {
                    const Icon = s.icon;
                    const badge = badges[s.id];
                    return (
                      <li key={s.id}>
                        <button
                          type="button"
                          onClick={() => go(s)}
                          className="group relative flex w-full items-start gap-2.5 rounded-xl border border-border bg-ivory p-3 text-left transition hover:bg-gold-tint hover:border-gold/55 motion-safe:hover:-translate-y-0.5"
                        >
                          <span
                            className={`inline-flex h-7 w-7 items-center justify-center rounded-lg shrink-0 ${group.iconChipClass}`}
                            aria-hidden="true"
                          >
                            <Icon className="h-4 w-4" strokeWidth={2.25} />
                          </span>
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-1.5">
                              <span className="text-sm font-semibold text-text-primary leading-tight">
                                {s.label}
                              </span>
                              {badge ? (
                                <span className="text-[0.62rem] text-text-muted">
                                  {badge.text}
                                </span>
                              ) : null}
                            </div>
                            <div className="text-[0.68rem] text-text-muted leading-snug mt-0.5">
                              {s.description}
                            </div>
                          </div>
                          {s.priority === "primary" ? (
                            <span
                              className="absolute top-1.5 right-1.5 rounded-full bg-gold-tint px-1.5 py-0.5 text-[0.55rem] font-bold uppercase tracking-wider text-gold-deep"
                              title="Also pinned to Quick Access"
                            >
                              ⚡ Quick
                            </span>
                          ) : null}
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))
          )}
        </DialogBody>
      </DialogContent>
    </Dialog>
  );
}

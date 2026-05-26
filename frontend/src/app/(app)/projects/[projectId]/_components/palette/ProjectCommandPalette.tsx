"use client";

/**
 * Phase F — ⌘K Command Palette.
 *
 * Modal overlay listing every project section grouped by section group.
 * We use cmdk's plain {@link Command} primitive (NOT Command.Dialog) wrapped
 * in our own modal scaffold so we avoid pulling in cmdk's transitive
 * @radix-ui/react-dialog dependency — which emits screen-reader warnings
 * about missing DialogTitle / Description when its assumed accessibility
 * props are absent. The plain Command primitive still gives us fuzzy match,
 * keyboard nav, and aria-selected styling; the surrounding modal mechanics
 * (Esc to close, backdrop click, focus capture/restore, body scroll lock)
 * live in this file and mirror the patterns in our shared Dialog component.
 *
 * Permission-gated entries (Costs, Risks) are filtered out via
 * {@link filterByPermission} so the palette never surfaces an action the
 * user can't perform. Badges piggyback on the shared {@link useHubBadges}
 * cache — when the palette opens on a section page (where the hub never
 * mounted), the bag is simply empty and items render without badges.
 */

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { Command } from "cmdk";
import { useAuthStore } from "@/lib/state/store";
import {
  SECTION_GROUPS,
  getSectionsByGroup,
  filterByPermission,
  type SectionDef,
} from "../../_data/projectNav";
import { useHubBadges } from "../../_data/useHubBadges";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: string;
}

export function ProjectCommandPalette({ open, onOpenChange, projectId }: Props) {
  const router = useRouter();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { badges } = useHubBadges(projectId);

  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Modal mechanics: Esc-to-close, body scroll lock, focus capture & restore.
  useEffect(() => {
    if (!open) return;

    previouslyFocusedRef.current = document.activeElement as HTMLElement | null;

    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onOpenChange(false);
      }
    };
    document.addEventListener("keydown", onKey);

    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      // Restore focus to the element that opened the palette.
      const prev = previouslyFocusedRef.current;
      if (prev && typeof prev.focus === "function") {
        prev.focus();
      }
    };
  }, [open, onOpenChange]);

  if (!open) return null;

  const go = (s: SectionDef) => {
    router.push(s.href(projectId));
    onOpenChange(false);
  };

  const onBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) onOpenChange(false);
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Project command palette"
      onClick={onBackdropClick}
      className="fixed inset-0 z-[60] flex items-start justify-center bg-charcoal/55 backdrop-blur p-4 pt-[8vh]"
    >
      <div
        ref={containerRef}
        className="w-[min(640px,92vw)] overflow-hidden rounded-2xl border border-border bg-surface shadow-2xl"
        // Stop click events from bubbling up to the backdrop-close handler.
        onClick={(e) => e.stopPropagation()}
      >
        <Command label="Project command palette" loop>
          <div className="flex items-center gap-2 border-b border-border px-4 py-3">
            <span className="text-xs font-mono text-text-muted">⌘K</span>
            <Command.Input
              autoFocus
              placeholder="Jump to a section, or type to search…"
              className="flex-1 bg-transparent text-base text-text-primary placeholder:text-text-muted focus:outline-none"
            />
            <kbd className="rounded border border-border bg-ivory px-1.5 py-0.5 text-[0.65rem] font-mono text-text-muted">
              esc
            </kbd>
          </div>

          <Command.List className="max-h-[60vh] overflow-y-auto px-2 py-2">
            <Command.Empty className="px-3 py-6 text-center text-sm text-text-muted">
              No sections match.
            </Command.Empty>

            {SECTION_GROUPS.map((group) => {
              const sections = filterByPermission(
                getSectionsByGroup(group.id),
                hasPermission,
              );
              if (sections.length === 0) return null;

              return (
                <Command.Group
                  key={group.id}
                  heading={group.label}
                  className="text-text-muted"
                >
                  {sections.map((s) => {
                    const Icon = s.icon;
                    const badge = badges[s.id];
                    const value = `${s.label} ${s.description} ${s.searchKeywords ?? ""} ${group.label}`;
                    return (
                      <Command.Item
                        key={s.id}
                        value={value}
                        onSelect={() => go(s)}
                        className="flex items-center gap-3 rounded-lg px-2.5 py-2 text-sm cursor-pointer aria-selected:bg-gold-tint data-[selected=true]:bg-gold-tint hover:bg-parchment"
                      >
                        <span
                          className={`inline-flex h-8 w-8 items-center justify-center rounded-md shrink-0 ${group.iconChipClass}`}
                          aria-hidden="true"
                        >
                          <Icon className="h-4 w-4" strokeWidth={2.25} />
                        </span>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-text-primary">{s.label}</span>
                            {badge ? (
                              <span className="text-[0.65rem] text-text-muted">{badge.text}</span>
                            ) : null}
                          </div>
                          <div className="text-[0.7rem] text-text-muted">
                            {group.label} · {s.description}
                          </div>
                        </div>
                      </Command.Item>
                    );
                  })}
                </Command.Group>
              );
            })}
          </Command.List>

          <div className="border-t border-border px-4 py-2 text-[0.65rem] text-text-muted flex items-center justify-between">
            <span>Tip: press ⌘K from anywhere in the project</span>
            <span>↑↓ navigate · ↵ open · esc close</span>
          </div>
        </Command>
      </div>
    </div>
  );
}

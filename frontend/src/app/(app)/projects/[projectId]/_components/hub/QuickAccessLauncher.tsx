"use client";

import { useMemo, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { useAuthStore } from "@/lib/state/store";
import {
  SECTIONS,
  filterByPermission,
  getPrimarySections,
  resolveActiveSection,
} from "../../_data/projectNav";
import { useHubBadges } from "../../_data/useHubBadges";
import { QuickAccessTile } from "./QuickAccessTile";
import { AllSectionsSheet } from "./AllSectionsSheet";
import { MIcon } from "./MIcon";

export function QuickAccessLauncher({ projectId }: { projectId: string }) {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { badges, loading } = useHubBadges(projectId);
  const [allSectionsOpen, setAllSectionsOpen] = useState(false);

  const active = resolveActiveSection(
    pathname,
    new URLSearchParams(searchParams.toString()),
    projectId,
  );

  const tiles = filterByPermission(getPrimarySections(), hasPermission);

  const secondaryCount = useMemo(() => {
    return filterByPermission(
      SECTIONS.filter((s) => s.priority === "secondary"),
      hasPermission,
    ).length;
  }, [hasPermission]);

  return (
    <section className="mb-6" aria-label="Quick access">
      <div className="mb-2.5 flex items-center justify-between">
        <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.18em] text-text-muted">
          <span className="flex items-center gap-1.5">
            <MIcon name="bolt" className="text-tertiary text-sm" />
            Quick Access
          </span>
          <span className="text-[10px] font-normal normal-case tracking-normal text-text-muted/70">
            — the sections you use every day, one click away
          </span>
        </div>
      </div>

      <ul
        role="list"
        aria-busy={loading}
        className="grid grid-cols-2 sm:grid-cols-4 md:grid-cols-7 gap-4 md:gap-5"
      >
        {tiles.map((s) => {
          // The hub is its own landing surface — it is NOT the Overview page.
          // Tiles are pure navigation links of equal status; none is "active"
          // when the user is on the hub. Active state only shows when the
          // user has drilled into a specific section (and even then the
          // launcher isn't visible — section pages render the slim context
          // bar instead). Keeping the prop wired for future surfaces that
          // might mount the launcher in-section.
          const isActive = active?.id === s.id;
          return (
            <li key={s.id}>
              <QuickAccessTile
                section={s}
                projectId={projectId}
                isActive={isActive}
                badge={badges[s.id]}
                loading={loading && !badges[s.id]}
              />
            </li>
          );
        })}

        {/* Dashed "Show all" tile — last grid item, opens the sections sheet. */}
        <li>
          <button
            type="button"
            onClick={() => setAllSectionsOpen(true)}
            className="vibrant-glass flex min-h-[150px] w-full flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-white/10 bg-surface-container/40 p-4 transition-colors hover:border-primary/50 hover:bg-surface-container"
          >
            <MIcon name="grid_view" className="text-[28px] text-text-secondary" />
            <p className="text-center text-[10px] uppercase tracking-widest text-text-secondary">
              Show all{" "}
              <span className="font-semibold text-primary">{secondaryCount} more</span>
            </p>
          </button>
        </li>
      </ul>

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <span className="text-[11px] text-text-muted">
          Or press{" "}
          <kbd className="rounded border border-border bg-ivory px-1 py-0.5 text-[0.62rem] font-mono">
            ⌘K
          </kbd>{" "}
          to jump anywhere
        </span>
      </div>

      <AllSectionsSheet
        open={allSectionsOpen}
        onOpenChange={setAllSectionsOpen}
        projectId={projectId}
      />
    </section>
  );
}

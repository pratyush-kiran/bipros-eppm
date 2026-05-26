"use client";

import { useMemo, useState } from "react";
import { LayoutGrid } from "lucide-react";
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
          <span>⚡ Quick Access</span>
          <span className="text-[10px] font-normal normal-case tracking-normal text-text-muted/70">
            — the sections you use every day, one click away
          </span>
        </div>
      </div>

      <ul
        role="list"
        aria-busy={loading}
        className="grid grid-cols-7 gap-2.5 max-[1100px]:grid-cols-4 max-[700px]:grid-cols-2"
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
      </ul>

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() => setAllSectionsOpen(true)}
          className="group inline-flex items-center gap-2.5 rounded-xl border border-gold/40 bg-gradient-to-br from-gold-tint to-surface px-4 py-2.5 text-sm font-semibold text-gold-deep transition-all hover:from-gold-tint hover:border-gold hover:shadow-sm motion-safe:hover:-translate-y-0.5"
        >
          <LayoutGrid className="h-4 w-4" strokeWidth={2.25} aria-hidden="true" />
          <span>Show all sections</span>
          <span className="rounded-md border border-gold/30 bg-surface px-1.5 py-0.5 text-[0.62rem] font-mono text-gold-deep">
            {secondaryCount} more
          </span>
        </button>
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

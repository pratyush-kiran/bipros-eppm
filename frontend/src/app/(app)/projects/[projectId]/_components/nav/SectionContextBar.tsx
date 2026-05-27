"use client";
import Link from "next/link";
import { useParams, usePathname, useSearchParams } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { resolveActiveSection } from "../../_data/projectNav";
import { GroupChip } from "./GroupChip";
import { DashboardsMenu } from "./DashboardsMenu";
import { useCommandPalette } from "../palette/CommandPaletteProvider";

export function SectionContextBar() {
  const params = useParams();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const projectId = params.projectId as string;
  const { openPalette } = useCommandPalette();

  const active = resolveActiveSection(pathname, new URLSearchParams(searchParams.toString()), projectId);
  // If we're on the hub itself we shouldn't be rendered, but bail safely
  if (!active) return null;

  return (
    // `relative` so the `before:` gold-gradient hairline sits inside the bar.
    // Solid surface in light theme, deeper surface in dark — dropping the
    // earlier `bg-surface/85 backdrop-blur` because translucent + blur read as
    // invisible on the light canvas. `#1F1F1F` is the intentional dark anchor
    // (matches the dark variant of the HeroPulse gradient).
    <div
      className="sticky top-0 z-30 relative border-b border-border bg-surface shadow-sm
                 dark:bg-[#1F1F1F] dark:border-white/10
                 before:absolute before:inset-x-0 before:top-0 before:h-[2px]
                 before:bg-gradient-to-r before:from-transparent before:via-gold before:to-transparent
                 before:opacity-60"
    >
      {/* Inner row has an extra px-4 sm:px-6 so the bar's items pick up the
          same "breathing room" inset that the section content below gets via
          the layout wrapper. Total side-margin for items = AppShell padding
          (px-4/6/8) + this row's padding (px-4/6). */}
      <div className="flex h-[56px] items-center justify-between gap-4 px-4 sm:px-6">
        <div className="flex min-w-0 items-center gap-3">
          <Link
            href={`/projects/${projectId}`}
            className="inline-flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm font-medium
                       text-text-secondary hover:bg-gold-tint hover:text-gold-deep
                       dark:hover:bg-white/10 dark:hover:text-white transition-colors"
          >
            <ArrowLeft size={14} />
            Project Hub
          </Link>
          <span className="text-text-muted" aria-hidden="true">·</span>
          <GroupChip groupId={active.group} />
          <div className="truncate font-serif text-lg text-text-primary dark:text-white">
            {active.label}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={openPalette}
            className="inline-flex items-center gap-2 rounded-lg bg-parchment px-3 py-1.5 text-sm font-medium text-text-secondary hover:bg-gold-tint hover:text-text-primary transition-colors"
            aria-label="Open command palette"
          >
            ⌘K Jump to…
            <kbd className="rounded border border-border bg-ivory px-1 py-0.5 text-[0.62rem] font-mono">⌘K</kbd>
          </button>
          <DashboardsMenu projectId={projectId} variant="bar" />
        </div>
      </div>
    </div>
  );
}

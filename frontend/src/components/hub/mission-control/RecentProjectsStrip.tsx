"use client";

import Link from "next/link";
import { FolderTree, History } from "lucide-react";
import { useRecentProjects } from "@/hooks/useRecentProjects";

const VISIBLE = 4;

export function RecentProjectsStrip() {
  const { recents, hydrated } = useRecentProjects();

  // Pre-hydration we don't know what the user has visited; rendering an empty
  // section and expanding it post-hydration would shift the rest of the page.
  if (!hydrated) return null;
  if (recents.length === 0) return null;

  const shown = recents.slice(0, VISIBLE);

  return (
    <section data-testid="mc-recent-projects" className="mb-8">
      <div className="mb-3 flex items-baseline gap-3 px-1">
        <h2 className="relative font-display text-[11px] font-semibold uppercase tracking-[0.2em] text-charcoal/70">
          <span
            aria-hidden
            className="absolute -left-3 top-1/2 hidden h-px w-2 -translate-y-1/2 bg-gold/60 sm:block"
          />
          Recent projects
        </h2>
        <span className="hidden text-[12px] text-slate sm:inline">
          Pick up where you left off.
        </span>
        {recents.length > VISIBLE && (
          <Link
            href="/projects"
            className="ml-auto text-[11px] font-medium text-slate transition-colors hover:text-gold-deep"
          >
            See all ›
          </Link>
        )}
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {shown.map((p) => (
          <Link
            key={p.id}
            href={`/projects/${p.id}`}
            data-testid="mc-recent-project"
            data-project-id={p.id}
            className="group flex items-center gap-3 rounded-2xl border border-hairline bg-paper px-4 py-3.5 shadow-[0_1px_2px_rgba(28,28,28,0.04)] transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/40 hover:shadow-[0_10px_28px_-16px_rgba(212,175,55,0.3)]"
          >
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-hairline bg-ivory/50 text-gold-deep">
              <FolderTree size={16} strokeWidth={1.75} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate font-display text-[15px] font-semibold leading-tight tracking-tight text-charcoal">
                {p.name}
              </div>
              <div className="mt-0.5 flex items-center gap-1.5 truncate text-[10.5px] font-medium uppercase tracking-[0.1em] text-slate">
                <span className="truncate">{p.code}</span>
                <span aria-hidden>·</span>
                <History size={9} strokeWidth={1.75} className="shrink-0" />
                <span className="shrink-0">{relativeTime(p.visitedAt)}</span>
              </div>
            </div>
            <span
              aria-hidden
              className="shrink-0 text-gold-deep opacity-0 transition-opacity duration-200 group-hover:opacity-100"
            >
              ›
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}

function relativeTime(ts: number): string {
  const diffMs = Date.now() - ts;
  const sec = Math.floor(diffMs / 1000);
  if (sec < 60) return "just now";
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const days = Math.floor(hr / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 5) return `${weeks}w ago`;
  return new Date(ts).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
  });
}

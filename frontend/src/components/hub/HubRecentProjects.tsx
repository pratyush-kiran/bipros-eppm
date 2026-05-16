"use client";

import Link from "next/link";
import { FolderTree } from "lucide-react";
import { useRecentProjects } from "@/hooks/useRecentProjects";

const VISIBLE = 3;

export function HubRecentProjects() {
  const { recents, hydrated } = useRecentProjects();

  // Pre-hydration we don't know what the user has visited, and rendering an
  // empty section then expanding it post-hydration would shift the rest of
  // the hub. Render nothing until localStorage has been read.
  if (!hydrated) return null;
  if (recents.length === 0) return null;

  const shown = recents.slice(0, VISIBLE);

  return (
    <section data-testid="hub-recent-projects" className="mb-8">
      <div className="mb-3 flex items-baseline justify-between px-1">
        <h2 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ash">
          Recent projects
        </h2>
        {recents.length > VISIBLE && (
          <Link
            href="/projects"
            className="text-[11px] font-medium text-slate transition-colors hover:text-gold-deep"
          >
            See all ›
          </Link>
        )}
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {shown.map((p) => (
          <Link
            key={p.id}
            href={`/projects/${p.id}`}
            data-testid="hub-recent-project"
            data-project-id={p.id}
            className="group flex items-center gap-3 rounded-2xl border border-hairline bg-paper px-4 py-3.5 shadow-[0_1px_2px_rgba(28,28,28,0.03)] transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_8px_24px_-14px_rgba(212,175,55,0.25)]"
          >
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-hairline bg-ivory/40 text-gold-deep">
              <FolderTree size={15} strokeWidth={1.75} />
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate font-display text-[15px] font-semibold leading-tight tracking-tight text-charcoal">
                {p.name}
              </div>
              <div className="mt-0.5 truncate text-[11px] font-medium uppercase tracking-[0.1em] text-slate">
                {p.code} · {relativeTime(p.visitedAt)}
              </div>
            </div>
            <span
              aria-hidden
              className="text-gold-deep opacity-0 transition-opacity group-hover:opacity-100"
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

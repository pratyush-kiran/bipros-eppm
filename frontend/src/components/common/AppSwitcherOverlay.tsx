"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { FolderTree, History, Search, X } from "lucide-react";
import { ModuleTile } from "@/components/hub/mission-control/ModuleTile";
import { useModuleAccess } from "@/components/hub/mission-control/hooks/useModuleAccess";
import { MODULE_SECTIONS } from "@/components/hub/mission-control/modulesConfig";
import { useRecentProjects } from "@/hooks/useRecentProjects";

interface Props {
  open: boolean;
  onClose: () => void;
}

const MAX_RECENT_SHOWN = 4;

export function AppSwitcherOverlay({ open, onClose }: Props) {
  const { canSee } = useModuleAccess();
  const { recents, hydrated: recentsHydrated } = useRecentProjects();
  const [query, setQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  // `mounted` gates the portal call until `document.body` exists on the client.
  // `createPortal` against an undefined target would crash on SSR.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open) return;
    setQuery("");
    const id = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open]);

  if (!open || !mounted) return null;

  const q = query.trim().toLowerCase();
  const matchesQuery = (a: string, b: string) =>
    !q || a.toLowerCase().includes(q) || b.toLowerCase().includes(q);

  const visibleSections = MODULE_SECTIONS.map((section) => ({
    label: section.label,
    intro: section.intro,
    tiles: section.tiles.filter(
      (t) => canSee(t) && matchesQuery(t.title, t.description),
    ),
  })).filter((s) => s.tiles.length > 0);

  const visibleRecents = recents
    .filter((r) => matchesQuery(r.name, r.code))
    .slice(0, MAX_RECENT_SHOWN);

  const isEmpty = visibleSections.length === 0 && visibleRecents.length === 0;

  // Portal to document.body so the overlay escapes the sticky Header's stacking
  // context (z-30) — otherwise sub-page chrome like the project-layout sticky
  // tab strip (also z-30) renders ABOVE the overlay because DOM order wins.
  return createPortal(
    <>
      {/* Backdrop starts BELOW the 64px Header so the main navbar (brand,
          switcher, breadcrumbs, search, notifications, theme, user) stays crisp
          and interactive — the overlay reads as a sheet attached to the header,
          not a full-page takeover. */}
      <div
        aria-hidden
        className="fixed inset-x-0 bottom-0 top-16 z-[100] bg-charcoal/40 backdrop-blur-md"
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-label="App switcher"
        data-testid="app-switcher-overlay"
        className="fixed inset-x-0 top-16 z-[101] max-h-[calc(100vh-4rem)] overflow-y-auto bg-paper shadow-[0_32px_80px_-24px_rgba(0,0,0,0.45)] ring-1 ring-hairline"
      >
        {/* Gold gradient hairline at the seam between header and sheet. */}
        <div
          aria-hidden
          className="pointer-events-none absolute inset-x-0 top-0 h-px"
          style={{
            background:
              "linear-gradient(90deg, transparent, #D4AF37 18%, #D4AF37 82%, transparent)",
            opacity: 0.5,
          }}
        />
        <div className="mx-auto max-w-[1600px] px-6 py-7 sm:px-10 sm:py-9">
          {/* Editorial header */}
          <div className="mb-6 flex items-end justify-between gap-4">
            <div>
              <p
                className="font-display text-[26px] font-semibold leading-none tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                Where to next?
              </p>
              <p className="mt-2 text-[12.5px] text-slate">
                Jump to a project, module, or recent place. Press{" "}
                <kbd className="rounded border border-hairline bg-ivory px-1.5 py-0.5 font-mono text-[10px] text-charcoal/80">
                  ⌘&nbsp;/
                </kbd>{" "}
                to reopen,{" "}
                <kbd className="rounded border border-hairline bg-ivory px-1.5 py-0.5 font-mono text-[10px] text-charcoal/80">
                  Esc
                </kbd>{" "}
                to close.
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close app switcher"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-slate transition-colors hover:bg-ivory hover:text-charcoal"
            >
              <X size={16} strokeWidth={1.75} />
            </button>
          </div>

          {/* Search — taller, softer, gold focus halo */}
          <div className="mb-8 flex h-12 items-center gap-3 rounded-2xl border border-hairline bg-ivory px-4 transition-colors focus-within:border-gold-deep/60 focus-within:ring-2 focus-within:ring-gold/15">
            <Search size={16} className="text-ash" strokeWidth={1.5} />
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Filter modules and projects…"
              className="flex-1 bg-transparent text-[14px] text-charcoal placeholder:text-slate outline-none"
            />
            {!q && (
              <span className="hidden text-[11px] text-ash sm:inline">
                Type to filter
              </span>
            )}
          </div>

          {recentsHydrated && visibleRecents.length > 0 && (
            <section className="mb-9">
              <SectionHeading label="Recent projects" />
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                {visibleRecents.map((p) => (
                  <Link
                    key={p.id}
                    href={`/projects/${p.id}`}
                    onClick={onClose}
                    className="group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/40 hover:shadow-[0_14px_32px_-18px_rgba(212,175,55,0.45)]"
                  >
                    <span
                      aria-hidden
                      className="pointer-events-none absolute -right-12 -top-12 h-32 w-32 rounded-full blur-3xl transition-opacity duration-300"
                      style={{
                        background: "#D4AF37",
                        opacity: 0.06,
                      }}
                    />
                    <div className="relative flex items-start justify-between gap-3">
                      <span className="flex h-10 w-10 items-center justify-center rounded-xl border border-hairline bg-ivory text-gold-deep">
                        <FolderTree size={15} strokeWidth={1.75} />
                      </span>
                      <span className="inline-flex items-center gap-1 rounded-full border border-gold/30 bg-[color-mix(in_srgb,#D4AF37_8%,var(--paper))] px-2 py-0.5 text-[9px] font-semibold uppercase tracking-[0.12em] text-gold-deep">
                        <History size={9} strokeWidth={1.75} />
                        Recent
                      </span>
                    </div>
                    <h3 className="relative mt-3.5 line-clamp-1 font-display text-[16px] font-semibold leading-tight tracking-tight text-charcoal">
                      {p.name}
                    </h3>
                    <p className="relative mt-0.5 text-[10.5px] font-medium uppercase tracking-[0.1em] text-slate">
                      {p.code}
                    </p>
                  </Link>
                ))}
              </div>
            </section>
          )}

          {visibleSections.map((section) => (
            <section key={section.label} className="mb-9 last:mb-0">
              <SectionHeading label={section.label} intro={section.intro} />
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                {section.tiles.map((tile) => (
                  <ModuleTile
                    key={tile.key}
                    tile={tile}
                    variant="compact"
                    onClick={onClose}
                  />
                ))}
              </div>
            </section>
          ))}

          {isEmpty && (
            <div className="py-16 text-center">
              <p
                className="font-display text-[18px] font-semibold text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {q ? "No matches." : "Nothing here yet."}
              </p>
              <p className="mt-1.5 text-[12.5px] text-slate">
                {q
                  ? "Try a different search term, or clear the filter."
                  : "Your account has no enabled modules yet. Contact your administrator."}
              </p>
            </div>
          )}
        </div>
      </div>
    </>,
    document.body,
  );
}

function SectionHeading({ label, intro }: { label: string; intro?: string }) {
  return (
    <div className="mb-4 flex items-baseline gap-3 px-1">
      <h2 className="relative font-display text-[11px] font-semibold uppercase tracking-[0.2em] text-charcoal/70">
        <span
          aria-hidden
          className="absolute -left-3 top-1/2 hidden h-px w-2 -translate-y-1/2 bg-gold/60 sm:block"
        />
        {label}
      </h2>
      {intro && (
        <span className="hidden text-[11.5px] text-slate sm:inline">{intro}</span>
      )}
    </div>
  );
}

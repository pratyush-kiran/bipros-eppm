"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Bell, HelpCircle, Plus, Search } from "lucide-react";
import { ThemeToggle } from "@/components/theme/ThemeToggle";
import { cn } from "@/lib/utils/cn";

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Acronym + special-case map for URL-slug → breadcrumb label. Without these,
// segments like "dbs" render as "Dbs" (title-cased) instead of the canonical
// "DBS". Keep the keys lowercase — the lookup runs on the raw slug.
const LABEL_OVERRIDES: Record<string, string> = {
  admin: "Admin",
  udf: "User Defined Fields",
  obs: "OBS",
  eps: "EPS",
  wbs: "WBS",
  dpr: "DPR",
  dbs: "DBS",
  evm: "EVM",
  boq: "BOQ",
  gis: "GIS",
  qc: "QC",
  ai: "AI",
  ncrs: "NCRs",
  grns: "GRNs",
  rfis: "RFIs",
  "ra-bills": "RA Bills",
};

function humanise(segment: string) {
  const override = LABEL_OVERRIDES[segment.toLowerCase()];
  if (override) return override;
  return segment.replace(/-/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * Build breadcrumbs from a pathname. UUID segments (route params like `[projectId]`) are
 * preserved in the href but rendered as a short hash so the chain doesn't show a 36-char id;
 * the meaningful label always comes from the *last* segment, which is the active sub-route.
 */
function useBreadcrumbs(pathname: string) {
  if (pathname === "/") return [{ label: "Home", href: "/" }];
  const parts = pathname.split("/").filter(Boolean);
  const crumbs: { label: string; href: string }[] = [
    { label: "Home", href: "/" },
  ];
  let href = "";
  for (const part of parts) {
    href += "/" + part;
    const label = UUID_RE.test(part) ? "…" : humanise(part);
    crumbs.push({ label, href });
  }
  return crumbs;
}

export function Header() {
  const pathname = usePathname();
  const crumbs = useBreadcrumbs(pathname);

  return (
    <header className="relative flex h-16 items-center gap-5 border-b border-hairline bg-paper px-7">
      {/* gold gradient hairline under header */}
      <div
        aria-hidden
        className="absolute inset-x-0 -bottom-px h-px"
        style={{
          background:
            "linear-gradient(90deg, transparent, #D4AF37 20%, #D4AF37 80%, transparent)",
          opacity: 0.4,
        }}
      />

      {/* Breadcrumbs — `key={pathname}` forces a clean re-render on every navigation so the
          last crumb always reflects the active sub-route (defensive against any dev-mode
          stale-render edge cases). */}
      <nav
        key={pathname}
        aria-label="Breadcrumbs"
        className="flex items-center gap-2 text-[13px]"
      >
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1;
          return (
            <span key={c.href} className="flex items-center gap-2">
              {last ? (
                <span className="font-semibold text-charcoal truncate max-w-[280px]">
                  {c.label}
                </span>
              ) : (
                <Link
                  href={c.href}
                  className="text-slate hover:text-gold-deep transition-colors truncate max-w-[160px]"
                >
                  {c.label}
                </Link>
              )}
              {!last && <span className="text-ash" aria-hidden>›</span>}
            </span>
          );
        })}
      </nav>

      {/* Command-palette search */}
      <button
        type="button"
        className={cn(
          "ml-4 flex h-10 max-w-[440px] flex-1 items-center gap-2.5 rounded-[10px] border border-hairline bg-ivory px-3.5",
          "text-[13px] text-slate hover:border-gold-deep/50 transition-colors"
        )}
        title="Search (⌘K)"
      >
        <Search size={15} className="text-ash" strokeWidth={1.5} />
        <span className="flex-1 text-left">Search projects, activities, resources…</span>
        <kbd className="rounded border border-hairline bg-paper px-1.5 py-0.5 font-mono text-[10px] text-slate">
          ⌘K
        </kbd>
      </button>

      {/* Right cluster */}
      <div className="flex items-center gap-2.5">
        <button
          type="button"
          aria-label="Notifications"
          className="relative flex h-10 w-10 items-center justify-center rounded-[10px] border border-transparent text-slate transition-colors hover:border-hairline hover:bg-ivory hover:text-gold-deep"
        >
          <Bell size={17} strokeWidth={1.5} />
          <span
            aria-hidden
            className="absolute right-2 top-2 h-[7px] w-[7px] rounded-full bg-gold ring-2 ring-paper"
          />
        </button>
        <button
          type="button"
          aria-label="Help"
          className="flex h-10 w-10 items-center justify-center rounded-[10px] border border-transparent text-slate transition-colors hover:border-hairline hover:bg-ivory hover:text-gold-deep"
        >
          <HelpCircle size={17} strokeWidth={1.5} />
        </button>
        <div className="h-5 w-px bg-hairline" />
        <ThemeToggle />
        <Link
          href="/projects/new"
          className="inline-flex h-10 items-center gap-1.5 rounded-[10px] bg-gold px-3.5 text-[13px] font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(212,175,55,0.3)] hover:-translate-y-px"
        >
          <Plus size={14} strokeWidth={2.5} />
          New project
        </Link>
      </div>
    </header>
  );
}

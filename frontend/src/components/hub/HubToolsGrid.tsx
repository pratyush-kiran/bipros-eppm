"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth/useAuth";
import { useAuthStore } from "@/lib/state/store";
import { TOOL_COLUMNS, type ToolColumn, type ToolLink } from "./hubConfig";

export function HubToolsGrid() {
  const { isAdmin } = useAuth();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  // Auth store is client-only — wait for hydration before filtering on perms,
  // otherwise the server emits an unfiltered column list and the client emits
  // the filtered one, triggering a hydration text/structure mismatch.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);
  if (!hydrated) return null;

  const columns = TOOL_COLUMNS.filter((c) => !c.adminOnly || isAdmin)
    .map((c) => ({
      ...c,
      links: c.links.filter((l) => {
        if (l.adminOnly && !isAdmin) return false;
        if (l.permission && !hasPermission(l.permission)) return false;
        return true;
      }),
    }))
    .filter((c) => c.links.length > 0);

  return (
    <section data-testid="hub-tools" aria-labelledby="hub-tools-heading">
      <h2
        id="hub-tools-heading"
        className="mb-3 px-1 text-[11px] font-semibold uppercase tracking-[0.14em] text-ash"
      >
        All tools
      </h2>
      <div
        className={
          "grid grid-cols-1 gap-4 sm:grid-cols-2 " +
          (columns.length >= 4 ? "lg:grid-cols-4" : `lg:grid-cols-${columns.length}`)
        }
      >
        {columns.map((col) => (
          <ToolCard key={col.title} column={col} />
        ))}
      </div>
    </section>
  );
}

function ToolCard({ column }: { column: ToolColumn }) {
  const Icon = column.icon;
  return (
    <div
      data-testid="hub-tool-column"
      data-column={column.title}
      className="overflow-hidden rounded-2xl border border-hairline bg-paper shadow-[0_1px_2px_rgba(28,28,28,0.03)]"
    >
      <div className="flex items-center gap-2.5 border-b border-hairline bg-ivory/40 px-4 py-3">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg border border-hairline bg-paper text-gold-deep">
          <Icon size={14} strokeWidth={1.75} />
        </div>
        <div className="min-w-0">
          <div className="font-display text-sm font-semibold tracking-tight text-charcoal">
            {column.title}
          </div>
          <div className="text-[10px] font-medium uppercase tracking-[0.1em] text-slate">
            {column.description}
          </div>
        </div>
      </div>
      <ul className="divide-y divide-hairline">
        {column.links.map((link) => (
          <ToolRow key={`${column.title}-${link.label}`} link={link} />
        ))}
      </ul>
    </div>
  );
}

function ToolRow({ link }: { link: ToolLink }) {
  const Icon = link.icon;
  return (
    <li>
      <Link
        href={link.href}
        className="group flex items-center gap-2.5 px-4 py-2.5 text-[13px] font-medium text-charcoal transition-colors hover:bg-ivory/60 hover:text-gold-deep"
      >
        <Icon
          size={14}
          strokeWidth={1.5}
          className="shrink-0 text-slate group-hover:text-gold-deep"
        />
        <span className="flex-1 truncate">{link.label}</span>
        <span aria-hidden className="text-ash transition-colors group-hover:text-gold-deep">
          ›
        </span>
      </Link>
    </li>
  );
}

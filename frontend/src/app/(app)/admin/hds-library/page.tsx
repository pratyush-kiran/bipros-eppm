"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { BookOpen, Library, Plus } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/common/EmptyState";
import { TabTip } from "@/components/common/TabTip";
import { hdsApi, type HdsDocument, type HdsVersion } from "@/lib/api/hdsApi";

interface PublicationRow extends HdsDocument {
  versionCount: number;
  indexedCount: number;
  lastIndexedAt: string | null;
}

export default function HdsLibraryPage() {
  const [rows, setRows] = useState<PublicationRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const [docs, versions] = await Promise.all([
          hdsApi.listDocuments(),
          hdsApi.listVersions(),
        ]);
        const byDoc = new Map<string, HdsVersion[]>();
        versions.forEach(v => {
          const existing = byDoc.get(v.hdsDocumentId) ?? [];
          existing.push(v);
          byDoc.set(v.hdsDocumentId, existing);
        });
        setRows(
          docs.map(d => {
            const vs = byDoc.get(d.id) ?? [];
            const latestIndexed = vs
              .filter(v => v.indexedAt)
              .sort((a, b) => (b.indexedAt ?? "").localeCompare(a.indexedAt ?? ""))[0];
            return {
              ...d,
              versionCount: vs.length,
              indexedCount: vs.filter(v => v.status === "INDEXED").length,
              lastIndexedAt: latestIndexed?.indexedAt ?? null,
            };
          }),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, []);

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <div className="mb-8 flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <div className="mb-2 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            <Library size={12} strokeWidth={1.75} /> Knowledge Library
          </div>
          <h1 className="font-display text-4xl font-medium tracking-tight text-charcoal">
            HDS Library
          </h1>
          <p className="mt-2 max-w-xl text-sm text-slate">
            Highway design standards indexed for grounded AI retrieval. Each publication may
            carry multiple revisions; the assistant cites the exact section and page it used.
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Link href="/admin/hds-library/new">
            <Button variant="primary" size="md">
              <Plus size={16} strokeWidth={1.75} />
              New publication
            </Button>
          </Link>
        </div>
      </div>

      <TabTip
        title="HDS Library"
        description="Upload an HDS PDF, wait for the green INDEXED status, then select it from the chat scope chip. Answers will cite section path and page number from the selected versions only."
      />

      {error && (
        <div className="mb-6 rounded-xl border border-burgundy/30 bg-burgundy/5 p-4 text-sm text-burgundy">
          {error}
        </div>
      )}

      {/* Loading state */}
      {!rows && !error && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div
              key={i}
              className="h-44 animate-pulse rounded-xl border border-hairline bg-ivory/40"
            />
          ))}
        </div>
      )}

      {/* Empty state */}
      {rows && rows.length === 0 && (
        <EmptyState
          icon={BookOpen}
          title="No publications yet"
          description="Add your first Highway Design Standard publication to get started."
          action={{
            label: "+ New publication",
            onClick: () => (window.location.href = "/admin/hds-library/new"),
          }}
        />
      )}

      {/* Card grid */}
      {rows && rows.length > 0 && (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {rows.map(row => (
            <Link key={row.id} href={`/admin/hds-library/${row.id}`} className="group">
              <Card variant="interactive" className="flex h-full flex-col gap-4 p-5">
                {/* Top: short code + discipline */}
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[11px] font-semibold uppercase tracking-[0.12em] text-gold-deep">
                    {row.shortCode}
                  </span>
                  <Badge variant="neutral">{row.discipline}</Badge>
                </div>

                {/* Title + authority */}
                <div className="flex-1">
                  <h2 className="font-display text-xl font-medium leading-tight text-charcoal transition-colors group-hover:text-gold-ink">
                    {row.title}
                  </h2>
                  {row.issuingAuthority && (
                    <p className="mt-1.5 text-xs text-slate">{row.issuingAuthority}</p>
                  )}
                </div>

                {/* Stats footer */}
                <div className="flex items-end justify-between border-t border-hairline pt-3">
                  <div>
                    <div className="font-mono text-2xl font-medium text-charcoal">
                      {row.indexedCount}
                      <span className="text-ash">/{row.versionCount}</span>
                    </div>
                    <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                      Indexed versions
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-xs text-slate">
                      {row.lastIndexedAt
                        ? new Date(row.lastIndexedAt).toLocaleDateString(undefined, {
                            day: "2-digit",
                            month: "short",
                            year: "numeric",
                          })
                        : "—"}
                    </div>
                    <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                      {row.lastIndexedAt ? "Last indexed" : "Awaiting upload"}
                    </div>
                  </div>
                </div>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

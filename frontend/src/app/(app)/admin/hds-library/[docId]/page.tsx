"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ArrowLeft, FileUp, Upload } from "lucide-react";

import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/common/EmptyState";
import {
  hdsApi,
  type HdsDocument,
  type HdsVersion,
  type HdsVersionStatus,
} from "@/lib/api/hdsApi";

const STATUS_VARIANT: Record<HdsVersionStatus, BadgeVariant> = {
  PENDING: "neutral",
  PARSING: "info",
  CHUNKING: "info",
  EMBEDDING: "info",
  INDEXED: "success",
  FAILED: "danger",
};

const STATUS_LABEL: Record<HdsVersionStatus, string> = {
  PENDING: "Pending",
  PARSING: "Parsing",
  CHUNKING: "Chunking",
  EMBEDDING: "Embedding",
  INDEXED: "Indexed",
  FAILED: "Failed",
};

function formatBytes(bytes?: number): string {
  if (!bytes) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export default function HdsVersionsListPage() {
  const params = useParams() as { docId: string };
  const [doc, setDoc] = useState<HdsDocument | null>(null);
  const [versions, setVersions] = useState<HdsVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const [docs, allVersions] = await Promise.all([
          hdsApi.listDocuments(),
          hdsApi.listVersions(),
        ]);
        const found = docs.find(d => d.id === params.docId) ?? null;
        setDoc(found);
        setVersions(
          allVersions
            .filter(v => v.hdsDocumentId === params.docId)
            .sort((a, b) => (b.uploadedAt ?? "").localeCompare(a.uploadedAt ?? "")),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [params.docId]);

  return (
    <div className="p-6 lg:p-8">
      {/* Breadcrumb */}
      <Link
        href="/admin/hds-library"
        className="mb-4 inline-flex items-center gap-1.5 text-xs font-medium text-slate transition-colors hover:text-gold-deep"
      >
        <ArrowLeft size={12} strokeWidth={1.75} />
        HDS Library
      </Link>

      {/* Publication header */}
      {!doc && !error && (
        <div className="mb-8 h-24 animate-pulse rounded-xl border border-hairline bg-ivory/40" />
      )}
      {doc && (
        <div className="mb-8 flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="mb-2 flex items-center gap-2">
              <span className="font-mono text-[11px] font-semibold uppercase tracking-[0.12em] text-gold-deep">
                {doc.shortCode}
              </span>
              <span className="text-ash">·</span>
              <Badge variant="neutral">{doc.discipline}</Badge>
              {doc.country && (
                <>
                  <span className="text-ash">·</span>
                  <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                    {doc.country}
                  </span>
                </>
              )}
            </div>
            <h1 className="font-display text-3xl font-medium leading-tight tracking-tight text-charcoal">
              {doc.title}
            </h1>
            {doc.issuingAuthority && (
              <p className="mt-2 text-sm text-slate">{doc.issuingAuthority}</p>
            )}
            {doc.description && (
              <p className="mt-3 max-w-2xl text-sm leading-relaxed text-slate">
                {doc.description}
              </p>
            )}
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Link href={`/admin/hds-library/${params.docId}/upload`}>
              <Button variant="primary" size="md">
                <Upload size={16} strokeWidth={1.75} />
                Upload version
              </Button>
            </Link>
          </div>
        </div>
      )}

      {error && (
        <div className="mb-6 rounded-xl border border-burgundy/30 bg-burgundy/5 p-4 text-sm text-burgundy">
          {error}
        </div>
      )}

      {/* Versions */}
      <div className="mb-3 flex items-baseline justify-between">
        <h2 className="font-display text-lg font-medium text-charcoal">Revisions</h2>
        <span className="text-xs text-slate">
          {versions?.length ?? 0} {versions?.length === 1 ? "version" : "versions"}
        </span>
      </div>

      {!versions && !error && (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div
              key={i}
              className="h-20 animate-pulse rounded-xl border border-hairline bg-ivory/40"
            />
          ))}
        </div>
      )}

      {versions && versions.length === 0 && (
        <EmptyState
          icon={FileUp}
          title="No revisions uploaded yet"
          description="Upload the first PDF revision to start indexing."
          action={{
            label: "+ Upload version",
            onClick: () =>
              (window.location.href = `/admin/hds-library/${params.docId}/upload`),
          }}
        />
      )}

      {versions && versions.length > 0 && (
        <div className="space-y-2.5">
          {versions.map(v => (
            <Link
              key={v.id}
              href={`/admin/hds-library/${params.docId}/versions/${v.id}`}
              className="group block"
            >
              <Card
                variant="flat"
                className="flex flex-col gap-4 p-5 transition-all hover:border-gold/40 hover:shadow-[0_4px_20px_rgba(28,28,28,0.05)] sm:flex-row sm:items-center sm:gap-6"
              >
                {/* Version label + year */}
                <div className="flex min-w-[10rem] flex-col">
                  <div className="font-mono text-base font-medium text-charcoal group-hover:text-gold-ink">
                    {v.versionLabel}
                  </div>
                  <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                    {v.revisionYear ? `Year ${v.revisionYear}` : "Revision"}
                  </div>
                </div>

                {/* File metadata */}
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm text-charcoal">{v.fileName ?? "—"}</div>
                  <div className="mt-0.5 flex flex-wrap items-center gap-x-3 text-xs text-slate">
                    <span className="font-mono">{formatBytes(v.fileSizeBytes)}</span>
                    {v.pageCount != null && (
                      <>
                        <span className="text-ash">·</span>
                        <span>
                          <span className="font-mono">{v.pageCount}</span>{" "}
                          {v.pageCount === 1 ? "page" : "pages"}
                        </span>
                      </>
                    )}
                    {v.chunkCount != null && (
                      <>
                        <span className="text-ash">·</span>
                        <span>
                          <span className="font-mono">{v.chunkCount}</span> chunks
                        </span>
                      </>
                    )}
                  </div>
                </div>

                {/* Status + uploaded */}
                <div className="flex shrink-0 flex-col items-start gap-1 sm:items-end">
                  <div className="flex items-center gap-2">
                    {(v.status === "PARSING" ||
                      v.status === "CHUNKING" ||
                      v.status === "EMBEDDING") && (
                      <span className="font-mono text-xs text-steel">
                        {v.indexingProgressPct}%
                      </span>
                    )}
                    <Badge variant={STATUS_VARIANT[v.status]} withDot>
                      {STATUS_LABEL[v.status]}
                    </Badge>
                  </div>
                  <div className="text-[11px] text-slate">
                    {v.uploadedAt
                      ? new Date(v.uploadedAt).toLocaleString(undefined, {
                          day: "2-digit",
                          month: "short",
                          year: "numeric",
                        })
                      : ""}
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

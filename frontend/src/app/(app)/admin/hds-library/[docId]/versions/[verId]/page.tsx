"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  FileText,
  RefreshCcw,
  Trash2,
} from "lucide-react";

import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Progress, type ProgressVariant } from "@/components/ui/progress";
import { cn } from "@/lib/utils/cn";
import {
  hdsApi,
  type HdsVersionDetail,
  type HdsVersionStatus,
} from "@/lib/api/hdsApi";

const STAGE_ORDER: HdsVersionStatus[] = [
  "PENDING",
  "PARSING",
  "CHUNKING",
  "EMBEDDING",
  "INDEXED",
];

const STAGE_LABEL: Record<HdsVersionStatus, string> = {
  PENDING: "Queued",
  PARSING: "Parsing PDF",
  CHUNKING: "Chunking",
  EMBEDDING: "Embedding",
  INDEXED: "Indexed",
  FAILED: "Failed",
};

const STATUS_BADGE: Record<HdsVersionStatus, BadgeVariant> = {
  PENDING: "neutral",
  PARSING: "info",
  CHUNKING: "info",
  EMBEDDING: "info",
  INDEXED: "success",
  FAILED: "danger",
};

const PROGRESS_VARIANT: Record<HdsVersionStatus, ProgressVariant> = {
  PENDING: "gold",
  PARSING: "gold",
  CHUNKING: "gold",
  EMBEDDING: "gold",
  INDEXED: "success",
  FAILED: "danger",
};

function formatBytes(bytes?: number): string {
  if (!bytes) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export default function HdsVersionDetailPage() {
  const params = useParams() as { docId: string; verId: string };
  const router = useRouter();
  const [detail, setDetail] = useState<HdsVersionDetail | null>(null);
  const [liveMsg, setLiveMsg] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    let mounted = true;
    hdsApi.getVersion(params.verId).then(d => mounted && setDetail(d));
    const sub = hdsApi.subscribeProgress(params.verId, ev => {
      setLiveMsg(ev.message);
      if (ev.stage === "COMPLETE" || ev.stage === "FAILED") {
        hdsApi.getVersion(params.verId).then(d => mounted && setDetail(d));
      } else if (mounted) {
        setDetail(prev =>
          prev
            ? {
                ...prev,
                version: {
                  ...prev.version,
                  indexingProgressPct: ev.progressPct,
                  status: (ev.stage as HdsVersionStatus) ?? prev.version.status,
                },
              }
            : prev,
        );
      }
    });
    return () => {
      mounted = false;
      sub.close();
    };
  }, [params.verId]);

  const stageIndex = useMemo(() => {
    if (!detail) return -1;
    return STAGE_ORDER.indexOf(detail.version.status);
  }, [detail]);

  const onRetry = async () => {
    if (!detail) return;
    setRetrying(true);
    try {
      await hdsApi.retryVersion(detail.version.id);
      const fresh = await hdsApi.getVersion(detail.version.id);
      setDetail(fresh);
    } finally {
      setRetrying(false);
    }
  };

  const onDelete = async () => {
    if (!detail) return;
    if (!confirm("Delete this revision and all its indexed chunks? This cannot be undone."))
      return;
    setDeleting(true);
    try {
      await hdsApi.deleteVersion(detail.version.id);
      router.push(`/admin/hds-library/${params.docId}`);
    } catch {
      setDeleting(false);
    }
  };

  if (!detail) {
    return (
      <div className="p-6 lg:p-8">
        <div className="h-12 animate-pulse rounded-xl border border-hairline bg-ivory/40" />
        <div className="mt-6 h-64 animate-pulse rounded-xl border border-hairline bg-ivory/40" />
      </div>
    );
  }

  const v = detail.version;
  const isInFlight =
    v.status === "PARSING" || v.status === "CHUNKING" || v.status === "EMBEDDING";

  return (
    <div className="p-6 lg:p-8">
      {/* Breadcrumb */}
      <Link
        href={`/admin/hds-library/${params.docId}`}
        className="mb-4 inline-flex items-center gap-1.5 text-xs font-medium text-slate transition-colors hover:text-gold-deep"
      >
        <ArrowLeft size={12} strokeWidth={1.75} />
        Publication
      </Link>

      {/* Title */}
      <div className="mb-8 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <div className="mb-2 flex items-center gap-2">
            <span className="font-mono text-[11px] font-semibold uppercase tracking-[0.12em] text-gold-deep">
              Revision
            </span>
            <Badge variant={STATUS_BADGE[v.status]} withDot>
              {STAGE_LABEL[v.status]}
            </Badge>
          </div>
          <h1 className="font-display text-3xl font-medium leading-tight tracking-tight text-charcoal">
            {v.versionLabel}
            {v.revisionYear && (
              <span className="ml-3 font-mono text-2xl font-normal text-ash">
                {v.revisionYear}
              </span>
            )}
          </h1>
          <p className="mt-2 font-mono text-sm text-slate">{v.fileName ?? "—"}</p>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {v.status === "FAILED" && (
            <Button variant="secondary" size="md" onClick={onRetry} disabled={retrying}>
              <RefreshCcw size={14} strokeWidth={1.75} />
              {retrying ? "Retrying…" : "Retry"}
            </Button>
          )}
          <Button variant="ghost" size="md" onClick={onDelete} disabled={deleting}>
            <Trash2 size={14} strokeWidth={1.75} />
            {deleting ? "Deleting…" : "Delete"}
          </Button>
        </div>
      </div>

      {/* Stat cards */}
      <div className="mb-6 grid gap-4 sm:grid-cols-3">
        <Card variant="flat" className="p-5">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate">
            File size
          </div>
          <div className="mt-1 font-mono text-2xl font-medium text-charcoal">
            {formatBytes(v.fileSizeBytes)}
          </div>
        </Card>
        <Card variant="flat" className="p-5">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate">
            Pages
          </div>
          <div className="mt-1 font-mono text-2xl font-medium text-charcoal">
            {v.pageCount ?? "—"}
          </div>
        </Card>
        <Card variant="flat" className="p-5">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate">
            Indexed chunks
          </div>
          <div className="mt-1 font-mono text-2xl font-medium text-charcoal">
            {v.chunkCount ?? "—"}
          </div>
        </Card>
      </div>

      {/* Ingestion pipeline visual */}
      <Card variant="flat" className="mb-6 p-7">
        <div className="mb-5 flex items-baseline justify-between">
          <div>
            <h2 className="font-display text-lg font-medium text-charcoal">Ingestion</h2>
            <p className="mt-0.5 text-xs text-slate">
              {liveMsg ??
                (v.status === "INDEXED"
                  ? "Indexed and ready for retrieval."
                  : v.status === "FAILED"
                    ? "Failed — see error below."
                    : "Waiting for the worker to pick up this job…")}
            </p>
          </div>
          {(isInFlight || v.status === "INDEXED") && (
            <span className="font-mono text-2xl font-medium text-charcoal">
              {v.status === "INDEXED" ? "100" : v.indexingProgressPct}
              <span className="text-ash">%</span>
            </span>
          )}
        </div>

        {/* Progress bar */}
        <Progress
          value={v.status === "INDEXED" ? 100 : v.indexingProgressPct}
          variant={PROGRESS_VARIANT[v.status]}
        />

        {/* Stage rail */}
        <div className="mt-6 grid grid-cols-5 gap-2">
          {STAGE_ORDER.map((stage, i) => {
            const reached = stageIndex >= i || v.status === "INDEXED";
            const isCurrent = v.status === stage && v.status !== "INDEXED";
            const isFinal = stage === "INDEXED" && v.status === "INDEXED";
            return (
              <div key={stage} className="flex flex-col items-center text-center">
                <div
                  className={cn(
                    "flex h-7 w-7 items-center justify-center rounded-full border-2 font-mono text-[11px] font-semibold transition-all",
                    isFinal
                      ? "border-emerald bg-emerald text-paper"
                      : isCurrent
                        ? "border-gold bg-gold-tint text-gold-ink shadow-[0_0_0_3px_rgba(212,175,55,0.18)]"
                        : reached
                          ? "border-gold/60 bg-paper text-gold-deep"
                          : "border-divider bg-paper text-ash",
                  )}
                >
                  {isFinal ? "✓" : i + 1}
                </div>
                <div
                  className={cn(
                    "mt-2 text-[10px] font-semibold uppercase tracking-[0.12em]",
                    reached ? "text-charcoal" : "text-ash",
                  )}
                >
                  {STAGE_LABEL[stage]}
                </div>
              </div>
            );
          })}
        </div>
      </Card>

      {/* Success / error panel */}
      {v.status === "INDEXED" && (
        <div className="flex items-start gap-3 rounded-xl border border-emerald/30 bg-emerald/5 p-5">
          <CheckCircle2 size={20} strokeWidth={1.75} className="mt-0.5 shrink-0 text-emerald" />
          <div>
            <div className="text-sm font-semibold text-emerald">Available for retrieval</div>
            <div className="mt-1 text-xs text-slate">
              Indexed {v.chunkCount} chunks
              {v.indexedAt && (
                <>
                  {" "}
                  on{" "}
                  <span className="font-mono">
                    {new Date(v.indexedAt).toLocaleString(undefined, {
                      day: "2-digit",
                      month: "short",
                      year: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </span>
                </>
              )}
              . Pick this version from the chat scope chip to query against it.
            </div>
          </div>
        </div>
      )}

      {v.status === "FAILED" && detail.indexingError && (
        <div className="flex items-start gap-3 rounded-xl border border-burgundy/30 bg-burgundy/5 p-5">
          <AlertTriangle
            size={20}
            strokeWidth={1.75}
            className="mt-0.5 shrink-0 text-burgundy"
          />
          <div className="min-w-0 flex-1">
            <div className="text-sm font-semibold text-burgundy">Ingestion failed</div>
            <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap rounded-lg bg-paper p-3 font-mono text-[11px] text-burgundy">
              {detail.indexingError}
            </pre>
            <div className="mt-3">
              <Button variant="secondary" size="sm" onClick={onRetry} disabled={retrying}>
                <RefreshCcw size={12} strokeWidth={1.75} />
                {retrying ? "Retrying…" : "Retry from last stage"}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Source file footer */}
      <div className="mt-8 flex items-center gap-3 border-t border-hairline pt-5 text-xs text-slate">
        <FileText size={14} strokeWidth={1.5} className="text-ash" />
        <span className="font-mono">{v.fileName ?? "—"}</span>
        {v.fileSizeBytes && (
          <>
            <span className="text-ash">·</span>
            <span className="font-mono">{formatBytes(v.fileSizeBytes)}</span>
          </>
        )}
        <span className="text-ash">·</span>
        <span>
          Uploaded{" "}
          <span className="font-mono">
            {v.uploadedAt
              ? new Date(v.uploadedAt).toLocaleString(undefined, {
                  day: "2-digit",
                  month: "short",
                  year: "numeric",
                  hour: "2-digit",
                  minute: "2-digit",
                })
              : "—"}
          </span>
        </span>
      </div>
    </div>
  );
}

"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { DragEvent, useRef, useState } from "react";
import { ArrowLeft, FileText, Upload, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Field, FieldError, FieldHint, Input, Label } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { cn } from "@/lib/utils/cn";
import { hdsApi } from "@/lib/api/hdsApi";

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export default function HdsUploadPage() {
  const router = useRouter();
  const params = useParams() as { docId: string };
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [versionLabel, setVersionLabel] = useState("");
  const [year, setYear] = useState<number | "">("");
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [pct, setPct] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped && dropped.type === "application/pdf") setFile(dropped);
    else if (dropped) setError("Only PDF files are accepted.");
  };

  const upload = async () => {
    if (!file || !versionLabel) return;
    setBusy(true);
    setError(null);
    setPct(0);
    try {
      const ver = await hdsApi.uploadVersion(
        params.docId,
        versionLabel,
        year === "" ? undefined : year,
        file,
        setPct,
      );
      router.push(`/admin/hds-library/${params.docId}/versions/${ver.id}`);
    } catch (e: unknown) {
      const errAny = e as {
        response?: { data?: { error?: { message?: string } } };
      };
      setError(
        errAny?.response?.data?.error?.message ?? (e instanceof Error ? e.message : String(e)),
      );
      setBusy(false);
    }
  };

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
      <div className="mb-8">
        <div className="mb-2 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
          <Upload size={12} strokeWidth={1.75} /> Upload revision
        </div>
        <h1 className="font-display text-3xl font-medium tracking-tight text-charcoal">
          Add a new revision
        </h1>
        <p className="mt-2 max-w-xl text-sm text-slate">
          PDFs up to ~1 GB. Ingestion runs in the background; you&apos;ll be redirected to the
          progress view after upload completes.
        </p>
      </div>

      <div className="max-w-3xl space-y-5">
        {/* Dropzone */}
        <Card variant="flat" className="p-7">
          <Label className="mb-3 block">PDF file</Label>
          <div
            onDragEnter={e => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragOver={e => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
            onClick={() => fileInputRef.current?.click()}
            className={cn(
              "relative cursor-pointer rounded-xl border-2 border-dashed p-10 text-center transition-all",
              dragOver
                ? "border-gold bg-gold-tint/50 shadow-[0_0_0_3px_rgba(212,175,55,0.18)]"
                : file
                  ? "border-gold/60 bg-gold-tint/20"
                  : "border-divider bg-ivory/40 hover:border-gold-deep/50 hover:bg-ivory",
            )}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept="application/pdf"
              hidden
              onChange={e => setFile(e.target.files?.[0] ?? null)}
            />
            {file ? (
              <div className="flex items-start gap-4 text-left">
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gold-tint text-gold-deep">
                  <FileText size={22} strokeWidth={1.5} />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-base font-medium text-charcoal">
                    {file.name}
                  </div>
                  <div className="mt-0.5 font-mono text-xs text-slate">
                    {formatBytes(file.size)}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={e => {
                    e.stopPropagation();
                    setFile(null);
                    if (fileInputRef.current) fileInputRef.current.value = "";
                  }}
                  className="rounded-lg p-1.5 text-slate transition-colors hover:bg-paper hover:text-burgundy"
                  aria-label="Remove file"
                >
                  <X size={16} strokeWidth={1.75} />
                </button>
              </div>
            ) : (
              <div>
                <Upload
                  size={28}
                  strokeWidth={1.5}
                  className="mx-auto mb-3 text-gold-deep"
                />
                <div className="text-sm font-medium text-charcoal">
                  Drop a PDF here, or click to browse
                </div>
                <div className="mt-1 text-xs text-slate">
                  Only application/pdf is accepted.
                </div>
              </div>
            )}
          </div>
        </Card>

        {/* Metadata */}
        <Card variant="flat" className="p-7">
          <div className="grid gap-5 sm:grid-cols-2">
            <Field>
              <Label htmlFor="label">
                Version label <span className="text-ash font-normal">*</span>
              </Label>
              <Input
                id="label"
                value={versionLabel}
                onChange={e => setVersionLabel(e.target.value)}
                placeholder="Rev 2.1"
                className="font-mono"
              />
              <FieldHint>Used in citations alongside the publication short code.</FieldHint>
            </Field>

            <Field>
              <Label htmlFor="year">Revision year</Label>
              <Input
                id="year"
                type="number"
                value={year}
                onChange={e => setYear(e.target.value === "" ? "" : Number(e.target.value))}
                placeholder="2024"
                className="font-mono"
              />
            </Field>
          </div>
        </Card>

        {/* Upload progress */}
        {busy && (
          <Card variant="accent" className="p-5">
            <div className="mb-2 flex items-baseline justify-between">
              <span className="text-xs font-semibold uppercase tracking-[0.14em] text-gold-deep">
                Uploading
              </span>
              <span className="font-mono text-sm font-medium text-charcoal">{pct}%</span>
            </div>
            <Progress value={pct} variant="gold" />
            <p className="mt-2 text-xs text-slate">
              When upload completes, indexing will begin automatically.
            </p>
          </Card>
        )}

        {error && (
          <div className="rounded-xl border border-burgundy/30 bg-burgundy/5 p-4">
            <FieldError>{error}</FieldError>
          </div>
        )}

        {/* Actions */}
        <div className="flex items-center gap-2">
          <Button
            variant="primary"
            size="md"
            onClick={upload}
            disabled={busy || !file || !versionLabel}
          >
            {busy ? "Uploading…" : "Upload & index"}
          </Button>
          <Link href={`/admin/hds-library/${params.docId}`}>
            <Button variant="ghost" size="md" type="button">
              Cancel
            </Button>
          </Link>
        </div>
      </div>
    </div>
  );
}

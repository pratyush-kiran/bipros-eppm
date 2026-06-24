"use client";

import { useEffect, useState } from "react";
import type { GeoJsonFeatureCollection } from "@/lib/api/gisApi";
import { Button } from "@/components/ui/button";

type Feature = GeoJsonFeatureCollection["features"][number];

interface PolygonEditPanelProps {
  polygon: Feature;
  isSaving: boolean;
  saveError: string | null;
  lastSavedAt: Date | null;
  onDelete: () => void;
  isDeleting: boolean;
  onFetchImagery: () => void;
  isFetchingImagery: boolean;
  fetchResult: {
    fetched: number;
    skipped: number;
    errors: number;
    errorMessages: string[];
  } | null;
  fetchError: string | null;
}

function relative(from: Date, now: Date): string {
  const sec = Math.round((now.getTime() - from.getTime()) / 1000);
  if (sec < 5) return "just now";
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  return `${Math.round(min / 60)}h ago`;
}

/**
 * Modify-mode side panel. No Save button — Modify auto-saves on every
 * modifyend; the last-saved timestamp is the user's confirmation the write
 * landed. Delete lives here too since modify and delete are both "work on a
 * selected polygon" and we don't want two overlapping selection states.
 */
export function PolygonEditPanel({
  polygon,
  isSaving,
  saveError,
  lastSavedAt,
  onDelete,
  isDeleting,
  onFetchImagery,
  isFetchingImagery,
  fetchResult,
  fetchError,
}: PolygonEditPanelProps) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 5000);
    return () => clearInterval(t);
  }, []);

  const { wbsCode, wbsName } = polygon.properties;

  return (
    <aside className="flex flex-col gap-4 rounded-lg border border-accent/40 bg-surface/50 p-4">
      <div>
        <h3 className="text-sm font-semibold text-text-primary mb-1">
          Edit polygon
        </h3>
        <p className="text-xs text-text-muted">
          Drag any vertex on the map to reshape. Changes save automatically.
        </p>
      </div>

      <section className="text-xs text-text-secondary space-y-1">
        <p>
          <span className="font-medium">Code:</span>{" "}
          <span className="text-text-primary">{wbsCode}</span>
        </p>
        <p>
          <span className="font-medium">Name:</span> {wbsName}
        </p>
      </section>

      <section className="text-xs">
        {isSaving ? (
          <span className="text-text-muted">Saving…</span>
        ) : lastSavedAt ? (
          <span className="text-green-400">
            Saved {relative(lastSavedAt, now)}
          </span>
        ) : (
          <span className="text-text-muted">No changes yet</span>
        )}
      </section>

      {saveError && (
        <div className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-xs text-danger">
          {saveError}
        </div>
      )}

      <section className="flex flex-col gap-2">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={onFetchImagery}
          disabled={isFetchingImagery}
        >
          {isFetchingImagery
            ? "Fetching imagery…"
            : "Fetch satellite imagery (last 30 days)"}
        </Button>
        {fetchResult && (
          <>
            <p className="text-xs text-text-secondary">
              Fetched <span className="text-text-primary">{fetchResult.fetched}</span>{" "}
              · skipped <span className="text-text-primary">{fetchResult.skipped}</span>
              {fetchResult.errors > 0 && (
                <span className="text-danger"> · {fetchResult.errors} errors</span>
              )}
            </p>
            {fetchResult.errorMessages.length > 0 && (
              <details className="rounded-md border border-danger/30 bg-danger/10 px-2 py-1 text-xs text-danger">
                <summary className="cursor-pointer">Show error detail</summary>
                <ul className="mt-1 list-disc pl-4 space-y-1 break-words">
                  {fetchResult.errorMessages.map((m, i) => (
                    <li key={i}>{m}</li>
                  ))}
                </ul>
              </details>
            )}
          </>
        )}
        {fetchError && (
          <div className="rounded-md border border-danger/30 bg-danger/10 px-2 py-1 text-xs text-danger">
            {fetchError}
          </div>
        )}
      </section>

      <Button
        type="button"
        variant="danger"
        size="sm"
        onClick={onDelete}
        disabled={isDeleting}
      >
        {isDeleting ? "Deleting…" : "Delete polygon"}
      </Button>
    </aside>
  );
}

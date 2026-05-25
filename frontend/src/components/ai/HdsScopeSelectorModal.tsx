"use client";

import { useCallback, useEffect, useState } from "react";
import { X, Loader2 } from "lucide-react";

/**
 * Multi-select modal for picking HDS document versions to ground answers in.
 *
 * The list of indexed versions comes from `hdsApi.listVersions()` (Track A).
 * If `hdsApi.ts` has not landed yet at compile time, this component falls back
 * to a dynamic import + a structural minimum for `HdsVersion` so it stays
 * self-contained — Track A's commit later refines the shape. The runtime
 * fetch is wrapped in a defensive try/catch so an unwired backend produces an
 * empty list rather than a thrown promise.
 */

// Structural minimum for an HDS version. When Track A's `hdsApi.ts` is
// present, its richer type augments this one transparently at the call site.
export type HdsVersionLike = {
  id: string;
  hdsDocumentId?: string;
  versionLabel: string;
  revisionYear?: number | null;
  fileName?: string;
  chunkCount?: number | null;
  status?: string;
};

interface Props {
  open: boolean;
  initiallySelectedIds: string[];
  onCancel: () => void;
  onConfirm: (versions: HdsVersionLike[]) => void;
}

async function loadIndexedVersions(): Promise<HdsVersionLike[]> {
  try {
    // Track A's hdsApi is imported dynamically so this file compiles even
    // when the module is absent from the repo. The dynamic import lets the
    // bundler tree-shake and gives us a graceful runtime fallback.
    const mod = (await import("@/lib/api/hdsApi")) as
      | { hdsApi?: { listVersions?: () => Promise<HdsVersionLike[]> } }
      | undefined;
    if (mod?.hdsApi?.listVersions) {
      return await mod.hdsApi.listVersions();
    }
    return [];
  } catch (err) {
    // Module not present yet or backend not reachable — surface an empty
    // list so the modal renders the empty-state instead of crashing.
    console.warn("HDS versions list unavailable:", err);
    return [];
  }
}

export default function HdsScopeSelectorModal({
  open,
  initiallySelectedIds,
  onCancel,
  onConfirm,
}: Props) {
  const [versions, setVersions] = useState<HdsVersionLike[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(
    new Set(initiallySelectedIds),
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reset + fetch is wrapped in a callback so we can call it asynchronously
  // from the effect — React 19 forbids synchronous setState in effect bodies
  // (`react-hooks/set-state-in-effect`).
  const refresh = useCallback(async (signal: { cancelled: boolean }) => {
    setLoading(true);
    setError(null);
    setSelectedIds(new Set(initiallySelectedIds));
    try {
      const list = await loadIndexedVersions();
      if (signal.cancelled) return;
      // Only show INDEXED versions when status is reported. If status is
      // absent (Track A's API not landed yet) we trust the list as-is.
      const usable = list.filter((v) => !v.status || v.status === "INDEXED");
      setVersions(usable);
    } catch (e) {
      if (!signal.cancelled) setError(String(e));
    } finally {
      if (!signal.cancelled) setLoading(false);
    }
  }, [initiallySelectedIds]);

  useEffect(() => {
    if (!open) return;
    const signal = { cancelled: false };
    void refresh(signal);
    return () => {
      signal.cancelled = true;
    };
  }, [open, refresh]);

  // Close on Escape — mirrors the chat panel's own keybinding so the dialog
  // feels native rather than a leaky overlay.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onCancel]);

  if (!open) return null;

  const toggle = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const confirm = () => {
    const chosen = versions.filter((v) => selectedIds.has(v.id));
    onConfirm(chosen);
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="hds-scope-selector-title"
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => {
        // Click outside closes; clicks inside the panel are stopped below.
        if (e.target === e.currentTarget) onCancel();
      }}
    >
      <div className="bg-surface text-text-primary rounded-lg shadow-xl border border-border w-full max-w-lg max-h-[80vh] flex flex-col">
        <div className="px-5 py-3 border-b border-border flex items-center justify-between">
          <h2 id="hds-scope-selector-title" className="font-semibold">
            Select HDS sources
          </h2>
          <button
            type="button"
            onClick={onCancel}
            className="p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-2">
          {loading && (
            <div className="flex items-center gap-2 p-4 text-sm text-text-muted">
              <Loader2 size={14} className="animate-spin" />
              Loading indexed versions…
            </div>
          )}
          {!loading && error && (
            <div className="p-4 text-sm text-danger">{error}</div>
          )}
          {!loading && !error && versions.length === 0 && (
            <div className="p-4 text-sm text-text-muted">
              No indexed HDS versions yet. Ask an administrator to upload a
              publication and wait for ingestion to complete.
            </div>
          )}
          {!loading && !error &&
            versions.map((v) => (
              <label
                key={v.id}
                className="flex items-start gap-3 p-3 rounded hover:bg-surface-hover cursor-pointer border border-transparent"
              >
                <input
                  type="checkbox"
                  checked={selectedIds.has(v.id)}
                  onChange={() => toggle(v.id)}
                  className="mt-1 accent-accent"
                />
                <div className="flex-1 min-w-0">
                  <div className="font-mono text-sm">
                    {v.versionLabel}
                    {v.revisionYear != null && ` (${v.revisionYear})`}
                  </div>
                  <div className="text-xs text-text-muted truncate">
                    {v.fileName ?? "—"}
                    {v.chunkCount != null && ` · ${v.chunkCount} chunks`}
                  </div>
                </div>
              </label>
            ))}
        </div>
        <div className="px-5 py-3 border-t border-border flex items-center justify-between">
          <div className="text-xs text-text-muted">
            {selectedIds.size === 0
              ? "Select at least one version to ground answers in cited content."
              : `${selectedIds.size} version${selectedIds.size === 1 ? "" : "s"} selected`}
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onCancel}
              className="px-3 py-1 text-sm text-text-secondary hover:bg-surface-hover rounded-md"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={confirm}
              disabled={selectedIds.size === 0}
              className="px-3 py-1 text-sm bg-accent text-accent-foreground hover:bg-accent-hover rounded-md disabled:bg-border disabled:text-text-muted"
            >
              Use {selectedIds.size || ""} selected
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

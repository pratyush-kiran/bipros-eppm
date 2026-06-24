"use client";

import { useState } from "react";
import type { WbsNodeResponse } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { WbsNodePicker } from "./WbsNodePicker";
import type { PolygonMeta } from "@/lib/gis/geometry";

export interface DrawPayload {
  wbsNodeId: string;
  wbsCode: string;
  wbsName: string;
  fillColor: string;
  strokeColor: string;
}

interface DrawReviewPanelProps {
  meta: PolygonMeta;
  tree: WbsNodeResponse[];
  mappedNodeIds: Set<string>;
  isSaving: boolean;
  saveError: string | null;
  onSave: (payload: DrawPayload) => void;
  onDiscard: () => void;
}

function findNode(
  tree: WbsNodeResponse[],
  id: string
): WbsNodeResponse | null {
  for (const n of tree) {
    if (n.id === id) return n;
    const inChild = findNode(n.children ?? [], id);
    if (inChild) return inChild;
  }
  return null;
}

/**
 * Right-side confirm step shown between drawend and POST. Explicit Save keeps
 * a misclicked boundary from being persisted; it's cheaper to re-draw than
 * to edit a wrong polygon after the fact.
 */
export function DrawReviewPanel({
  meta,
  tree,
  mappedNodeIds,
  isSaving,
  saveError,
  onSave,
  onDiscard,
}: DrawReviewPanelProps) {
  const [nodeId, setNodeId] = useState<string | null>(null);
  const [fillColor, setFillColor] = useState("#3388ff");
  const [strokeColor, setStrokeColor] = useState("#000000");

  const node = nodeId ? findNode(tree, nodeId) : null;
  const ha = (meta.areaSqM / 10_000).toFixed(2);

  return (
    <aside className="flex flex-col gap-4 rounded-lg border border-accent/40 bg-surface/50 p-4">
      <div>
        <h3 className="text-sm font-semibold text-text-primary mb-1">
          New polygon
        </h3>
        <p className="text-xs text-text-muted">
          Review and attach to a WBS node, then Save.
        </p>
      </div>

      <section>
        <label className="block text-xs text-text-secondary mb-1">
          WBS node
        </label>
        <WbsNodePicker
          tree={tree}
          value={nodeId}
          mappedNodeIds={mappedNodeIds}
          onChange={setNodeId}
          disabled={isSaving}
        />
      </section>

      <section className="text-xs text-text-secondary space-y-1">
        <div>
          <span className="font-medium">Area:</span> {ha} ha (
          {Math.round(meta.areaSqM).toLocaleString()} m²)
        </div>
        <div>
          <span className="font-medium">Centroid:</span>{" "}
          {meta.centerLat.toFixed(6)}, {meta.centerLon.toFixed(6)}
        </div>
      </section>

      <section>
        <label className="block text-xs text-text-secondary mb-1">
          Fill / stroke
        </label>
        <div className="flex items-center gap-2">
          <input
            type="color"
            value={fillColor}
            onChange={(e) => setFillColor(e.target.value)}
            className="h-8 w-12 rounded border border-border bg-surface"
            disabled={isSaving}
          />
          <input
            type="color"
            value={strokeColor}
            onChange={(e) => setStrokeColor(e.target.value)}
            className="h-8 w-12 rounded border border-border bg-surface"
            disabled={isSaving}
          />
        </div>
      </section>

      {saveError && (
        <div className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-xs text-danger">
          {saveError}
        </div>
      )}

      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          disabled={!node || isSaving}
          onClick={() =>
            node &&
            onSave({
              wbsNodeId: node.id,
              wbsCode: node.code,
              wbsName: node.name,
              fillColor,
              strokeColor,
            })
          }
        >
          {isSaving ? "Saving…" : "Save polygon"}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          onClick={onDiscard}
          disabled={isSaving}
        >
          Discard
        </Button>
      </div>
    </aside>
  );
}

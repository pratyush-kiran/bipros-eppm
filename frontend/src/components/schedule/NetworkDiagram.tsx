"use client";

import React, { useMemo, useRef, useState, useEffect } from "react";
import type { ActivityResponse } from "@/lib/api/activityApi";
import { ZoomIn, ZoomOut, Maximize } from "lucide-react";

interface NetworkDiagramProps {
  activities: ActivityResponse[];
  relationships?: Array<{
    predecessorActivityId: string;
    successorActivityId: string;
    relationshipType: string;
  }>;
  onActivityClick?: (id: string) => void;
  onActivityContextMenu?: (id: string, x: number, y: number) => void;
}

interface ActivityNode {
  activity: ActivityResponse;
  depth: number;
  x: number;
  y: number;
}

interface ViewState {
  x: number;
  y: number;
  scale: number;
}

export function NetworkDiagram({ activities, relationships = [], onActivityClick, onActivityContextMenu }: NetworkDiagramProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<ViewState>({ x: 0, y: 0, scale: 1 });
  const [view, setView] = useState<ViewState>({ x: 0, y: 0, scale: 1 });
  const [isDragging, setIsDragging] = useState(false);
  const dragState = useRef({ active: false, startX: 0, startY: 0, viewX: 0, viewY: 0 });

  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null);

  const { nodes, svgWidth, svgHeight } = useMemo(() => {
    return calculateLayout(activities, relationships);
  }, [activities, relationships]);

  const [draggableNodes, setDraggableNodes] = useState<ActivityNode[]>([]);

  // Sync viewRef with view state
  viewRef.current = view;

  // Initialize draggable nodes from layout when data changes
  useEffect(() => {
    setDraggableNodes(nodes);
  }, [nodes]);

  // Auto-fit on first meaningful render
  useEffect(() => {
    if (!containerRef.current || activities.length === 0) return;
    const container = containerRef.current;
    const rect = container.getBoundingClientRect();
    const padding = 40;
    const scaleX = (rect.width - padding * 2) / svgWidth;
    const scaleY = (rect.height - padding * 2) / svgHeight;
    const scale = Math.min(scaleX, scaleY, 1);
    const x = (rect.width - svgWidth * scale) / 2;
    const y = padding;
    const next = { x, y, scale };
    viewRef.current = next;
    setView(next);
  }, [svgWidth, svgHeight, activities.length]);

  // Native wheel listener with passive: false
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      const mouseX = e.clientX - rect.left;
      const mouseY = e.clientY - rect.top;

      const zoomIntensity = 0.1;
      const delta = e.deltaY < 0 ? zoomIntensity : -zoomIntensity;
      const current = viewRef.current;
      const newScale = Math.min(Math.max(current.scale + delta, 0.2), 3);

      const scaleRatio = newScale / current.scale;
      const newX = mouseX - (mouseX - current.x) * scaleRatio;
      const newY = mouseY - (mouseY - current.y) * scaleRatio;

      const next = { x: newX, y: newY, scale: newScale };
      viewRef.current = next;
      setView(next);
    };

    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, []);

  // Mouse drag handlers — canvas pan
  const handleMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    const current = viewRef.current;
    dragState.current = {
      active: true,
      startX: e.clientX,
      startY: e.clientY,
      viewX: current.x,
      viewY: current.y,
    };
    setIsDragging(true);
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    // Node drag takes priority
    if (draggingNodeId) {
      const dx = e.movementX / viewRef.current.scale;
      const dy = e.movementY / viewRef.current.scale;
      setDraggableNodes((prev) =>
        prev.map((n) =>
          n.activity.id === draggingNodeId
            ? { ...n, x: n.x + dx, y: n.y + dy }
            : n
        )
      );
      return;
    }

    if (!dragState.current.active) return;
    const dx = e.clientX - dragState.current.startX;
    const dy = e.clientY - dragState.current.startY;
    const next = {
      x: dragState.current.viewX + dx,
      y: dragState.current.viewY + dy,
      scale: viewRef.current.scale,
    };
    viewRef.current = next;
    setView(next);
  };

  const endDrag = () => {
    setDraggingNodeId(null);
    dragState.current.active = false;
    setIsDragging(false);
  };

  // Touch handlers — canvas pan only (node drag on touch is future work)
  const handleTouchStart = (e: React.TouchEvent) => {
    if (e.touches.length !== 1) return;
    const current = viewRef.current;
    dragState.current = {
      active: true,
      startX: e.touches[0].clientX,
      startY: e.touches[0].clientY,
      viewX: current.x,
      viewY: current.y,
    };
    setIsDragging(true);
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (!dragState.current.active || e.touches.length !== 1) return;
    const dx = e.touches[0].clientX - dragState.current.startX;
    const dy = e.touches[0].clientY - dragState.current.startY;
    const next = {
      x: dragState.current.viewX + dx,
      y: dragState.current.viewY + dy,
      scale: viewRef.current.scale,
    };
    viewRef.current = next;
    setView(next);
  };

  const handleTouchEnd = () => {
    dragState.current.active = false;
    setIsDragging(false);
  };

  // Node drag handlers
  const handleNodeMouseDown = (e: React.MouseEvent, nodeId: string) => {
    e.stopPropagation();
    setDraggingNodeId(nodeId);
    setIsDragging(true);
  };

  const zoomIn = () => {
    const current = viewRef.current;
    const next = { ...current, scale: Math.min(current.scale + 0.2, 3) };
    viewRef.current = next;
    setView(next);
  };

  const zoomOut = () => {
    const current = viewRef.current;
    const next = { ...current, scale: Math.max(current.scale - 0.2, 0.2) };
    viewRef.current = next;
    setView(next);
  };

  const resetView = () => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const padding = 40;
    const scaleX = (rect.width - padding * 2) / svgWidth;
    const scaleY = (rect.height - padding * 2) / svgHeight;
    const scale = Math.min(scaleX, scaleY, 1);
    const x = (rect.width - svgWidth * scale) / 2;
    const y = padding;
    const next = { x, y, scale };
    viewRef.current = next;
    setView(next);
  };

  if (activities.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border py-12 text-center">
        <p className="text-text-secondary">No activities to display</p>
        <p className="mt-2 text-sm text-text-muted">Create activities and add dependencies to see the network diagram.</p>
      </div>
    );
  }

  const nodeMap = new Map(draggableNodes.map((n) => [n.activity.id, n]));

  const containerCursor = draggingNodeId ? "grabbing" : isDragging ? "grabbing" : "grab";

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text-primary">Activity Network Diagram</h2>
        <div className="flex items-center gap-1">
          <button onClick={zoomIn} className="rounded-md p-1.5 text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors" title="Zoom In">
            <ZoomIn size={16} />
          </button>
          <button onClick={zoomOut} className="rounded-md p-1.5 text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors" title="Zoom Out">
            <ZoomOut size={16} />
          </button>
          <button onClick={resetView} className="rounded-md p-1.5 text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors" title="Fit to Screen">
            <Maximize size={16} />
          </button>
          <div className="ml-2 rounded-md bg-surface-hover px-2 py-1 text-xs font-mono text-text-secondary">
            {Math.round(view.scale * 100)}%
          </div>
        </div>
      </div>

      <div
        ref={containerRef}
        className="relative overflow-hidden rounded-lg border border-border bg-surface/50 select-none"
        style={{ height: "600px", cursor: containerCursor, touchAction: "none" }}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={endDrag}
        onMouseLeave={endDrag}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
      >
        {/* Grid background */}
        <div
          className="absolute inset-0 opacity-10 pointer-events-none"
          style={{
            backgroundImage: `
              linear-gradient(to right, var(--border) 1px, transparent 1px),
              linear-gradient(to bottom, var(--border) 1px, transparent 1px)
            `,
            backgroundSize: `${20 * view.scale}px ${20 * view.scale}px`,
            backgroundPosition: `${view.x}px ${view.y}px`,
          }}
        />

        <svg
          width={svgWidth}
          height={svgHeight}
          style={{
            transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})`,
            transformOrigin: "0 0",
            overflow: "visible",
          }}
        >
          <defs>
            <marker
              id="arrowhead"
              markerWidth="10"
              markerHeight="10"
              refX="9"
              refY="3"
              orient="auto"
            >
              <polygon points="0 0, 10 3, 0 6" fill="var(--text-secondary)" />
            </marker>
            <marker
              id="arrowhead-critical"
              markerWidth="10"
              markerHeight="10"
              refX="9"
              refY="3"
              orient="auto"
            >
              <polygon points="0 0, 10 3, 0 6" fill="var(--danger)" />
            </marker>
          </defs>

          {/* Relationship lines — no pointer events so they don't block node drag */}
          <g pointerEvents="none">
            {relationships.map((rel, index) => {
              const predNode = nodeMap.get(rel.predecessorActivityId);
              const succNode = nodeMap.get(rel.successorActivityId);

              if (!predNode || !succNode) return null;

              const x1 = predNode.x + 120;
              const y1 = predNode.y + 60;
              const x2 = succNode.x - 10;
              const y2 = succNode.y + 60;

              const isCritical = predNode.activity.totalFloat === 0 && succNode.activity.totalFloat === 0;

              return (
                <g key={`rel-${index}`}>
                  <line
                    x1={x1}
                    y1={y1}
                    x2={x2}
                    y2={y2}
                    stroke={isCritical ? "var(--danger)" : "var(--text-muted)"}
                    strokeWidth="2"
                    markerEnd={isCritical ? "url(#arrowhead-critical)" : "url(#arrowhead)"}
                  />
                  {rel.relationshipType && (
                    <text
                      x={(x1 + x2) / 2}
                      y={(y1 + y2) / 2 - 5}
                      fontSize="11"
                      fill="var(--text-secondary)"
                      textAnchor="middle"
                    >
                      {rel.relationshipType}
                    </text>
                  )}
                </g>
              );
            })}
          </g>

          {/* Activity boxes — draggable */}
          {draggableNodes.map((node) => {
            const isCritical = node.activity.totalFloat === 0;
            const boxWidth = 240;
            const boxHeight = 100;
            const isBeingDragged = draggingNodeId === node.activity.id;

            return (
              <g
                key={node.activity.id}
                onMouseDown={(e) => handleNodeMouseDown(e, node.activity.id)}
                style={{ cursor: isBeingDragged ? "grabbing" : "grab" }}
                pointerEvents="all"
              >
                <rect
                  x={node.x}
                  y={node.y}
                  width={boxWidth}
                  height={boxHeight}
                  fill="var(--surface)"
                  stroke={isCritical ? "var(--danger)" : "var(--accent)"}
                  strokeWidth={isCritical ? "3" : "2"}
                  rx="4"
                  style={{
                    filter: isBeingDragged
                      ? "drop-shadow(0 4px 8px rgba(0,0,0,0.3))"
                      : "none",
                  }}
                  onClick={() => onActivityClick?.(node.activity.id)}
                  onContextMenu={(e) => {
                    e.preventDefault();
                    onActivityContextMenu?.(node.activity.id, e.clientX, e.clientY);
                  }}
                />

                <text
                  x={node.x + 8}
                  y={node.y + 18}
                  fontSize="12"
                  fontWeight="bold"
                  fill="var(--text-secondary)"
                  className="font-mono"
                >
                  {node.activity.code}
                </text>

                <text
                  x={node.x + 8}
                  y={node.y + 35}
                  fontSize="11"
                  fill="var(--text-secondary)"
                  className="max-w-xs truncate"
                >
                  {node.activity.name.substring(0, 30)}
                  {node.activity.name.length > 30 ? "..." : ""}
                </text>

                <line
                  x1={node.x}
                  y1={node.y + 42}
                  x2={node.x + boxWidth}
                  y2={node.y + 42}
                  stroke="var(--border)"
                  strokeWidth="1"
                />

                <text x={node.x + 8} y={node.y + 58} fontSize="9" fill="var(--text-secondary)">
                  ES
                </text>
                <text
                  x={node.x + 8}
                  y={node.y + 70}
                  fontSize="10"
                  fontWeight="bold"
                  fill="var(--text-primary)"
                  className="font-mono"
                >
                  {formatDate(node.activity.earlyStartDate)}
                </text>

                <text x={node.x + 65} y={node.y + 58} fontSize="9" fill="var(--text-secondary)">
                  EF
                </text>
                <text
                  x={node.x + 65}
                  y={node.y + 70}
                  fontSize="10"
                  fontWeight="bold"
                  fill="var(--text-primary)"
                  className="font-mono"
                >
                  {formatDate(node.activity.earlyFinishDate)}
                </text>

                <text x={node.x + 122} y={node.y + 58} fontSize="9" fill="var(--text-secondary)">
                  Float
                </text>
                <text
                  x={node.x + 122}
                  y={node.y + 70}
                  fontSize="10"
                  fontWeight={isCritical ? "bold" : "normal"}
                  fill={isCritical ? "var(--danger)" : "var(--text-primary)"}
                  className="font-mono"
                >
                  {(node.activity.totalFloat ?? 0).toFixed(1)}
                </text>

                <text x={node.x + 8} y={node.y + 82} fontSize="9" fill="var(--text-secondary)">
                  LS
                </text>
                <text
                  x={node.x + 8}
                  y={node.y + 94}
                  fontSize="10"
                  fontWeight="bold"
                  fill="var(--text-primary)"
                  className="font-mono"
                >
                  {formatDate(node.activity.lateStartDate)}
                </text>

                <text x={node.x + 65} y={node.y + 82} fontSize="9" fill="var(--text-secondary)">
                  LF
                </text>
                <text
                  x={node.x + 65}
                  y={node.y + 94}
                  fontSize="10"
                  fontWeight="bold"
                  fill="var(--text-primary)"
                  className="font-mono"
                >
                  {formatDate(node.activity.lateFinishDate)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>

      {/* Legend */}
      <div className="flex flex-wrap gap-6 rounded-lg border border-border bg-surface/80 p-4">
        <div className="flex items-center gap-2">
          <div className="h-4 w-12 border-2 border-accent rounded" />
          <span className="text-sm text-text-secondary">Normal Activity</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="h-4 w-12 border-3 border-danger rounded" />
          <span className="text-sm text-text-secondary">Critical Activity</span>
        </div>
      </div>
    </div>
  );
}

function calculateLayout(
  activities: ActivityResponse[],
  relationships: Array<{
    predecessorActivityId: string;
    successorActivityId: string;
    relationshipType: string;
  }>
): {
  nodes: ActivityNode[];
  depthLevels: Record<number, ActivityResponse[]>;
  svgWidth: number;
  svgHeight: number;
} {
  const predecessors = new Map<string, Set<string>>();
  const successors = new Map<string, Set<string>>();

  activities.forEach((a) => {
    predecessors.set(a.id, new Set());
    successors.set(a.id, new Set());
  });

  relationships.forEach((rel) => {
    predecessors.get(rel.successorActivityId)?.add(rel.predecessorActivityId);
    successors.get(rel.predecessorActivityId)?.add(rel.successorActivityId);
  });

  const depth = new Map<string, number>();
  const visited = new Set<string>();
  const visiting = new Set<string>();

  function calculateDepthDFS(activityId: string): number {
    if (depth.has(activityId)) {
      return depth.get(activityId)!;
    }

    if (visiting.has(activityId)) {
      return 0;
    }

    visiting.add(activityId);

    const preds = predecessors.get(activityId) || new Set();
    if (preds.size === 0) {
      depth.set(activityId, 0);
    } else {
      let maxPredDepth = 0;
      for (const pred of preds) {
        maxPredDepth = Math.max(maxPredDepth, calculateDepthDFS(pred));
      }
      depth.set(activityId, maxPredDepth + 1);
    }

    visiting.delete(activityId);
    return depth.get(activityId)!;
  }

  activities.forEach((a) => {
    if (!visited.has(a.id)) {
      calculateDepthDFS(a.id);
      visited.add(a.id);
    }
  });

  const depthLevels: Record<number, ActivityResponse[]> = {};
  activities.forEach((a) => {
    const d = depth.get(a.id) || 0;
    if (!depthLevels[d]) {
      depthLevels[d] = [];
    }
    depthLevels[d].push(a);
  });

  Object.values(depthLevels).forEach((level) => {
    level.sort((a, b) => a.code.localeCompare(b.code));
  });

  // Box is 240×100; leave generous gaps so dependency edges read clearly
  // and the diagram resembles a tree rather than a tight grid.
  const colWidth = 360; // 240 box + 120 horizontal gap
  const rowHeight = 180; // 100 box + 80 vertical gap (within band)
  const padding = 40;
  const bandGap = 80; // vertical gap between wrapped row-bands
  const MAX_COLS_PER_ROW = 6; // wrap long chains into multiple bands

  // Group depth levels into horizontal bands of up to MAX_COLS_PER_ROW columns.
  // Within each band, rows stack per-level (activities at the same depth sit
  // one above the other); bands stack vertically, each sized by the widest
  // level it contains. Net effect: a linear chain of N activities wraps into
  // ceil(N / MAX_COLS_PER_ROW) readable bands instead of one ~(N × 250)px row.
  const depthKeys = Object.keys(depthLevels)
    .map((d) => parseInt(d, 10))
    .sort((a, b) => a - b);

  const bandHeightMap = new Map<number, number>(); // band index → max activities in any of its levels
  depthKeys.forEach((d) => {
    const band = Math.floor(d / MAX_COLS_PER_ROW);
    const count = depthLevels[d].length;
    bandHeightMap.set(band, Math.max(bandHeightMap.get(band) ?? 0, count));
  });

  const bandYStart = new Map<number, number>();
  let cursorY = padding;
  const bandIndices = [...bandHeightMap.keys()].sort((a, b) => a - b);
  bandIndices.forEach((band) => {
    bandYStart.set(band, cursorY);
    cursorY += (bandHeightMap.get(band) ?? 1) * rowHeight + bandGap;
  });

  const nodes: ActivityNode[] = [];
  depthKeys.forEach((depthNum) => {
    const band = Math.floor(depthNum / MAX_COLS_PER_ROW);
    const colInBand = depthNum % MAX_COLS_PER_ROW;
    const xStart = padding + colInBand * colWidth;
    const yStart = bandYStart.get(band) ?? padding;

    depthLevels[depthNum].forEach((activity, index) => {
      nodes.push({
        activity,
        depth: depthNum,
        x: xStart,
        y: yStart + index * rowHeight,
      });
    });
  });

  const usedCols = Math.min(depthKeys.length, MAX_COLS_PER_ROW);
  const svgWidth = usedCols * colWidth + 2 * padding;
  // cursorY accumulated one trailing bandGap we don't actually need
  const svgHeight = Math.max(cursorY + padding - bandGap, rowHeight + 2 * padding);

  return { nodes, depthLevels, svgWidth, svgHeight };
}

function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return "N/A";
  const date = new Date(dateStr);
  return date.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "2-digit",
  });
}

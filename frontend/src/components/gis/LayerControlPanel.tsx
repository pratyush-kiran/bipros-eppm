"use client";

import { useEffect, useRef } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { format } from "date-fns";
import { gisApi, GisLayer, SatelliteImage } from "@/lib/api/gisApi";
import { Button } from "@/components/ui/button";
import type { LayerVisibility } from "./MapViewer";

interface LayerControlPanelProps {
  projectId: `${string}-${string}-${string}-${string}-${string}`;
  visibility: LayerVisibility;
  onVisibilityChange: (v: LayerVisibility) => void;
  satelliteOpacity: number;
  onSatelliteOpacityChange: (v: number) => void;
  selectedScene: SatelliteImage | null;
  onZoomToPolygons: () => void;
  canZoomToPolygons: boolean;
  backendLayers: GisLayer[] | undefined;
}

/**
 * Side panel that drives layer visibility, satellite opacity, and surfaces the
 * selected scene's metadata. Visibility/opacity state is owned by the page;
 * this panel also best-effort persists overlay layer visibility + opacity via
 * PUT /gis/layers/{id} so the preferences survive navigation. Opacity writes
 * are fired on pointer release so slider drags don't flood the API.
 */
export function LayerControlPanel({
  projectId,
  visibility,
  onVisibilityChange,
  satelliteOpacity,
  onSatelliteOpacityChange,
  selectedScene,
  onZoomToPolygons,
  canZoomToPolygons,
  backendLayers,
}: LayerControlPanelProps) {
  const qc = useQueryClient();
  const overlayLayers = (backendLayers ?? []).filter(
    (l) => l.layerType === "SATELLITE_OVERLAY"
  );

  const updateMutation = useMutation({
    mutationFn: async (patch: { isVisible?: boolean; opacity?: number }) => {
      await Promise.all(
        overlayLayers.map((l) => gisApi.updateLayer(projectId, l.id, patch))
      );
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["gis", projectId, "layers"] });
    },
  });

  // Persist opacity on pointerUp (fires from the slider's onMouseUp/onTouchEnd);
  // visibility toggles persist immediately via the checkbox handler.
  const lastPersistedOpacity = useRef(satelliteOpacity);
  useEffect(() => {
    lastPersistedOpacity.current = satelliteOpacity;
  }, [satelliteOpacity]);

  const setVisibility = (patch: Partial<LayerVisibility>) =>
    onVisibilityChange({ ...visibility, ...patch });

  const rasterControlsDisabled = !visibility.satellite || !selectedScene;

  return (
    <aside className="flex flex-col gap-4 rounded-lg border border-border bg-surface/50 p-4">
      <section>
        <h3 className="text-sm font-semibold text-text-primary mb-2">Layers</h3>
        <label className="flex items-center gap-2 text-sm text-text-secondary py-1 cursor-pointer">
          <input
            type="checkbox"
            checked={visibility.baseMap}
            onChange={(e) => setVisibility({ baseMap: e.target.checked })}
          />
          <span>Base map (OSM)</span>
        </label>
        <label className="flex items-center gap-2 text-sm text-text-secondary py-1 cursor-pointer">
          <input
            type="checkbox"
            checked={visibility.polygons}
            onChange={(e) => setVisibility({ polygons: e.target.checked })}
          />
          <span>WBS polygons</span>
        </label>
        <label className="flex items-center gap-2 text-sm text-text-secondary py-1 cursor-pointer">
          <input
            type="checkbox"
            checked={visibility.satellite}
            onChange={(e) => {
              const isVisible = e.target.checked;
              setVisibility({ satellite: isVisible });
              if (overlayLayers.length > 0) {
                updateMutation.mutate({ isVisible });
              }
            }}
          />
          <span>Satellite overlay</span>
        </label>
      </section>

      <section>
        <h3 className="text-sm font-semibold text-text-primary mb-2">
          Satellite Opacity
        </h3>
        <input
          type="range"
          min={0}
          max={100}
          value={Math.round(satelliteOpacity * 100)}
          onChange={(e) =>
            onSatelliteOpacityChange(Number(e.target.value) / 100)
          }
          onPointerUp={() => {
            if (overlayLayers.length > 0) {
              updateMutation.mutate({ opacity: lastPersistedOpacity.current });
            }
          }}
          disabled={rasterControlsDisabled}
          className="w-full"
        />
        <div className="text-xs text-text-muted mt-1">
          {Math.round(satelliteOpacity * 100)}%
          {rasterControlsDisabled && (
            <span className="ml-2 text-text-muted">
              (enable overlay + pick a scene)
            </span>
          )}
        </div>
      </section>

      {selectedScene && (
        <section>
          <h3 className="text-sm font-semibold text-text-primary mb-2">
            Selected scene
          </h3>
          <div className="space-y-1 text-xs text-text-secondary">
            <p>
              <span className="font-medium">Date:</span>{" "}
              {format(new Date(selectedScene.captureDate), "dd MMM yyyy")}
            </p>
            <p>
              <span className="font-medium">Source:</span>{" "}
              {selectedScene.source.replace(/_/g, " ")}
            </p>
            {selectedScene.resolution && (
              <p>
                <span className="font-medium">Resolution:</span>{" "}
                {selectedScene.resolution}
              </p>
            )}
            <p>
              <span className="font-medium">Status:</span>{" "}
              <span
                className={`ml-1 px-2 py-0.5 rounded text-xs font-medium ${
                  selectedScene.status === "READY"
                    ? "bg-success/10 text-success"
                    : selectedScene.status === "FAILED"
                      ? "bg-danger/10 text-danger"
                      : "bg-warning/10 text-warning"
                }`}
              >
                {selectedScene.status}
              </span>
            </p>
            {selectedScene.description && (
              <p className="text-text-muted pt-1">
                {selectedScene.description}
              </p>
            )}
            <p className="text-text-muted pt-1 break-all">
              id: {String(selectedScene.id)}
            </p>
          </div>
        </section>
      )}

      <section>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={onZoomToPolygons}
          disabled={!canZoomToPolygons}
          className="w-full"
        >
          Zoom to polygons
        </Button>
      </section>
    </aside>
  );
}

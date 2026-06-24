"use client";

import { useEffect, useRef, useState } from "react";
import { Map, View } from "ol";
import TileLayer from "ol/layer/Tile";
import VectorLayer from "ol/layer/Vector";
import ImageLayer from "ol/layer/Image";
import OSM from "ol/source/OSM";
import VectorSource from "ol/source/Vector";
import ImageStatic from "ol/source/ImageStatic";
import Feature from "ol/Feature";
import { Style, Fill, Stroke, Text } from "ol/style";
import GeoJSON from "ol/format/GeoJSON";
import Overlay from "ol/Overlay";
import { transformExtent } from "ol/proj";
import { isEmpty as isExtentEmpty } from "ol/extent";
import Draw from "ol/interaction/Draw";
import Modify from "ol/interaction/Modify";
import Snap from "ol/interaction/Snap";
import Polygon from "ol/geom/Polygon";
import "ol/ol.css";
import { GeoJsonFeatureCollection, SatelliteImage } from "@/lib/api/gisApi";
import type { FeatureLike } from "ol/Feature";
import type { MapMode } from "./MapModeToolbar";

export interface LayerVisibility {
  baseMap: boolean;
  polygons: boolean;
  satellite: boolean;
}

interface MapViewerProps {
  geoJsonData: GeoJsonFeatureCollection;
  visibility: LayerVisibility;
  satelliteOpacity: number;
  selectedScene: SatelliteImage | null;
  sceneBlobUrl: string | null;
  fitPolygonsSignal: number;
  mode?: MapMode;
  onDrawEnd?: (geom: Polygon) => void;
  onModifyEnd?: (feature: Feature) => void;
  onDeleteClick?: (feature: Feature) => void;
  onSelectFeature?: (feature: Feature | null) => void;
}

/**
 * OpenLayers map with three stacked layers: OSM base, satellite raster
 * overlay (ImageStatic from a blob URL), WBS polygon vectors on top so the
 * outlines remain readable. Layer visibility/opacity are controlled via props
 * so the sibling LayerControlPanel can drive them without touching the map
 * directly. Effects are split so that swapping a scene doesn't tear down the
 * map or the vector features.
 */
export function MapViewer({
  geoJsonData,
  visibility,
  satelliteOpacity,
  selectedScene,
  sceneBlobUrl,
  fitPolygonsSignal,
  mode = "view",
  onDrawEnd,
  onModifyEnd,
  onDeleteClick,
  onSelectFeature,
}: MapViewerProps) {
  const mapEl = useRef<HTMLDivElement>(null);
  const mapRef = useRef<Map | null>(null);
  const baseLayerRef = useRef<TileLayer<OSM> | null>(null);
  const vectorLayerRef = useRef<VectorLayer<VectorSource> | null>(null);
  const rasterLayerRef = useRef<ImageLayer<ImageStatic> | null>(null);
  const popupRef = useRef<Overlay | null>(null);
  const popupElRef = useRef<HTMLDivElement | null>(null);
  const drawRef = useRef<Draw | null>(null);
  const modifyRef = useRef<Modify | null>(null);
  const snapRef = useRef<Snap | null>(null);
  // Hold the latest handlers in refs so the [mode] effect doesn't need to
  // re-run when parent-supplied callbacks change identity between renders.
  const modeRef = useRef<MapMode>(mode);
  const onDrawEndRef = useRef(onDrawEnd);
  const onModifyEndRef = useRef(onModifyEnd);
  const onDeleteClickRef = useRef(onDeleteClick);
  const onSelectFeatureRef = useRef(onSelectFeature);
  useEffect(() => {
    modeRef.current = mode;
    onDrawEndRef.current = onDrawEnd;
    onModifyEndRef.current = onModifyEnd;
    onDeleteClickRef.current = onDeleteClick;
    onSelectFeatureRef.current = onSelectFeature;
  }, [mode, onDrawEnd, onModifyEnd, onDeleteClick, onSelectFeature]);

  const [selectedPolygon, setSelectedPolygon] = useState<
    Record<string, unknown> | null
  >(null);

  // Mount-once: build the map, layers, popup. Cleanup disposes everything so
  // React 19 StrictMode's double-mount doesn't leave stale popup nodes or
  // throw "map already has target".
  useEffect(() => {
    if (!mapEl.current) return;

    const baseLayer = new TileLayer({ source: new OSM() });
    const rasterLayer = new ImageLayer<ImageStatic>({ source: undefined });
    const vectorSource = new VectorSource();
    const vectorLayer = new VectorLayer({
      source: vectorSource,
      style: (feature) => {
        const props = feature.getProperties();
        return new Style({
          fill: new Fill({ color: props.fillColor || "#3388ff" }),
          stroke: new Stroke({
            color: props.strokeColor || "#000000",
            width: 2,
          }),
          text: new Text({
            text: props.wbsCode || "",
            fill: new Fill({ color: "#000" }),
            font: "12px Arial",
          }),
        });
      },
    });

    const popupContainer = document.createElement("div");
    popupContainer.className =
      "absolute bg-surface rounded-lg shadow-lg p-4 z-50 border border-border pointer-events-none";
    document.body.appendChild(popupContainer);
    const popup = new Overlay({ element: popupContainer, autoPan: true });

    const map = new Map({
      target: mapEl.current,
      // Z-order: base → raster → vector (polygons always on top)
      layers: [baseLayer, rasterLayer, vectorLayer],
      view: new View({
        center: [8388635.2, 2147483.6],
        zoom: 4,
        projection: "EPSG:3857",
      }),
      overlays: [popup],
    });

    map.on("click", (event) => {
      const currentMode = modeRef.current;
      // While drawing, OL owns the click stream; surfacing a popup here would
      // also race with the drawend handler.
      if (currentMode === "draw") return;

      let clickedFeature: FeatureLike | null = null;
      map.forEachFeatureAtPixel(event.pixel, (feature) => {
        clickedFeature = feature;
        return true;
      });

      if (currentMode === "delete") {
        if (clickedFeature) onDeleteClickRef.current?.(clickedFeature as Feature);
        return;
      }

      if (currentMode === "modify") {
        onSelectFeatureRef.current?.(
          clickedFeature ? (clickedFeature as Feature) : null
        );
        return;
      }

      // view mode: keep the popup behaviour.
      if (clickedFeature) {
        const feat = clickedFeature as Feature;
        const props = feat.getProperties();
        setSelectedPolygon(props);
        const extent = feat.getGeometry()?.getExtent();
        if (extent) {
          const center: [number, number] = [
            (extent[0] + extent[2]) / 2,
            (extent[1] + extent[3]) / 2,
          ];
          popup.setPosition(center);
          popupContainer.textContent = "";
          const div = document.createElement("div");
          div.className = "text-sm";
          const h3 = document.createElement("h3");
          h3.className = "font-bold text-text-primary";
          h3.textContent = String(props.wbsCode ?? "");
          const p = document.createElement("p");
          p.className = "text-text-secondary";
          p.textContent = String(props.wbsName ?? "");
          div.appendChild(h3);
          div.appendChild(p);
          popupContainer.appendChild(div);
        }
      } else {
        popup.setPosition(undefined);
        setSelectedPolygon(null);
      }
    });

    map.on("pointermove", (event) => {
      if (!mapEl.current) return;
      const hovering = map.hasFeatureAtPixel(event.pixel);
      const currentMode = modeRef.current;
      if (currentMode === "draw") {
        mapEl.current.style.cursor = "crosshair";
      } else if (currentMode === "modify") {
        mapEl.current.style.cursor = hovering ? "grab" : "default";
      } else if (currentMode === "delete") {
        mapEl.current.style.cursor = hovering ? "not-allowed" : "default";
      } else {
        mapEl.current.style.cursor = hovering ? "pointer" : "default";
      }
    });

    mapRef.current = map;
    baseLayerRef.current = baseLayer;
    rasterLayerRef.current = rasterLayer;
    vectorLayerRef.current = vectorLayer;
    popupRef.current = popup;
    popupElRef.current = popupContainer;

    return () => {
      map.setTarget(undefined);
      map.dispose();
      popupContainer.remove();
      mapRef.current = null;
      baseLayerRef.current = null;
      rasterLayerRef.current = null;
      vectorLayerRef.current = null;
      popupRef.current = null;
      popupElRef.current = null;
    };
  }, []);

  // Swap vector features when geoJsonData changes — don't rebuild the map.
  useEffect(() => {
    const layer = vectorLayerRef.current;
    if (!layer) return;
    const src = layer.getSource();
    if (!src) return;
    src.clear();
    if (!geoJsonData || !geoJsonData.features?.length) return;
    const feats = new GeoJSON().readFeatures(geoJsonData, {
      featureProjection: "EPSG:3857",
    });
    src.addFeatures(feats);
  }, [geoJsonData]);

  // Swap the raster when the scene (or its blob URL) changes.
  useEffect(() => {
    const layer = rasterLayerRef.current;
    if (!layer) return;
    if (!selectedScene || !sceneBlobUrl) {
      layer.setSource(null as unknown as ImageStatic);
      return;
    }
    const { westBound: w, southBound: s, eastBound: e, northBound: n } =
      selectedScene;
    if (
      typeof w !== "number" ||
      typeof s !== "number" ||
      typeof e !== "number" ||
      typeof n !== "number"
    ) {
      layer.setSource(null as unknown as ImageStatic);
      return;
    }
    if (w > e) {
      // Antimeridian-crossing rasters would render as a globe-spanning smear.
      console.warn("[MapViewer] skipping antimeridian-crossing raster", {
        w, e, sceneId: selectedScene.id,
      });
      layer.setSource(null as unknown as ImageStatic);
      return;
    }
    const extent3857 = transformExtent(
      [w, s, e, n],
      "EPSG:4326",
      "EPSG:3857"
    );
    layer.setSource(
      new ImageStatic({
        url: sceneBlobUrl,
        imageExtent: extent3857,
        projection: "EPSG:3857",
        crossOrigin: undefined,
      })
    );
  }, [selectedScene, sceneBlobUrl]);

  // Live visibility + opacity — no rebuild.
  useEffect(() => {
    baseLayerRef.current?.setVisible(visibility.baseMap);
    vectorLayerRef.current?.setVisible(visibility.polygons);
    rasterLayerRef.current?.setVisible(visibility.satellite);
    rasterLayerRef.current?.setOpacity(
      Math.max(0, Math.min(1, satelliteOpacity))
    );
  }, [visibility, satelliteOpacity]);

  // Fit view to polygon extent when data loads or the parent re-requests it.
  useEffect(() => {
    const map = mapRef.current;
    const layer = vectorLayerRef.current;
    if (!map || !layer) return;
    const src = layer.getSource();
    if (!src) return;
    const extent = src.getExtent();
    if (!extent || isExtentEmpty(extent)) return;
    map.getView().fit(extent, {
      padding: [40, 40, 40, 40],
      maxZoom: 18,
      duration: 300,
    });
  }, [geoJsonData, fitPolygonsSignal]);

  // Mount/teardown Draw/Modify/Snap interactions when the mode changes. We
  // intentionally re-create them each transition so event listeners, snap
  // targets, and the active-feature collection start from a clean slate.
  useEffect(() => {
    const map = mapRef.current;
    const vectorLayer = vectorLayerRef.current;
    if (!map || !vectorLayer) return;
    const source = vectorLayer.getSource();
    if (!source) return;

    // Any mode change hides the view-mode popup and clears an outside selection.
    popupRef.current?.setPosition(undefined);
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedPolygon(null);

    if (mode === "draw") {
      const draw = new Draw({ source, type: "Polygon" });
      draw.on("drawend", (evt) => {
        const geom = evt.feature.getGeometry();
        if (geom instanceof Polygon) {
          onDrawEndRef.current?.(geom);
        }
        // Remove the just-drawn feature from the live source; the parent owns
        // it via pending-state until the user commits via Save.
        setTimeout(() => {
          try {
            source.removeFeature(evt.feature);
          } catch {
            // already removed / feature replaced — safe to ignore
          }
        }, 0);
      });
      const snap = new Snap({ source });
      map.addInteraction(draw);
      map.addInteraction(snap);
      drawRef.current = draw;
      snapRef.current = snap;
    } else if (mode === "modify") {
      const modify = new Modify({ source });
      modify.on("modifyend", (evt) => {
        const feat = evt.features.item(0);
        if (feat) onModifyEndRef.current?.(feat as Feature);
      });
      const snap = new Snap({ source });
      map.addInteraction(modify);
      map.addInteraction(snap);
      modifyRef.current = modify;
      snapRef.current = snap;
    }
    // "view" and "delete" need no OL interaction — the shared click handler
    // branches on modeRef.

    return () => {
      if (drawRef.current) {
        drawRef.current.abortDrawing();
        map.removeInteraction(drawRef.current);
        drawRef.current = null;
      }
      if (modifyRef.current) {
        map.removeInteraction(modifyRef.current);
        modifyRef.current = null;
      }
      if (snapRef.current) {
        map.removeInteraction(snapRef.current);
        snapRef.current = null;
      }
    };
  }, [mode]);

  return (
    <div className="relative w-full">
      <div
        ref={mapEl}
        className="w-full h-[32rem] bg-surface-hover rounded-lg border border-border"
      />
      {selectedPolygon && (
        <div className="mt-4 p-3 bg-info/10 border border-info/20 rounded">
          <p className="text-sm font-medium text-text-primary">
            Selected: {String(selectedPolygon.wbsCode ?? "")}
          </p>
          <p className="text-sm text-text-secondary">
            {String(selectedPolygon.wbsName ?? "")}
          </p>
        </div>
      )}
    </div>
  );
}

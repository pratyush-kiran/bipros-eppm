"use client";

import { useEffect, useState } from "react";
import { gisApi, SatelliteImage } from "@/lib/api/gisApi";
import { formatDate } from "date-fns";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import toast from "react-hot-toast";

/**
 * One thumbnail tile. Fetches the raster bytes via the authenticated apiClient,
 * wraps them in a blob URL, renders with an {@code <img>}. Blob URLs are
 * revoked on unmount so navigating away doesn't leak Blob references.
 */
function SatelliteThumbnail({
  projectId,
  imageId,
  mimeType,
}: {
  projectId: string;
  imageId: string;
  mimeType?: string;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let revoked: string | null = null;
    let cancelled = false;
    gisApi
      .getSatelliteImageThumbnail(
        projectId as `${string}-${string}-${string}-${string}-${string}`,
        imageId as `${string}-${string}-${string}-${string}-${string}`
      )
      .then((response) => {
        if (cancelled) return;
        const blob = new Blob([response.data], {
          type: mimeType || "image/png",
        });
        const url = URL.createObjectURL(blob);
        revoked = url;
        setSrc(url);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
      if (revoked) URL.revokeObjectURL(revoked);
    };
  }, [projectId, imageId, mimeType]);

  if (error) {
    return (
      <div className="bg-surface-hover h-32 flex items-center justify-center text-text-muted">
        <span className="text-xs">⚠ thumbnail unavailable</span>
      </div>
    );
  }
  if (!src) {
    return (
      <div className="bg-surface-hover h-32 flex items-center justify-center text-text-muted animate-pulse">
        <span className="text-3xl">📡</span>
      </div>
    );
  }
  return (
    // next/image can't open blob: URLs, so use a plain <img>.
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt="Satellite tile" className="h-32 w-full object-cover bg-black" />
  );
}

interface SatelliteImageGalleryProps {
  projectId: string;
  images: SatelliteImage[];
}

export function SatelliteImageGallery({
  projectId,
  images,
}: SatelliteImageGalleryProps) {
  const qc = useQueryClient();

  const deleteMutation = useMutation({
    mutationFn: (imageId: string) =>
      gisApi.deleteSatelliteImage(
        projectId as `${string}-${string}-${string}-${string}-${string}`,
        imageId as `${string}-${string}-${string}-${string}-${string}`
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["gis", projectId, "satellite-images"] });
      toast.success("Image deleted");
    },
    onError: () => {
      toast.error("Failed to delete image");
    },
  });

  const sortedImages = [...images].sort(
    (a, b) =>
      new Date(b.captureDate).getTime() - new Date(a.captureDate).getTime()
  );

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <h3 className="text-lg font-semibold text-text-primary">
          Satellite Images
        </h3>
        <span className="text-sm text-text-secondary">{images.length} images</span>
      </div>

      {images.length === 0 ? (
        <div className="bg-surface/50 rounded-lg border border-border p-8 text-center">
          <p className="text-text-secondary">No satellite images uploaded</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {sortedImages.map((image) => (
            <div
              key={image.id}
              className="bg-surface/50 rounded-lg border border-border overflow-hidden hover:shadow-lg transition-shadow"
            >
              <SatelliteThumbnail
                projectId={projectId}
                imageId={image.id}
                mimeType={image.mimeType}
              />

              <div className="p-4">
                <div className="flex items-start justify-between gap-2 mb-2">
                  <h4 className="font-medium text-text-primary text-sm line-clamp-2">
                    {image.imageName}
                  </h4>
                  {image.source === "MANUAL_UPLOAD" && (
                    <button
                      onClick={() => {
                        if (confirm("Delete this uploaded image?")) {
                          deleteMutation.mutate(image.id);
                        }
                      }}
                      disabled={deleteMutation.isPending}
                      className="text-text-muted hover:text-danger transition-colors"
                      title="Delete"
                    >
                      <Trash2 size={14} />
                    </button>
                  )}
                </div>

                <div className="space-y-1 text-xs text-text-secondary">
                  <p>
                    <span className="font-medium">Date:</span>{" "}
                    {formatDate(new Date(image.captureDate), "dd MMM yyyy")}
                  </p>
                  <p>
                    <span className="font-medium">Source:</span>{" "}
                    {image.source.replace(/_/g, " ")}
                  </p>
                  {image.resolution && (
                    <p>
                      <span className="font-medium">Resolution:</span>{" "}
                      {image.resolution}
                    </p>
                  )}
                  <p>
                    <span className="font-medium">Size:</span>{" "}
                    {(image.fileSize / 1024 / 1024).toFixed(2)} MB
                  </p>
                  <p>
                    <span className="font-medium">Status:</span>
                    <span
                      className={`ml-1 px-2 py-1 rounded text-xs font-medium ${
                        image.status === "READY"
                          ? "bg-success/10 text-success"
                          : image.status === "FAILED"
                            ? "bg-danger/10 text-danger"
                            : "bg-warning/10 text-warning"
                      }`}
                    >
                      {image.status}
                    </span>
                  </p>
                </div>

                {image.description && (
                  <p className="text-xs text-text-muted mt-2 line-clamp-2">
                    {image.description}
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

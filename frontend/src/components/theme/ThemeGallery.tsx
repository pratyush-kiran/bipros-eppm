"use client";

import { useState } from "react";
import { Pencil, Trash2 } from "lucide-react";
import type { ThemeDefinition } from "@/lib/themes/definitions";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";

interface ThemeGalleryProps {
  themes: ThemeDefinition[];
  activeThemeId: string;
  onSelect: (id: string) => void;
  isAdmin: boolean;
  /** When provided, cards render with edit/delete affordances (custom-theme mode). */
  onEdit?: (theme: ThemeDefinition) => void;
  onDelete?: (id: string) => void;
}

export function ThemeGallery({
  themes,
  activeThemeId,
  onSelect,
  isAdmin,
  onEdit,
  onDelete,
}: ThemeGalleryProps) {
  const [deleteId, setDeleteId] = useState<string | null>(null);
  // A theme is "custom" (deletable, tagged, shows radius/font subtitle) only when
  // a delete handler is supplied. Predefined cards receive onEdit (Duplicate & edit)
  // but no onDelete, so they stay untagged and read-only as source themes.
  const isCustomTheme = Boolean(onDelete);

  return (
    <>
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3">
        {themes.map((theme) => {
          const isActive = theme.id === activeThemeId;
          const l = theme.light;
          const d = theme.dark;

          return (
            <div
              key={theme.id}
              className={`group relative rounded-xl border bg-surface overflow-hidden transition-all duration-200 ${
                isActive
                  ? "ring-2 ring-offset-1 shadow-md"
                  : "hover:-translate-y-0.5 hover:shadow-lg hover:border-accent/40"
              }`}
              style={
                {
                  ringColor: isActive ? l.accent : undefined,
                } as React.CSSProperties
              }
            >
              {/* ── Mini UI Mock (~60% of card) ── */}
              <div
                className="relative h-[104px] p-2.5 flex gap-2"
                style={{ backgroundColor: l.background }}
              >
                {/* Sidebar strip */}
                <div
                  className="w-5 rounded-md shrink-0"
                  style={{ backgroundColor: l.backgroundSubtle }}
                />

                {/* Main mock area */}
                <div className="flex-1 flex flex-col gap-1.5 min-w-0">
                  {/* Button row */}
                  <div className="flex gap-1.5">
                    <div
                      className="h-3.5 rounded px-2 text-[7px] font-semibold flex items-center justify-center leading-none"
                      style={{ backgroundColor: l.accent, color: "#ffffff" }}
                    >
                      Primary
                    </div>
                    <div
                      className="h-3.5 rounded px-2 text-[7px] font-medium flex items-center justify-center leading-none border"
                      style={{
                        backgroundColor: "transparent",
                        color: l.foregroundSecondary,
                        borderColor: l.border,
                      }}
                    >
                      Ghost
                    </div>
                  </div>

                  {/* Mini card */}
                  <div
                    className="rounded-md border p-1.5 flex flex-col gap-1"
                    style={{
                      backgroundColor: l.backgroundSubtle,
                      borderColor: l.borderSubtle,
                    }}
                  >
                    <div className="flex items-center justify-between">
                      <span
                        className="text-[7px] font-semibold leading-none"
                        style={{ color: l.foreground }}
                      >
                        Card
                      </span>
                      <span
                        className="text-[6px] px-1 py-0.5 rounded-full font-medium leading-none"
                        style={{
                          backgroundColor: l.accentTint,
                          color: l.accentSubtle,
                        }}
                      >
                        Badge
                      </span>
                    </div>
                    <span
                      className="text-[6px] leading-tight"
                      style={{ color: l.foregroundSecondary }}
                    >
                      Body text preview
                    </span>
                  </div>

                  {/* Status dots */}
                  <div className="flex gap-1">
                    <div
                      className="w-2 h-2 rounded-full"
                      style={{ backgroundColor: l.success }}
                      title="success"
                    />
                    <div
                      className="w-2 h-2 rounded-full"
                      style={{ backgroundColor: l.warning }}
                      title="warning"
                    />
                    <div
                      className="w-2 h-2 rounded-full"
                      style={{ backgroundColor: l.danger }}
                      title="danger"
                    />
                    <div
                      className="w-2 h-2 rounded-full"
                      style={{ backgroundColor: l.info }}
                      title="info"
                    />
                  </div>
                </div>

                {/* Dark mode mini swatch — top-right corner */}
                <div className="absolute top-1.5 right-1.5 flex items-center gap-0.5">
                  <div
                    className="w-2.5 h-2.5 rounded-full border"
                    style={{
                      backgroundColor: d.background,
                      borderColor: l.border,
                    }}
                    title="Dark background"
                  />
                  <div
                    className="w-2.5 h-2.5 rounded-full border"
                    style={{
                      backgroundColor: d.accent,
                      borderColor: l.border,
                    }}
                    title="Dark accent"
                  />
                </div>

                {/* "Custom" tag — top-left, only in custom mode */}
                {isCustomTheme && (
                  <span
                    className="absolute top-1.5 left-1.5 px-1.5 py-0.5 rounded text-[8px] font-semibold leading-none uppercase tracking-wider"
                    style={{
                      backgroundColor: `${l.accent}22`,
                      color: l.accent,
                    }}
                  >
                    Custom
                  </span>
                )}
              </div>

              {/* ── Info strip (~40% of card) ── */}
              <div className="p-2.5 pt-2">
                <div className="flex items-center justify-between gap-2 mb-0.5">
                  <h3 className="text-xs font-semibold text-text-primary truncate">
                    {theme.name}
                  </h3>
                  {isActive && (
                    <span
                      className="shrink-0 inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium"
                      style={{
                        backgroundColor: `${l.accent}18`,
                        color: l.accent,
                      }}
                    >
                      Active
                    </span>
                  )}
                </div>

                <p className="text-[10px] text-text-muted truncate mb-2">
                  {isCustomTheme
                    ? `${theme.borderRadius}px radius${theme.fontFamily ? ` · ${theme.fontFamily}` : ""}`
                    : theme.description ?? " "}
                </p>

                {isAdmin && (
                  <div className="flex items-center gap-1 mt-1">
                    <button
                      onClick={() => onSelect(theme.id)}
                      disabled={isActive}
                      className={`flex-1 rounded-md px-2 py-1 text-[11px] font-medium transition-colors ${
                        isActive ? "cursor-default" : "hover:opacity-90"
                      }`}
                      style={{
                        backgroundColor: isActive ? l.border : l.accent,
                        color: isActive ? l.foregroundMuted : "#ffffff",
                      }}
                    >
                      {isActive ? "Applied" : "Apply"}
                    </button>

                    {onEdit && (
                      <button
                        onClick={() => onEdit(theme)}
                        title={isCustomTheme ? "Edit" : "Duplicate & edit"}
                        className="shrink-0 p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover transition-colors"
                      >
                        <Pencil size={13} />
                      </button>
                    )}
                    {onDelete && (
                      <button
                        onClick={() => setDeleteId(theme.id)}
                        title="Delete"
                        className="shrink-0 p-1.5 rounded-md text-text-muted hover:text-danger hover:bg-danger/5 transition-colors"
                      >
                        <Trash2 size={13} />
                      </button>
                    )}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {onDelete && (
        <ConfirmDialog
          open={deleteId !== null}
          title="Delete Custom Theme"
          message="Are you sure you want to delete this theme? This cannot be undone."
          confirmLabel="Delete"
          variant="danger"
          onConfirm={() => {
            if (deleteId) onDelete(deleteId);
            setDeleteId(null);
          }}
          onCancel={() => setDeleteId(null)}
        />
      )}
    </>
  );
}

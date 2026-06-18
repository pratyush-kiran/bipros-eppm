"use client";

import { useState, useCallback, useRef } from "react";
import { Plus, ArrowLeft, Sparkles, Layers } from "lucide-react";
import { useThemeManager } from "@/hooks/useThemeManager";
import { ThemeGallery } from "@/components/theme/ThemeGallery";
import { ThemeBuilder } from "@/components/theme/ThemeBuilder";
import { PREDEFINED_THEMES, type ThemeDefinition } from "@/lib/themes/definitions";

export function ThemesTab() {
  const {
    activeThemeId,
    activeTheme,
    customThemes,
    isAdmin,
    isLoading,
    switchTheme,
    saveCustomTheme,
    deleteCustomTheme,
    previewTheme,
    cancelPreview,
  } = useThemeManager();

  const [view, setView] = useState<"gallery" | "builder">("gallery");
  const [editingTheme, setEditingTheme] = useState<ThemeDefinition | undefined>(undefined);

  // Capture snapshot of active theme when opening builder so cancel restores exactly that
  const snapshotRef = useRef<ThemeDefinition | null>(null);

  const openBuilder = useCallback(
    (theme?: ThemeDefinition) => {
      snapshotRef.current = activeTheme;
      if (!theme) {
        // Seed a new custom theme from the currently active palette so the
        // builder starts from a familiar baseline instead of an empty default.
        setEditingTheme({
          ...activeTheme,
          id: "",
          name: "",
          isCustom: true,
          createdAt: undefined,
        });
      } else {
        setEditingTheme(theme);
      }
      setView("builder");
    },
    [activeTheme]
  );

  // Predefined themes are read-only code constants, so "editing" one really means
  // cloning it into a new custom theme. Everything is pre-filled from the source
  // theme (colors, fonts, app names); the name is seeded as "<Name> Copy" so it is
  // populated and editable, and the blank id makes it save as a new custom theme.
  const duplicateAndEditTheme = useCallback(
    (theme: ThemeDefinition) => {
      snapshotRef.current = activeTheme;
      setEditingTheme({
        ...theme,
        id: "",
        name: `${theme.name} Copy`,
        isCustom: true,
        createdAt: undefined,
      });
      setView("builder");
    },
    [activeTheme]
  );

  const handleCancelBuilder = useCallback(() => {
    setView("gallery");
    setEditingTheme(undefined);
    if (snapshotRef.current) {
      previewTheme(snapshotRef.current);
    } else {
      cancelPreview();
    }
  }, [previewTheme, cancelPreview]);

  const handleSaveBuilder = useCallback(
    (theme: ThemeDefinition) => {
      saveCustomTheme(theme);
      // Also make the saved theme active so the user sees their creation immediately.
      switchTheme(theme.id);
      setView("gallery");
      setEditingTheme(undefined);
    },
    [saveCustomTheme, switchTheme]
  );

  if (isLoading && view === "gallery") {
    return <div className="text-center text-text-muted py-12">Loading themes...</div>;
  }

  const atCap = customThemes.length >= 20;
  const capWarning = customThemes.length >= 16;

  return (
    <div className="space-y-8">
      {view === "gallery" ? (
        <>
          {/* ── My Themes (custom, on top) ─────────────────────── */}
          <section className="space-y-3">
            <div className="flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-2">
                <Sparkles size={16} className="text-accent" />
                <h2 className="text-lg font-semibold text-text-primary">My Themes</h2>
                <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-surface-hover text-text-muted">
                  {customThemes.length}/20
                </span>
              </div>
              {isAdmin && (
                <button
                  onClick={() => openBuilder()}
                  disabled={atCap}
                  className="flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border disabled:text-text-muted disabled:cursor-not-allowed"
                >
                  <Plus size={16} />
                  Create Custom Theme
                </button>
              )}
            </div>

            {capWarning && (
              <div
                className={`text-xs px-3 py-2 rounded-lg border ${
                  atCap
                    ? "border-warning/30 bg-warning/5 text-warning"
                    : "border-border bg-surface-hover text-text-secondary"
                }`}
              >
                {atCap
                  ? "Maximum of 20 custom themes reached. Delete one to create more."
                  : "Approaching limit (20 custom themes max)."}
              </div>
            )}

            {customThemes.length === 0 ? (
              <div className="rounded-xl border border-dashed border-border bg-surface/30 px-6 py-10 text-center">
                <div className="mx-auto mb-3 inline-flex h-10 w-10 items-center justify-center rounded-full bg-accent/10">
                  <Sparkles size={18} className="text-accent" />
                </div>
                <p className="text-sm font-medium text-text-primary">No custom themes yet</p>
                <p className="mt-1 text-xs text-text-muted">
                  Build a theme tailored to your brand — start from any predefined palette below.
                </p>
                {isAdmin && (
                  <button
                    onClick={() => openBuilder()}
                    className="mt-4 inline-flex items-center gap-2 rounded-md border border-border bg-surface px-3 py-1.5 text-xs font-medium text-text-primary hover:border-accent/40 hover:bg-surface-hover"
                  >
                    <Plus size={14} />
                    Create your first theme
                  </button>
                )}
              </div>
            ) : (
              <ThemeGallery
                themes={customThemes}
                activeThemeId={activeThemeId}
                onSelect={switchTheme}
                isAdmin={isAdmin}
                onEdit={openBuilder}
                onDelete={deleteCustomTheme}
              />
            )}
          </section>

          {/* ── Predefined Themes (below) ──────────────────────── */}
          <section className="space-y-3 border-t border-border pt-6">
            <div className="flex items-center gap-2">
              <Layers size={16} className="text-text-secondary" />
              <h2 className="text-lg font-semibold text-text-primary">Predefined Themes</h2>
              <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-surface-hover text-text-muted">
                {PREDEFINED_THEMES.length}
              </span>
            </div>

            <ThemeGallery
              themes={PREDEFINED_THEMES}
              activeThemeId={activeThemeId}
              onSelect={switchTheme}
              isAdmin={isAdmin}
              onEdit={duplicateAndEditTheme}
            />
          </section>
        </>
      ) : (
        <div className="space-y-4">
          <button
            onClick={handleCancelBuilder}
            className="flex items-center gap-2 text-sm text-text-secondary hover:text-text-primary"
          >
            <ArrowLeft size={16} />
            Back to Gallery
          </button>

          <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
            <h3 className="mb-4 text-lg font-semibold text-text-primary">
              {editingTheme?.id ? "Edit Custom Theme" : "Create Custom Theme"}
            </h3>
            <ThemeBuilder
              initialTheme={editingTheme}
              onSave={handleSaveBuilder}
              onCancel={handleCancelBuilder}
              onPreview={previewTheme}
            />
          </div>
        </div>
      )}
    </div>
  );
}

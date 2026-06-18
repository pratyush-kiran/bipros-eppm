"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { HexColorPicker } from "react-colorful";
import { Shuffle, ImageIcon, X } from "lucide-react";
import type { ThemeDefinition, PaletteValues } from "@/lib/themes/definitions";
import { ThemePreview } from "./ThemePreview";

interface ThemeBuilderProps {
  initialTheme?: ThemeDefinition;
  onSave: (theme: ThemeDefinition) => void;
  onCancel: () => void;
  onPreview?: (theme: ThemeDefinition) => void;
}

type Mode = "light" | "dark";

type ColorKey = keyof PaletteValues;

const COLOR_SECTIONS: { label: string; keys: ColorKey[] }[] = [
  { label: "Backgrounds", keys: ["background", "backgroundSubtle", "backgroundMuted"] },
  { label: "Text", keys: ["foreground", "foregroundSecondary", "foregroundMuted"] },
  { label: "Accent", keys: ["accent", "accentHover", "accentSubtle", "accentTint"] },
  { label: "Borders", keys: ["border", "borderSubtle"] },
  { label: "Status", keys: ["success", "warning", "danger", "info"] },
  { label: "Amber Flame", keys: ["amberFlame"] },
  { label: "Logo Text Colors", keys: ["logoPrimary", "logoSecondary"] },
];

const FONT_OPTIONS = ["", "Fraunces", "Inter", "JetBrains Mono"];
const BORDER_OPTIONS: Array<4 | 6 | 8 | 12> = [4, 6, 8, 12];

const ADJECTIVES = [
  "Deep", "Soft", "Electric", "Muted", "Bright", "Dark", "Pastel", "Neon",
  "Royal", "Rustic", "Urban", "Tropical", "Arctic", "Cosmic", "Vintage", "Modern",
  "Dreamy", "Crisp", "Warm", "Cool",
];

// ── HSL → Hex utilities ──

function hslToHex(h: number, s: number, l: number): string {
  const sat = s / 100;
  const light = l / 100;
  const c = (1 - Math.abs(2 * light - 1)) * sat;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = light - c / 2;

  let r = 0;
  let g = 0;
  let b = 0;

  if (h >= 0 && h < 60) {
    r = c; g = x; b = 0;
  } else if (h >= 60 && h < 120) {
    r = x; g = c; b = 0;
  } else if (h >= 120 && h < 180) {
    r = 0; g = c; b = x;
  } else if (h >= 180 && h < 240) {
    r = 0; g = x; b = c;
  } else if (h >= 240 && h < 300) {
    r = x; g = 0; b = c;
  } else {
    r = c; g = 0; b = x;
  }

  const toHex = (n: number) =>
    Math.round((n + m) * 255)
      .toString(16)
      .padStart(2, "0");

  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

function hexToRgb(hex: string): { r: number; g: number; b: number } {
  const sanitized = hex.replace("#", "");
  const bigint = parseInt(sanitized, 16);
  return {
    r: (bigint >> 16) & 255,
    g: (bigint >> 8) & 255,
    b: bigint & 255,
  };
}

function relativeLuminance(hex: string): number {
  const { r, g, b } = hexToRgb(hex);
  const [rs, gs, bs] = [r, g, b].map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs;
}

function contrastRatio(hexA: string, hexB: string): number {
  const l1 = relativeLuminance(hexA);
  const l2 = relativeLuminance(hexB);
  const lighter = Math.max(l1, l2);
  const darker = Math.min(l1, l2);
  return (lighter + 0.05) / (darker + 0.05);
}

// ── Intelligent palette generation ──

function randomInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function clamp(n: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, n));
}

function pickHueBand(): number {
  const bands = [
    [0, 30],    // reds/oranges
    [30, 60],   // golds/ambers
    [60, 120],  // greens
    [120, 180], // teals
    [180, 240], // blues
    [240, 300], // purples
    [300, 360], // pinks
  ];
  const band = bands[randomInt(0, bands.length - 1)];
  return randomInt(band[0], band[1]);
}

function colorNameFromHue(hue: number): string {
  if (hue < 30) return ["Crimson", "Coral", "Rose"][randomInt(0, 2)];
  if (hue < 60) return ["Amber", "Saffron", "Honey"][randomInt(0, 2)];
  if (hue < 120) return ["Sage", "Fern", "Jade"][randomInt(0, 2)];
  if (hue < 180) return ["Teal", "Seafoam", "Aqua"][randomInt(0, 2)];
  if (hue < 240) return ["Cobalt", "Sky", "Indigo"][randomInt(0, 2)];
  if (hue < 300) return ["Violet", "Plum", "Mauve"][randomInt(0, 2)];
  return ["Blush", "Magenta", "Ruby"][randomInt(0, 2)];
}

function deriveThemePalettes(baseHue: number): { light: PaletteValues; dark: PaletteValues } {
  const h = baseHue;

  // --- Light mode ---
  const bgSat = randomInt(0, 5);
  const bgL = randomInt(97, 100);
  const background = hslToHex(h, bgSat, bgL);
  const backgroundSubtle = hslToHex(h, bgSat, randomInt(93, 96));
  const backgroundMuted = hslToHex(h, bgSat, randomInt(88, 92));

  let fgL = randomInt(8, 15);
  let foreground = hslToHex(h, randomInt(0, 10), fgL);
  // Ensure contrast
  for (let i = 0; i < 10 && contrastRatio(foreground, background) < 4.5; i++) {
    fgL = clamp(fgL - 3, 0, 20);
    foreground = hslToHex(h, randomInt(0, 10), fgL);
  }

  const foregroundSecondary = hslToHex(h, randomInt(0, 10), randomInt(35, 45));
  const foregroundMuted = hslToHex(h, randomInt(0, 10), randomInt(55, 65));

  const accentSat = randomInt(70, 90);
  const accentL = randomInt(45, 60);
  const accent = hslToHex(h, accentSat, accentL);
  const accentHover = hslToHex(h, accentSat, clamp(accentL - 10, 30, 50));
  const accentSubtle = hslToHex(h, clamp(accentSat - 30, 30, 60), clamp(accentL - 15, 20, 40));
  const accentTint = hslToHex(h, randomInt(40, 60), randomInt(85, 95));

  const border = hslToHex(h, bgSat + randomInt(0, 3), randomInt(82, 88));
  const borderSubtle = hslToHex(h, bgSat + randomInt(0, 3), randomInt(85, 90));

  const success = hslToHex(randomInt(145, 155), randomInt(55, 65), randomInt(30, 40));
  const warning = hslToHex(randomInt(30, 40), randomInt(70, 80), randomInt(40, 50));
  const danger = hslToHex(randomInt(355, 360) % 360 || 0, randomInt(60, 70), randomInt(30, 40));
  const info = hslToHex(randomInt(210, 220), randomInt(30, 50), randomInt(35, 45));
  const amberFlame = hslToHex(randomInt(22, 32), randomInt(75, 85), randomInt(45, 55));

  const light: PaletteValues = {
    background,
    backgroundSubtle,
    backgroundMuted,
    foreground,
    foregroundSecondary,
    foregroundMuted,
    accent,
    accentHover,
    accentSubtle,
    accentTint,
    border,
    borderSubtle,
    success,
    warning,
    danger,
    info,
    amberFlame,
    logoPrimary: foreground,
    logoSecondary: accent,
  };

  // --- Dark mode (derived deterministically) ---
  const dBgSat = clamp(bgSat + randomInt(5, 10), 5, 15);
  const dBackground = hslToHex(h, dBgSat, randomInt(7, 12));
  const dBackgroundSubtle = hslToHex(h, dBgSat, randomInt(12, 16));
  const dBackgroundMuted = hslToHex(h, dBgSat, randomInt(16, 22));

  let dFgL = randomInt(90, 96);
  let dForeground = hslToHex(h, randomInt(0, 10), dFgL);
  for (let i = 0; i < 10 && contrastRatio(dForeground, dBackground) < 4.5; i++) {
    dFgL = clamp(dFgL + 3, 85, 100);
    dForeground = hslToHex(h, randomInt(0, 10), dFgL);
  }

  const dAccentSat = clamp(accentSat + 5, 60, 95);
  const dAccentL = clamp(accentL + 10, 50, 75);

  const dark: PaletteValues = {
    background: dBackground,
    backgroundSubtle: dBackgroundSubtle,
    backgroundMuted: dBackgroundMuted,
    foreground: dForeground,
    foregroundSecondary: hslToHex(h, randomInt(0, 10), randomInt(65, 75)),
    foregroundMuted: hslToHex(h, randomInt(0, 10), randomInt(45, 55)),
    accent: hslToHex(h, dAccentSat, dAccentL),
    accentHover: hslToHex(h, dAccentSat, clamp(dAccentL - 8, 45, 70)),
    accentSubtle: hslToHex(h, clamp(dAccentSat - 20, 40, 70), clamp(dAccentL - 12, 35, 60)),
    accentTint: hslToHex(h, randomInt(40, 60), randomInt(20, 35)),
    border: hslToHex(h, dBgSat, randomInt(22, 28)),
    borderSubtle: hslToHex(h, dBgSat, randomInt(25, 30)),
    success: hslToHex(150, randomInt(60, 70), randomInt(45, 55)),
    warning: hslToHex(35, randomInt(75, 85), randomInt(55, 65)),
    danger: hslToHex(0, randomInt(65, 75), randomInt(50, 60)),
    info: hslToHex(215, randomInt(40, 55), randomInt(55, 65)),
    amberFlame: hslToHex(27, randomInt(80, 90), randomInt(60, 70)),
    logoPrimary: dForeground,
    logoSecondary: hslToHex(h, dAccentSat, dAccentL),
  };

  return { light, dark };
}

function generateIntelligentTheme(): {
  light: PaletteValues;
  dark: PaletteValues;
  name: string;
} {
  const hue = pickHueBand();
  const { light, dark } = deriveThemePalettes(hue);
  const name = `${ADJECTIVES[randomInt(0, ADJECTIVES.length - 1)]} ${colorNameFromHue(hue)}`;
  return { light, dark, name };
}

// ── Legacy / seed utilities ──

function emptyPalette(): PaletteValues {
  return {
    background: "#FFFFFF",
    backgroundSubtle: "#FAFAFA",
    backgroundMuted: "#F5F5F5",
    foreground: "#1C1C1C",
    foregroundSecondary: "#6B7280",
    foregroundMuted: "#9CA3AF",
    accent: "#D4AF37",
    accentHover: "#B8962E",
    accentSubtle: "#8C6F1E",
    accentTint: "#F5E7B5",
    border: "#EDE7D3",
    borderSubtle: "#E5E7EB",
    success: "#2E7D5B",
    warning: "#C7882E",
    danger: "#9B2C2C",
    info: "#475569",
    amberFlame: "#E07A1F",
    logoPrimary: "#1C1C1C",
    logoSecondary: "#B8962E",
  };
}

function cloneTheme(initial?: ThemeDefinition): ThemeDefinition {
  if (initial) {
    return {
      ...initial,
      light: { ...initial.light },
      dark: { ...initial.dark },
    };
  }
  return {
    id: "",
    name: "",
    light: emptyPalette(),
    dark: emptyPalette(),
    borderRadius: 6,
    fontFamily: undefined,
    isCustom: true,
  };
}

export function ThemeBuilder({ initialTheme, onSave, onCancel, onPreview }: ThemeBuilderProps) {
  const [draft, setDraft] = useState<ThemeDefinition>(() => cloneTheme(initialTheme));
  const [mode, setMode] = useState<Mode>("light");
  const [openPicker, setOpenPicker] = useState<ColorKey | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [logoError, setLogoError] = useState<string | null>(null);

  const lightLogoInputRef = useRef<HTMLInputElement>(null);
  const darkLogoInputRef = useRef<HTMLInputElement>(null);
  const previewTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hasPreviewedRef = useRef(false);

  const triggerPreview = useCallback(
    (theme: ThemeDefinition) => {
      if (previewTimeoutRef.current) clearTimeout(previewTimeoutRef.current);
      previewTimeoutRef.current = setTimeout(() => {
        onPreview?.(theme);
      }, 150);
    },
    [onPreview]
  );

  useEffect(() => {
    if (!hasPreviewedRef.current) {
      hasPreviewedRef.current = true;
      return;
    }
    triggerPreview(draft);
    return () => {
      if (previewTimeoutRef.current) clearTimeout(previewTimeoutRef.current);
    };
  }, [draft, triggerPreview]);

  function updateColor(key: ColorKey, value: string) {
    setDraft((prev) => ({
      ...prev,
      [mode]: { ...prev[mode], [key]: value },
    }));
  }

  function handleRandomize() {
    const generated = generateIntelligentTheme();
    setDraft((prev) => ({
      ...prev,
      name: prev.name || generated.name,
      light: generated.light,
      dark: generated.dark,
      borderRadius: BORDER_OPTIONS[randomInt(0, BORDER_OPTIONS.length - 1)],
      fontFamily: FONT_OPTIONS[randomInt(0, FONT_OPTIONS.length - 1)] || undefined,
    }));
  }

  function handleLogoUpload(slot: "logoLight" | "logoDark", file: File) {
    setLogoError(null);
    if (file.size > 1024 * 1024) {
      setLogoError(`Logo too large (${(file.size / 1024).toFixed(0)} KB). Max 1 MB.`);
      return;
    }
    const reader = new FileReader();
    reader.onload = (e) => {
      const dataUrl = e.target?.result as string;
      setDraft((prev) => ({ ...prev, [slot]: dataUrl }));
    };
    reader.readAsDataURL(file);
  }

  function clearLogo(slot: "logoLight" | "logoDark") {
    setLogoError(null);
    setDraft((prev) => ({ ...prev, [slot]: undefined }));
  }

  function handleSave() {
    if (!draft.name.trim()) {
      setError("Theme name is required");
      return;
    }
    setError(null);
    const final: ThemeDefinition = {
      ...draft,
      id: draft.id || crypto.randomUUID(),
      isCustom: true,
      createdAt: draft.createdAt || new Date().toISOString(),
    };
    onSave(final);
  }

  const palette = draft[mode];

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Left: Controls */}
      <div className="space-y-5">
        {/* Name + Meta */}
        <div className="space-y-3">
          <div className="flex gap-3">
            <div className="flex-1">
              <label className="block text-sm font-medium text-text-secondary">Theme Name *</label>
              <input
                type="text"
                value={draft.name}
                onChange={(e) => setDraft((prev) => ({ ...prev, name: e.target.value }))}
                placeholder="e.g., My Custom Theme"
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
              />
            </div>
            <div className="pt-5">
              <button
                onClick={handleRandomize}
                title="Generate random theme"
                className="flex items-center gap-1.5 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-secondary hover:bg-surface-active hover:text-text-primary transition-colors"
              >
                <Shuffle size={14} />
                Random
              </button>
            </div>
          </div>

          <div className="flex gap-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Border Radius</label>
              <div className="mt-1 flex rounded-md border border-border overflow-hidden">
                {BORDER_OPTIONS.map((r) => (
                  <button
                    key={r}
                    onClick={() => setDraft((prev) => ({ ...prev, borderRadius: r }))}
                    className={`px-3 py-1.5 text-sm font-medium ${
                      draft.borderRadius === r
                        ? "bg-accent text-accent-foreground"
                        : "bg-surface-hover text-text-secondary hover:bg-surface-active"
                    }`}
                  >
                    {r}px
                  </button>
                ))}
              </div>
            </div>

            <div className="flex-1">
              <label className="block text-sm font-medium text-text-secondary">Font Family</label>
              <select
                value={draft.fontFamily || ""}
                onChange={(e) =>
                  setDraft((prev) => ({
                    ...prev,
                    fontFamily: e.target.value || undefined,
                  }))
                }
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
              >
                <option value="">Default (Inter)</option>
                <option value="Fraunces">Fraunces (Display)</option>
                <option value="Inter">Inter (Sans)</option>
                <option value="JetBrains Mono">JetBrains Mono (Mono)</option>
              </select>
            </div>
          </div>

          {error && <p className="text-sm text-danger">{error}</p>}
        </div>

        {/* App name */}
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wider text-text-muted mb-2">App Name</h4>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs text-text-secondary mb-1">Primary name</label>
              <input
                type="text"
                value={draft.appNamePrimary ?? ""}
                onChange={(e) => setDraft((prev) => ({ ...prev, appNamePrimary: e.target.value || undefined }))}
                placeholder="Bipros"
                className="block w-full rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
              />
            </div>
            <div>
              <label className="block text-xs text-text-secondary mb-1">Secondary name</label>
              <input
                type="text"
                value={draft.appNameSecondary ?? ""}
                onChange={(e) => setDraft((prev) => ({ ...prev, appNameSecondary: e.target.value || undefined }))}
                placeholder="EPPM"
                className="block w-full rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
              />
            </div>
          </div>
        </div>

        {/* Logo upload */}
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wider text-text-muted mb-2">Logo Images</h4>
          <div className="grid grid-cols-2 gap-3">
            {(["logoLight", "logoDark"] as const).map((slot) => {
              const label = slot === "logoLight" ? "Light Mode Logo" : "Dark Mode Logo";
              const inputRef = slot === "logoLight" ? lightLogoInputRef : darkLogoInputRef;
              const value = draft[slot];
              const otherValue = slot === "logoLight" ? draft.logoDark : draft.logoLight;
              const fallbackNote = !value && otherValue
                ? (slot === "logoLight" ? "Dark logo used as fallback" : "Light logo used as fallback")
                : null;
              return (
                <div key={slot} className="flex flex-col gap-1.5">
                  <span className="text-xs text-text-secondary">{label}</span>
                  <div className="rounded-md border border-dashed border-border bg-surface-hover p-2 flex flex-col items-center gap-2">
                    {value ? (
                      <img src={value} alt={label} className="h-10 w-10 rounded-md object-contain" />
                    ) : (
                      <div className="h-10 w-10 rounded-md flex items-center justify-center bg-surface-active">
                        <ImageIcon size={16} className="text-text-muted" />
                      </div>
                    )}
                    <div className="flex gap-1.5 w-full">
                      <button
                        type="button"
                        onClick={() => inputRef.current?.click()}
                        className="flex-1 rounded px-2 py-1 text-[11px] font-medium bg-surface-active text-text-secondary hover:text-text-primary hover:bg-border transition-colors"
                      >
                        {value ? "Change" : "Upload"}
                      </button>
                      {value && (
                        <button
                          type="button"
                          onClick={() => clearLogo(slot)}
                          className="rounded p-1 text-text-muted hover:text-danger hover:bg-surface-active transition-colors"
                          title="Remove logo"
                        >
                          <X size={12} />
                        </button>
                      )}
                    </div>
                  </div>
                  {fallbackNote && (
                    <p className="text-[10px] text-text-muted leading-tight">{fallbackNote}</p>
                  )}
                  <input
                    ref={inputRef}
                    type="file"
                    accept="image/png,image/svg+xml,image/jpeg,image/webp"
                    className="hidden"
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file) handleLogoUpload(slot, file);
                      e.target.value = "";
                    }}
                  />
                </div>
              );
            })}
          </div>
          {logoError && <p className="mt-1.5 text-xs text-danger">{logoError}</p>}
          <p className="mt-1 text-[10px] text-text-muted">PNG · SVG · JPG · WebP · max 1 MB each</p>
        </div>

        {/* Mode tabs */}
        <div className="flex rounded-md border border-border overflow-hidden">
          <button
            onClick={() => setMode("light")}
            className={`flex-1 px-3 py-1.5 text-sm font-medium ${
              mode === "light" ? "bg-accent text-accent-foreground" : "bg-surface-hover text-text-secondary"
            }`}
          >
            Light Mode
          </button>
          <button
            onClick={() => setMode("dark")}
            className={`flex-1 px-3 py-1.5 text-sm font-medium ${
              mode === "dark" ? "bg-accent text-accent-foreground" : "bg-surface-hover text-text-secondary"
            }`}
          >
            Dark Mode
          </button>
        </div>

        {/* Color sections */}
        <div className="space-y-4 max-h-[60vh] overflow-y-auto pr-1">
          {COLOR_SECTIONS.map((section) => (
            <div key={section.label}>
              <h4 className="text-xs font-semibold uppercase tracking-wider text-text-muted mb-2">
                {section.label}
              </h4>
              <div className="grid grid-cols-2 gap-3">
                {section.keys.map((key) => (
                  <div key={key}>
                    <label className="block text-xs text-text-secondary capitalize mb-1">
                      {key.replace(/([A-Z])/g, " $1").trim()}
                    </label>
                    <button
                      onClick={() => setOpenPicker(openPicker === key ? null : key)}
                      className="flex items-center gap-2 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-left"
                    >
                      <div
                        className="w-5 h-5 rounded border border-border"
                        style={{ backgroundColor: palette[key] }}
                      />
                      <span className="text-xs text-text-primary font-mono">{palette[key]}</span>
                    </button>
                    {openPicker === key && (
                      <div className="mt-2">
                        <HexColorPicker
                          color={palette[key]}
                          onChange={(v) => updateColor(key, v)}
                          style={{ width: "100%", height: "120px" }}
                        />
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>

        {/* Actions */}
        <div className="flex gap-2 pt-2">
          <button
            onClick={handleSave}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
          >
            Save Theme
          </button>
          <button
            onClick={onCancel}
            className="rounded-md border border-border px-4 py-2 text-sm text-text-secondary hover:bg-surface-hover"
          >
            Cancel
          </button>
        </div>
      </div>

      {/* Right: Preview */}
      <div className="space-y-3">
        <h4 className="text-sm font-semibold text-text-primary">Live Preview</h4>
        <ThemePreview
          palette={palette}
          mode={mode}
          logoSrc={mode === "light" ? (draft.logoLight ?? draft.logoDark) : (draft.logoDark ?? draft.logoLight)}
          appNamePrimary={draft.appNamePrimary}
          appNameSecondary={draft.appNameSecondary}
        />
      </div>
    </div>
  );
}

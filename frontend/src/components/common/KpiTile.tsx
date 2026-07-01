import React from "react";

type Tone = "default" | "success" | "warning" | "danger" | "accent";

interface KpiTileProps {
  label: string;
  value: string | number;
  hint?: string;
  tone?: Tone;
  icon?: React.ReactNode;
  delta?: { value: string; direction: "up" | "down" | "flat" };
  onClick?: () => void;
  valueTitle?: string;
}

const valueToneCls: Record<Tone, string> = {
  default: "text-charcoal",
  success: "text-emerald",
  warning: "text-amber-flame",
  danger: "text-burgundy",
  accent: "text-gold-deep",
};

const iconWrapTone: Record<Tone, string> = {
  default: "border-hairline bg-ivory/60 text-slate",
  success: "border-emerald/30 bg-emerald/10 text-emerald",
  warning: "border-amber-flame/40 bg-amber-flame/12 text-amber-flame",
  danger: "border-burgundy/35 bg-burgundy/10 text-burgundy",
  accent: "border-gold/40 bg-gold-tint/50 text-gold-deep",
};

// Tile-level tinting — keeps default tiles clean, but warning/danger/success/accent
// carry a subtle background + coloured ring so the tone is readable at a glance,
// not just in the value text colour.
const tileTone: Record<Tone, string> = {
  default: "border-hairline bg-paper",
  success: "border-emerald/35 bg-gradient-to-br from-emerald/10 via-paper to-paper",
  warning:
    "border-amber-flame/50 bg-gradient-to-br from-amber-flame/15 via-paper to-paper",
  danger:
    "border-burgundy/45 bg-gradient-to-br from-burgundy/12 via-paper to-paper",
  accent:
    "border-gold/40 bg-gradient-to-br from-gold-tint/45 via-paper to-paper",
};

const stripeTone: Record<Tone, string> = {
  default: "",
  success: "bg-emerald",
  warning: "bg-amber-flame",
  danger: "bg-burgundy",
  accent: "bg-gold-deep",
};

const hoverTone: Record<Tone, string> = {
  default: "hover:border-gold/30 hover:shadow-[0_8px_20px_-10px_rgba(212,175,55,0.25)]",
  success: "hover:shadow-[0_8px_20px_-10px_rgba(46,125,91,0.30)]",
  warning: "hover:shadow-[0_8px_20px_-10px_rgba(224,122,31,0.30)]",
  danger: "hover:shadow-[0_8px_20px_-10px_rgba(155,44,44,0.28)]",
  accent: "hover:shadow-[0_8px_20px_-10px_rgba(212,175,55,0.30)]",
};

export function KpiTile({ label, value, hint, tone = "default", icon, delta, onClick, valueTitle }: KpiTileProps) {
  const interactiveProps = onClick
    ? {
        role: "button" as const,
        tabIndex: 0,
        onClick,
        onKeyDown: (e: React.KeyboardEvent<HTMLDivElement>) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onClick();
          }
        },
      }
    : {};

  return (
    <div
      {...interactiveProps}
      className={`group relative overflow-hidden rounded-xl border p-4 shadow-[0_1px_2px_rgba(28,28,28,0.03)] transition-all duration-200 hover:-translate-y-0.5 ${tileTone[tone]} ${hoverTone[tone]}${onClick ? " cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold/50" : ""}`}
    >
      {/* Top status stripe so the tone reads even before you parse the value */}
      {tone !== "default" && (
        <div className={`pointer-events-none absolute inset-x-0 top-0 h-[3px] ${stripeTone[tone]}`} />
      )}
      {tone === "accent" && (
        <div className="pointer-events-none absolute -right-12 -top-12 h-28 w-28 rounded-full bg-gold/12 blur-2xl" />
      )}
      <div className="relative flex items-center justify-between gap-2">
        <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
          {label}
        </div>
        {icon && (
          <div
            className={`flex h-7 w-7 items-center justify-center rounded-lg border ${iconWrapTone[tone]}`}
          >
            {icon}
          </div>
        )}
      </div>
      <div className="relative mt-2 flex min-w-0 items-baseline gap-2">
        <div
          className={`min-w-0 truncate font-display text-[24px] font-semibold leading-none tracking-tight ${valueToneCls[tone]}`}
          title={valueTitle ?? String(value ?? "")}
        >
          {value}
        </div>
        {delta && (
          <span
            className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${
              delta.direction === "up"
                ? "bg-emerald/10 text-emerald"
                : delta.direction === "down"
                  ? "bg-burgundy/10 text-burgundy"
                  : "bg-ivory text-slate"
            }`}
          >
            {delta.direction === "up" ? "▲" : delta.direction === "down" ? "▼" : "•"}
            {delta.value}
          </span>
        )}
      </div>
      {hint && <div className="relative mt-1.5 text-[11px] text-slate">{hint}</div>}
    </div>
  );
}

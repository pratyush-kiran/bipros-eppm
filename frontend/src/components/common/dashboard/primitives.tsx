import React from "react";

export function SectionCard({
  title,
  subtitle,
  actions,
  icon,
  accent,
  badge,
  children,
}: {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  icon?: React.ReactNode;
  accent?: boolean;
  badge?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section
      className={`group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)] transition-all duration-300 hover:border-gold/30 hover:shadow-[0_2px_4px_rgba(28,28,28,0.04),0_16px_40px_-16px_rgba(212,175,55,0.18)] ${
        accent ? "before:absolute before:inset-y-6 before:left-0 before:w-[3px] before:rounded-r-full before:bg-gradient-to-b before:from-gold before:to-gold-deep" : ""
      }`}
    >
      <div className="mb-5 flex items-start justify-between gap-4">
        <div className="flex items-start gap-3">
          {icon && (
            <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-hairline bg-gradient-to-br from-ivory to-paper text-gold-deep shadow-sm">
              {icon}
            </div>
          )}
          <div>
            <div className="flex items-center gap-2">
              <h2 className="font-display text-lg font-semibold leading-tight tracking-tight text-charcoal">
                {title}
              </h2>
              {badge}
            </div>
            {subtitle && (
              <p className="mt-1 text-sm leading-relaxed text-slate">{subtitle}</p>
            )}
          </div>
        </div>
        {actions && <div className="shrink-0">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

export function LoadingBlock({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="flex items-center justify-center rounded-xl border border-dashed border-hairline bg-ivory/40 p-8 text-sm text-slate">
      <div className="flex items-center gap-2">
        <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-gold" />
        {label}
      </div>
    </div>
  );
}

export function EmptyBlock({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center rounded-xl border border-dashed border-hairline bg-ivory/40 p-8 text-sm text-slate">
      {label}
    </div>
  );
}

export const CHART_TOOLTIP_STYLE = {
  backgroundColor: "#1C1C1C",
  border: "1px solid #2A2520",
  borderRadius: "0.625rem",
  color: "#F5F2E8",
  boxShadow: "0 12px 32px -8px rgba(28,28,28,0.25)",
  padding: "10px 12px",
  fontSize: "12px",
};

export const CHART_COLORS = {
  pv: "#3b82f6",
  ev: "#2E7D5B",
  ac: "#9B2C2C",
  planned: "#3b82f6",
  actual: "#2E7D5B",
  forecast: "#D4AF37",
  warning: "#C7882E",
  danger: "#9B2C2C",
  committed: "#a855f7",
  good: "#2E7D5B",
  amber: "#C7882E",
  red: "#9B2C2C",
  muted: "#9CA3AF",
  gold: "#D4AF37",
  goldDeep: "#B8962E",
};

/** Navy / orange palette used by the Project Command dashboards. Recharts
 *  takes stroke/fill as props rather than CSS, so these hex values are kept
 *  in sync with the .theme-command block in globals.css. */
export const CHART_COLORS_COMMAND = {
  primary: "#FF6B35",
  success: "#10B981",
  warning: "#F59E0B",
  danger: "#EF4444",
  info: "#3B82F6",
  violet: "#8B5CF6",
  cyan: "#06B6D4",
  rose: "#F43F5E",
  muted: "#94A3B8",
  grid: "rgba(148, 163, 184, 0.12)",
  surface: "#0C1929",
  surfaceDeep: "#070D1A",
  textPrimary: "#F1F5F9",
  textSecondary: "#94A3B8",
  // common chart slots
  planned: "#3B82F6",
  actual: "#FF6B35",
  forecast: "#F59E0B",
  pv: "#3B82F6",
  ev: "#10B981",
  ac: "#EF4444",
};

export const CHART_TOOLTIP_STYLE_COMMAND = {
  backgroundColor: "#0C1929",
  border: "1px solid rgba(148, 163, 184, 0.18)",
  borderRadius: "0.625rem",
  color: "#F1F5F9",
  boxShadow: "0 12px 32px -8px rgba(0,0,0,0.45)",
  padding: "10px 12px",
  fontSize: "12px",
};

export function formatCrore(n: number | null | undefined, digits = 2): string {
  if (n == null || Number.isNaN(n)) return "—";
  return `₹ ${n.toLocaleString("en-IN", { maximumFractionDigits: digits })} Cr`;
}

export function formatPct(n: number | null | undefined, digits = 1): string {
  if (n == null || Number.isNaN(n)) return "—";
  return `${n.toFixed(digits)}%`;
}

export function truncate(s: string | null | undefined, n: number): string {
  if (!s) return "";
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

import React from "react";
import Link from "next/link";
import { cn } from "@/lib/utils/cn";

export type KpiAccent =
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "violet"
  | "cyan"
  | "primary"
  | "neutral";

export type KpiRail = "left" | "top" | "none";

export interface KpiCardProps {
  label: string;
  value: React.ReactNode;
  unit?: React.ReactNode;
  delta?: React.ReactNode;
  accent?: KpiAccent;
  rail?: KpiRail;
  className?: string;
  children?: React.ReactNode;
  /** When set, the whole card becomes a link to this URL. */
  href?: string;
}

const railClass: Record<KpiAccent, string> = {
  success: "bg-emerald",
  warning: "bg-bronze-warn",
  danger: "bg-burgundy",
  info: "bg-steel",
  violet: "bg-[var(--cmd-violet)]",
  cyan: "bg-[var(--cmd-cyan)]",
  primary: "bg-gold",
  neutral: "bg-slate/40",
};

export function KpiCard({
  label,
  value,
  unit,
  delta,
  accent = "primary",
  rail = "left",
  className,
  children,
  href,
}: KpiCardProps) {
  const Wrapper = href ? Link : "div";
  const wrapperProps = href ? { href } : {};
  return (
    <Wrapper
      {...(wrapperProps as { href: string })}
      className={cn(
        "relative block overflow-hidden rounded-xl border border-hairline bg-ivory p-5 transition-all duration-200",
        "hover:-translate-y-0.5 hover:shadow-[0_4px_20px_rgba(0,0,0,0.25)]",
        href && "cursor-pointer hover:border-gold/50",
        className
      )}
    >
      <span
        className={cn(
          "absolute",
          rail === "left" && "left-0 top-0 h-full w-[3px]",
          rail === "top" && "left-0 top-0 h-[3px] w-full",
          rail === "none" && "hidden",
          railClass[accent]
        )}
        aria-hidden
      />
      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
        {label}
      </div>
      <div className="mt-3 flex items-baseline gap-1.5">
        <span className="font-display text-[32px] font-semibold leading-none tracking-tight text-text-primary tabular-nums">
          {value}
        </span>
        {unit && <span className="text-sm text-text-secondary">{unit}</span>}
      </div>
      {(delta || children) && (
        <div className="mt-4 flex items-center gap-2">
          {delta}
          {children}
        </div>
      )}
    </Wrapper>
  );
}

"use client";

import Link from "next/link";
import { type LucideIcon, ArrowUpRight } from "lucide-react";
import { cn } from "@/lib/utils/cn";

interface MetricTileProps {
  title: string;
  icon: LucideIcon;
  href?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  tone?: "default" | "danger" | "warn" | "ok";
  className?: string;
  testid?: string;
}

const toneRing: Record<NonNullable<MetricTileProps["tone"]>, string> = {
  default: "",
  danger: "before:bg-burgundy",
  warn: "before:bg-bronze-warn",
  ok: "before:bg-emerald",
};

export function MetricTile({
  title,
  icon: Icon,
  href,
  children,
  footer,
  tone = "default",
  className,
  testid,
}: MetricTileProps) {
  const body = (
    <div
      className={cn(
        "group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-5",
        "shadow-[0_1px_2px_rgba(28,28,28,0.04)]",
        "before:absolute before:left-0 before:top-0 before:h-full before:w-[3px] before:opacity-0",
        toneRing[tone],
        tone !== "default" && "before:opacity-100",
        href &&
          "transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_18px_42px_-18px_rgba(212,175,55,0.28)] cursor-pointer",
        className,
      )}
      data-testid={testid}
    >
      <div className="pointer-events-none absolute -right-12 -top-12 h-28 w-28 rounded-full bg-gold/8 opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-100" />

      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg border border-hairline bg-ivory/50 text-gold-deep">
            <Icon size={14} strokeWidth={1.75} />
          </span>
          <span className="text-[10.5px] font-semibold uppercase tracking-[0.14em] text-ash">
            {title}
          </span>
        </div>
        {href && (
          <span
            aria-hidden
            className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-hairline bg-paper text-gold-deep opacity-0 transition-opacity duration-200 group-hover:opacity-100"
          >
            <ArrowUpRight size={12} strokeWidth={1.75} />
          </span>
        )}
      </div>

      <div className="mt-4">{children}</div>

      {footer && (
        <div className="mt-4 border-t border-hairline/60 pt-3 text-[11.5px] text-slate">
          {footer}
        </div>
      )}
    </div>
  );

  if (href) {
    return (
      <Link href={href} className="block" data-tile-href={href}>
        {body}
      </Link>
    );
  }
  return body;
}

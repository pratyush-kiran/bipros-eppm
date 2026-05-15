"use client";

import Link from "next/link";

/**
 * Cinematic sign-in top bar. Brand mark on the left, live site count + UTC
 * timestamp on the right. The timestamp is a static label, not a clock —
 * sign-in should not jitter the layout while you type a password.
 */
export function CinematicHeader() {
  return (
    <header className="relative z-30 flex h-18 items-center justify-between border-b border-white/[0.06] px-6 py-5 lg:px-12">
      <Link href="/" className="flex items-center gap-3">
        <span
          aria-hidden
          className="grid h-8 w-8 place-items-center rounded-md text-[13px] font-bold"
          style={{
            background: "linear-gradient(180deg,#F4B36A,#C97A3A)",
            color: "#0B1224",
          }}
        >
          B
        </span>
        <div className="leading-tight">
          <div className="text-[15px] font-semibold tracking-[-0.01em]">Bipros</div>
          <div className="text-[10.5px] uppercase tracking-[0.20em] text-white/55">
            Site of Record
          </div>
        </div>
      </Link>
      <div className="hidden items-center gap-3 text-[11px] uppercase tracking-[0.18em] text-white/55 sm:flex">
        <span className="flex items-center gap-1.5">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-400" />
          47 sites live
        </span>
        <span className="hidden font-mono text-white/35 md:inline">·</span>
        <span className="hidden md:inline">18:42 UTC</span>
      </div>
    </header>
  );
}

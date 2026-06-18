"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { brandingApi } from "@/lib/api/brandingApi";

/**
 * Cinematic sign-in top bar. Brand mark on the left, live site count + UTC
 * timestamp on the right. The brand mark is the globally-active theme's logo
 * (fetched from the public branding endpoint). The login canvas is always dark,
 * so we always prefer the dark-mode logo; if none is set we fall back to the
 * default "B" badge + name. The timestamp is a static label, not a clock.
 */
export function CinematicHeader() {
  const { data } = useQuery({
    queryKey: ["public-branding"],
    queryFn: () => brandingApi.getBranding(),
    staleTime: 1000 * 60 * 5,
    retry: 1,
  });

  const branding = data?.data;
  const logo = branding?.logoDark ?? branding?.logoLight ?? null;
  const primary = branding?.appNamePrimary || "Bipros";

  return (
    <header className="relative z-30 flex h-18 items-center justify-between border-b border-white/[0.06] px-6 py-5 lg:px-12">
      <Link href="/" className="flex items-center gap-3">
        {logo ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={logo}
            alt={primary}
            className="h-8 w-auto max-w-[180px] object-contain"
          />
        ) : (
          <span
            aria-hidden
            className="grid h-8 w-8 place-items-center rounded-md text-[13px] font-bold"
            style={{
              background: "linear-gradient(180deg,#B0C6FF,#5E8BE0)",
              color: "#0B1224",
            }}
          >
            B
          </span>
        )}
        <div className="leading-tight">
          {!logo && (
            <div className="text-[15px] font-semibold tracking-[-0.01em]">{primary}</div>
          )}
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

"use client";

import Link from "next/link";
import { useActiveLogo, useAppName } from "@/hooks/useThemeManager";

export function Brand() {
  const logoSrc = useActiveLogo();
  const appName = useAppName();

  return (
    <Link
      href="/"
      aria-label={`${appName.primary} home`}
      className="group flex shrink-0 items-center gap-2.5 rounded-lg px-1 py-1 outline-none focus-visible:ring-2 focus-visible:ring-gold/40"
    >
      <img
        src={logoSrc}
        alt={appName.primary}
        width={28}
        height={28}
        className="h-7 w-7 rounded-md object-contain"
      />
      <div className="hidden flex-col leading-none sm:flex">
        <span className="font-display text-[15px] font-semibold tracking-tight text-logo-primary transition-colors group-hover:text-gold-deep">
          {appName.primary}
        </span>
        <span className="mt-0.5 text-[8px] font-semibold uppercase tracking-[0.2em] text-logo-secondary">
          {appName.secondary}
        </span>
      </div>
    </Link>
  );
}

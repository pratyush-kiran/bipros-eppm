"use client";

import Link from "next/link";
import { useActiveLogo, useAppName } from "@/hooks/useThemeManager";
import { withBasePath } from "@/lib/basePath";

export function Brand() {
  const logoSrc = useActiveLogo();
  const appName = useAppName();

  // A custom uploaded logo is a base64 data URL (often a wide wordmark), so it is
  // rendered with its natural aspect ratio (w-auto) instead of being squashed into
  // a square — and data: URLs must skip withBasePath. The built-in fallback logo
  // keeps its compact square mark. The app-name text shows in both cases.
  const isUploaded = logoSrc.startsWith("data:");

  return (
    <Link
      href="/"
      aria-label={`${appName.primary} home`}
      className="group flex shrink-0 items-center gap-2.5 rounded-lg px-1 py-1 outline-none focus-visible:ring-2 focus-visible:ring-gold/40"
    >
      {isUploaded ? (
        <img
          src={logoSrc}
          alt={appName.primary}
          className="h-7 w-auto max-w-[120px] object-contain"
        />
      ) : (
        <img
          src={withBasePath(logoSrc)}
          alt={appName.primary}
          width={28}
          height={28}
          className="h-7 w-7 rounded-md object-contain"
        />
      )}
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

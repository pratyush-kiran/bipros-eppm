"use client";

import Link from "next/link";
import { useActiveLogo, useAppName } from "@/hooks/useThemeManager";
import { withBasePath } from "@/lib/basePath";

export function Brand() {
  const logoSrc = useActiveLogo();
  const appName = useAppName();

  // A custom uploaded logo is a base64 data URL — a full wordmark that already
  // contains the brand name. So when one is set we show the logo ALONE, a bit
  // larger, and drop the "Bipros EPPM" text. With no custom logo we keep the
  // default compact square mark + app-name text. (data: URLs skip withBasePath.)
  const isUploaded = logoSrc.startsWith("data:");

  if (isUploaded) {
    return (
      <Link
        href="/"
        aria-label={`${appName.primary} home`}
        className="group flex shrink-0 items-center rounded-lg px-1 py-1 outline-none focus-visible:ring-2 focus-visible:ring-gold/40"
      >
        <img
          src={logoSrc}
          alt={appName.primary}
          className="h-9 w-auto max-w-[200px] object-contain"
        />
      </Link>
    );
  }

  return (
    <Link
      href="/"
      aria-label={`${appName.primary} home`}
      className="group flex shrink-0 items-center gap-2.5 rounded-lg px-1 py-1 outline-none focus-visible:ring-2 focus-visible:ring-gold/40"
    >
      <img
        src={withBasePath(logoSrc)}
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

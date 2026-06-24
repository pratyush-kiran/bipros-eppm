"use client";

import { useEffect } from "react";

/**
 * Re-validates auth when a page is restored from the browser's back/forward cache (bfcache).
 *
 * <p>The server-side route guard ({@code proxy.ts}) gates every route by the {@code access_token}
 * cookie, but it only runs on a real request. A Back/Forward navigation that hits the bfcache is
 * served from memory with no request, so the proxy never runs — which is how a signed-out user
 * could press Back and briefly see a cached authenticated page, and a freshly signed-in user
 * could press Back and see the cached sign-in form.
 *
 * <p>On a bfcache restore ({@code pageshow} with {@code event.persisted}) we re-apply the proxy's
 * two redirect rules against the live cookie, so the cache can never show a page that no longer
 * matches the session. Mounted once in the root layout so it covers both the auth pages and the
 * authenticated app.
 */
export function BfcacheAuthSync() {
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return; // only bfcache restores; normal/cached loads run the inline guard

      const hasToken = /(?:^|;\s*)access_token=[^;]+/.test(document.cookie);
      const { pathname } = window.location;
      const onAuthPage = pathname === "/auth" || pathname.startsWith("/auth/");
      const isPublic =
        pathname === "/welcome" || pathname.startsWith("/welcome/") || pathname === "/forbidden";

      if (!hasToken && !onAuthPage && !isPublic) {
        // Signed out but a protected page is cached → mirror proxy.ts's unauth redirect.
        window.location.replace("/auth/login");
      } else if (hasToken && onAuthPage) {
        // Signed in but an auth page is cached → mirror proxy.ts's signed-in redirect.
        window.location.replace("/");
      }
    };

    window.addEventListener("pageshow", onPageShow);
    return () => window.removeEventListener("pageshow", onPageShow);
  }, []);

  return null;
}

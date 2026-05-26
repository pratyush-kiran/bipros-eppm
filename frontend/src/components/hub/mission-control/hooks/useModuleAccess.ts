"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuthStore } from "@/lib/state/store";

// Stable empty array reference so the selector below doesn't return a fresh
// `[]` each render — zustand's getServerSnapshot check triggers an infinite-loop
// warning otherwise.
const EMPTY_ROLES: readonly string[] = Object.freeze([]);

/**
 * Anything that carries the standard permission gates. Used so the predicate
 * returned by useModuleAccess can vet both module tiles AND featured promo
 * cards (and any future gated surface) with one consistent check.
 */
export interface GatedDef {
  permission?: string;
  adminOnly?: boolean;
  requireRoles?: readonly string[];
}

/**
 * Mirrors Sidebar.tsx visibility rules so a user only sees module tiles they
 * can actually open. ADMIN short-circuits all gates (matches Sidebar behavior).
 *
 * Returns a stable predicate plus a `hydrated` flag — pre-hydration we render
 * only tiles with no gates so SSR markup matches the first client paint.
 */
export function useModuleAccess() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const isAdmin = useAuthStore((s) => s.isAdmin);
  const roles = useAuthStore((s) => s.user?.roles ?? EMPTY_ROLES);

  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);

  const canSee = useCallback(
    (gated: GatedDef) => {
      if (!hydrated) {
        // Server frame: only show items with no gates so client/server match.
        return !gated.permission && !gated.adminOnly && !gated.requireRoles;
      }
      if (isAdmin()) return true;
      if (gated.adminOnly) return false;
      if (gated.permission && !hasPermission(gated.permission)) return false;
      if (gated.requireRoles && gated.requireRoles.length > 0) {
        const has = gated.requireRoles.some((r) => roles.includes(r));
        if (!has) return false;
      }
      return true;
    },
    [hydrated, isAdmin, hasPermission, roles],
  );

  return { canSee, hydrated };
}

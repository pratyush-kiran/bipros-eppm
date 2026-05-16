"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuthStore } from "@/lib/state/store";

export interface RecentProject {
  id: string;
  code: string;
  name: string;
  /** Unix ms of the last visit. */
  visitedAt: number;
}

const STORAGE_PREFIX = "bipros.recentProjects.v1";
const MAX_RECENTS = 6;

function storageKey(userId: string | null | undefined): string | null {
  if (!userId) return null;
  return `${STORAGE_PREFIX}.${userId}`;
}

function load(key: string): RecentProject[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (p): p is RecentProject =>
        !!p &&
        typeof p.id === "string" &&
        typeof p.code === "string" &&
        typeof p.name === "string" &&
        typeof p.visitedAt === "number",
    );
  } catch {
    return [];
  }
}

function save(key: string, items: RecentProject[]): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(key, JSON.stringify(items));
  } catch {
    /* quota exceeded or storage disabled — silently drop */
  }
}

/**
 * MRU list of projects the current user has visited, persisted in localStorage
 * keyed by user id (so shared machines don't leak history between accounts).
 *
 * Consumed by the home hub's "Recent projects" strip; can be swapped for a
 * backend signal (`project_last_accessed_at` per user) later without touching
 * call sites — the recorder + reader stay the same shape.
 */
export function useRecentProjects() {
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const [recents, setRecents] = useState<RecentProject[]>([]);
  const [hydrated, setHydrated] = useState(false);

  // localStorage is client-only; defer the initial read so SSR markup matches.
  useEffect(() => {
    const key = storageKey(userId);
    setRecents(key ? load(key) : []);
    setHydrated(true);
  }, [userId]);

  const recordVisit = useCallback(
    (project: { id: string; code: string; name: string }) => {
      const key = storageKey(userId);
      if (!key) return;
      setRecents((prev) => {
        const filtered = prev.filter((p) => p.id !== project.id);
        const next: RecentProject[] = [
          {
            id: project.id,
            code: project.code,
            name: project.name,
            visitedAt: Date.now(),
          },
          ...filtered,
        ].slice(0, MAX_RECENTS);
        save(key, next);
        return next;
      });
    },
    [userId],
  );

  const clear = useCallback(() => {
    const key = storageKey(userId);
    if (!key) return;
    setRecents([]);
    save(key, []);
  }, [userId]);

  return { recents, recordVisit, clear, hydrated };
}

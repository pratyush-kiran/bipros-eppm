"use client";

import { useMemo } from "react";
import { usePathname } from "next/navigation";
import { commands, buildProjectCommands, groupRank, type Command } from "./registry";
import { useAuth } from "@/lib/auth/useAuth";
import { useAccess } from "@/lib/auth/useAccess";
import { useAppStore, useAuthStore, useCommandPaletteStore, useAiStore } from "@/lib/state/store";
import { useTheme } from "next-themes";

const PROJECT_PATH_RE = /\/projects\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:\/|$)/i;

function inferProjectIdFromPath(pathname: string): string | null {
  const m = pathname.match(PROJECT_PATH_RE);
  return m ? m[1] : null;
}

function commandVisible(
  cmd: Command,
  isAdmin: boolean,
  hasAnyRole: (rs: readonly string[]) => boolean,
  canAccessModule: (m: string) => boolean,
  hasPermission: (code: string) => boolean,
): boolean {
  if (cmd.adminOnly && !isAdmin) return false;
  if (cmd.requireRoles && !hasAnyRole(cmd.requireRoles)) return false;
  if (cmd.module && !canAccessModule(cmd.module)) return false;
  if (cmd.permission && !hasPermission(cmd.permission)) return false;
  return true;
}

function normalize(str: string) {
  return str.toLowerCase().replace(/\s+/g, " ").trim();
}

function matchesQuery(cmd: Command, query: string): boolean {
  if (!query) return true;
  const q = normalize(query);
  const haystack = normalize([cmd.title, ...cmd.keywords, cmd.group].join(" "));
  return haystack.includes(q);
}

export function useFilteredCommands() {
  const pathname = usePathname();
  const query = useCommandPaletteStore((s) => s.query);
  const recentIds = useCommandPaletteStore((s) => s.recentCommandIds);
  const storeProjectId = useAppStore((s) => s.currentProjectId);
  const projectId = storeProjectId ?? inferProjectIdFromPath(pathname);
  const { isAdmin, hasAnyRole } = useAuth();
  const { canAccessModule } = useAccess();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const aiSetOpen = useAiStore((s) => s.setOpen);
  const { setTheme, resolvedTheme } = useTheme();

  const allCommands = useMemo<Command[]>(() => {
    const base = [...commands];

    // Wire up action command handlers at runtime so we avoid importing React hooks in registry.ts
    const actionMap: Record<string, () => void> = {
      "toggle-ai": () => aiSetOpen(true),
      "toggle-theme": () => setTheme(resolvedTheme === "dark" ? "light" : "dark"),
    };
    for (const cmd of base) {
      if (cmd.action && actionMap[cmd.id]) {
        cmd.action = actionMap[cmd.id];
      }
    }

    if (projectId) {
      base.push(...buildProjectCommands(projectId));
    }
    return base;
  }, [projectId, aiSetOpen, setTheme, resolvedTheme]);

  const visible = useMemo(() => {
    return allCommands.filter((cmd) =>
      commandVisible(
        cmd,
        isAdmin,
        hasAnyRole,
        (m) => canAccessModule(m as import("@/lib/types").IcpmsModule),
        hasPermission,
      )
    );
  }, [allCommands, isAdmin, hasAnyRole, canAccessModule, hasPermission]);

  const filtered = useMemo(() => {
    return visible.filter((cmd) => matchesQuery(cmd, query));
  }, [visible, query]);

  const sorted = useMemo(() => {
    if (!query) {
      // When no query: Recent first, then by group order, then title
      const recentSet = new Set(recentIds);
      return [...filtered].sort((a, b) => {
        const aRecent = recentSet.has(a.id) ? recentIds.indexOf(a.id) : Infinity;
        const bRecent = recentSet.has(b.id) ? recentIds.indexOf(b.id) : Infinity;
        if (aRecent !== bRecent) return aRecent - bRecent;
        const g = groupRank(a.group) - groupRank(b.group);
        if (g !== 0) return g;
        return a.title.localeCompare(b.title);
      });
    }
    // With query: exact title match first, then startsWith, then includes, then group order
    const q = normalize(query);
    return [...filtered].sort((a, b) => {
      const aTitle = normalize(a.title);
      const bTitle = normalize(b.title);
      const aExact = aTitle === q ? 0 : aTitle.startsWith(q) ? 1 : 2;
      const bExact = bTitle === q ? 0 : bTitle.startsWith(q) ? 1 : 2;
      if (aExact !== bExact) return aExact - bExact;
      const g = groupRank(a.group) - groupRank(b.group);
      if (g !== 0) return g;
      return a.title.localeCompare(b.title);
    });
  }, [filtered, query, recentIds]);

  const grouped = useMemo(() => {
    const map = new Map<string, Command[]>();
    for (const cmd of sorted) {
      const list = map.get(cmd.group) ?? [];
      list.push(cmd);
      map.set(cmd.group, list);
    }
    return map;
  }, [sorted]);

  const flatItems = useMemo(() => {
    const items: ({ type: "group"; label: string } | { type: "cmd"; cmd: Command })[] = [];
    const seenGroups = new Set<string>();
    for (const cmd of sorted) {
      if (!seenGroups.has(cmd.group)) {
        seenGroups.add(cmd.group);
        items.push({ type: "group", label: cmd.group });
      }
      items.push({ type: "cmd", cmd });
    }
    return items;
  }, [sorted]);

  return { sorted, grouped, flatItems, count: sorted.length };
}

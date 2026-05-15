"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTheme } from "next-themes";
import toast from "react-hot-toast";
import { useAuthStore } from "@/lib/state/store";
import { useThemeStore } from "@/lib/state/themeStore";
import { themeApi } from "@/lib/api/themeApi";
import {
  applyTheme,
  previewTheme as applyPreview,
  getCachedThemeCSS,
} from "@/lib/themes/themeService";
import {
  PREDEFINED_THEMES,
  getThemeById,
  type ThemeDefinition,
} from "@/lib/themes/definitions";

export function useThemeManager() {
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const {
    activeThemeId,
    customThemes,
    activeSettingRecordId,
    customSettingRecordId,
    setActiveThemeId,
    setCustomThemes,
    setSettingRecordIds,
    addOrUpdateCustomTheme,
    deleteCustomTheme: deleteCustomThemeFromStore,
  } = useThemeStore();

  const accessToken = useAuthStore((s) => s.accessToken);
  const isAuthenticated = accessToken !== null;
  // /v1/admin/settings is gated on ADMIN_SETTINGS.READ — skip the fetch entirely
  // for non-admins so we don't spam the console with 403s. Theme falls back to
  // the localStorage cache + predefined themes for these users.
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canReadAdminSettings = hasPermission("ADMIN_SETTINGS.READ");

  const isAdmin = useMemo(() => {
    const roles = user?.roles ?? [];
    return roles.some((r) => r === "ROLE_ADMIN" || r === "ADMIN");
  }, [user?.roles]);

  const activeTheme = useMemo(
    () => getThemeById(activeThemeId, customThemes),
    [activeThemeId, customThemes]
  );

  const allThemes = useMemo(
    () => [...PREDEFINED_THEMES, ...customThemes],
    [customThemes]
  );

  // --- Backend sync ---

  const activeThemeQuery = useQuery({
    queryKey: ["theme-active"],
    queryFn: () => themeApi.getActiveThemeId(),
    enabled: isAuthenticated && canReadAdminSettings,
    retry: 1,
    staleTime: 1000 * 60 * 5,
  });

  const customThemesQuery = useQuery({
    queryKey: ["theme-custom"],
    queryFn: () => themeApi.getCustomThemes(),
    enabled: isAuthenticated && canReadAdminSettings,
    retry: 1,
    staleTime: 1000 * 60 * 5,
  });

  useEffect(() => {
    if (activeThemeQuery.data?.data) {
      const record = activeThemeQuery.data.data;
      setActiveThemeId(record.settingValue);
      setSettingRecordIds(record.id, customSettingRecordId);
    }
  }, [activeThemeQuery.data, setActiveThemeId, setSettingRecordIds, customSettingRecordId]);

  useEffect(() => {
    if (customThemesQuery.data?.data) {
      const record = customThemesQuery.data.data;
      try {
        const parsed: ThemeDefinition[] = JSON.parse(record.settingValue);
        setCustomThemes(Array.isArray(parsed) ? parsed : []);
      } catch {
        setCustomThemes([]);
      }
      setSettingRecordIds(activeSettingRecordId, record.id);
    }
  }, [customThemesQuery.data, setCustomThemes, setSettingRecordIds, activeSettingRecordId]);

  // --- Apply theme on mount / when activeTheme changes ---

  const appliedRef = useRef<string | null>(null);
  useEffect(() => {
    if (appliedRef.current !== activeThemeId) {
      applyTheme(activeTheme);
      appliedRef.current = activeThemeId;
    }
  }, [activeTheme, activeThemeId]);

  // --- Mutations ---

  const switchMutation = useMutation({
    mutationFn: (themeId: string) =>
      themeApi.setActiveThemeId(themeId, activeSettingRecordId ?? undefined),
    onSuccess: (data) => {
      if (data.data?.id) {
        setSettingRecordIds(data.data.id, customSettingRecordId);
      }
      queryClient.invalidateQueries({ queryKey: ["theme-active"] });
    },
    onError: (err: unknown) => {
      const message = err instanceof Error ? err.message : "Failed to switch theme";
      toast.error(message);
    },
  });

  const saveCustomMutation = useMutation({
    mutationFn: (themes: ThemeDefinition[]) =>
      themeApi.saveCustomThemes(themes, customSettingRecordId ?? undefined),
    onSuccess: (data) => {
      if (data.data?.id) {
        setSettingRecordIds(activeSettingRecordId, data.data.id);
      }
      queryClient.invalidateQueries({ queryKey: ["theme-custom"] });
    },
    onError: (err: unknown) => {
      const message = err instanceof Error ? err.message : "Failed to save custom themes";
      toast.error(message);
    },
  });

  // --- Actions ---

  const switchTheme = useCallback(
    (id: string) => {
      setActiveThemeId(id);
      applyTheme(getThemeById(id, customThemes));
      switchMutation.mutate(id);
    },
    [setActiveThemeId, customThemes, switchMutation]
  );

  const saveCustomTheme = useCallback(
    (theme: ThemeDefinition) => {
      addOrUpdateCustomTheme(theme);
      const next = useThemeStore.getState().customThemes;
      saveCustomMutation.mutate(next);
    },
    [addOrUpdateCustomTheme, saveCustomMutation]
  );

  const deleteCustomThemeAction = useCallback(
    (id: string) => {
      deleteCustomThemeFromStore(id);
      const next = useThemeStore.getState().customThemes;
      saveCustomMutation.mutate(next);
    },
    [deleteCustomThemeFromStore, saveCustomMutation]
  );

  const previewTheme = useCallback((theme: ThemeDefinition) => {
    applyPreview(theme);
  }, []);

  const cancelPreview = useCallback(() => {
    const currentId = useThemeStore.getState().activeThemeId;
    const currentCustom = useThemeStore.getState().customThemes;
    applyTheme(getThemeById(currentId, currentCustom));
  }, []);

  return {
    activeThemeId,
    activeTheme,
    allThemes,
    customThemes,
    isAdmin,
    isLoading: activeThemeQuery.isLoading || customThemesQuery.isLoading,
    switchTheme,
    saveCustomTheme,
    deleteCustomTheme: deleteCustomThemeAction,
    previewTheme,
    cancelPreview,
  };
}

export function useAppName(): { primary: string; secondary: string } {
  const { activeThemeId, customThemes } = useThemeStore();
  const theme = getThemeById(activeThemeId, customThemes);
  return {
    primary: theme.appNamePrimary || "Bipros",
    secondary: theme.appNameSecondary || "EPPM",
  };
}

export function useActiveLogo(): string {
  const { activeThemeId, customThemes } = useThemeStore();
  const { resolvedTheme } = useTheme();
  const theme = getThemeById(activeThemeId, customThemes);
  const isDark = resolvedTheme === "dark";
  if (isDark) {
    return theme.logoDark ?? theme.logoLight ?? "/bipros-logo.png";
  }
  return theme.logoLight ?? theme.logoDark ?? "/bipros-logo.png";
}

export function initThemeFromCache(): void {
  if (typeof document === "undefined") return;
  try {
    const css = getCachedThemeCSS();
    if (css) {
      let style = document.getElementById("bipros-theme-vars") as HTMLStyleElement | null;
      if (!style) {
        style = document.createElement("style");
        style.id = "bipros-theme-vars";
        document.head.appendChild(style);
      }
      style.textContent = css;
    }
  } catch {
    // ignore
  }
}

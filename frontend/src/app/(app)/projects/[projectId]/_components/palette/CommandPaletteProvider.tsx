"use client";

/**
 * Phase F — Command palette provider.
 *
 * Owns the palette's open state and the single global ⌘K / Ctrl+K keyboard
 * listener. Mounted once in the project workspace layout so the palette is
 * reachable from the hub AND every section page. Outside the project
 * workspace this provider has no `projectId` and the palette doesn't render
 * (it's a noop), so the rest of the app is unaffected.
 */

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useParams } from "next/navigation";
import { ProjectCommandPalette } from "./ProjectCommandPalette";

interface PaletteContextValue {
  open: boolean;
  openPalette: () => void;
  closePalette: () => void;
}

const PaletteContext = createContext<PaletteContextValue | null>(null);

export function useCommandPalette(): PaletteContextValue {
  const ctx = useContext(PaletteContext);
  if (!ctx) {
    // Safe fallback so consumers don't crash if used outside the provider.
    return { open: false, openPalette: () => {}, closePalette: () => {} };
  }
  return ctx;
}

export function CommandPaletteProvider({ children }: { children: ReactNode }) {
  const params = useParams();
  const projectId = (params?.projectId as string | undefined) ?? "";
  const [open, setOpen] = useState(false);

  // Single global ⌘K / Ctrl+K keybinding. preventDefault stops the browser
  // from intercepting (e.g. Firefox's quick-find on Ctrl+K).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const isMod = e.metaKey || e.ctrlKey;
      if (isMod && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <PaletteContext.Provider
      value={{
        open,
        openPalette: () => setOpen(true),
        closePalette: () => setOpen(false),
      }}
    >
      {children}
      {projectId ? (
        <ProjectCommandPalette
          open={open}
          onOpenChange={setOpen}
          projectId={projectId}
        />
      ) : null}
    </PaletteContext.Provider>
  );
}

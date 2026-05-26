"use client";

import { useCallback, useEffect, useState } from "react";
import { LayoutGrid } from "lucide-react";
import { cn } from "@/lib/utils/cn";
import { AppSwitcherOverlay } from "./AppSwitcherOverlay";

export function AppSwitcher() {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);

  // Global shortcut: ⌘/ (or Ctrl+/) toggles the switcher from anywhere.
  // Esc closes when open. We deliberately don't preventDefault on Esc unless
  // the switcher is actually open — other components rely on Esc semantics.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const meta = e.metaKey || e.ctrlKey;
      if (meta && e.key === "/") {
        e.preventDefault();
        setOpen((v) => !v);
        return;
      }
      if (e.key === "Escape" && open) {
        e.preventDefault();
        setOpen(false);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="Open app switcher"
        aria-expanded={open}
        title="Switch app (⌘/)"
        data-testid="app-switcher-button"
        className={cn(
          "flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] border border-transparent transition-colors",
          open
            ? "border-hairline bg-ivory text-gold-deep"
            : "text-slate hover:border-hairline hover:bg-ivory hover:text-gold-deep",
        )}
      >
        <LayoutGrid size={17} strokeWidth={1.5} />
      </button>
      <AppSwitcherOverlay open={open} onClose={close} />
    </>
  );
}

"use client";

import { useEffect, useRef, useCallback } from "react";
import { useRouter } from "next/navigation";
import { Search, CornerDownLeft, ArrowUp, ArrowDown, Command } from "lucide-react";
import { useCommandPaletteStore } from "@/lib/state/store";
import { useFilteredCommands } from "@/lib/commands/useFilteredCommands";
import { cn } from "@/lib/utils/cn";

export function CommandPalette() {
  const open = useCommandPaletteStore((s) => s.open);
  const query = useCommandPaletteStore((s) => s.query);
  const selectedIndex = useCommandPaletteStore((s) => s.selectedIndex);
  const setOpen = useCommandPaletteStore((s) => s.setOpen);
  const toggle = useCommandPaletteStore((s) => s.toggle);
  const setQuery = useCommandPaletteStore((s) => s.setQuery);
  const setSelectedIndex = useCommandPaletteStore((s) => s.setSelectedIndex);
  const pushRecent = useCommandPaletteStore((s) => s.pushRecent);
  const router = useRouter();

  const { sorted, flatItems, count } = useFilteredCommands();
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const activeRef = useRef<HTMLDivElement>(null);

  // Global Cmd/Ctrl + K listener
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k" && !e.shiftKey) {
        e.preventDefault();
        toggle();
      }
      if (e.key === "Escape" && open) {
        e.preventDefault();
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, toggle, setOpen]);

  // Focus input on open
  useEffect(() => {
    if (open) {
      const id = requestAnimationFrame(() => {
        inputRef.current?.focus();
      });
      return () => cancelAnimationFrame(id);
    }
  }, [open]);

  // Clamp selection when filtered list shrinks
  useEffect(() => {
    if (count > 0 && selectedIndex >= count) {
      setSelectedIndex(count - 1);
    }
  }, [count, selectedIndex, setSelectedIndex]);

  // Prevent stale detached-node scrolls and force the list to the top on open.
  const wasOpenRef = useRef(false);
  useEffect(() => {
    const justOpened = open && !wasOpenRef.current;
    wasOpenRef.current = open;

    if (justOpened) {
      if (listRef.current) {
        listRef.current.scrollTop = 0;
      }
      return;
    }

    if (!open) return;

    if (activeRef.current && listRef.current) {
      activeRef.current.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
  }, [selectedIndex, open]);

  const execute = useCallback(
    (index: number) => {
      const cmd = sorted[index];
      if (!cmd) return;
      pushRecent(cmd.id);
      setOpen(false);
      setQuery("");
      if (cmd.action) {
        cmd.action();
      } else if (cmd.href) {
        router.push(cmd.href);
      }
    },
    [sorted, pushRecent, setOpen, setQuery, router]
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (count === 0) return;
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setSelectedIndex((selectedIndex + 1) % count);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setSelectedIndex((selectedIndex - 1 + count) % count);
      } else if (e.key === "Enter") {
        e.preventDefault();
        execute(selectedIndex);
      } else if (e.key === "Home") {
        e.preventDefault();
        setSelectedIndex(0);
      } else if (e.key === "End") {
        e.preventDefault();
        setSelectedIndex(count - 1);
      }
    },
    [selectedIndex, count, setSelectedIndex, execute]
  );

  if (!open) return null;

  let cmdIndex = -1;

  return (
    <div
      className="fixed inset-0 z-[60] flex items-start justify-center bg-black/40 backdrop-blur-sm pt-[15vh] px-4"
      onClick={() => setOpen(false)}
      role="dialog"
      aria-modal="true"
      aria-label="Command palette"
    >
      <div
        className="flex w-full max-w-[640px] flex-col overflow-hidden rounded-xl border border-border bg-surface shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Search input */}
        <div className="flex items-center gap-3 border-b border-border px-4 py-3">
          <Search size={18} className="shrink-0 text-text-muted" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Search commands, pages, and actions..."
            className="flex-1 bg-transparent text-sm text-text-primary placeholder-text-muted outline-none"
            aria-autocomplete="list"
            aria-controls="cmd-list"
            aria-activedescendant={`cmd-item-${selectedIndex}`}
          />
          <kbd className="hidden rounded-md border border-border bg-surface-hover px-1.5 py-0.5 text-[10px] font-mono font-medium text-text-muted sm:inline-block">
            ESC
          </kbd>
        </div>

        {/* List */}
        <div
          ref={listRef}
          id="cmd-list"
          role="listbox"
          className="max-h-[min(60vh,480px)] overflow-y-auto py-2"
        >
          {count === 0 && (
            <div className="px-4 py-8 text-center text-sm text-text-muted">
              No commands found.
            </div>
          )}
          {flatItems.map((item) => {
            if (item.type === "group") {
              return (
                <div
                  key={`g-${item.label}`}
                  className="px-4 pt-3 pb-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted"
                >
                  {item.label}
                </div>
              );
            }
            cmdIndex++;
            const active = cmdIndex === selectedIndex;
            const Icon = item.cmd.icon;
            return (
              <div
                key={item.cmd.id}
                ref={active ? activeRef : null}
                role="option"
                aria-selected={active}
                id={`cmd-item-${cmdIndex}`}
                onClick={() => execute(cmdIndex)}
                onMouseEnter={() => setSelectedIndex(cmdIndex)}
                className={cn(
                  "mx-2 flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors",
                  active
                    ? "bg-accent text-accent-foreground"
                    : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
                )}
              >
                <Icon size={16} className="shrink-0 opacity-80" />
                <span className="flex-1 truncate">{item.cmd.title}</span>
                {item.cmd.href && (
                  <CornerDownLeft
                    size={14}
                    className={cn(
                      "shrink-0 opacity-0 transition-opacity",
                      active && "opacity-60"
                    )}
                  />
                )}
              </div>
            );
          })}
        </div>

        {/* Footer hints */}
        <div className="flex items-center gap-4 border-t border-border bg-surface-hover px-4 py-2 text-[10px] text-text-muted">
          <span className="flex items-center gap-1">
            <Command size={10} />
            <span className="font-medium">K</span> to open
          </span>
          <span className="flex items-center gap-1">
            <ArrowUp size={10} />
            <ArrowDown size={10} /> navigate
          </span>
          <span className="flex items-center gap-1">
            <CornerDownLeft size={10} /> select
          </span>
          <span className="ml-auto">ESC to close</span>
        </div>
      </div>
    </div>
  );
}

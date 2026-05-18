"use client";

import { useState, useRef, useEffect, useLayoutEffect, useCallback } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, Search, X } from "lucide-react";
import { cn } from "@/lib/utils/cn";

export interface SelectOption {
  value: string;
  label: string;
}

interface SearchableSelectProps {
  options: SelectOption[];
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  /**
   * When provided, the component switches to async mode: the parent owns the
   * `options` list and receives every keystroke (debounce in the parent).
   * Local label-based filtering is skipped — parent must apply the filter
   * server-side and feed back the matching options.
   */
  onSearchChange?: (search: string) => void;
  /** Show a "Loading…" row in the open list. Only meaningful in async mode. */
  loading?: boolean;
  /**
   * Display label for the currently selected `value` when `value` is not
   * present in `options` (common in async mode where the option list reflects
   * the current search, not the historical selection). Falls back to a lookup
   * inside `options` when omitted.
   */
  selectedLabel?: string;
}

export function SearchableSelect({
  options,
  value,
  onChange,
  placeholder = "Search...",
  disabled = false,
  className,
  onSearchChange,
  loading = false,
  selectedLabel: selectedLabelProp,
}: SearchableSelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [highlightedIndex, setHighlightedIndex] = useState(0);
  const [dropdownPos, setDropdownPos] = useState<{
    top: number;
    left: number;
    width: number;
  } | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  const isAsync = typeof onSearchChange === "function";
  const selectedLabel =
    selectedLabelProp ??
    options.find((o) => o.value === value)?.label ??
    "";

  // In async mode the parent owns filtering — feed every keystroke back and
  // render `options` verbatim. In sync mode keep the original local-filter UX.
  const filtered = isAsync
    ? options
    : search
      ? options.filter((o) => o.label.toLowerCase().includes(search.toLowerCase()))
      : options;

  // Close on click outside (works whether the dropdown is inside containerRef or in the portal —
  // listRef holds the portaled list, so a click inside the list is treated as inside).
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node;
      const insideContainer = containerRef.current?.contains(target);
      const insideList = listRef.current?.contains(target);
      if (!insideContainer && !insideList) {
        setIsOpen(false);
        setSearch("");
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  // Position the portaled dropdown right below the input. Re-measure on open + on
  // scroll/resize so it tracks the trigger when ancestors scroll (e.g., the drawer body).
  // We deliberately don't clear dropdownPos on close — the list is gated on `isOpen` in
  // the JSX, so a stale position is harmless and skipping the clear keeps this effect
  // strictly synchronizing-with-an-external-system (DOM rect / scroll events).
  useLayoutEffect(() => {
    if (!isOpen) return;
    const update = () => {
      const el = containerRef.current;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      setDropdownPos({ top: rect.bottom + 4, left: rect.left, width: rect.width });
    };
    update();
    window.addEventListener("scroll", update, true); // capture: catch ancestor scrolls
    window.addEventListener("resize", update);
    return () => {
      window.removeEventListener("scroll", update, true);
      window.removeEventListener("resize", update);
    };
  }, [isOpen]);

  // Reset highlight when filtered list changes
  useEffect(() => {
    setHighlightedIndex(0);
  }, [search]);

  // Scroll highlighted item into view
  useEffect(() => {
    if (isOpen && listRef.current) {
      const item = listRef.current.children[highlightedIndex] as HTMLElement;
      item?.scrollIntoView({ block: "nearest" });
    }
  }, [highlightedIndex, isOpen]);

  const handleSelect = useCallback(
    (val: string) => {
      onChange(val);
      setIsOpen(false);
      setSearch("");
      onSearchChange?.("");
    },
    [onChange, onSearchChange]
  );

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!isOpen) {
      if (e.key === "ArrowDown" || e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        setIsOpen(true);
        return;
      }
    }

    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        setHighlightedIndex((prev) => Math.min(prev + 1, filtered.length - 1));
        break;
      case "ArrowUp":
        e.preventDefault();
        setHighlightedIndex((prev) => Math.max(prev - 1, 0));
        break;
      case "Enter":
        e.preventDefault();
        if (filtered[highlightedIndex]) {
          handleSelect(filtered[highlightedIndex].value);
        }
        break;
      case "Escape":
        setIsOpen(false);
        setSearch("");
        break;
    }
  };

  const handleOpen = () => {
    if (disabled) return;
    setIsOpen(true);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* Display Button */}
      {!isOpen && (
        <button
          type="button"
          onClick={handleOpen}
          disabled={disabled}
          className={cn(
            "flex w-full items-center justify-between rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-left",
            "focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent",
            disabled ? "opacity-50 cursor-not-allowed" : "cursor-pointer hover:border-border",
            value ? "text-text-primary" : "text-text-muted"
          )}
        >
          <span className="truncate">{selectedLabel || placeholder}</span>
          <ChevronDown size={16} className="ml-2 text-text-secondary flex-shrink-0" />
        </button>
      )}

      {/* Search Input (shown when open) */}
      {isOpen && (
        <div className="flex items-center rounded-md border border-accent bg-surface-hover ring-1 ring-blue-500">
          <Search size={14} className="ml-3 text-text-secondary flex-shrink-0" />
          <input
            ref={inputRef}
            type="text"
            value={search}
            onChange={(e) => {
              const next = e.target.value;
              setSearch(next);
              onSearchChange?.(next);
            }}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            className="w-full bg-transparent px-2 py-2 text-sm text-text-primary placeholder-text-muted focus:outline-none"
          />
          {(search || value) && (
            <button
              type="button"
              onClick={() => {
                if (search) {
                  setSearch("");
                  onSearchChange?.("");
                } else {
                  onChange("");
                  setIsOpen(false);
                }
              }}
              className="mr-2 text-text-secondary hover:text-text-primary flex-shrink-0"
            >
              <X size={14} />
            </button>
          )}
        </div>
      )}

      {/* Dropdown List — portaled to body so an `overflow:auto` ancestor (e.g., the DPR
          drawer's table cell) doesn't clip it. Position is fixed and tracked against the
          trigger via useLayoutEffect above. */}
      {isOpen && dropdownPos && typeof document !== "undefined" &&
        createPortal(
          <ul
            ref={listRef}
            data-testid="searchable-select-list"
            style={{
              position: "fixed",
              top: dropdownPos.top,
              left: dropdownPos.left,
              width: dropdownPos.width,
            }}
            className="z-[60] max-h-60 overflow-auto rounded-md border border-border bg-surface-hover py-1 shadow-lg"
          >
            {loading ? (
              <li className="px-3 py-2 text-sm text-text-muted">Loading...</li>
            ) : filtered.length === 0 ? (
              <li className="px-3 py-2 text-sm text-text-muted">No matches found</li>
            ) : (
              filtered.map((option, index) => (
                <li
                  key={option.value}
                  data-testid="searchable-select-option"
                  onClick={() => handleSelect(option.value)}
                  onMouseEnter={() => setHighlightedIndex(index)}
                  className={cn(
                    "cursor-pointer px-3 py-2 text-sm transition-colors",
                    index === highlightedIndex
                      ? "bg-accent text-accent-foreground"
                      : option.value === value
                        ? "bg-accent/10 text-accent"
                        : "text-text-secondary hover:bg-surface-active"
                  )}
                >
                  {option.label}
                </li>
              ))
            )}
          </ul>,
          document.body,
        )}
    </div>
  );
}

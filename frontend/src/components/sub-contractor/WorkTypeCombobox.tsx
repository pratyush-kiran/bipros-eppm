"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { scWorkTypeApi, type SubContractorWorkType } from "@/lib/api/subContractorWorkTypeApi";
import { Loader2, Plus } from "lucide-react";

interface WorkTypeComboboxProps {
  value: string;
  displayValue: string;
  /** The unit already selected on this mapping row — saved as defaultUnit when creating a new work type on the fly. */
  currentUnit?: string;
  onChange: (id: string, name: string, defaultUnit?: string | null) => void;
  placeholder?: string;
}

const inputCls =
  "w-full rounded-[10px] border border-hairline bg-paper px-3 py-2 text-sm text-charcoal placeholder:text-ash focus:border-gold focus:outline-none focus:shadow-[0_0_0_3px_rgba(212,175,55,0.18)]";

export default function WorkTypeCombobox({
  displayValue,
  currentUnit,
  onChange,
  placeholder = "Type work type…",
}: WorkTypeComboboxProps) {
  const [query, setQuery] = useState(displayValue);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [options, setOptions] = useState<SubContractorWorkType[]>([]);
  const [activeIndex, setActiveIndex] = useState(-1);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);

  const fetchOptions = useCallback(async (q: string) => {
    setLoading(true);
    try {
      const resp = await scWorkTypeApi.search(q);
      setOptions(resp.data ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setQuery(displayValue);
  }, [displayValue]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const handleInputChange = (text: string) => {
    setQuery(text);
    setOpen(true);
    setActiveIndex(-1);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      fetchOptions(text);
    }, 300);
  };

  const handleSelect = (item: SubContractorWorkType) => {
    setQuery(item.name);
    setOpen(false);
    onChange(item.id, item.name, item.defaultUnit);
  };

  const handleCreate = async (name: string) => {
    setLoading(true);
    try {
      const resp = await scWorkTypeApi.findOrCreate(name, currentUnit || null);
      const created = resp.data;
      if (created) {
        setQuery(created.name);
        setOpen(false);
        onChange(created.id, created.name, created.defaultUnit ?? currentUnit);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleBlurCreate = () => {
    if (!query.trim()) return;
    // If the text matches an existing option exactly, select it.
    const exactMatch = options.find(
      (o) => o.name.toLowerCase() === query.trim().toLowerCase()
    );
    if (exactMatch) {
      handleSelect(exactMatch);
      return;
    }
    // Otherwise just surface the typed name back to the parent with no ID.
    // Creation is deferred to form-submit time so the unit is definitely set.
    if (query.trim() !== displayValue) {
      onChange("", query.trim(), undefined);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!open) return;
    const total = options.length + (canCreate ? 1 : 0);
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActiveIndex((i) => (i + 1) % total);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIndex((i) => (i - 1 + total) % total);
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (activeIndex >= 0 && activeIndex < options.length) {
        handleSelect(options[activeIndex]);
      } else if (activeIndex === options.length && canCreate) {
        handleCreate(query.trim());
      } else if (canCreate) {
        handleCreate(query.trim());
      }
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  };

  const canCreate =
    query.trim().length > 0 &&
    !options.some((o) => o.name.toLowerCase() === query.trim().toLowerCase());

  return (
    <div ref={wrapperRef} className="relative">
      <input
        type="text"
        value={query}
        onChange={(e) => handleInputChange(e.target.value)}
        onFocus={() => {
          setOpen(true);
          if (query.trim()) fetchOptions(query.trim());
        }}
        onBlur={() => {
          setTimeout(() => handleBlurCreate(), 150);
        }}
        onKeyDown={handleKeyDown}
        className={inputCls}
        placeholder={placeholder}
        autoComplete="off"
      />
      {open && (
        <div className="absolute z-50 mt-1 w-full rounded-lg border border-hairline bg-paper shadow-lg max-h-60 overflow-auto">
          {loading && options.length === 0 ? (
            <div className="flex items-center gap-2 px-3 py-2 text-xs text-slate">
              <Loader2 className="h-3 w-3 animate-spin" />
              Loading…
            </div>
          ) : options.length === 0 && !canCreate ? (
            <div className="px-3 py-2 text-xs text-slate">No matches</div>
          ) : (
            <ul className="py-1">
              {options.map((opt, idx) => (
                <li
                  key={opt.id}
                  className={`px-3 py-2 text-sm cursor-pointer flex items-center justify-between ${
                    idx === activeIndex
                      ? "bg-parchment text-charcoal"
                      : "text-charcoal hover:bg-parchment/50"
                  }`}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    handleSelect(opt);
                  }}
                  onMouseEnter={() => setActiveIndex(idx)}
                >
                  <span>{opt.name}</span>
                  {opt.defaultUnit && (
                    <span className="text-xs text-slate">{opt.defaultUnit}</span>
                  )}
                </li>
              ))}
              {canCreate && (
                <li
                  className={`px-3 py-2 text-sm cursor-pointer flex items-center gap-2 border-t border-hairline ${
                    activeIndex === options.length
                      ? "bg-parchment text-charcoal"
                      : "text-charcoal hover:bg-parchment/50"
                  }`}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    handleCreate(query.trim());
                  }}
                  onMouseEnter={() => setActiveIndex(options.length)}
                >
                  <Plus className="h-3.5 w-3.5 text-gold" />
                  <span>Create &ldquo;{query.trim()}&rdquo;</span>
                </li>
              )}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

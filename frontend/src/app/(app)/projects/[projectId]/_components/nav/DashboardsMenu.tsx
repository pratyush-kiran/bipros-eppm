"use client";
import { useState, useRef, useEffect } from "react";
import { useRouter } from "next/navigation";
import { BarChart3, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils/cn";

interface Props {
  projectId: string;
  variant?: "hero" | "bar"; // hero = on dark bg; bar = on light bg
}

export function DashboardsMenu({ projectId, variant = "bar" }: Props) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  // close on outside click + Escape key
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const go = (path: string) => {
    router.push(path);
    setOpen(false);
  };

  const triggerCls = variant === "hero"
    ? "inline-flex items-center gap-2 rounded-xl border border-gold/40 bg-gold/15 px-4 py-2.5 text-sm font-semibold text-gold hover:bg-gold/25 backdrop-blur"
    : "inline-flex items-center gap-2 rounded-lg border border-gold/45 bg-gold-tint/40 px-3 py-2 text-sm font-semibold text-gold-deep transition-colors hover:border-gold hover:bg-gold-tint";

  return (
    <div ref={wrapRef} className="relative shrink-0">
      <button type="button" onClick={() => setOpen(!open)} className={triggerCls}
        aria-haspopup="menu" aria-expanded={open}>
        <BarChart3 size={15} strokeWidth={1.75} />
        Open dashboards
        <ChevronDown size={14} className={cn("transition-transform duration-200", open && "rotate-180")} />
      </button>
      {open && (
        <div role="menu" className="absolute right-0 top-full mt-1 w-56 rounded-md border border-border bg-surface shadow-lg z-50">
          <button role="menuitem" type="button" onClick={() => go(`/projects/${projectId}/insights/operational`)}
            className="block w-full rounded-t-md px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-surface-hover/50 hover:text-text-primary">
            Operational
          </button>
          <button role="menuitem" type="button" onClick={() => go(`/projects/${projectId}/insights/field`)}
            className="block w-full px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-surface-hover/50 hover:text-text-primary">
            Field
          </button>
          <button role="menuitem" type="button" onClick={() => go(`/projects/${projectId}/capacity-utilization`)}
            className="block w-full rounded-b-md border-t border-border px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-surface-hover/50 hover:text-text-primary">
            Capacity Utilisation
          </button>
        </div>
      )}
    </div>
  );
}

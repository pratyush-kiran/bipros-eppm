"use client";

import React from "react";
import { cn } from "@/lib/utils/cn";

export interface ReportKind {
  id: string;
  label: string;
}

export interface ReportGenerationPanelProps {
  kinds: ReportKind[];
  onGenerate: (kindId: string) => void;
  busyId?: string | null;
  className?: string;
}

export function ReportGenerationPanel({
  kinds,
  onGenerate,
  busyId,
  className,
}: ReportGenerationPanelProps) {
  return (
    <div className={cn("space-y-3", className)}>
      {kinds.map((k) => {
        const busy = busyId === k.id;
        return (
          <div
            key={k.id}
            className="flex items-center justify-between gap-3 rounded-lg border border-hairline bg-parchment/40 px-4 py-2.5"
          >
            <div className="flex items-center gap-3">
              <span className="inline-block h-2 w-2 rounded-full bg-gold" aria-hidden />
              <span className="text-sm text-text-primary">{k.label}</span>
            </div>
            <button
              type="button"
              onClick={() => onGenerate(k.id)}
              disabled={busy}
              className={cn(
                "rounded-lg bg-gold px-3 py-1.5 text-xs font-semibold text-[var(--accent-foreground)] transition-colors",
                "hover:bg-gold-deep",
                "disabled:cursor-not-allowed disabled:opacity-50"
              )}
            >
              {busy ? "Generating…" : "Generate"}
            </button>
          </div>
        );
      })}
    </div>
  );
}

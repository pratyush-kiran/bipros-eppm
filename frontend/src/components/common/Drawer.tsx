"use client";

import { useEffect, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { cn } from "@/lib/utils/cn";

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  title: string;
  /** Tailwind max-width class for the panel. Default: "max-w-2xl" (~672px). */
  widthClass?: string;
  children: ReactNode;
}

/**
 * Right-side slide-in drawer with strict close behavior:
 * the backdrop and ESC key do NOT dismiss — only the X button (or whatever
 * the consumer wires into onClose) closes it. Designed for forms with
 * unsaved data where accidental dismissal is costly.
 *
 * Stays mounted across open/close so the slide-out transition can play.
 * Renders into document.body via a portal to avoid being clipped by the
 * stacking/transform context of sticky page headers.
 */
export function Drawer({
  open,
  onClose,
  title,
  widthClass = "max-w-2xl",
  children,
}: DrawerProps) {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  if (!mounted) return null;

  const node = (
    <div inert={!open || undefined}>
      <div
        data-testid="drawer-backdrop"
        className={cn(
          "fixed inset-0 z-40 bg-charcoal/40 transition-opacity duration-200 ease-out",
          open ? "opacity-100" : "pointer-events-none opacity-0"
        )}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cn(
          "fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-paper shadow-[0_20px_40px_rgba(28,28,28,0.12)] transition-transform duration-200 ease-out",
          widthClass,
          open ? "translate-x-0" : "pointer-events-none translate-x-full"
        )}
      >
        <div className="flex items-center justify-between gap-3 border-b border-hairline px-5 py-3">
          <h2 className="font-display text-lg font-semibold tracking-tight text-charcoal">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-ivory hover:text-gold-deep"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto">{children}</div>
      </div>
    </div>
  );

  return createPortal(node, document.body);
}

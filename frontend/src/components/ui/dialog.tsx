"use client";

import React, { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils/cn";

export interface DialogProps {
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  children: React.ReactNode;
}

interface DialogContextType {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
}

const DialogContext = React.createContext<DialogContextType | undefined>(undefined);

function useDialog() {
  const ctx = React.useContext(DialogContext);
  if (!ctx) throw new Error("Dialog components must be used within a Dialog");
  return ctx;
}

export function Dialog({ open = false, onOpenChange, children }: DialogProps) {
  const [isOpen, setIsOpen] = useState(open);

  useEffect(() => {
    setIsOpen(open);
  }, [open]);

  const handleOpenChange = (next: boolean) => {
    setIsOpen(next);
    onOpenChange?.(next);
  };

  return (
    <DialogContext.Provider value={{ isOpen, onOpenChange: handleOpenChange }}>
      {children}
    </DialogContext.Provider>
  );
}

export type DialogContentProps = React.HTMLAttributes<HTMLDivElement>;

/** Returns every focusable element currently inside `container`. */
function getFocusable(container: HTMLElement): HTMLElement[] {
  const sel =
    'a[href], area[href], input:not([disabled]):not([type="hidden"]), select:not([disabled]),' +
    ' textarea:not([disabled]), button:not([disabled]), iframe, object, embed,' +
    ' [tabindex]:not([tabindex="-1"]), [contenteditable="true"]';
  return Array.from(container.querySelectorAll<HTMLElement>(sel)).filter(
    (el) => !el.hasAttribute("disabled") && el.offsetParent !== null,
  );
}

export function DialogContent({ className = "", children, ...props }: DialogContentProps) {
  const { isOpen, onOpenChange } = useDialog();
  const contentRef = useRef<HTMLDivElement | null>(null);
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  // Hold the latest close handler in a ref so the open-effect doesn't re-run
  // (and thus re-grab focus) every render. The parent's `onOpenChange` is a
  // fresh function on each pass through {@link Dialog}.
  const closeRef = useRef(onOpenChange);
  closeRef.current = onOpenChange;

  // Escape-to-close, body scroll lock, initial focus, focus restore, focus trap.
  // Everything is gated on `isOpen` so closed dialogs do nothing.
  useEffect(() => {
    if (!isOpen) return;

    // Capture the element that had focus when the dialog opened so we can
    // restore it on close. Falls back to document.body which is harmless.
    previouslyFocusedRef.current =
      (document.activeElement as HTMLElement | null) ?? null;

    // Body scroll lock — preserve the prior overflow so we can restore it.
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    // Move initial focus into the dialog. Prefer the first focusable child;
    // fall back to the dialog container itself (which gets tabIndex={-1}).
    const focusInitial = () => {
      const node = contentRef.current;
      if (!node) return;
      const focusables = getFocusable(node);
      if (focusables.length > 0) {
        focusables[0].focus();
      } else {
        node.focus();
      }
    };
    // Run after paint so cmdk-style portals settle first.
    const raf = requestAnimationFrame(focusInitial);

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        closeRef.current(false);
        return;
      }
      if (e.key === "Tab") {
        const node = contentRef.current;
        if (!node) return;
        const focusables = getFocusable(node);
        if (focusables.length === 0) {
          // Nothing to tab to — keep focus on the container.
          e.preventDefault();
          node.focus();
          return;
        }
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        const active = document.activeElement as HTMLElement | null;
        if (e.shiftKey) {
          if (active === first || !node.contains(active)) {
            e.preventDefault();
            last.focus();
          }
        } else {
          if (active === last || !node.contains(active)) {
            e.preventDefault();
            first.focus();
          }
        }
      }
    };
    document.addEventListener("keydown", onKey);

    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      // Restore focus to whatever had it before we opened. Guarded because the
      // node could have been unmounted (e.g. route change) since capture.
      const prev = previouslyFocusedRef.current;
      if (prev && document.body.contains(prev)) {
        prev.focus();
      }
    };
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-charcoal/40 p-4"
      onClick={() => onOpenChange(false)}
    >
      <div
        ref={contentRef}
        role="dialog"
        aria-modal="true"
        tabIndex={-1}
        className={cn(
          "relative w-full max-w-md rounded-2xl bg-paper shadow-[0_20px_40px_rgba(28,28,28,0.08)] focus:outline-none",
          className
        )}
        onClick={(e) => e.stopPropagation()}
        {...props}
      >
        <button
          onClick={() => onOpenChange(false)}
          className="absolute right-4 top-4 rounded-md p-1 text-slate hover:text-gold-deep transition-colors"
          aria-label="Close"
        >
          <X size={18} />
        </button>
        {children}
      </div>
    </div>
  );
}

export function DialogHeader({ className = "", ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("px-6 pt-6", className)} {...props} />;
}

export function DialogTitle({
  className = "",
  ...props
}: React.HTMLAttributes<HTMLHeadingElement>) {
  return (
    <h2
      className={cn(
        "font-display text-xl font-semibold text-charcoal tracking-tight",
        className
      )}
      {...props}
    />
  );
}

export function DialogBody({ className = "", ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("px-6 py-4 text-sm text-slate leading-relaxed", className)} {...props} />;
}

export function DialogFooter({ className = "", ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "flex items-center justify-end gap-2 rounded-b-2xl border-t border-hairline bg-ivory px-6 py-4",
        className
      )}
      {...props}
    />
  );
}

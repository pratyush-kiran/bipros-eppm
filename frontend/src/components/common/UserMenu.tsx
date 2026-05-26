"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { LogOut, UserCircle2 } from "lucide-react";
import { useAuthStore } from "@/lib/state/store";
import { useThemeStore } from "@/lib/state/themeStore";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";
import { cn } from "@/lib/utils/cn";

export function UserMenu() {
  const user = useAuthStore((s) => s.user);
  const clearAuth = useAuthStore((s) => s.clearAuth);
  const { label: roleLabel } = useMostSeniorRole();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => setHydrated(true), []);

  // Close on outside click + Escape
  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const handleLogout = () => {
    try {
      useThemeStore.getState().clearBackendIds();
    } catch {
      /* theme store optional */
    }
    clearAuth();
    setOpen(false);
    // Hard navigation guarantees the AccessProvider re-runs against an empty
    // auth store; a soft router.push after clearAuth can race with React's
    // state flush and leave the user on the (now broken) authenticated page.
    window.location.href = "/auth/login";
  };

  // SSR-safe placeholder until auth rehydrates from localStorage on the client.
  const displayUser = hydrated ? user : null;
  const fullName = displayUser
    ? `${displayUser.firstName ?? ""} ${displayUser.lastName ?? ""}`.trim() ||
      displayUser.username
    : "User";
  const username = displayUser?.username ?? "user";
  const initials = (fullName || "U").slice(0, 2).toUpperCase();

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Open account menu"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex h-10 w-10 items-center justify-center rounded-full border border-hairline bg-paper text-[11px] font-semibold uppercase tracking-[0.06em] text-charcoal transition-colors",
          "hover:border-gold/45 hover:bg-ivory",
          open && "border-gold/50 bg-ivory shadow-[0_0_0_3px_rgba(212,175,55,0.12)]",
        )}
      >
        <span suppressHydrationWarning>{initials}</span>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-12 z-40 w-64 overflow-hidden rounded-xl border border-hairline bg-paper shadow-[0_18px_40px_-18px_rgba(28,28,28,0.25)]"
        >
          <div className="flex items-center gap-3 border-b border-hairline px-4 py-3">
            <span className="flex h-9 w-9 items-center justify-center rounded-full border border-hairline bg-ivory text-[11px] font-semibold uppercase tracking-[0.06em] text-charcoal">
              {initials}
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-semibold text-charcoal">
                {fullName}
              </div>
              <div className="mt-0.5 flex items-center gap-1.5 text-[10.5px] font-medium uppercase tracking-[0.12em] text-ash">
                <span className="truncate">@{username}</span>
                {roleLabel && (
                  <>
                    <span aria-hidden>·</span>
                    <span className="truncate text-gold-ink">{roleLabel}</span>
                  </>
                )}
              </div>
            </div>
          </div>

          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              router.push("/profile");
            }}
            className="flex w-full items-center gap-2.5 px-4 py-2.5 text-[13px] text-charcoal transition-colors hover:bg-ivory"
          >
            <UserCircle2 size={15} strokeWidth={1.75} className="text-slate" />
            Profile
          </button>

          <button
            type="button"
            role="menuitem"
            onClick={handleLogout}
            className="flex w-full items-center gap-2.5 border-t border-hairline px-4 py-2.5 text-[13px] text-burgundy transition-colors hover:bg-burgundy/8"
          >
            <LogOut size={15} strokeWidth={1.75} />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

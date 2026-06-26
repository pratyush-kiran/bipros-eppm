"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Bell,
  CheckCheck,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Info,
} from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notificationApi } from "@/lib/api/notificationApi";
import type { NotificationItem } from "@/lib/types/notification";
import { cn } from "@/lib/utils/cn";

// ─── Relative-time helper ─────────────────────────────────────────────────────

function relativeTime(isoString: string): string {
  const diff = Date.now() - new Date(isoString).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return "just now";
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}d ago`;
  return new Date(isoString).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
  });
}

// ─── Type → icon/accent map ───────────────────────────────────────────────────

function NotifIcon({ type }: { type: string }) {
  const t = type?.toUpperCase() ?? "";
  if (t.includes("OVERDUE") || t.includes("DELAY") || t.includes("WARNING")) {
    return <AlertTriangle size={14} className="shrink-0 text-amber-500" strokeWidth={1.75} />;
  }
  if (t.includes("APPROVED") || t.includes("APPROVE")) {
    return <CheckCircle2 size={14} className="shrink-0 text-emerald-600" strokeWidth={1.75} />;
  }
  if (t.includes("REJECTED") || t.includes("REJECT")) {
    return <XCircle size={14} className="shrink-0 text-burgundy" strokeWidth={1.75} />;
  }
  return <Info size={14} className="shrink-0 text-slate" strokeWidth={1.75} />;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function NotificationBell() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  // Unread badge — polls every 45 s
  const { data: unreadCount = 0 } = useQuery({
    queryKey: ["notifications-unread-count"],
    queryFn: notificationApi.unreadCount,
    refetchInterval: 45_000,
  });

  // Notification list — only fetched when dropdown is open
  const { data: page, isLoading: listLoading } = useQuery({
    queryKey: ["notifications-list"],
    queryFn: () => notificationApi.list({ size: 20 }),
    enabled: open,
  });

  const items: NotificationItem[] = page?.content ?? [];

  // Close on outside click + Escape (same pattern as UserMenu)
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

  const invalidateBoth = () => {
    queryClient.invalidateQueries({ queryKey: ["notifications-unread-count"] });
    queryClient.invalidateQueries({ queryKey: ["notifications-list"] });
  };

  const markReadMutation = useMutation({
    mutationFn: (id: string) => notificationApi.markRead(id),
    onSuccess: invalidateBoth,
  });

  const markAllMutation = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: invalidateBoth,
  });

  const handleItemClick = (item: NotificationItem) => {
    setOpen(false);
    if (!item.readAt) {
      markReadMutation.mutate(item.id);
    }
    if (item.linkUrl) {
      router.push(item.linkUrl);
    }
  };

  const badgeLabel = unreadCount > 9 ? "9+" : String(unreadCount);

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        aria-label="Notifications"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "relative flex h-10 w-10 items-center justify-center rounded-[10px] border border-transparent text-slate transition-colors",
          "hover:border-hairline hover:bg-ivory hover:text-gold-deep",
          open && "border-hairline bg-ivory text-gold-deep",
        )}
      >
        <Bell size={17} strokeWidth={1.5} />
        {unreadCount > 0 && (
          <span
            aria-label={`${unreadCount} unread notifications`}
            className="absolute right-1.5 top-1.5 flex h-[16px] min-w-[16px] items-center justify-center rounded-full bg-gold px-0.5 text-[9px] font-bold leading-none text-paper ring-2 ring-paper"
          >
            {badgeLabel}
          </span>
        )}
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-12 z-40 w-[360px] overflow-hidden rounded-xl border border-hairline bg-paper shadow-[0_18px_40px_-18px_rgba(28,28,28,0.25)]"
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-hairline px-4 py-2.5">
            <span className="text-[13px] font-semibold text-charcoal">Notifications</span>
            {items.some((n) => !n.readAt) && (
              <button
                type="button"
                onClick={() => markAllMutation.mutate()}
                disabled={markAllMutation.isPending}
                className="flex items-center gap-1 text-[11px] font-medium text-slate hover:text-gold-deep disabled:opacity-50 transition-colors"
              >
                <CheckCheck size={13} strokeWidth={1.75} />
                Mark all read
              </button>
            )}
          </div>

          {/* List */}
          <div className="max-h-[400px] overflow-y-auto">
            {listLoading && (
              <div className="px-4 py-6 text-center text-[12px] text-ash">Loading…</div>
            )}
            {!listLoading && items.length === 0 && (
              <div className="px-4 py-8 text-center text-[12px] text-ash">
                No notifications yet.
              </div>
            )}
            {!listLoading &&
              items.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  role="menuitem"
                  onClick={() => handleItemClick(item)}
                  className={cn(
                    "flex w-full gap-3 border-b border-hairline px-4 py-3 text-left transition-colors last:border-b-0",
                    "hover:bg-ivory",
                    !item.readAt && "bg-gold/5",
                  )}
                >
                  {/* Type icon */}
                  <span className="mt-0.5">
                    <NotifIcon type={item.type} />
                  </span>

                  {/* Content */}
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-2">
                      <span
                        className={cn(
                          "truncate text-[12px]",
                          item.readAt
                            ? "font-medium text-charcoal"
                            : "font-semibold text-charcoal",
                        )}
                      >
                        {item.title}
                      </span>
                      {!item.readAt && (
                        <span
                          aria-hidden
                          className="mt-1 h-[6px] w-[6px] shrink-0 rounded-full bg-gold"
                        />
                      )}
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-[11px] leading-[1.4] text-slate">
                      {item.body}
                    </p>
                    <span className="mt-1 block text-[10px] text-ash">
                      {relativeTime(item.createdAt)}
                    </span>
                  </div>
                </button>
              ))}
          </div>
        </div>
      )}
    </div>
  );
}

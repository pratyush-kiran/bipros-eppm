"use client";

import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import {
  Eye,
  Users,
  Package,
  Wrench,
  Play,
  CheckCircle2,
  Pencil,
  Trash2,
  UserCheck,
  FilePlus2,
  Library,
  Gauge,
} from "lucide-react";
import type { ActivityResponse } from "@/lib/api/activityApi";
import type { ResourceKind } from "./QuickAssignResourceDialog";
import { useActivityMasterStatus } from "@/lib/hooks/useActivityMasterStatus";
import type { DialogMode } from "./LinkOrCreateWorkActivityDialog";

export interface ContextMenuPosition {
  x: number;
  y: number;
}

interface Props {
  activity: ActivityResponse;
  position: ContextMenuPosition;
  canStart: boolean;
  canComplete: boolean;
  canDelete?: boolean;
  onClose: () => void;
  onOpenDetail: (activity: ActivityResponse) => void;
  onAssign: (activity: ActivityResponse, kind: ResourceKind) => void;
  onStart: (activity: ActivityResponse) => void;
  onComplete: (activity: ActivityResponse) => void;
  onEdit: (activity: ActivityResponse) => void;
  onDelete: (activity: ActivityResponse) => void;
  onSetSupervisor: (activity: ActivityResponse) => void;
  onCreateDpr: (activity: ActivityResponse) => void;
  /** Opens the LinkOrCreateWorkActivityDialog. Rendered conditionally — only when the
   *  activity's master/norm setup is incomplete. */
  onOpenMasterDialog?: (activity: ActivityResponse, mode: DialogMode) => void;
}

const MENU_WIDTH = 220;
// Approximate height; the actual height is governed by CSS but we use this for clamping so
// the menu doesn't render with its bottom edge outside the viewport.
const MENU_HEIGHT_ESTIMATE = 360;

export function ActivityRowContextMenu({
  activity,
  position,
  canStart,
  canComplete,
  canDelete = true,
  onClose,
  onOpenDetail,
  onAssign,
  onStart,
  onComplete,
  onEdit,
  onDelete,
  onSetSupervisor,
  onCreateDpr,
  onOpenMasterDialog,
}: Props) {
  const masterStatus = useActivityMasterStatus(activity.workActivityId);
  const menuRef = useRef<HTMLDivElement>(null);

  // Clamp during render using static estimates (the menu has a fixed item count, so the
  // estimated height is a tight upper bound). Avoids an effect-driven re-render after mount.
  const vw = typeof window !== "undefined" ? window.innerWidth : 1024;
  const vh = typeof window !== "undefined" ? window.innerHeight : 768;
  const clamped: ContextMenuPosition = {
    x: Math.min(position.x, Math.max(0, vw - MENU_WIDTH - 8)),
    y: Math.min(position.y, Math.max(0, vh - MENU_HEIGHT_ESTIMATE - 8)),
  };

  // Close on outside click, Escape, or scroll.
  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    const onScroll = () => onClose();
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onKey);
    window.addEventListener("scroll", onScroll, true);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", onScroll, true);
    };
  }, [onClose]);

  if (typeof document === "undefined") return null;

  type Item = {
    key: string;
    label: string;
    icon: React.ReactNode;
    onClick: () => void;
    danger?: boolean;
    disabled?: boolean;
    divider?: never;
  };
  type Divider = { key: string; divider: true };

  const items: Array<Item | Divider> = [
    {
      key: "open",
      label: "Open detail",
      icon: <Eye size={14} />,
      onClick: () => onOpenDetail(activity),
    },
    { key: "d1", divider: true },
    {
      key: "assign-manpower",
      label: "Assign Manpower",
      icon: <Users size={14} />,
      onClick: () => onAssign(activity, "MANPOWER"),
    },
    {
      key: "assign-material",
      label: "Assign Material",
      icon: <Package size={14} />,
      onClick: () => onAssign(activity, "MATERIAL"),
    },
    {
      key: "assign-equipment",
      label: "Assign Equipment",
      icon: <Wrench size={14} />,
      onClick: () => onAssign(activity, "EQUIPMENT"),
    },
    {
      key: "set-supervisor",
      label: "Set supervisor",
      icon: <UserCheck size={14} />,
      onClick: () => onSetSupervisor(activity),
    },
    {
      key: "create-dpr",
      label: "Create DPR",
      icon: <FilePlus2 size={14} />,
      onClick: () => onCreateDpr(activity),
    },
    ...(onOpenMasterDialog && masterStatus.state === "UNLINKED"
      ? ([
          {
            key: "add-master",
            label: "Add to Master Work Activities",
            icon: <Library size={14} />,
            onClick: () => onOpenMasterDialog(activity, "LINK_OR_CREATE"),
          },
        ] as Array<Item | Divider>)
      : []),
    ...(onOpenMasterDialog && masterStatus.state === "LINKED_NO_NORMS"
      ? ([
          {
            key: "configure-norms",
            label: "Configure Productivity Norms",
            icon: <Gauge size={14} />,
            onClick: () => onOpenMasterDialog(activity, "CONFIGURE_NORMS_ONLY"),
          },
        ] as Array<Item | Divider>)
      : []),
    { key: "d2", divider: true },
    {
      key: "start",
      label: "Start activity",
      icon: <Play size={14} />,
      onClick: () => onStart(activity),
      disabled: !canStart,
    },
    {
      key: "complete",
      label: "Mark complete",
      icon: <CheckCircle2 size={14} />,
      onClick: () => onComplete(activity),
      disabled: !canComplete,
    },
    {
      key: "edit",
      label: "Edit % complete",
      icon: <Pencil size={14} />,
      onClick: () => onEdit(activity),
    },
    ...(canDelete
      ? ([
          { key: "d3", divider: true },
          {
            key: "delete",
            label: "Delete activity",
            icon: <Trash2 size={14} />,
            onClick: () => onDelete(activity),
            danger: true,
          },
        ] as Array<Item | Divider>)
      : []),
  ];

  return createPortal(
    <div
      ref={menuRef}
      role="menu"
      style={{
        position: "fixed",
        top: clamped.y,
        left: clamped.x,
        width: MENU_WIDTH,
      }}
      className="z-[100] rounded-md border border-border bg-surface py-1 shadow-lg"
      onContextMenu={(e) => e.preventDefault()}
    >
      {items.map((item) => {
        if ("divider" in item) {
          return <div key={item.key} className="my-1 h-px bg-border/60" />;
        }
        return (
          <button
            key={item.key}
            type="button"
            disabled={item.disabled}
            onClick={() => {
              if (item.disabled) return;
              item.onClick();
              onClose();
            }}
            className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm ${
              item.disabled
                ? "cursor-not-allowed text-text-muted opacity-60"
                : item.danger
                  ? "text-danger hover:bg-danger/10"
                  : "text-text-primary hover:bg-surface-hover"
            }`}
            role="menuitem"
          >
            <span className="flex h-4 w-4 items-center justify-center text-text-secondary">
              {item.icon}
            </span>
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>,
    document.body
  );
}

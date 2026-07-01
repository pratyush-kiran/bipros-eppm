import React from "react";
import { cn } from "@/lib/utils/cn";

type Tone = "warning" | "danger" | "info" | "success";

const TONE: Record<Tone, string> = {
  warning: "border-warning/40 bg-warning/10",
  danger: "border-danger/40 bg-danger/10",
  info: "border-info/40 bg-info/10",
  success: "border-success/40 bg-success/10",
};

interface AlertBannerProps {
  tone: Tone;
  message: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}

export function AlertBanner({ tone, message, actions, className }: AlertBannerProps) {
  return (
    <div role="alert" className={cn("mb-4 rounded-lg border px-4 py-3", TONE[tone], className)}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="text-sm text-text-primary">{message}</div>
        {actions && <div className="flex gap-2">{actions}</div>}
      </div>
    </div>
  );
}

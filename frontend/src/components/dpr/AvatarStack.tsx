"use client";

import { ResourceAvatar } from "@/components/resource/supervisor-assign/ResourceAvatar";

interface Person {
  id: string;
  name: string;
}

interface Props {
  people: Person[];
  max?: number;
  /** Visual size of each avatar in the stack. Defaults to "sm". */
  size?: "sm" | "md";
  className?: string;
}

/**
 * Overlapping stack of {@link ResourceAvatar}s, with a "+N" chip when the supplied
 * list exceeds {@code max}. Uses a ring of the page surface (`ring-paper`) so each
 * avatar reads cleanly against its neighbour. Built for the Site-Ledger DPR view
 * where each activity card shows the supervisors who worked that activity today.
 */
export function AvatarStack({ people, max = 4, size = "sm", className }: Props) {
  if (people.length === 0) return null;
  const visible = people.slice(0, max);
  const overflow = Math.max(0, people.length - visible.length);
  const dim = size === "sm" ? "h-5 w-5 text-[10px]" : "h-7 w-7 text-[11px]";

  return (
    <div className={`flex items-center ${className ?? ""}`}>
      <div className="flex -space-x-1.5">
        {visible.map((p) => (
          <ResourceAvatar
            key={p.id}
            id={p.id}
            name={p.name}
            size={size}
            className="ring-2 ring-paper"
          />
        ))}
        {overflow > 0 && (
          <span
            className={`inline-flex shrink-0 items-center justify-center rounded-full bg-parchment font-semibold tracking-tight text-charcoal ring-2 ring-paper ${dim}`}
            aria-label={`${overflow} more`}
          >
            +{overflow}
          </span>
        )}
      </div>
    </div>
  );
}

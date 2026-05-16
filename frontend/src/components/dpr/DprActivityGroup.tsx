"use client";

import { useState } from "react";
import { ChevronDown, ChevronRight, Layers } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DailyProgressReportResponse } from "@/lib/types/dpr";
import { AvatarStack } from "./AvatarStack";
import { DprWorkFrontRow } from "./DprWorkFrontRow";

export interface ActivityGroup {
  /** Stable key for React. */
  key: string;
  boqItemNo: string | null;
  activityName: string;
  unit: string;
  totalQty: number;
  uniqueSupervisors: Array<{ id: string; name: string }>;
  rows: DailyProgressReportResponse[]; // one per work-front, sorted by chainage asc
}

interface Props {
  group: ActivityGroup;
  onEditRow: (row: DailyProgressReportResponse) => void;
  onDeleteRow: (row: DailyProgressReportResponse) => void;
}

const fmt = (n: number, digits = 2) =>
  n.toLocaleString(undefined, { maximumFractionDigits: digits });

/**
 * One BOQ-activity entry inside a day card. Renders an editorial "site ledger"
 * entry: gold left-rule, activity title with BOQ pill, aggregate qty, work-front
 * count, stacked supervisor avatars. Body collapses to show a list of
 * {@link DprWorkFrontRow}s (one per supervisor's chainage), default-open when
 * the activity has more than one work front (the case the user flagged).
 */
export function DprActivityGroup({ group, onEditRow, onDeleteRow }: Props) {
  const multiFront = group.rows.length > 1;
  const [open, setOpen] = useState(multiFront);

  return (
    <article
      className="overflow-hidden rounded-lg border border-hairline border-l-4 border-l-gold bg-paper shadow-sm transition-shadow hover:shadow"
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-3 px-4 py-3 text-left"
        aria-expanded={open}
      >
        <span className="flex-none text-slate">
          {open ? (
            <ChevronDown className="h-4 w-4 transition-transform" />
          ) : (
            <ChevronRight className="h-4 w-4 transition-transform" />
          )}
        </span>

        {/* Title + BOQ */}
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
          {group.boqItemNo && (
            <Badge variant="gold">BOQ {group.boqItemNo}</Badge>
          )}
          <h3 className="truncate font-display text-base font-semibold tracking-tight text-charcoal">
            {group.activityName}
          </h3>
        </div>

        {/* Avatars */}
        {group.uniqueSupervisors.length > 0 && (
          <AvatarStack people={group.uniqueSupervisors} max={4} size="sm" />
        )}

        {/* Work-front count */}
        <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-ivory/70 px-2 py-0.5 text-xs text-slate">
          <Layers className="h-3 w-3 text-gold-deep" />
          {group.rows.length} {group.rows.length === 1 ? "front" : "fronts"}
        </span>

        {/* Total qty (right-aligned) */}
        {group.totalQty > 0 && (
          <div className="flex shrink-0 items-baseline gap-1 pl-2">
            <span className="font-display text-xl font-semibold tabular-nums text-gold-ink">
              {fmt(group.totalQty)}
            </span>
            <span className="text-xs text-slate">{group.unit || "qty"}</span>
          </div>
        )}
      </button>

      {open && (
        <div className="divide-y divide-hairline border-t border-hairline">
          {group.rows.map((row, i) => (
            <DprWorkFrontRow
              key={row.id}
              row={row}
              index={i}
              total={group.rows.length}
              onEdit={() => onEditRow(row)}
              onDelete={() => onDeleteRow(row)}
            />
          ))}
        </div>
      )}
    </article>
  );
}

"use client";

import { Pencil, Trash2 } from "lucide-react";
import type { QcTestRecord } from "@/lib/types/qc";
import { Badge } from "@/components/ui/badge";
interface Props {
  record: QcTestRecord;
  onEdit: () => void;
  onDelete: () => void;
}

const OUTCOME_VARIANT = {
  PASS: "success",
  FAIL: "danger",
  REPEAT: "warning",
} as const;

export function QcTestRecordCard({ record, onEdit, onDelete }: Props) {
  return (
    <div className="rounded-lg border border-hairline bg-paper">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate font-semibold text-charcoal">{record.activityName}</span>
            <Badge variant={OUTCOME_VARIANT[record.outcome]} withDot>
              {record.outcome}
            </Badge>
            <span className="text-xs text-slate">{record.testTypeName}</span>
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate">
            <span>{record.testDate}</span>
            {record.chainage && <span>· Chainage {record.chainage}</span>}
            {record.sampleRefNo && <span>· Ref {record.sampleRefNo}</span>}
            {record.labInspector && <span>· {record.labInspector}</span>}
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            {record.testResult != null && (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-parchment px-2 py-0.5 text-xs font-semibold text-charcoal tabular-nums">
                Result: {record.testResult}
              </span>
            )}
            {record.requiredIrc != null && (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-ivory px-2 py-0.5 text-xs text-slate tabular-nums">
                IRC ≥ {record.requiredIrc}
              </span>
            )}
          </div>
        </div>
        <div className="flex flex-none items-center gap-1">
          <button
            type="button"
            onClick={onEdit}
            className="rounded-md p-1.5 text-slate hover:bg-ivory hover:text-charcoal"
            aria-label="Edit"
          >
            <Pencil className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={onDelete}
            className="rounded-md p-1.5 text-slate hover:bg-burgundy/10 hover:text-burgundy"
            aria-label="Delete"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

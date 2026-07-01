"use client";

import { CalendarDays, Pencil, Trash2 } from "lucide-react";
import type { QcSession, QcTestItemResponse } from "@/lib/types/qc";
import { cn } from "@/lib/utils/cn";

interface Props {
  sessions: QcSession[];
  onEdit: (session: QcSession) => void;
  onDelete: (session: QcSession) => void;
  onRaiseNcr?: (session: QcSession, item: QcTestItemResponse) => void;
}

const OUTCOME_CLS: Record<string, string> = {
  PASS: "bg-emerald/10 text-emerald ring-1 ring-emerald/40",
  FAIL: "bg-burgundy/10 text-burgundy ring-1 ring-burgundy/40",
  REPEAT: "bg-bronze-warn/10 text-bronze-warn ring-1 ring-bronze-warn/40",
};

function fmtDate(iso: string) {
  return new Date(iso + "T00:00:00").toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

interface FlatRow {
  sNo: number;
  session: QcSession;
  item: QcTestItemResponse;
  isFirstInSession: boolean;
  sessionRowCount: number;
}

function flatten(sessions: QcSession[]): FlatRow[] {
  const rows: FlatRow[] = [];
  let sNo = 1;
  for (const session of sessions) {
    if (session.items.length === 0) {
      // Show a placeholder row for empty sessions
      rows.push({
        sNo: sNo++,
        session,
        item: {
          id: `empty-${session.id}`,
          testTypeId: "",
          testTypeName: "—",
          sampleRefNo: null,
          testResult: null,
          requiredIrc: null,
          outcome: "PASS",
          labInspector: null,
          createdAt: session.createdAt,
        },
        isFirstInSession: true,
        sessionRowCount: 1,
      });
    } else {
      session.items.forEach((item, i) => {
        rows.push({
          sNo: sNo++,
          session,
          item,
          isFirstInSession: i === 0,
          sessionRowCount: session.items.length,
        });
      });
    }
  }
  return rows;
}

export function QcSessionGrid({ sessions, onEdit, onDelete, onRaiseNcr }: Props) {
  if (sessions.length === 0) return null;

  const rows = flatten(sessions);

  return (
    <div className="overflow-x-auto rounded-lg border border-hairline shadow-sm">
      <table className="w-full min-w-[900px] border-collapse text-sm">

        {/* ── Column headers ── */}
        <thead>
          <tr className="border-b-2 border-gold/40 bg-charcoal dark:bg-parchment text-xs font-semibold uppercase tracking-wider text-white/75 dark:text-white/60">
            <th className="w-10 px-3 py-3 text-center text-white/40 dark:text-white/30">S.No</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 180 }}>Activity</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 130 }}>Test Date</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 100 }}>CH. From</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 100 }}>CH. To</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 190 }}>Test Type</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 120 }}>Sample / Test Ref.</th>
            <th className="px-3 py-3 text-right" style={{ minWidth: 90 }}>Result</th>
            <th className="px-3 py-3 text-right" style={{ minWidth: 110 }}>OHDS Spec</th>
            <th className="px-3 py-3 text-center" style={{ minWidth: 90 }}>Outcome</th>
            <th className="px-3 py-3 text-left" style={{ minWidth: 150 }}>Lab / Inspector</th>
            <th className="sticky right-0 w-16 bg-charcoal dark:bg-parchment px-3 py-3 shadow-[-4px_0_8px_rgba(0,0,0,0.15)]" />
          </tr>
        </thead>

        <tbody>
          {rows.map((row, idx) => {
            const { sNo, session, item, isFirstInSession } = row;
            const isEven = idx % 2 === 0;

            // Subtle group separator above first row of each new session (except the very first)
            const isGroupStart = isFirstInSession && idx > 0;

            return (
              <tr
                key={item.id}
                className={cn(
                  "border-b border-hairline/60 transition-colors hover:bg-gold/5",
                  isEven ? "bg-paper" : "bg-ivory/50",
                  isGroupStart && "border-t-2 border-gold/20"
                )}
              >
                {/* S.No */}
                <td className="px-3 py-2.5 text-center">
                  <span className="font-mono text-xs text-ash">{sNo}</span>
                </td>

                {/* Activity — only on first item */}
                <td className="px-3 py-2.5">
                  {isFirstInSession ? (
                    <span className="font-semibold text-charcoal">{session.activityName}</span>
                  ) : null}
                </td>

                {/* Test Date — badge, only on first item of session */}
                <td className="px-3 py-2.5">
                  {isFirstInSession ? (
                    <span className="inline-flex items-center gap-1 rounded-full border border-gold/40 bg-gold/10 px-2 py-0.5 text-xs font-medium text-gold-deep whitespace-nowrap">
                      <CalendarDays className="h-3 w-3 shrink-0" />
                      {fmtDate(session.testDate)}
                    </span>
                  ) : (
                    <span className="ml-1 text-xs text-ash/60">↳</span>
                  )}
                </td>

                {/* Chainage From — only on first item */}
                <td className="px-3 py-2.5">
                  {isFirstInSession ? (
                    <span className="font-mono text-xs text-charcoal">
                      {session.chainageFrom ?? "—"}
                    </span>
                  ) : null}
                </td>

                {/* Chainage To — only on first item */}
                <td className="px-3 py-2.5">
                  {isFirstInSession ? (
                    <span className="font-mono text-xs text-charcoal">
                      {session.chainageTo ?? "—"}
                    </span>
                  ) : null}
                </td>

                {/* Test Type */}
                <td className="px-3 py-2.5 font-medium text-charcoal">
                  {item.testTypeName}
                </td>

                {/* Sample Ref */}
                <td className="px-3 py-2.5 text-slate">
                  {item.sampleRefNo ?? "—"}
                </td>

                {/* Result */}
                <td className={cn(
                  "px-3 py-2.5 text-right tabular-nums font-semibold",
                  item.testResult != null && item.requiredIrc != null
                    ? item.testResult >= item.requiredIrc
                      ? "text-emerald"
                      : "text-burgundy"
                    : "text-charcoal"
                )}>
                  {item.testResult ?? "—"}
                </td>

                {/* IRC Spec */}
                <td className="px-3 py-2.5 text-right tabular-nums text-slate">
                  {item.requiredIrc ?? "—"}
                </td>

                {/* Outcome */}
                <td className="px-3 py-2.5 text-center">
                  <span className={cn(
                    "inline-block rounded px-2 py-0.5 text-xs font-bold tracking-wide",
                    OUTCOME_CLS[item.outcome] ?? "bg-hairline text-slate"
                  )}>
                    {item.outcome}
                  </span>
                  {item.outcome === "FAIL" && onRaiseNcr && (
                    <button
                      type="button"
                      onClick={() => onRaiseNcr(session, item)}
                      className="ml-2 rounded px-1.5 py-0.5 text-[11px] font-semibold text-burgundy ring-1 ring-burgundy/40 hover:bg-burgundy/10"
                    >
                      Raise NCR
                    </button>
                  )}
                </td>

                {/* Lab / Inspector */}
                <td className="px-3 py-2.5 text-slate">
                  {item.labInspector ?? "—"}
                </td>

                {/* Edit / Delete — sticky right, only on first item row of each session */}
                <td className={cn(
                  "sticky right-0 px-2 py-2.5 shadow-[-4px_0_8px_rgba(0,0,0,0.06)]",
                  isEven ? "bg-paper" : "bg-ivory/50"
                )}>
                  {isFirstInSession && (
                    <div className="flex items-center justify-end gap-0.5">
                      <button
                        type="button"
                        onClick={() => onEdit(session)}
                        className="rounded p-1.5 text-ash hover:bg-ivory hover:text-charcoal transition"
                        aria-label="Edit session"
                      >
                        <Pencil className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => onDelete(session)}
                        className="rounded p-1.5 text-ash hover:bg-burgundy/10 hover:text-burgundy transition"
                        aria-label="Delete session"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>

        {/* Summary footer */}
        {rows.length > 1 && (() => {
          const allItems = sessions.flatMap((s) => s.items);
          const pass = allItems.filter((i) => i.outcome === "PASS").length;
          const fail = allItems.filter((i) => i.outcome === "FAIL").length;
          const repeat = allItems.filter((i) => i.outcome === "REPEAT").length;
          return (
            <tfoot>
              <tr className="border-t-2 border-gold/30 bg-parchment/60 text-xs font-semibold text-slate">
                <td colSpan={5} className="px-4 py-2">
                  {sessions.length} session{sessions.length === 1 ? "" : "s"} · {allItems.length} tests
                </td>
                <td colSpan={2} />
                <td className="px-3 py-2 text-right tabular-nums text-charcoal">
                  {allItems.some((i) => i.testResult != null)
                    ? (
                        allItems.reduce((s, i) => s + (i.testResult ?? 0), 0) /
                        allItems.filter((i) => i.testResult != null).length
                      ).toFixed(2)
                    : "—"}
                  <span className="ml-1 font-normal text-ash">avg</span>
                </td>
                <td />
                <td className="px-3 py-2 text-center">
                  <span className="text-emerald">{pass}P</span>
                  {" · "}
                  <span className="text-burgundy">{fail}F</span>
                  {" · "}
                  <span className="text-bronze-warn">{repeat}R</span>
                </td>
                <td className="sticky right-0 bg-parchment/60" />
              </tr>
            </tfoot>
          );
        })()}
      </table>
    </div>
  );
}

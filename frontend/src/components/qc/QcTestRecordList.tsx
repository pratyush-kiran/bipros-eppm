"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { qcApi } from "@/lib/api/qcApi";
import type { QcOutcome, QcSession, QcTestType } from "@/lib/types/qc";
import { QcSessionGrid } from "./QcSessionGrid";
import { QcSessionForm } from "./QcSessionForm";
import { EmptyState } from "@/components/common/EmptyState";
import { ListSkeleton } from "@/components/common/Skeleton";
import type { SelectOption } from "@/components/common/SearchableSelect";
import { getErrorMessage } from "@/lib/utils/error";
import { RaiseNcrDialog, type RaiseNcrPrefill } from "@/components/quality/RaiseNcrDialog";

interface Props {
  projectId: string;
  activityOptions: SelectOption[];
  testTypeOptions: QcTestType[];
}

export function QcTestRecordList({ projectId, activityOptions, testTypeOptions }: Props) {
  const queryClient = useQueryClient();
  const [activityFilter, setActivityFilter] = useState("");
  const [outcomeFilter, setOutcomeFilter] = useState<QcOutcome | "">("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<QcSession | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const [ncrPrefill, setNcrPrefill] = useState<RaiseNcrPrefill | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["qc-sessions", projectId, activityFilter, outcomeFilter, from, to],
    queryFn: () =>
      qcApi.listSessions(projectId, {
        activityId: activityFilter || undefined,
        outcome: outcomeFilter || undefined,
        from: from || undefined,
        to: to || undefined,
      }),
  });

  const sessions = Array.isArray(data?.data) ? data.data : [];

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["qc-sessions", projectId] });
    queryClient.invalidateQueries({ queryKey: ["qc-dashboard", projectId] });
  };

  const openNew = () => { setEditing(null); setShowForm(true); setPageError(null); };
  const openEdit = (s: QcSession) => { setEditing(s); setShowForm(true); setPageError(null); };
  const closeForm = () => { setShowForm(false); setEditing(null); };

  const handleSave = async (req: Parameters<typeof qcApi.createSession>[1]) => {
    if (editing) {
      await qcApi.updateSession(projectId, editing.id, req);
    } else {
      await qcApi.createSession(projectId, req);
    }
    invalidate();
  };

  const handleDelete = async (s: QcSession) => {
    if (!confirm(`Delete QC session for "${s.activityName}" on ${s.testDate}? This removes all ${s.items.length} test item(s).`)) return;
    try {
      await qcApi.deleteSession(projectId, s.id);
      invalidate();
    } catch (err: unknown) {
      setPageError(getErrorMessage(err, "Failed to delete session"));
    }
  };

  const filterCls = "rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40";

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">Activity</label>
            <select value={activityFilter} onChange={(e) => setActivityFilter(e.target.value)} className={filterCls}>
              <option value="">All activities</option>
              {activityOptions.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">Outcome</label>
            <select value={outcomeFilter} onChange={(e) => setOutcomeFilter(e.target.value as QcOutcome | "")} className={filterCls}>
              <option value="">All</option>
              <option value="PASS">PASS</option>
              <option value="FAIL">FAIL</option>
              <option value="REPEAT">REPEAT</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">From</label>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className={filterCls} />
          </div>
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">To</label>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className={filterCls} />
          </div>
        </div>
        <button
          onClick={openNew}
          className="inline-flex items-center gap-1.5 rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep transition"
        >
          <Plus className="h-4 w-4" /> New Session
        </button>
      </div>

      {pageError && <div className="text-sm text-burgundy">{pageError}</div>}

      {isLoading ? (
        <ListSkeleton items={3} />
      ) : sessions.length === 0 ? (
        <EmptyState
          title="No QC sessions"
          description="No quality control sessions match the current filters. Create a session to log test results against an activity."
        />
      ) : (
        <QcSessionGrid
          sessions={sessions}
          onEdit={openEdit}
          onDelete={handleDelete}
          onRaiseNcr={(session, item) =>
            setNcrPrefill({
              title: `QC FAIL: ${item.testTypeName} @ ${session.chainageFrom ?? ""}`,
              description: `Sample ${item.sampleRefNo ?? "—"} result ${item.testResult ?? "—"} vs spec ${item.requiredIrc ?? "—"} (${session.activityName}, ${session.testDate}).`,
              activityId: session.activityId,
              sourceRefId: item.id,
            })
          }
        />
      )}

      <QcSessionForm
        open={showForm}
        onClose={closeForm}
        projectId={projectId}
        editing={editing}
        activityOptions={activityOptions}
        testTypeOptions={testTypeOptions}
        onSave={handleSave}
      />

      {ncrPrefill && (
        <RaiseNcrDialog projectId={projectId} prefill={ncrPrefill} onClose={() => setNcrPrefill(null)} />
      )}
    </div>
  );
}

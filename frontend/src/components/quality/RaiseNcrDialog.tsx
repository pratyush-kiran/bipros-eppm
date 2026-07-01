"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { ncrApi, type NcrSeverity } from "@/lib/api/ncrApi";
import { getErrorMessage } from "@/lib/utils/error";

export interface RaiseNcrPrefill {
  title: string;
  description: string;
  activityId: string;
  sourceRefId: string;
}

interface Props {
  projectId: string;
  prefill: RaiseNcrPrefill;
  onClose: () => void;
  onCreated?: () => void;
}

export function RaiseNcrDialog({ projectId, prefill, onClose, onCreated }: Props) {
  const [title, setTitle] = useState(prefill.title);
  const [description, setDescription] = useState(prefill.description);
  const [severity, setSeverity] = useState<NcrSeverity>("MEDIUM");

  const mutation = useMutation({
    mutationFn: () =>
      ncrApi.create(projectId, {
        title,
        description,
        category: "QUALITY",
        severity,
        sourceType: "QC_TEST_FAIL",
        sourceRefId: prefill.sourceRefId,
        activityId: prefill.activityId,
      }),
    onSuccess: () => {
      toast.success("NCR raised");
      onCreated?.();
      onClose();
    },
    onError: (err: unknown) => toast.error(getErrorMessage(err, "Failed to raise NCR")),
  });

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/30" onClick={onClose}>
      <div className="h-full w-full max-w-md overflow-y-auto bg-surface p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <h2 className="mb-4 text-lg font-bold text-text-primary">Raise NCR from failed test</h2>
        <div className="space-y-3">
          <input value={title} onChange={(e) => setTitle(e.target.value)} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={4} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary" />
          <select value={severity} onChange={(e) => setSeverity(e.target.value as NcrSeverity)} className="w-full rounded-lg border border-border bg-surface-hover px-3 py-2 text-text-primary">
            {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <div className="flex gap-2">
            <button type="button" disabled={mutation.isPending} onClick={() => mutation.mutate()} className="flex-1 rounded-lg bg-accent px-4 py-2 font-medium text-accent-foreground">Raise NCR</button>
            <button type="button" onClick={onClose} className="flex-1 rounded-lg border border-border bg-surface-hover px-4 py-2 text-text-secondary">Cancel</button>
          </div>
        </div>
      </div>
    </div>
  );
}

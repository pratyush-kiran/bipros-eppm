"use client";

import React, { useRef, useState, useMemo } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import {
  Sparkles,
  RefreshCw,
  Loader2,
  AlertCircle,
  CheckCircle,
  XCircle,
  FileText,
  Upload,
  X,
} from "lucide-react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogBody, DialogFooter } from "@/components/ui/dialog";
import { SimpleTable } from "@/components/common/SimpleTable";
import { aiApi } from "@/lib/api/aiApi";
import { getErrorMessage } from "@/lib/utils/error";
import toast from "react-hot-toast";
import type {
  ActivityAiNode,
  ActivityAiGenerationResponse,
  ActivityAiApplyResponse,
  CollisionAction,
  CollisionResult,
} from "@/lib/types";

interface ActivityAiGenerateDialogProps {
  open: boolean;
  onClose: () => void;
  projectId: string;
}

type Phase = "setup" | "generating" | "preview" | "report";
type SetupTab = "scratch" | "document";

const ALLOWED_DOC_EXTS = [".pdf", ".xlsx", ".xls"];
const MAX_DOC_BYTES = 50 * 1024 * 1024;

export function ActivityAiGenerateDialog({ open, onClose, projectId }: ActivityAiGenerateDialogProps) {
  const queryClient = useQueryClient();

  const [phase, setPhase] = useState<Phase>("setup");
  const [setupTab, setSetupTab] = useState<SetupTab>("document");
  const [projectTypeHint, setProjectTypeHint] = useState("");
  const [additionalContext, setAdditionalContext] = useState("");
  const [targetActivityCount, setTargetActivityCount] = useState(15);
  const [defaultDurationDays, setDefaultDurationDays] = useState(5);
  const [docFile, setDocFile] = useState<File | null>(null);
  const [docTargetCount, setDocTargetCount] = useState(15);
  const [docDefaultDuration, setDocDefaultDuration] = useState(5);
  const [generationResult, setGenerationResult] = useState<ActivityAiGenerationResponse | null>(null);
  const [applyResult, setApplyResult] = useState<ActivityAiApplyResponse | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);
  /**
   * Original-WBS-code → confirmed-existing-WBS-code map. Populated when the
   * user accepts a WBS_NEAR_MATCH suggestion in the preview. Sent on apply
   * so the backend uses the resolved code instead of skipping the activity.
   */
  const [wbsRemap, setWbsRemap] = useState<Record<string, string>>({});

  const generateMutation = useMutation({
    mutationFn: () =>
      aiApi.generateActivities(projectId, {
        projectTypeHint: projectTypeHint || undefined,
        additionalContext: additionalContext || undefined,
        targetActivityCount,
        defaultDurationDays,
      }),
    onSuccess: (data) => {
      if (data.data) {
        setGenerationResult(data.data);
        setPhase("preview");
      }
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to generate activities");
      if (msg.includes("WBS_REQUIRED") || msg.includes("Project has no WBS")) {
        toast.error("Please create a WBS for this project before generating activities.");
      } else {
        toast.error(msg);
      }
      setPhase("setup");
    },
  });

  const generateFromDocumentMutation = useMutation({
    mutationFn: () => {
      if (!docFile) return Promise.reject(new Error("No file selected"));
      return aiApi.generateActivitiesFromDocument(
        projectId,
        { targetActivityCount: docTargetCount, defaultDurationDays: docDefaultDuration },
        docFile,
      );
    },
    onSuccess: (data) => {
      if (data.data) {
        setGenerationResult(data.data);
        setPhase("preview");
      }
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to generate activities from document"));
      setPhase("setup");
    },
  });

  const applyMutation = useMutation({
    mutationFn: () =>
      aiApi.applyGeneratedActivities(projectId, {
        activities: generationResult?.activities ?? [],
        wbsRemap: Object.keys(wbsRemap).length > 0 ? wbsRemap : undefined,
        // from-scratch batches are compressed to fit the project window on the backend
        fromScratch: setupTab === "scratch",
      }),
    onSuccess: (data) => {
      if (data.data) {
        setApplyResult(data.data);
        setPhase("report");
        queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
        queryClient.invalidateQueries({ queryKey: ["relationships", projectId] });
        queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
        const created = data.data.createdActivityIds.length;
        toast.success(`${created} activities created successfully`);
      }
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to apply activities");
      setApplyError(msg);
      toast.error(msg);
    },
  });

  const handleClose = () => {
    setPhase("setup");
    setSetupTab("document");
    setGenerationResult(null);
    setApplyResult(null);
    setApplyError(null);
    setDocFile(null);
    setWbsRemap({});
    onClose();
  };

  const handleGenerate = () => {
    setPhase("generating");
    generateMutation.mutate();
  };

  const handleGenerateFromDocument = () => {
    if (!docFile) {
      toast.error("Please choose a PDF or Excel file");
      return;
    }
    setPhase("generating");
    generateFromDocumentMutation.mutate();
  };

  const handleRegenerate = () => {
    setPhase("setup");
    setGenerationResult(null);
    setWbsRemap({});
  };

  // Compute apply-button state from preview annotations: how many activities
  // would be skipped (MISSING_WBS_NODE not yet remapped + SKIPPED_DUPLICATE)?
  const applyState = React.useMemo(() => {
    const annotations = generationResult?.previewAnnotations ?? [];
    let missing = 0;
    let skippedDup = 0;
    for (const a of annotations) {
      if (a.action === "SKIPPED_DUPLICATE") skippedDup++;
      if (a.action === "MISSING_WBS_NODE") {
        // Skip in count if the user has already remapped this activity's WBS.
        // We need the activity's wbsNodeCode; look it up from the generated
        // list keyed by activity code.
        const act = generationResult?.activities.find((x) => x.code === a.originalCode);
        if (!act || !wbsRemap[act.wbsNodeCode]) missing++;
      }
    }
    return { missing, skippedDup };
  }, [generationResult, wbsRemap]);

  return (
    <Dialog open={open} onOpenChange={(v) => !v && handleClose()}>
      <DialogContent className="max-w-3xl max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Sparkles size={20} className="text-gold-deep" />
            Generate Activities with AI
          </DialogTitle>
        </DialogHeader>

        <DialogBody className="flex-1 overflow-y-auto">
          {phase === "setup" && (
            <div className="space-y-4">
              <SetupTabs activeTab={setupTab} onChange={setSetupTab} />
              {setupTab === "scratch" ? (
                <SetupFromScratch
                  projectTypeHint={projectTypeHint}
                  setProjectTypeHint={setProjectTypeHint}
                  additionalContext={additionalContext}
                  setAdditionalContext={setAdditionalContext}
                  targetActivityCount={targetActivityCount}
                  setTargetActivityCount={setTargetActivityCount}
                  defaultDurationDays={defaultDurationDays}
                  setDefaultDurationDays={setDefaultDurationDays}
                />
              ) : (
                <SetupFromDocument
                  file={docFile}
                  setFile={setDocFile}
                  targetActivityCount={docTargetCount}
                  setTargetActivityCount={setDocTargetCount}
                  defaultDurationDays={docDefaultDuration}
                  setDefaultDurationDays={setDocDefaultDuration}
                />
              )}
            </div>
          )}

          {phase === "generating" && (
            <GeneratingPhase fromDocument={generateFromDocumentMutation.isPending} />
          )}

          {phase === "preview" && generationResult && (
            <>
              {applyError && (
                <div className="mx-4 mt-3 rounded-md border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700">
                  <strong>Apply failed:</strong> {applyError}
                </div>
              )}
              <PreviewPhase
                rationale={generationResult.rationale}
                activities={generationResult.activities}
                annotations={generationResult.previewAnnotations ?? null}
                wbsRemap={wbsRemap}
                onAcceptNearMatch={(originalWbsCode, suggestedCode) =>
                  setWbsRemap((prev) => ({ ...prev, [originalWbsCode]: suggestedCode }))
                }
                onRejectNearMatch={(originalWbsCode) =>
                  setWbsRemap((prev) => {
                    const next = { ...prev };
                    delete next[originalWbsCode];
                    return next;
                  })
                }
              />
            </>
          )}

          {phase === "report" && applyResult && <ReportPhase result={applyResult} />}
        </DialogBody>

        <DialogFooter>
          {phase === "setup" && (
            <>
              <button
                onClick={handleClose}
                className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
              >
                Cancel
              </button>
              {setupTab === "scratch" ? (
                <button
                  onClick={handleGenerate}
                  disabled={generateMutation.isPending}
                  className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
                >
                  <Sparkles size={16} />
                  Generate
                </button>
              ) : (
                <button
                  onClick={handleGenerateFromDocument}
                  disabled={!docFile || generateFromDocumentMutation.isPending}
                  className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
                >
                  <Sparkles size={16} />
                  Generate from document
                </button>
              )}
            </>
          )}

          {phase === "generating" && (
            <button
              onClick={handleClose}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
          )}

          {phase === "preview" && (
            <>
              <button
                onClick={handleRegenerate}
                className="inline-flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
              >
                <RefreshCw size={16} />
                Regenerate
              </button>
              <button
                onClick={() => { setApplyError(null); applyMutation.mutate(); }}
                disabled={applyMutation.isPending}
                className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                {applyMutation.isPending
                  ? "Applying..."
                  : applyState.missing + applyState.skippedDup > 0
                    ? `Apply (${applyState.missing + applyState.skippedDup} will be skipped)`
                    : "Apply to Project"}
              </button>
            </>
          )}

          {phase === "report" && (
            <button
              onClick={handleClose}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
            >
              Done
            </button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function SetupTabs({
  activeTab,
  onChange,
}: {
  activeTab: SetupTab;
  onChange: (tab: SetupTab) => void;
}) {
  const tabClass = (active: boolean) =>
    `inline-flex items-center gap-2 px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
      active
        ? "border-accent text-text-primary"
        : "border-transparent text-text-secondary hover:text-text-primary"
    }`;
  return (
    <div className="flex border-b border-border">
      <button type="button" onClick={() => onChange("scratch")} className={tabClass(activeTab === "scratch")}>
        <Sparkles size={14} />
        From scratch
      </button>
      <button type="button" onClick={() => onChange("document")} className={tabClass(activeTab === "document")}>
        <FileText size={14} />
        From document
      </button>
    </div>
  );
}

function SetupFromDocument({
  file,
  setFile,
  targetActivityCount,
  setTargetActivityCount,
  defaultDurationDays,
  setDefaultDurationDays,
}: {
  file: File | null;
  setFile: (f: File | null) => void;
  targetActivityCount: number;
  setTargetActivityCount: (v: number) => void;
  defaultDurationDays: number;
  setDefaultDurationDays: (v: number) => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragOver, setDragOver] = useState(false);

  const inputClass =
    "mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent";

  const validateAndSet = (chosen: File) => {
    const lower = chosen.name.toLowerCase();
    if (!ALLOWED_DOC_EXTS.some((ext) => lower.endsWith(ext))) {
      toast.error("Only PDF or Excel (.xlsx, .xls) files are allowed");
      return;
    }
    if (chosen.size > MAX_DOC_BYTES) {
      toast.error("File exceeds the 50 MB limit");
      return;
    }
    setFile(chosen);
  };

  const onPick = () => inputRef.current?.click();
  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const chosen = e.target.files?.[0];
    if (chosen) validateAndSet(chosen);
    e.target.value = "";
  };
  const onDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(false);
    const chosen = e.dataTransfer.files?.[0];
    if (chosen) validateAndSet(chosen);
  };

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-text-secondary">Document</label>
        <p className="mt-1 text-xs text-text-muted">
          Upload a PDF or Excel file describing the project&apos;s activity list / schedule. Max 50 MB.
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.xlsx,.xls,application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
          className="hidden"
          onChange={onChange}
        />
        {file ? (
          <div className="mt-2 flex items-center gap-3 rounded-md border border-border bg-surface-hover/40 px-3 py-2">
            <FileText size={18} className="text-accent flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="truncate text-sm text-text-primary">{file.name}</p>
              <p className="text-xs text-text-muted">{(file.size / 1024).toFixed(1)} KB</p>
            </div>
            <button
              type="button"
              onClick={() => setFile(null)}
              className="rounded p-1 text-text-secondary hover:bg-surface-active hover:text-text-primary"
              aria-label="Remove file"
            >
              <X size={14} />
            </button>
          </div>
        ) : (
          <div
            onClick={onPick}
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
            className={`mt-2 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-md border-2 border-dashed px-4 py-8 text-center transition-colors ${
              dragOver
                ? "border-accent bg-surface-hover/60"
                : "border-border bg-surface-hover/20 hover:bg-surface-hover/40"
            }`}
          >
            <Upload size={22} className="text-text-secondary" />
            <p className="text-sm text-text-primary">Drop a file here or click to browse</p>
            <p className="text-xs text-text-muted">PDF, .xlsx, .xls — up to 50 MB</p>
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-text-secondary">Target Activity Count</label>
          <input
            type="number"
            min={5}
            max={500}
            value={targetActivityCount}
            onChange={(e) => setTargetActivityCount(Number(e.target.value))}
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-text-secondary">Default Duration (days)</label>
          <input
            type="number"
            min={1}
            max={365}
            value={defaultDurationDays}
            onChange={(e) => setDefaultDurationDays(Number(e.target.value))}
            className={inputClass}
          />
        </div>
      </div>
    </div>
  );
}

function SetupFromScratch({
  projectTypeHint,
  setProjectTypeHint,
  additionalContext,
  setAdditionalContext,
  targetActivityCount,
  setTargetActivityCount,
  defaultDurationDays,
  setDefaultDurationDays,
}: {
  projectTypeHint: string;
  setProjectTypeHint: (v: string) => void;
  additionalContext: string;
  setAdditionalContext: (v: string) => void;
  targetActivityCount: number;
  setTargetActivityCount: (v: number) => void;
  defaultDurationDays: number;
  setDefaultDurationDays: (v: number) => void;
}) {
  const inputClass =
    "mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent";

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-text-secondary">
          Describe your project type <span className="text-text-muted">(optional)</span>
        </label>
        <input
          type="text"
          value={projectTypeHint}
          onChange={(e) => setProjectTypeHint(e.target.value)}
          placeholder="e.g., Highway widening, 12 km, 4-lane"
          className={inputClass}
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-text-secondary">
          Additional context <span className="text-text-muted">(optional)</span>
        </label>
        <textarea
          value={additionalContext}
          onChange={(e) => setAdditionalContext(e.target.value)}
          placeholder="Special phases, regulatory constraints, soil conditions..."
          rows={3}
          className={inputClass}
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-text-secondary">Target Activity Count</label>
          <input
            type="number"
            min={5}
            max={100}
            value={targetActivityCount}
            onChange={(e) => setTargetActivityCount(Number(e.target.value))}
            className={inputClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-text-secondary">Default Duration (days)</label>
          <input
            type="number"
            min={1}
            max={365}
            value={defaultDurationDays}
            onChange={(e) => setDefaultDurationDays(Number(e.target.value))}
            className={inputClass}
          />
        </div>
      </div>
    </div>
  );
}

function GeneratingPhase({ fromDocument }: { fromDocument: boolean }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 gap-4">
      <Loader2 size={40} className="animate-spin text-accent" />
      <div className="text-center">
        <p className="text-sm font-medium text-text-primary">
          {fromDocument
            ? "Reading document and extracting activities..."
            : "Generating activities..."}
        </p>
        <p className="mt-1 text-xs text-text-muted">This may take 30-90 seconds</p>
      </div>
    </div>
  );
}

function actionTag(action: CollisionAction): { label: string; className: string } | null {
  switch (action) {
    case "INSERTED_NEW":
      return { label: "NEW", className: "bg-emerald-500/15 text-emerald-300" };
    case "RENAMED":
      return { label: "RENAMED", className: "bg-amber-500/15 text-amber-300" };
    case "SKIPPED_DUPLICATE":
      return { label: "SKIPPED", className: "bg-slate-500/15 text-slate-300" };
    default:
      return null;
  }
}

function PreviewPhase({
  rationale,
  activities,
  annotations,
  wbsRemap,
  onAcceptNearMatch,
  onRejectNearMatch,
}: {
  rationale: string;
  activities: ActivityAiNode[];
  annotations: CollisionResult[] | null;
  wbsRemap: Record<string, string>;
  onAcceptNearMatch: (originalWbsCode: string, suggestedCode: string) => void;
  onRejectNearMatch: (originalWbsCode: string) => void;
}) {
  // Code-collision annotations (NEW / RENAMED / SKIPPED_DUPLICATE) keyed by activity code.
  const codeAnnotByCode = React.useMemo(() => {
    const m = new Map<string, CollisionResult>();
    (annotations ?? []).forEach((a) => {
      if (a.action === "INSERTED_NEW" || a.action === "RENAMED" || a.action === "SKIPPED_DUPLICATE") {
        m.set(a.originalCode, a);
      }
    });
    return m;
  }, [annotations]);
  // WBS-resolution annotations (MISSING_WBS_NODE / WBS_NEAR_MATCH) keyed by activity code.
  const wbsAnnotByCode = React.useMemo(() => {
    const m = new Map<string, CollisionResult>();
    (annotations ?? []).forEach((a) => {
      if (a.action === "MISSING_WBS_NODE" || a.action === "WBS_NEAR_MATCH") {
        m.set(a.originalCode, a);
      }
    });
    return m;
  }, [annotations]);
  const counts = React.useMemo(() => {
    const c: Record<string, number> = {
      INSERTED_NEW: 0, RENAMED: 0, SKIPPED_DUPLICATE: 0,
      MISSING_WBS_NODE: 0, WBS_NEAR_MATCH: 0,
    };
    (annotations ?? []).forEach((a) => {
      c[a.action] = (c[a.action] ?? 0) + 1;
    });
    return c;
  }, [annotations]);
  // How many MISSING_WBS_NODE entries the user has not yet remapped.
  const unresolvedMissing = React.useMemo(() => {
    let n = 0;
    (annotations ?? []).forEach((a) => {
      if (a.action !== "MISSING_WBS_NODE") return;
      const act = activities.find((x) => x.code === a.originalCode);
      if (act && !wbsRemap[act.wbsNodeCode]) n++;
    });
    return n;
  }, [annotations, activities, wbsRemap]);

  const columns = useMemo<ColumnDef<ActivityAiNode>[]>(
    () => [
      {
        header: "Code",
        accessorKey: "code",
        cell: ({ getValue }) => (
          <span className="font-mono text-accent whitespace-nowrap">{String(getValue())}</span>
        ),
      },
      {
        header: "Name",
        accessorKey: "name",
        cell: ({ getValue }) => (
          <span className="text-text-primary">{String(getValue())}</span>
        ),
      },
      {
        header: "WBS",
        accessorKey: "wbsNodeCode",
        cell: ({ row }) => {
          const a = row.original;
          const wbsAnnot = wbsAnnotByCode.get(a.code);
          const accepted = wbsAnnot?.action === "WBS_NEAR_MATCH"
            && wbsRemap[a.wbsNodeCode] === wbsAnnot.resolvedCode;
          return (
            <span className="font-mono whitespace-nowrap">
              {wbsAnnot?.action === "MISSING_WBS_NODE" ? (
                <span className="text-rose-300" title={wbsAnnot.reason ?? ""}>
                  {a.wbsNodeCode}
                </span>
              ) : wbsAnnot?.action === "WBS_NEAR_MATCH" && wbsAnnot.resolvedCode ? (
                <span className="flex items-center gap-2">
                  {accepted ? (
                    <>
                      <span className="text-text-secondary line-through">{a.wbsNodeCode}</span>
                      <span className="text-sky-300">\u2192 {wbsAnnot.resolvedCode}</span>
                      <button
                        type="button"
                        className="text-xs text-text-muted hover:text-text-primary underline"
                        onClick={() => onRejectNearMatch(a.wbsNodeCode)}
                      >
                        undo
                      </button>
                    </>
                  ) : (
                    <>
                      <span className="text-text-secondary">{a.wbsNodeCode}</span>
                      <button
                        type="button"
                        className="text-xs text-sky-300 hover:text-sky-200 underline"
                        title={wbsAnnot.reason ?? ""}
                        onClick={() => onAcceptNearMatch(a.wbsNodeCode, wbsAnnot.resolvedCode!)}
                      >
                        use {wbsAnnot.resolvedCode}
                      </button>
                    </>
                  )}
                </span>
              ) : (
                <span className="text-text-secondary">{a.wbsNodeCode}</span>
              )}
            </span>
          );
        },
      },
      {
        header: "Days",
        accessorKey: "originalDurationDays",
        cell: ({ getValue }) => (
          <span className="text-text-secondary whitespace-nowrap">{Number(getValue())}</span>
        ),
      },
      {
        header: "Predecessors",
        accessorKey: "predecessors",
        cell: ({ getValue }) => {
          const preds = getValue() as Array<{ code: string; lagDays: number; type?: string | null }>;
          if (!preds || preds.length === 0) return <span className="text-text-muted text-xs">&mdash;</span>;
          const labels = preds.map(p => p.lagDays > 0 ? `${p.code}+${p.lagDays}d` : p.code);
          return (
            <span className="text-text-muted text-xs">
              {labels.join(", ")}
            </span>
          );
        },
      },
      {
        header: "",
        id: "tags",
        cell: ({ row }) => {
          const a = row.original;
          const codeAnnot = codeAnnotByCode.get(a.code);
          const codeTag = codeAnnot ? actionTag(codeAnnot.action) : null;
          const wbsAnnot = wbsAnnotByCode.get(a.code);
          return (
            <span className="whitespace-nowrap space-x-1">
              {codeTag && (
                <span
                  title={codeAnnot?.reason ?? ""}
                  className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${codeTag.className}`}
                >
                  {codeTag.label}
                  {codeAnnot?.action === "RENAMED" && codeAnnot.resolvedCode !== codeAnnot.originalCode
                    ? ` \u2192 ${codeAnnot.resolvedCode}`
                    : ""}
                </span>
              )}
              {wbsAnnot?.action === "MISSING_WBS_NODE" && (
                <span
                  title={wbsAnnot.reason ?? ""}
                  className="rounded px-1.5 py-0.5 text-[10px] font-medium bg-rose-500/15 text-rose-300"
                >
                  MISSING WBS
                </span>
              )}
            </span>
          );
        },
      },
    ],
    [wbsAnnotByCode, codeAnnotByCode, wbsRemap, onAcceptNearMatch, onRejectNearMatch]
  );

  return (
    <div className="space-y-4">
      {rationale && (
        <div className="rounded-lg border border-border bg-surface-hover/30 p-3">
          <p className="text-xs font-medium text-text-secondary mb-1">AI Rationale</p>
          <p className="text-sm text-text-primary">{rationale}</p>
        </div>
      )}
      {annotations && annotations.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          {counts.INSERTED_NEW > 0 && (
            <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-emerald-300">
              {counts.INSERTED_NEW} new
            </span>
          )}
          {counts.RENAMED > 0 && (
            <span className="rounded-full bg-amber-500/15 px-2 py-0.5 text-amber-300">
              {counts.RENAMED} renamed
            </span>
          )}
          {counts.SKIPPED_DUPLICATE > 0 && (
            <span className="rounded-full bg-slate-500/15 px-2 py-0.5 text-slate-300">
              {counts.SKIPPED_DUPLICATE} skipped (duplicates)
            </span>
          )}
          {counts.WBS_NEAR_MATCH > 0 && (
            <span className="rounded-full bg-sky-500/15 px-2 py-0.5 text-sky-300">
              {counts.WBS_NEAR_MATCH} WBS near-match
            </span>
          )}
          {counts.MISSING_WBS_NODE > 0 && (
            <span className="rounded-full bg-rose-500/15 px-2 py-0.5 text-rose-300">
              {counts.MISSING_WBS_NODE} missing WBS
            </span>
          )}
        </div>
      )}
      {unresolvedMissing > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-rose-500/30 bg-rose-500/10 p-3 text-sm">
          <AlertCircle size={16} className="mt-0.5 flex-shrink-0 text-rose-300" />
          <div>
            <p className="font-medium text-rose-200">
              {unresolvedMissing} {unresolvedMissing === 1 ? "activity references" : "activities reference"} a WBS code
              that doesn&apos;t exist in this project.
            </p>
            <p className="mt-0.5 text-xs text-rose-300/80">
              These will be skipped on Apply. Accept any near-match suggestions below, regenerate, or create the
              missing WBS nodes first.
            </p>
          </div>
        </div>
      )}
      <div>
        <p className="text-xs font-medium text-text-secondary mb-2">
          Generated Activities ({activities.length})
        </p>
        <div className="rounded-lg border border-border bg-surface/50 max-h-80 overflow-y-auto">
          <SimpleTable
            data={activities}
            columns={columns}
            sortable={false}
            className="rounded-lg border-0 max-h-none"
          />
        </div>
      </div>
    </div>
  );
}

function ReportPhase({ result }: { result: ActivityAiApplyResponse }) {
  const hasIssues =
    result.codeCollisions.length > 0 ||
    result.wbsResolutionFailures.length > 0 ||
    result.relationshipResolutionFailures.length > 0;

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-success/30 bg-success/10 p-3 flex items-center gap-2">
        <CheckCircle size={16} className="text-success shrink-0" />
        <p className="text-sm text-text-primary">
          {result.createdActivityIds.length} activities and {result.createdRelationshipIds.length} relationships created.
        </p>
      </div>

      {hasIssues && (
        <div className="space-y-3">
          {result.codeCollisions.length > 0 && (
            <div className="rounded-lg border border-warning/30 bg-warning/10 p-3">
              <div className="flex items-center gap-2 mb-1">
                <AlertCircle size={14} className="text-warning" />
                <p className="text-xs font-medium text-warning">Code Collisions Resolved</p>
              </div>
              <ul className="text-xs text-text-secondary space-y-0.5">
                {result.codeCollisions.map((c, i) => (
                  <li key={i} className="font-mono">{c}</li>
                ))}
              </ul>
            </div>
          )}

          {result.wbsResolutionFailures.length > 0 && (
            <div className="rounded-lg border border-danger/30 bg-danger/10 p-3">
              <div className="flex items-center gap-2 mb-1">
                <XCircle size={14} className="text-danger" />
                <p className="text-xs font-medium text-danger">WBS Resolution Failures</p>
              </div>
              <ul className="text-xs text-text-secondary space-y-0.5">
                {result.wbsResolutionFailures.map((f, i) => (
                  <li key={i} className="font-mono">{f}</li>
                ))}
              </ul>
            </div>
          )}

          {result.relationshipResolutionFailures.length > 0 && (
            <div className="rounded-lg border border-warning/30 bg-warning/10 p-3">
              <div className="flex items-center gap-2 mb-1">
                <AlertCircle size={14} className="text-warning" />
                <p className="text-xs font-medium text-warning">Relationship Resolution Failures</p>
              </div>
              <ul className="text-xs text-text-secondary space-y-0.5">
                {result.relationshipResolutionFailures.map((f, i) => (
                  <li key={i} className="font-mono">{f}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useAiStore } from "@/lib/state/store";
import { aiApi, type SseEvent } from "@/lib/api/aiApi";
import { projectApi } from "@/lib/api/projectApi";
import { ScopeToggle, type ScopeMode } from "@/components/ai/ScopeToggle";
import { Bot, X, Send, Loader2, PanelRightClose, PanelRightOpen, Mic, Image as ImageIcon, Square, Check, Copy, Maximize2, Minimize2, Plus, History, Download, FileText, FileSpreadsheet, Sheet } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Components } from "react-markdown";
import { Children, isValidElement, type ReactElement } from "react";
import { ChatChart } from "@/components/ai/charts/chatChart";
import { AiHistoryView } from "@/components/ai/AiHistoryView";
import HdsScopeChip from "@/components/ai/HdsScopeChip";
import HdsScopeSelectorModal, { type HdsVersionLike } from "@/components/ai/HdsScopeSelectorModal";
import HdsCitationCard from "@/components/ai/HdsCitationCard";
import {
  exportConversationCsv,
  exportConversationPdf,
  exportConversationXlsx,
} from "@/lib/utils/conversationExport";

/**
 * HDS citation payload as emitted by the backend's `search_hds_standards`
 * tool result (snake_case JSON, mapped to camelCase here). Tracked locally so
 * the chat panel does not have to depend on Track C's component file before
 * it lands — Track C's `HdsCitationCard` accepts the same structural shape.
 */
export interface HdsCitationData {
  marker: string;
  chunkId: string;
  versionId: string;
  versionLabel: string;
  sectionPath: string;
  pageStart: number;
  pageEnd: number;
  excerpt: string;
}

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "tool_call" | "tool_result";
  content: string;
  imageUrl?: string;
  meta?: Record<string, unknown>;
  /**
   * Citations attached to an assistant message when the answer came from the
   * HDS retrieval tool. Populated when a `tool_result` event for
   * `search_hds_standards` arrives during streaming.
   */
  hdsCitations?: HdsCitationData[];
}

const markdownComponents: Components = {
  p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
  ul: ({ children }) => <ul className="list-disc pl-4 mb-2">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal pl-4 mb-2">{children}</ol>,
  li: ({ children }) => <li className="mb-1">{children}</li>,
  strong: ({ children }) => <strong className="font-bold">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  code: ({ children, className }) => (
    <code className={`bg-black/20 px-1 py-0.5 rounded text-sm font-mono ${className || ""}`}>
      {children}
    </code>
  ),
  pre: ({ children }) => {
    // Detect ```chart fenced blocks and swap in an inline ECharts viz.
    // react-markdown nests <code class="language-chart"> inside <pre>.
    const only = Children.toArray(children).find(isValidElement) as
      | ReactElement<{ className?: string; children?: unknown }>
      | undefined;
    if (only?.props?.className === "language-chart") {
      const raw = String(only.props.children ?? "").trim();
      return <ChatChart raw={raw} />;
    }
    return <pre className="bg-black/20 p-2 rounded-lg overflow-x-auto mb-2">{children}</pre>;
  },
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-accent underline">
      {children}
    </a>
  ),
  h1: ({ children }) => <h1 className="text-lg font-bold mb-2">{children}</h1>,
  h2: ({ children }) => <h2 className="text-base font-bold mb-2">{children}</h2>,
  h3: ({ children }) => <h3 className="text-sm font-bold mb-1">{children}</h3>,
  blockquote: ({ children }) => (
    <blockquote className="border-l-2 border-border pl-3 italic text-text-muted mb-2">
      {children}
    </blockquote>
  ),
};

// Friendly progress labels — keep tool-name plumbing out of the UI while still
// telling the user what's underway. Unknown tools fall back to "Working".
export const TOOL_PROGRESS_LABELS: Record<string, string> = {
  list_projects: "Looking up projects",
  list_activities: "Checking activities",
  list_activity_resources: "Checking activity resources",
  find_resource_deployment: "Checking resource deployment",
  summarize_activity_resources: "Rolling up resource costs by type",
  analyze_schedule: "Reading schedule health",
  analyze_cost: "Reading cost performance",
  analyze_risk: "Reading risk register",
  forecast_completion: "Running forecast",
  portfolio_kpi: "Reading portfolio KPIs",
  read_dpr_summary: "Reading daily progress",
  query_clickhouse: "Querying analytics",
  describe_schema: "Inspecting data shape",
  // Site Manager
  analyze_labour_utilization: "Reading crew utilization",
  analyze_machine_idle_time: "Checking machine idle time",
  analyze_material_wastage: "Reading material wastage",
  check_stockpile_vs_plan: "Comparing stockpile vs plan",
  // Project Engineer
  analyze_productivity_factor: "Reading productivity vs norm",
  analyze_yield_variance: "Reading yield variance",
  analyze_equipment_cycle_time: "Reading equipment cycle times",
  // QC Manager
  analyze_ncr_trends: "Reading NCR trends",
  audit_traceability: "Auditing traceability",
  analyze_quality_data_gaps: "Looking for quality data gaps",
  // Project Manager
  analyze_labour_cost_per_unit: "Reading labour cost per unit",
  analyze_material_burn_rate: "Reading material burn rate",
  analyze_equipment_utilization_cost: "Reading equipment utilization cost",
  // BIM / Data Coordinator
  audit_dpr_data_quality: "Auditing DPR data quality",
  report_data_lag: "Reading data entry lag",
  // HDS retrieval (deterministic branch — keep aliases for phase strings the
  // tool may emit if it ever switches to progress events).
  search_hds_standards: "Searching HDS standards",
  "search_hds_standards: planning": "Planning HDS retrieval…",
  "search_hds_standards: retrieving (round 1 of 2)": "Searching HDS standards…",
  "search_hds_standards: retrieving (round 2 of 2)": "Searching HDS standards (deeper)…",
  "search_hds_standards: drafting answer": "Drafting answer…",
  "search_hds_standards: verifying grounding": "Verifying citations…",
};
export function friendlyToolLabel(name: string): string {
  return TOOL_PROGRESS_LABELS[name] ?? "Working";
}

/**
 * Parses the `data` payload of a `tool_result` event for the HDS retrieval
 * tool. The backend (see `SearchHdsStandardsTool#execute`) emits citations as
 * a snake_case JSON array; we normalise to the camelCase {@link HdsCitationData}
 * shape used by `HdsCitationCard`. Tolerant of missing / malformed entries so
 * a single bad row does not lose the rest of the list.
 */
export function extractHdsCitations(raw: unknown): HdsCitationData[] {
  if (!raw || typeof raw !== "object") return [];
  const root = raw as Record<string, unknown>;
  const list = Array.isArray(root.citations) ? (root.citations as unknown[]) : [];
  const out: HdsCitationData[] = [];
  for (const entry of list) {
    if (!entry || typeof entry !== "object") continue;
    const c = entry as Record<string, unknown>;
    const marker = typeof c.marker === "string" ? c.marker : "";
    const chunkId =
      typeof c.chunk_id === "string"
        ? c.chunk_id
        : typeof c.chunkId === "string"
          ? c.chunkId
          : "";
    const versionId =
      typeof c.version_id === "string"
        ? c.version_id
        : typeof c.versionId === "string"
          ? c.versionId
          : "";
    const versionLabel =
      typeof c.version_label === "string"
        ? c.version_label
        : typeof c.versionLabel === "string"
          ? c.versionLabel
          : "";
    const sectionPath =
      typeof c.section_path === "string"
        ? c.section_path
        : typeof c.sectionPath === "string"
          ? c.sectionPath
          : "";
    const pageStart =
      typeof c.page_start === "number"
        ? c.page_start
        : typeof c.pageStart === "number"
          ? c.pageStart
          : 0;
    const pageEnd =
      typeof c.page_end === "number"
        ? c.page_end
        : typeof c.pageEnd === "number"
          ? c.pageEnd
          : pageStart;
    const excerpt = typeof c.excerpt === "string" ? c.excerpt : "";
    if (!marker || !chunkId) continue;
    out.push({ marker, chunkId, versionId, versionLabel, sectionPath, pageStart, pageEnd, excerpt });
  }
  return out;
}

function inferModule(pathname: string): string {
  if (pathname.includes("/cost")) return "cost";
  if (pathname.includes("/schedule")) return "schedule";
  if (pathname.includes("/risk")) return "risk";
  if (pathname.includes("/evm")) return "evm";
  if (pathname.includes("/dpr")) return "dpr";
  if (pathname.includes("/activity")) return "activity";
  return "general";
}

// Pull a project UUID out of /projects/<uuid>/... — the sole source of truth
// for the chat's "current project" scope. We deliberately do NOT consult
// `useAppStore.currentProjectId`: labour-master sets that store and never
// clears it, so it leaked into the chat scope on every other page.
const PROJECT_PATH_RE = /\/projects\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:\/|$)/i;
function inferProjectIdFromPath(pathname: string): string | null {
  const m = pathname.match(PROJECT_PATH_RE);
  return m ? m[1] : null;
}

function ToolDataExpander({ data }: { data: unknown }) {
  const [expanded, setExpanded] = useState(false);
  const json = typeof data === "string" ? data : JSON.stringify(data, null, 2);
  const isLong = json.length > 200;
  return (
    <div className="mt-2">
      <button
        onClick={() => setExpanded(!expanded)}
        className="text-accent hover:underline text-xs font-medium"
      >
        {expanded ? "Hide data" : "View data"}
      </button>
      {expanded && (
        <pre className="mt-1 bg-black/20 p-2 rounded-lg overflow-x-auto max-h-[300px] overflow-y-auto text-xs">
          {json}
        </pre>
      )}
      {!expanded && isLong && (
        <div className="mt-1 text-text-muted text-xs">{json.substring(0, 200)}...</div>
      )}
    </div>
  );
}

function CopyButton({ text, dark }: { text: string; dark?: boolean }) {
  const [copied, setCopied] = useState(false);
  const onClick = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Some older browsers / insecure contexts deny clipboard writes — fall
      // back to a hidden textarea + execCommand so the action still succeeds.
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "absolute";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy"); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch {}
      document.body.removeChild(ta);
    }
  };
  return (
    <button
      type="button"
      onClick={onClick}
      title={copied ? "Copied" : "Copy"}
      aria-label={copied ? "Copied" : "Copy message"}
      className={`absolute -bottom-2 ${dark ? "right-2 text-text-primary/70 hover:text-text-primary" : "left-2 text-text-secondary hover:text-text-primary"} bg-surface border border-border rounded-md p-1 opacity-0 group-hover:opacity-100 transition-opacity shadow-sm`}
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
    </button>
  );
}

export function AiChatPanel() {
  const open = useAiStore((s) => s.open);
  const toggle = useAiStore((s) => s.toggle);
  const setOpen = useAiStore((s) => s.setOpen);
  const conversationId = useAiStore((s) => s.currentConversationId);
  const setConversationId = useAiStore((s) => s.setConversationId);
  const pathname = usePathname();
  const activeModule = inferModule(pathname);
  // URL is now the sole "current project" signal for the chat. We deliberately
  // do NOT read `useAppStore.currentProjectId` any more: labour-master sets it
  // and never clears it, so it leaks into every other page and silently
  // re-scopes the chat. The store is still owned by the labour-master nav
  // helper — we just stop the chat from peeking at it.
  const pathProjectId = inferProjectIdFromPath(pathname);

  // User-controlled override for the URL-derived scope. "auto" pins to the
  // current project page; "general" forces portfolio mode even on a project
  // page. Off a project page (pathProjectId == null) the toggle is hidden and
  // the chat is always general regardless of this value.
  const [scopeMode, setScopeMode] = useState<ScopeMode>("auto");

  // When the user reloads a stored conversation from History we keep the
  // conversation's own original scope (decision: avoid silent broadening of an
  // old scoped chat just because the user is now browsing a different page).
  // Cleared whenever the user starts a new chat or flips the toggle, since
  // those are explicit "new intent" signals.
  const [historyScope, setHistoryScope] = useState<{
    projectId: string | null;
    module: string;
  } | null>(null);

  const effectiveProjectId = historyScope
    ? historyScope.projectId
    : scopeMode === "general"
      ? null
      : pathProjectId;
  const effectiveModule = historyScope ? historyScope.module : activeModule;

  // Resolve the in-scope project to a human label (code — name) so the chat
  // header makes the active scope obvious. Without this users sometimes ask
  // "for project X" while browsing a different project's page, which causes
  // tool calls to mismatch the AI's stated project.
  const { data: activeProject } = useQuery({
    queryKey: ["ai-chat-active-project", effectiveProjectId],
    queryFn: () =>
      effectiveProjectId ? projectApi.getProject(effectiveProjectId) : Promise.resolve(null),
    enabled: !!effectiveProjectId,
    staleTime: 60_000,
  });
  const activeProjectLabel = activeProject?.data
    ? `${activeProject.data.code} — ${activeProject.data.name}`
    : null;

  // Portfolio-mode banner shows "All accessible projects (N)". Fetched once
  // and cached for the panel's lifetime so the count is steady across renders.
  const { data: accessibleProjects } = useQuery({
    queryKey: ["ai-chat-accessible-projects"],
    queryFn: () => projectApi.listAccessible(),
    staleTime: 5 * 60_000,
    enabled: effectiveProjectId == null,
  });
  const accessibleProjectCount = accessibleProjects?.data?.length ?? null;

  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingAssistantId, setStreamingAssistantId] = useState<string | null>(null);
  const [streamingStatus, setStreamingStatus] = useState<string | null>(null);
  // Selected HDS document versions for grounded retrieval. When non-empty
  // the backend routes the chat through `runHdsDeterministic` instead of the
  // normal agentic loop and the answer carries citation chunks.
  const [hdsScope, setHdsScope] = useState<HdsVersionLike[]>([]);
  const [scopeModalOpen, setScopeModalOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [maximized, setMaximized] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [micError, setMicError] = useState<string | null>(null);
  const [pendingImage, setPendingImage] = useState<string | null>(null);
  const [view, setView] = useState<"chat" | "history">("chat");
  const [exportOpen, setExportOpen] = useState(false);
  const [exporting, setExporting] = useState<null | "pdf" | "xlsx" | "csv">(null);
  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const exportMenuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isStreaming]);

  // Rehydrate messages from the server when we have a conversationId but no
  // local transcript (cold start, panel reopen, or after loading a past
  // conversation from History). Backend is authoritative; the store only
  // persists the conversation id, never the transcript.
  useEffect(() => {
    if (!conversationId) return;
    if (messages.length > 0) return;
    if (isStreaming) return;
    let cancelled = false;
    aiApi
      .getConversation(conversationId)
      .then((res) => {
        if (cancelled) return;
        const detail = res.data;
        if (!detail) return;
        const restored: ChatMessage[] = detail.messages
          .filter((m) => m.role === "user" || m.role === "assistant")
          .map((m) => ({
            id: crypto.randomUUID(),
            role: m.role as "user" | "assistant",
            content: m.content,
          }));
        setMessages(restored);
      })
      .catch((err) => {
        // 404 here is fine — the persisted conversationId may have been
        // soft-deleted on another device. Drop it so a fresh chat starts.
        const status = (err as { response?: { status?: number } })?.response?.status;
        if (status === 404) {
          setConversationId(null);
        } else {
          console.error("Failed to load conversation history", err);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId, messages.length, isStreaming, setConversationId]);

  // Close the export menu on outside-click.
  useEffect(() => {
    if (!exportOpen) return;
    const onClick = (e: MouseEvent) => {
      if (!exportMenuRef.current) return;
      if (!exportMenuRef.current.contains(e.target as Node)) setExportOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, [exportOpen]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key.toLowerCase() === "k") {
        e.preventDefault();
        toggle();
      }
      if (e.key === "Escape" && open) {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, toggle, setOpen]);

  const newChat = useCallback(() => {
    abortRef.current?.abort();
    setConversationId(null);
    setMessages([]);
    setInput("");
    setPendingImage(null);
    setStreamingStatus(null);
    setStreamingAssistantId(null);
    setIsStreaming(false);
    setHistoryScope(null);
    // Drop the HDS scope too — starting a fresh chat is an explicit "clean
    // slate" signal, and keeping a stale HDS pin would silently re-ground
    // the next question in unrelated documents.
    setHdsScope([]);
    setScopeModalOpen(false);
    setView("chat");
  }, [setConversationId]);

  // Flipping the scope toggle is an explicit "new intent" signal — drop any
  // historyScope so the toggle (not the stored conversation) wins from here.
  const handleScopeChange = useCallback((next: ScopeMode) => {
    setScopeMode(next);
    setHistoryScope(null);
  }, []);

  const loadConversation = useCallback(
    (id: string) => {
      if (id === conversationId) {
        setView("chat");
        return;
      }
      abortRef.current?.abort();
      setIsStreaming(false);
      setStreamingAssistantId(null);
      setStreamingStatus(null);
      setMessages([]);
      setInput("");
      setPendingImage(null);
      setConversationId(id);
      setView("chat");

      // Reloaded conversation keeps its original scope (decision: don't let
      // browsing a different page silently broaden an old scoped chat). The
      // backend currently returns title + messages only; once the detail DTO
      // is extended with projectId/module (Phase 1a backend work) those
      // fields flow through automatically. Until then we default to the
      // module the History list already gave us via the summary (no public
      // way to thread that through the existing onSelect signature without
      // changing AiHistoryView — out of scope here), so we issue an extra
      // detail fetch and read fields defensively.
      aiApi
        .getConversation(id)
        .then((res) => {
          // The backend DTO may grow `projectId` / `module` fields; read them
          // optionally so we light up automatically once that lands.
          const detail = res.data as
            | (typeof res.data & { projectId?: string | null; module?: string | null })
            | null;
          if (!detail) return;
          setHistoryScope({
            projectId: detail.projectId ?? null,
            module: detail.module ?? "general",
          });
        })
        .catch(() => {
          // 404 / network — the rehydration effect handles cleanup. Leave
          // historyScope unset so the chat falls back to URL-derived scope.
        });
    },
    [conversationId, setConversationId],
  );

  const conversationTitle = useMemo(() => {
    const firstUser = messages.find((m) => m.role === "user");
    if (firstUser?.content) {
      const single = firstUser.content.replace(/\s+/g, " ").trim();
      return single.length <= 60 ? single : `${single.slice(0, 57)}...`;
    }
    return "Bipros AI Conversation";
  }, [messages]);

  const runExport = useCallback(
    async (kind: "pdf" | "xlsx" | "csv") => {
      setExportOpen(false);
      if (messages.length === 0) return;
      setExporting(kind);
      try {
        const conv: { title: string; messages: { role: string; content: string; createdAt: string }[] } = {
          title: conversationTitle,
          messages: messages
            .filter((m) => m.role === "user" || m.role === "assistant")
            .map((m) => ({
              role: m.role as string,
              content: m.content,
              createdAt: new Date().toISOString(),
            })),
        };
        // Pull canonical timestamps from the server when we have an id, so
        // CSV/XLSX exports show real send times instead of "now".
        if (conversationId) {
          try {
            const res = await aiApi.getConversation(conversationId);
            const detail = res.data;
            if (detail?.messages?.length) {
              conv.messages = detail.messages.filter(
                (m) => m.role === "user" || m.role === "assistant",
              );
              if (detail.title) conv.title = detail.title;
            }
          } catch {
            // Fall back to client-side data — export should still succeed.
          }
        }
        if (kind === "csv") exportConversationCsv(conv);
        else if (kind === "xlsx") await exportConversationXlsx(conv);
        else await exportConversationPdf(conv);
      } catch (err) {
        console.error("Export failed", err);
      } finally {
        setExporting(null);
      }
    },
    [conversationId, conversationTitle, messages],
  );

  const sendMessage = useCallback(async () => {
    if ((!input.trim() && !pendingImage) || isStreaming) return;
    const userMsg = input.trim();
    setInput("");

    const msgId = crypto.randomUUID();
    setMessages((prev) => [
      ...prev,
      { id: msgId, role: "user", content: userMsg, imageUrl: pendingImage || undefined },
    ]);
    setPendingImage(null);
    setIsStreaming(true);
    setStreamingStatus(null);

    abortRef.current = new AbortController();
    const assistantId = crypto.randomUUID();
    setStreamingAssistantId(assistantId);
    setMessages((prev) => [
      ...prev,
      { id: assistantId, role: "assistant", content: "" },
    ]);

    try {
      const chatReq: import("@/lib/api/aiApi").ChatRequest = {
        conversationId: conversationId ?? null,
        projectId: effectiveProjectId,
        module: effectiveModule,
        message: userMsg,
        imageUrl: pendingImage,
        // Empty array is fine — the backend treats null and [] the same and
        // falls back to the normal agentic loop. Sending the list explicitly
        // makes the wire payload self-describing for debugging.
        hdsVersionIds: hdsScope.map((v) => v.id),
      };
      for await (const ev of aiApi.streamChat(
        chatReq,
        abortRef.current.signal
      )) {
        handleEvent(ev, assistantId);
      }
    } catch (err) {
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId
            ? { ...m, content: m.content + "\n[Error: " + (err instanceof Error ? err.message : String(err)) + "]" }
            : m
        )
      );
    } finally {
      setIsStreaming(false);
      setStreamingAssistantId(null);
      setStreamingStatus(null);
      setMessages((prev) =>
        prev.map((m) =>
          m.role === "tool_call" ? { ...m, meta: { ...m.meta, completed: true } } : m
        )
      );
      abortRef.current = null;
    }
  }, [input, isStreaming, effectiveProjectId, effectiveModule, pendingImage, conversationId, hdsScope]);

  const handleEvent = (ev: SseEvent, assistantId: string) => {
    if (ev.event === "conversation_started") {
      const id = ev.data.conversationId as string | undefined;
      if (id && id !== conversationId) setConversationId(id);
      return;
    }
    if (ev.event === "token") {
      const delta = (ev.data.delta as string) || "";
      setMessages((prev) =>
        prev.map((m) => (m.id === assistantId ? { ...m, content: m.content + delta } : m))
      );
    } else if (ev.event === "tool_call") {
      // Show a friendly progress line inside the assistant bubble so the user
      // sees something happening during multi-round agentic work. We map the
      // tool name to a business label — never leak the raw internal name.
      const name = (ev.data.name as string) || "";
      setStreamingStatus(friendlyToolLabel(name));
    } else if (ev.event === "tool_result") {
      // Keep the last tool_call's label visible until the next tool_call or
      // the final answer arrives — gives a steady "still working" signal
      // without flickering between rounds.
      //
      // Special case: the HDS retrieval tool emits its citations inside the
      // `data` payload of the result. We surface them as structured cards
      // attached to the streaming assistant message so the user sees
      // grounding sources alongside the final answer.
      const toolName = (ev.data.name as string) || "";
      if (toolName === "search_hds_standards" && ev.data.success !== false) {
        const cites = extractHdsCitations(ev.data.data);
        if (cites.length > 0) {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId ? { ...m, hdsCitations: cites } : m,
            ),
          );
        }
      }
    } else if (ev.event === "verifying") {
      // Server-side verification pass — the orchestrator is asking the model
      // to re-check its draft before showing it to the user.
      setStreamingStatus("Cross-checking the answer…");
    } else if (ev.event === "done" || ev.event === "final_answer") {
      const text = (ev.data.text as string) || "";
      setStreamingStatus(null);
      setMessages((prev) =>
        prev.map((m) => {
          if (m.id === assistantId) return { ...m, content: text || m.content };
          if (m.role === "tool_call") return { ...m, meta: { ...m.meta, completed: true } };
          return m;
        })
      );
    } else if (ev.event === "max_rounds_exceeded") {
      const rounds = (ev.data.rounds as number) ?? 0;
      setStreamingStatus(null);
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId
            ? {
                ...m,
                content:
                  m.content +
                  `\n\n_(Agent stopped after ${rounds} steps — refine your question or pick a specific project to drill into.)_`,
              }
            : m
        )
      );
    } else if (ev.event === "error") {
      const message = (ev.data.message as string) || "Unknown error";
      setStreamingStatus(null);
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + "\n[Error: " + message + "]" } : m
        )
      );
    }
  };

  const startRecording = async () => {
    setMicError(null);

    if (typeof window === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      setMicError(
        window.isSecureContext
          ? "Microphone API is not available in this browser."
          : "Microphone requires a secure context (HTTPS or localhost)."
      );
      return;
    }

    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (err) {
      const name = (err as DOMException)?.name;
      if (name === "NotAllowedError" || name === "SecurityError") {
        setMicError(
          "Microphone permission denied. Click the lock icon in the address bar to allow it, then try again."
        );
      } else if (name === "NotFoundError" || name === "OverconstrainedError") {
        setMicError("No microphone device was found.");
      } else if (name === "NotReadableError") {
        setMicError("Microphone is in use by another application.");
      } else {
        setMicError("Could not start the microphone. Check browser settings.");
      }
      console.error("Microphone access failed", err);
      return;
    }

    const mimeType = MediaRecorder.isTypeSupported("audio/webm")
      ? "audio/webm"
      : MediaRecorder.isTypeSupported("audio/mp4")
      ? "audio/mp4"
      : "";
    let recorder: MediaRecorder;
    try {
      recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
    } catch (err) {
      console.error("MediaRecorder init failed", err);
      setMicError("Audio recording is not supported in this browser.");
      stream.getTracks().forEach((t) => t.stop());
      return;
    }

    const chunks: BlobPart[] = [];
    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.push(e.data);
    };
    recorder.onstop = async () => {
      const blob = new Blob(chunks, { type: recorder.mimeType || "audio/webm" });
      try {
        const text = await aiApi.speechToText(blob);
        setInput((prev) => prev + (prev ? " " : "") + text);
      } catch (err) {
        console.error("STT failed", err);
        setMicError("Speech-to-text failed. Please try again.");
      }
      setIsRecording(false);
      stream.getTracks().forEach((t) => t.stop());
    };
    mediaRecorderRef.current = recorder;
    recorder.start();
    setIsRecording(true);
  };

  const stopRecording = () => {
    mediaRecorderRef.current?.stop();
  };

  const handleImageSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !effectiveProjectId) return;
    try {
      const convId = "temp-conv"; // Will be replaced with actual conversation ID
      const result = await aiApi.uploadImage(convId, file);
      setPendingImage(`${process.env.NEXT_PUBLIC_API_URL ?? ""}${result.url}`);
    } catch (err) {
      console.error("Image upload failed", err);
    }
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  if (!open) {
    return (
      <button
        onClick={toggle}
        // ai-chat-fab class is hidden via globals.css when the activity detail drawer
        // sets body[data-activity-drawer-open="true"]. Avoids the FAB overlapping the
        // drawer footer (Create DPR) without a global store.
        className="ai-chat-fab fixed bottom-6 right-6 z-50 flex items-center gap-2 rounded-full bg-accent px-4 py-3 text-sm font-medium text-accent-foreground shadow-lg hover:bg-accent-hover transition-colors"
        title="Open AI chat (Ctrl+Shift+K)"
      >
        <Bot size={18} />
        <span className="hidden sm:inline">Ask AI</span>
      </button>
    );
  }

  const widthClass = collapsed
    ? "w-16"
    : maximized
      ? "w-screen sm:w-[min(96vw,1200px)]"
      : "w-full sm:w-[420px]";

  return (
    <div className="fixed inset-y-0 right-0 z-50 flex">
      <div className="absolute inset-0 bg-black/20 sm:hidden" onClick={() => setOpen(false)} />
      <div
        className={`relative flex flex-col bg-surface border-l border-border shadow-xl transition-all duration-200 ${widthClass}`}
      >
        {/* Header */}
        <div className="flex flex-col border-b border-border px-4 py-3">
          <div className="flex items-center justify-between">
            {!collapsed && (
              <div className="flex items-center gap-2 min-w-0">
                <Bot size={18} className="text-accent shrink-0" />
                <span className="text-sm font-semibold text-text-primary shrink-0">Bipros AI</span>
                <span className="text-xs text-text-muted bg-surface-hover px-2 py-0.5 rounded shrink-0">
                  {effectiveModule}
                </span>
                {/* Scope toggle is only meaningful on a project page — off a
                    project page the chat is forced general anyway. Flipping
                    the toggle while a history-scoped chat is loaded clears
                    historyScope (treated as a "new intent" signal). */}
                {pathProjectId != null && (
                  <ScopeToggle mode={scopeMode} onChange={handleScopeChange} />
                )}
              </div>
            )}
          <div className="flex items-center gap-1 ml-auto">
            {!collapsed && (
              <>
                <button
                  onClick={newChat}
                  className="p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover"
                  title="New chat"
                  aria-label="Start a new chat"
                >
                  <Plus size={16} />
                </button>
                <button
                  onClick={() => setView((v) => (v === "history" ? "chat" : "history"))}
                  className={`p-1.5 rounded-md hover:bg-surface-hover ${
                    view === "history" ? "text-accent" : "text-text-secondary hover:text-text-primary"
                  }`}
                  title={view === "history" ? "Back to chat" : "History"}
                  aria-label="Conversation history"
                  aria-pressed={view === "history"}
                >
                  <History size={16} />
                </button>
                {messages.length > 0 && view === "chat" && (
                  <div className="relative" ref={exportMenuRef}>
                    <button
                      onClick={() => setExportOpen((v) => !v)}
                      disabled={exporting !== null}
                      className="p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover disabled:opacity-50"
                      title="Export conversation"
                      aria-label="Export conversation"
                      aria-haspopup="menu"
                      aria-expanded={exportOpen}
                    >
                      {exporting ? <Loader2 size={16} className="animate-spin" /> : <Download size={16} />}
                    </button>
                    {exportOpen && (
                      <div
                        role="menu"
                        className="absolute right-0 top-full mt-1 z-10 w-44 rounded-md border border-border bg-surface shadow-lg py-1"
                      >
                        <button
                          role="menuitem"
                          onClick={() => runExport("pdf")}
                          className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-text-primary hover:bg-surface-hover"
                        >
                          <FileText size={14} /> PDF (transcript)
                        </button>
                        <button
                          role="menuitem"
                          onClick={() => runExport("xlsx")}
                          className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-text-primary hover:bg-surface-hover"
                        >
                          <FileSpreadsheet size={14} /> Excel (.xlsx)
                        </button>
                        <button
                          role="menuitem"
                          onClick={() => runExport("csv")}
                          className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-text-primary hover:bg-surface-hover"
                        >
                          <Sheet size={14} /> CSV
                        </button>
                      </div>
                    )}
                  </div>
                )}
                <button
                  onClick={() => setMaximized((m) => !m)}
                  className="p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover"
                  title={maximized ? "Restore size" : "Maximize"}
                  aria-label={maximized ? "Restore panel size" : "Maximize panel"}
                >
                  {maximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
                </button>
              </>
            )}
            <button
              onClick={() => setCollapsed(!collapsed)}
              className="p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover"
              title={collapsed ? "Expand" : "Collapse"}
            >
              {collapsed ? <PanelRightOpen size={16} /> : <PanelRightClose size={16} />}
            </button>
            {!collapsed && (
              <button
                onClick={() => setOpen(false)}
                className="p-1.5 rounded-md text-text-secondary hover:text-text-primary hover:bg-surface-hover"
                title="Close (Esc)"
              >
                <X size={16} />
              </button>
            )}
          </div>
          </div>
          {!collapsed && (
            <div className="mt-1.5 text-[11px] text-text-muted flex items-center gap-1.5">
              <span className="font-medium uppercase tracking-wide text-text-secondary">Scope:</span>
              {effectiveProjectId == null ? (
                // Portfolio mode — show the count of accessible projects so
                // the user knows the AI has cross-project visibility.
                <span className="truncate">
                  🌐 All accessible projects
                  {accessibleProjectCount != null ? ` (${accessibleProjectCount})` : ""}
                </span>
              ) : activeProjectLabel ? (
                <span className="truncate" title={activeProjectLabel}>
                  📌 {activeProjectLabel}
                  {historyScope ? " (from history)" : ""}
                </span>
              ) : (
                <span className="italic">loading project…</span>
              )}
            </div>
          )}
          {/* HDS retrieval scope. Selecting one or more versions flips the
              backend into deterministic retrieval mode for the next message;
              the chip itself acts as both indicator and CTA. */}
          {!collapsed && view === "chat" && (
            <div className="mt-2">
              <HdsScopeChip
                selected={hdsScope}
                onEdit={() => setScopeModalOpen(true)}
                onClear={() => setHdsScope([])}
              />
            </div>
          )}
        </div>

        {!collapsed && view === "history" && (
          <AiHistoryView
            currentConversationId={conversationId}
            onSelect={loadConversation}
          />
        )}

        {!collapsed && view === "chat" && (
          <>
            {/* Messages */}
            <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
              {messages.length === 0 && (
                <div className="text-center text-text-muted py-12">
                  <Bot size={32} className="mx-auto mb-3 opacity-50" />
                  <p className="text-sm">Ask me about cost variance, schedule health, DPR summaries, or EVM forecasts.</p>
                  {!effectiveProjectId && (
                    <p className="text-xs mt-3 text-text-muted/80">
                      Portfolio mode — try cross-project questions like
                      <span className="block italic mt-1">
                        &ldquo;Which projects have the worst CPI this month?&rdquo;
                      </span>
                    </p>
                  )}
                  <p className="text-xs mt-2 text-text-muted/70">
                    Use the microphone for voice input or attach images for analysis.
                  </p>
                </div>
              )}
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`group relative flex ${
                    msg.role === "user" ? "justify-end" : "justify-start"
                  }`}
                >
                  <div
                    className={`relative max-w-[90%] rounded-xl px-3 py-2 text-sm ${
                      msg.role === "user"
                        ? "bg-accent text-accent-foreground"
                        : msg.role === "tool_call"
                        ? "bg-info/10 text-info border border-info/20"
                        : msg.role === "tool_result"
                        ? "bg-success/10 text-success border border-success/20"
                        : "bg-surface-hover text-text-primary border border-border"
                    }`}
                  >
                    {(msg.role === "user" || msg.role === "assistant") && msg.content && (
                      <CopyButton text={msg.content} dark={msg.role === "user"} />
                    )}
                    {msg.role === "tool_call" && (
                      <div className="flex items-center gap-2 text-xs font-medium">
                        {msg.meta?.completed ? (
                          <Check size={12} className="text-success" />
                        ) : (
                          <Loader2 size={12} className="animate-spin" />
                        )}
                        {msg.content}
                      </div>
                    )}
                    {msg.role === "tool_result" && (
                      <div className="text-xs">
                        <div className={`font-medium mb-1 ${msg.meta?.success === false ? "text-danger" : ""}`}>
                          {msg.meta?.success === false ? "Failed" : "Result"}
                        </div>
                        <div className="whitespace-pre-wrap">{msg.content}</div>
                        {msg.meta?.data != null && (
                          <ToolDataExpander data={msg.meta.data} />
                        )}
                      </div>
                    )}
                    {(msg.role === "user" || msg.role === "assistant") && (
                      <div>
                        {msg.imageUrl && (
                          <img
                            src={msg.imageUrl}
                            alt="Uploaded"
                            className="max-w-full max-h-[200px] rounded-lg mb-2 object-cover"
                          />
                        )}
                        {msg.role === "assistant" ? (
                          msg.id === streamingAssistantId ? (
                            <div>
                              {msg.content && (
                                <div className="whitespace-pre-wrap">{msg.content}</div>
                              )}
                              <div
                                className={`flex items-center gap-2 text-xs text-text-muted ${
                                  msg.content ? "mt-2" : ""
                                }`}
                              >
                                <Loader2 size={11} className="animate-spin" />
                                <span>{streamingStatus ?? "Thinking"}…</span>
                              </div>
                            </div>
                          ) : (
                            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                              {msg.content}
                            </ReactMarkdown>
                          )
                        ) : (
                          <div className="whitespace-pre-wrap">{msg.content}</div>
                        )}
                        {/* HDS source cards. Rendered whenever the assistant
                            message has citations attached (whether the answer
                            is still streaming or finished). Each card is the
                            structured grounding for a [cN] marker in the
                            answer text. */}
                        {msg.role === "assistant" &&
                          msg.hdsCitations &&
                          msg.hdsCitations.length > 0 && (
                            <div className="mt-3 space-y-2">
                              <div className="text-[11px] font-semibold uppercase tracking-wide text-text-secondary">
                                Sources
                              </div>
                              {msg.hdsCitations.map((c) => (
                                <HdsCitationCard key={c.marker} citation={c} />
                              ))}
                            </div>
                          )}
                      </div>
                    )}
                  </div>
                </div>
              ))}
              <div ref={bottomRef} />
            </div>

            {/* Pending image preview */}
            {pendingImage && (
              <div className="px-4 py-2 border-t border-border">
                <div className="relative inline-block">
                  <img
                    src={pendingImage}
                    alt="Pending"
                    className="h-16 w-16 rounded-lg object-cover border border-border"
                  />
                  <button
                    onClick={() => setPendingImage(null)}
                    className="absolute -top-1 -right-1 bg-danger text-white rounded-full p-0.5"
                  >
                    <X size={10} />
                  </button>
                </div>
              </div>
            )}

            {/* Mic error */}
            {micError && (
              <div className="px-4 pt-2">
                <div className="flex items-start gap-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-xs text-danger">
                  <span className="flex-1">{micError}</span>
                  <button
                    onClick={() => setMicError(null)}
                    className="text-danger/70 hover:text-danger"
                    title="Dismiss"
                  >
                    <X size={12} />
                  </button>
                </div>
              </div>
            )}

            {/* Input */}
            <div className="border-t border-border px-4 py-3">
              <div className="flex items-end gap-2">
                <button
                  onClick={() => fileInputRef.current?.click()}
                  disabled={!effectiveProjectId || isStreaming}
                  className="p-2 rounded-lg text-text-secondary hover:text-text-primary hover:bg-surface-hover transition-colors"
                  title="Attach image"
                >
                  <ImageIcon size={16} />
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={handleImageSelect}
                />
                <button
                  onClick={isRecording ? stopRecording : startRecording}
                  disabled={isStreaming}
                  className={`p-2 rounded-lg transition-colors ${
                    isRecording
                      ? "bg-danger text-white animate-pulse"
                      : "text-text-secondary hover:text-text-primary hover:bg-surface-hover"
                  }`}
                  title={isRecording ? "Stop recording" : "Voice input"}
                >
                  {isRecording ? <Square size={16} /> : <Mic size={16} />}
                </button>
                <textarea
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.shiftKey) {
                      e.preventDefault();
                      sendMessage();
                    }
                  }}
                  placeholder={effectiveProjectId ? "Ask anything..." : "Ask anything (portfolio mode)..."}
                  disabled={isStreaming}
                  rows={1}
                  className="flex-1 resize-none rounded-lg border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent min-h-[40px] max-h-[120px]"
                />
                <button
                  onClick={sendMessage}
                  disabled={(!input.trim() && !pendingImage) || isStreaming}
                  className="rounded-lg bg-accent p-2 text-accent-foreground hover:bg-accent-hover disabled:bg-border disabled:text-text-muted transition-colors"
                >
                  {isStreaming ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                </button>
              </div>
            </div>
          </>
        )}
      </div>
      {/* Lives at the panel root so the dialog overlays everything (its own
          backdrop handles outside-click). The modal short-circuits to null
          when `scopeModalOpen` is false, so this costs nothing when idle. */}
      <HdsScopeSelectorModal
        open={scopeModalOpen}
        initiallySelectedIds={hdsScope.map((v) => v.id)}
        onCancel={() => setScopeModalOpen(false)}
        onConfirm={(vs) => {
          setHdsScope(vs);
          setScopeModalOpen(false);
        }}
      />
    </div>
  );
}

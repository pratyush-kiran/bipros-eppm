# HDS Phase 4 — Frontend

> **Three parallel tracks.** Phase 3 must be green.
>
> **Next.js 16 caveat (from project CLAUDE.md):** Before editing anything that touches App Router, server components, or Next-specific imports, read the relevant guide in `frontend/node_modules/next/dist/docs/`. APIs may differ from your training data.

**Goal:** Admin can upload an HDS PDF and watch progress. Users can pick HDS versions in chat and see cited answers.

**Verify gate:**
```bash
(cd frontend && pnpm install --frozen-lockfile)
(cd frontend && pnpm typecheck)
(cd frontend && pnpm lint)
(cd frontend && pnpm build)
# Visual smoke is done as part of Phase 5
```

---

## Track A — `hdsApi.ts` + admin pages

**Owns**: `frontend/src/lib/api/hdsApi.ts`, `frontend/src/app/(app)/admin/hds-library/**`. **Does NOT touch** `frontend/src/components/ai/**`.

### Task A.1 — API client

**Files:**
- Create: `frontend/src/lib/api/hdsApi.ts`

- [ ] **Step 1: Types + client**

```ts
import { apiClient } from "./client";
import { ApiResponse } from "@/lib/types";

export type HdsDiscipline = "HIGHWAY" | "BRIDGE" | "GEOTECH" | "PAVEMENT" | "TRAFFIC" | "DRAINAGE" | "OTHER";

export type HdsVersionStatus =
  | "PENDING" | "PARSING" | "CHUNKING" | "EMBEDDING" | "INDEXED" | "FAILED";

export interface HdsDocument {
  id: string;
  title: string;
  shortCode: string;
  discipline: HdsDiscipline;
  issuingAuthority?: string | null;
  country?: string | null;
  description?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HdsVersion {
  id: string;
  hdsDocumentId: string;
  versionLabel: string;
  revisionYear?: number | null;
  effectiveDate?: string | null;
  fileName?: string;
  fileSizeBytes?: number;
  pageCount?: number | null;
  status: HdsVersionStatus;
  indexingProgressPct: number;
  chunkCount?: number | null;
  uploadedAt: string;
  indexedAt?: string | null;
}

export interface HdsVersionDetail {
  version: HdsVersion;
  indexingError?: string | null;
}

export interface HdsChunk {
  id: string;
  versionId: string;
  versionLabel: string;
  pageStart: number;
  pageEnd: number;
  sectionPath: string;
  chunkType: string;
  content: string;
}

export interface CreateHdsDocumentRequest {
  title: string;
  shortCode: string;
  discipline: HdsDiscipline;
  issuingAuthority?: string;
  country?: string;
  description?: string;
}

export const hdsApi = {
  listDocuments: async (): Promise<HdsDocument[]> => {
    const { data } = await apiClient.get<ApiResponse<HdsDocument[]>>("/v1/hds/admin/documents");
    return data.data;
  },

  createDocument: async (req: CreateHdsDocumentRequest): Promise<HdsDocument> => {
    const { data } = await apiClient.post<ApiResponse<HdsDocument>>("/v1/hds/admin/documents", req);
    return data.data;
  },

  updateDocument: async (id: string, patch: Partial<CreateHdsDocumentRequest>) => {
    const { data } = await apiClient.patch<ApiResponse<HdsDocument>>(`/v1/hds/admin/documents/${id}`, patch);
    return data.data;
  },

  deleteDocument: async (id: string) => {
    await apiClient.delete(`/v1/hds/admin/documents/${id}`);
  },

  listVersions: async (): Promise<HdsVersion[]> => {
    const { data } = await apiClient.get<ApiResponse<HdsVersion[]>>("/v1/hds/versions");
    return data.data;
  },

  getVersion: async (id: string): Promise<HdsVersionDetail> => {
    const { data } = await apiClient.get<ApiResponse<HdsVersionDetail>>(`/v1/hds/admin/versions/${id}`);
    return data.data;
  },

  uploadVersion: async (
    documentId: string,
    versionLabel: string,
    revisionYear: number | undefined,
    file: File,
    onProgress?: (pct: number) => void
  ): Promise<HdsVersion> => {
    const form = new FormData();
    form.append("versionLabel", versionLabel);
    if (revisionYear) form.append("revisionYear", String(revisionYear));
    form.append("file", file);
    const { data } = await apiClient.post<ApiResponse<HdsVersion>>(
      `/v1/hds/admin/documents/${documentId}/versions`,
      form,
      {
        headers: { "Content-Type": "multipart/form-data" },
        onUploadProgress: (e) => {
          if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100));
        },
        maxBodyLength: Infinity,
        maxContentLength: Infinity,
      }
    );
    return data.data;
  },

  retryVersion: async (id: string) => {
    await apiClient.post(`/v1/hds/admin/versions/${id}/retry`);
  },

  deleteVersion: async (id: string) => {
    await apiClient.delete(`/v1/hds/admin/versions/${id}`);
  },

  getChunk: async (id: string): Promise<HdsChunk> => {
    const { data } = await apiClient.get<ApiResponse<HdsChunk>>(`/v1/hds/chunks/${id}`);
    return data.data;
  },

  presignPdf: async (versionId: string): Promise<{ url: string; expiresAt: string }> => {
    const { data } = await apiClient.get<ApiResponse<{ url: string; expiresAt: string }>>(
      `/v1/hds/versions/${versionId}/pdf`
    );
    return data.data;
  },

  /**
   * Subscribe to SSE ingestion progress for a version.
   * Returns an AbortController-like handle with `.close()`.
   */
  subscribeProgress: (
    versionId: string,
    onEvent: (ev: { stage: string; progressPct: number; message: string }) => void
  ): { close: () => void } => {
    const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : "";
    const url = `${process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080"}/v1/hds/admin/versions/${versionId}/progress`;
    const controller = new AbortController();

    (async () => {
      const res = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
        signal: controller.signal,
      });
      if (!res.ok || !res.body) return;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const events = buf.split("\n\n");
        buf = events.pop() ?? "";
        for (const block of events) {
          const dataLine = block.split("\n").find((l) => l.startsWith("data:"));
          if (!dataLine) continue;
          try {
            const parsed = JSON.parse(dataLine.slice(5).trim());
            onEvent(parsed);
          } catch { /* ignore */ }
        }
      }
    })().catch(() => { /* aborted */ });

    return { close: () => controller.abort() };
  },
};
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck)
git add frontend/src/lib/api/hdsApi.ts
git commit -m "feat(hds): frontend API client (admin + query + SSE progress)"
```

### Task A.2 — Admin list page

**Files:**
- Create: `frontend/src/app/(app)/admin/hds-library/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { hdsApi, HdsDocument } from "@/lib/api/hdsApi";

export default function HdsLibraryPage() {
  const [docs, setDocs] = useState<HdsDocument[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    hdsApi.listDocuments().then(setDocs).catch((e) => setError(String(e)));
  }, []);

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-semibold">HDS Library</h1>
        <Link
          href="/admin/hds-library/new"
          className="px-3 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
        >
          + New publication
        </Link>
      </div>

      {error && <div className="text-red-600 mb-4">{error}</div>}
      {!docs && !error && <div>Loading…</div>}

      {docs && docs.length === 0 && (
        <div className="text-gray-500">No HDS publications yet. Create one to get started.</div>
      )}

      {docs && docs.length > 0 && (
        <table className="w-full border-collapse">
          <thead>
            <tr className="text-left border-b">
              <th className="py-2">Short code</th>
              <th>Title</th>
              <th>Discipline</th>
              <th>Authority</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {docs.map((d) => (
              <tr key={d.id} className="border-b hover:bg-gray-50">
                <td className="py-2 font-mono">{d.shortCode}</td>
                <td>
                  <Link href={`/admin/hds-library/${d.id}`} className="text-blue-600 hover:underline">
                    {d.title}
                  </Link>
                </td>
                <td>{d.discipline}</td>
                <td>{d.issuingAuthority || "—"}</td>
                <td>{new Date(d.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/app/(app)/admin/hds-library/page.tsx
git commit -m "feat(hds): admin HDS library list page"
```

### Task A.3 — Create publication page

**Files:**
- Create: `frontend/src/app/(app)/admin/hds-library/new/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { hdsApi, HdsDiscipline } from "@/lib/api/hdsApi";

const DISCIPLINES: HdsDiscipline[] = ["HIGHWAY","BRIDGE","GEOTECH","PAVEMENT","TRAFFIC","DRAINAGE","OTHER"];

export default function NewHdsPublicationPage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [shortCode, setShortCode] = useState("");
  const [discipline, setDiscipline] = useState<HdsDiscipline>("HIGHWAY");
  const [issuingAuthority, setIssuingAuthority] = useState("");
  const [country, setCountry] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true); setError(null);
    try {
      const doc = await hdsApi.createDocument({ title, shortCode, discipline, issuingAuthority, country, description });
      router.push(`/admin/hds-library/${doc.id}`);
    } catch (e: any) {
      setError(e?.response?.data?.message || String(e));
      setBusy(false);
    }
  };

  return (
    <div className="p-6 max-w-2xl">
      <h1 className="text-2xl font-semibold mb-6">New HDS Publication</h1>
      <div className="space-y-4">
        <label className="block">
          <span className="block text-sm font-medium">Title *</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)}
            className="mt-1 w-full border rounded px-3 py-2"
            placeholder="Highway Design Standard, Volume 3 — Geometric Design" />
        </label>
        <label className="block">
          <span className="block text-sm font-medium">Short code *</span>
          <input value={shortCode} onChange={(e) => setShortCode(e.target.value)}
            className="mt-1 w-full border rounded px-3 py-2 font-mono"
            placeholder="HDS-V3" />
          <span className="text-xs text-gray-500">Used in citation strings. Must be unique.</span>
        </label>
        <label className="block">
          <span className="block text-sm font-medium">Discipline *</span>
          <select value={discipline} onChange={(e) => setDiscipline(e.target.value as HdsDiscipline)}
            className="mt-1 w-full border rounded px-3 py-2">
            {DISCIPLINES.map((d) => <option key={d}>{d}</option>)}
          </select>
        </label>
        <label className="block">
          <span className="block text-sm font-medium">Issuing authority</span>
          <input value={issuingAuthority} onChange={(e) => setIssuingAuthority(e.target.value)}
            className="mt-1 w-full border rounded px-3 py-2"
            placeholder="Sultanate of Oman, MoT" />
        </label>
        <label className="block">
          <span className="block text-sm font-medium">Country (ISO-3166)</span>
          <input value={country} onChange={(e) => setCountry(e.target.value)}
            maxLength={2}
            className="mt-1 w-32 border rounded px-3 py-2 font-mono"
            placeholder="OM" />
        </label>
        <label className="block">
          <span className="block text-sm font-medium">Description</span>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)}
            rows={3} className="mt-1 w-full border rounded px-3 py-2" />
        </label>
        {error && <div className="text-red-600">{error}</div>}
        <button onClick={submit} disabled={busy || !title || !shortCode}
          className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50">
          {busy ? "Creating…" : "Create publication"}
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add "frontend/src/app/(app)/admin/hds-library/new/page.tsx"
git commit -m "feat(hds): admin create-publication page"
```

### Task A.4 — Versions list page

**Files:**
- Create: `frontend/src/app/(app)/admin/hds-library/[docId]/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { hdsApi, HdsVersion } from "@/lib/api/hdsApi";

export default function HdsVersionsListPage() {
  const params = useParams() as { docId: string };
  const [versions, setVersions] = useState<HdsVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // listVersions returns INDEXED only; for admin we need ALL — adjust the API
    // For v1 keep it simple: combine indexed list with /admin/documents/:id (which doesn't list versions).
    // PLACEHOLDER: until backend adds GET /v1/hds/admin/documents/:id/versions, the indexed list is the best we have.
    hdsApi.listVersions()
      .then(all => setVersions(all.filter(v => v.hdsDocumentId === params.docId)))
      .catch(e => setError(String(e)));
  }, [params.docId]);

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-semibold">Versions</h1>
        <Link href={`/admin/hds-library/${params.docId}/upload`}
              className="px-3 py-2 bg-blue-600 text-white rounded hover:bg-blue-700">
          + Upload version
        </Link>
      </div>

      {error && <div className="text-red-600 mb-4">{error}</div>}
      {!versions && !error && <div>Loading…</div>}
      {versions && versions.length === 0 && (
        <div className="text-gray-500">No versions yet. Upload the first revision.</div>
      )}

      {versions && versions.length > 0 && (
        <table className="w-full border-collapse">
          <thead>
            <tr className="text-left border-b">
              <th>Label</th><th>Year</th><th>Status</th><th>Pages</th><th>Chunks</th><th>Uploaded</th><th></th>
            </tr>
          </thead>
          <tbody>
            {versions.map((v) => (
              <tr key={v.id} className="border-b">
                <td className="py-2 font-mono">{v.versionLabel}</td>
                <td>{v.revisionYear ?? "—"}</td>
                <td><StatusBadge status={v.status} pct={v.indexingProgressPct} /></td>
                <td>{v.pageCount ?? "—"}</td>
                <td>{v.chunkCount ?? "—"}</td>
                <td>{new Date(v.uploadedAt).toLocaleString()}</td>
                <td className="text-right">
                  <Link href={`/admin/hds-library/${params.docId}/versions/${v.id}`}
                        className="text-blue-600 hover:underline">Detail</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function StatusBadge({ status, pct }: { status: string; pct: number }) {
  const color = {
    INDEXED: "bg-green-100 text-green-800",
    FAILED: "bg-red-100 text-red-800",
    PENDING: "bg-gray-100 text-gray-700",
  }[status] || "bg-blue-100 text-blue-700";
  return (
    <span className={`inline-block px-2 py-0.5 text-xs rounded ${color}`}>
      {status === "INDEXED" || status === "FAILED" || status === "PENDING" ? status : `${status} · ${pct}%`}
    </span>
  );
}
```

> **Backend gap**: there's no `/admin/documents/:id/versions` listing endpoint yet. Phase 5 should add it; for now the page filters the indexed list. If the gap is blocking, add a new endpoint in `HdsDocumentAdminController` returning `listVersions(documentId)` from `HdsLibraryService`.

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add "frontend/src/app/(app)/admin/hds-library/[docId]/page.tsx"
git commit -m "feat(hds): versions list page (status badges)"
```

### Task A.5 — Upload page

**Files:**
- Create: `frontend/src/app/(app)/admin/hds-library/[docId]/upload/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";

import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { hdsApi } from "@/lib/api/hdsApi";

export default function HdsUploadPage() {
  const router = useRouter();
  const params = useParams() as { docId: string };

  const [versionLabel, setVersionLabel] = useState("");
  const [year, setYear] = useState<number | "">("");
  const [file, setFile] = useState<File | null>(null);
  const [pct, setPct] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const upload = async () => {
    if (!file || !versionLabel) return;
    setBusy(true); setError(null); setPct(0);
    try {
      const ver = await hdsApi.uploadVersion(
        params.docId,
        versionLabel,
        year === "" ? undefined : year,
        file,
        setPct
      );
      router.push(`/admin/hds-library/${params.docId}/versions/${ver.id}`);
    } catch (e: any) {
      const status = e?.response?.status;
      if (status === 409) {
        const existing = e.response.data?.data;
        setError(`Identical file already uploaded as version ${existing?.versionLabel}.`);
      } else {
        setError(e?.response?.data?.message || String(e));
      }
      setBusy(false);
    }
  };

  return (
    <div className="p-6 max-w-2xl">
      <h1 className="text-2xl font-semibold mb-6">Upload HDS Version</h1>
      <div className="space-y-4">
        <label className="block">
          <span className="block text-sm font-medium">Version label *</span>
          <input value={versionLabel} onChange={(e) => setVersionLabel(e.target.value)}
            className="mt-1 w-full border rounded px-3 py-2 font-mono"
            placeholder="Rev 2.1" />
        </label>
        <label className="block">
          <span className="block text-sm font-medium">Revision year</span>
          <input type="number" value={year} onChange={(e) => setYear(e.target.value === "" ? "" : Number(e.target.value))}
            className="mt-1 w-32 border rounded px-3 py-2"
            placeholder="2024" />
        </label>
        <label className="block">
          <span className="block text-sm font-medium">PDF *</span>
          <input type="file" accept="application/pdf"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          {file && <span className="ml-2 text-sm text-gray-600">{(file.size / 1024 / 1024).toFixed(1)} MB</span>}
        </label>
        {busy && (
          <div>
            <div className="text-sm text-gray-600">Uploading… {pct}%</div>
            <div className="w-full h-2 bg-gray-200 rounded">
              <div className="h-2 bg-blue-600 rounded" style={{ width: `${pct}%` }} />
            </div>
          </div>
        )}
        {error && <div className="text-red-600">{error}</div>}
        <button onClick={upload} disabled={busy || !file || !versionLabel}
          className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50">
          {busy ? "Uploading…" : "Upload"}
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add "frontend/src/app/(app)/admin/hds-library/[docId]/upload/page.tsx"
git commit -m "feat(hds): admin upload page (multipart with progress)"
```

### Task A.6 — Version detail page with SSE progress

**Files:**
- Create: `frontend/src/app/(app)/admin/hds-library/[docId]/versions/[verId]/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { hdsApi, HdsVersionDetail } from "@/lib/api/hdsApi";

export default function HdsVersionDetailPage() {
  const params = useParams() as { docId: string; verId: string };
  const [detail, setDetail] = useState<HdsVersionDetail | null>(null);
  const [progress, setProgress] = useState<{ stage: string; pct: number; msg: string } | null>(null);

  useEffect(() => {
    hdsApi.getVersion(params.verId).then(setDetail);
    const sub = hdsApi.subscribeProgress(params.verId, (ev) => {
      setProgress({ stage: ev.stage, pct: ev.progressPct, msg: ev.message });
      if (ev.stage === "COMPLETE" || ev.stage === "FAILED") {
        hdsApi.getVersion(params.verId).then(setDetail);
      }
    });
    return () => sub.close();
  }, [params.verId]);

  if (!detail) return <div className="p-6">Loading…</div>;
  const v = detail.version;

  return (
    <div className="p-6 max-w-3xl">
      <h1 className="text-2xl font-semibold mb-2">{v.versionLabel}</h1>
      <div className="text-sm text-gray-600 mb-4">
        {v.fileName} · {((v.fileSizeBytes ?? 0) / 1024 / 1024).toFixed(1)} MB · {v.pageCount ?? "—"} pages
      </div>

      <div className="mb-4">
        <div className="font-medium">Status: {v.status} ({v.indexingProgressPct}%)</div>
        {progress && progress.stage !== "COMPLETE" && (
          <>
            <div className="text-sm text-gray-600">{progress.msg}</div>
            <div className="w-full h-2 bg-gray-200 rounded mt-1">
              <div className="h-2 bg-blue-600 rounded" style={{ width: `${progress.pct}%` }} />
            </div>
          </>
        )}
        {v.status === "FAILED" && detail.indexingError && (
          <div className="mt-2 p-3 bg-red-50 text-red-700 rounded text-sm">
            <div className="font-medium">Error</div>
            <pre className="whitespace-pre-wrap text-xs mt-1">{detail.indexingError}</pre>
            <button onClick={() => hdsApi.retryVersion(v.id).then(() => location.reload())}
              className="mt-2 px-3 py-1 bg-red-600 text-white rounded">Retry</button>
          </div>
        )}
        {v.status === "INDEXED" && (
          <div className="mt-2 text-green-700">Indexed {v.chunkCount} chunks at {v.indexedAt}</div>
        )}
      </div>

      <button onClick={async () => {
                if (confirm("Delete this version and its chunks?")) {
                  await hdsApi.deleteVersion(v.id);
                  location.href = `/admin/hds-library/${params.docId}`;
                }
              }}
              className="px-3 py-1 bg-gray-200 text-gray-700 rounded hover:bg-red-100 hover:text-red-700">
        Delete version
      </button>
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add "frontend/src/app/(app)/admin/hds-library/[docId]/versions/[verId]/page.tsx"
git commit -m "feat(hds): version detail page with live SSE progress"
```

---

## Track B — Chat scope chip + selector modal

**Owns**: `frontend/src/components/ai/HdsScopeChip.tsx`, `HdsScopeSelectorModal.tsx`, `AiChatPanel.tsx` *modifications limited to the scope-chip slot and the request-wiring path*. **Does NOT touch** the citation rendering body (Track C).

### Task B.1 — Scope chip component

**Files:**
- Create: `frontend/src/components/ai/HdsScopeChip.tsx`

- [ ] **Step 1: Component**

```tsx
"use client";

import { HdsVersion } from "@/lib/api/hdsApi";

interface Props {
  selected: HdsVersion[];
  onEdit: () => void;
  onClear: () => void;
}

export default function HdsScopeChip({ selected, onEdit, onClear }: Props) {
  if (selected.length === 0) {
    return (
      <button onClick={onEdit}
        className="inline-flex items-center gap-1 px-3 py-1 text-sm border border-dashed rounded-full text-gray-600 hover:bg-gray-50">
        <span>📚</span> Select HDS sources
      </button>
    );
  }
  const label = selected.length <= 2
    ? selected.map(v => v.versionLabel).join(", ")
    : `${selected[0].versionLabel} + ${selected.length - 1} more`;

  return (
    <div className="inline-flex items-center gap-2 px-3 py-1 text-sm bg-blue-50 border border-blue-200 rounded-full">
      <span>📚 HDS scope: <strong>{label}</strong></span>
      <button onClick={onEdit} className="text-blue-600 hover:underline">edit</button>
      <button onClick={onClear} className="text-gray-500 hover:underline">clear</button>
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/components/ai/HdsScopeChip.tsx
git commit -m "feat(hds): chat scope chip component"
```

### Task B.2 — Selector modal

**Files:**
- Create: `frontend/src/components/ai/HdsScopeSelectorModal.tsx`

- [ ] **Step 1: Component**

```tsx
"use client";

import { useEffect, useState } from "react";
import { hdsApi, HdsVersion } from "@/lib/api/hdsApi";

interface Props {
  open: boolean;
  initiallySelectedIds: string[];
  onCancel: () => void;
  onConfirm: (versions: HdsVersion[]) => void;
}

export default function HdsScopeSelectorModal({ open, initiallySelectedIds, onCancel, onConfirm }: Props) {
  const [versions, setVersions] = useState<HdsVersion[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set(initiallySelectedIds));

  useEffect(() => {
    if (open) {
      hdsApi.listVersions().then(setVersions).catch(() => setVersions([]));
      setSelectedIds(new Set(initiallySelectedIds));
    }
  }, [open, initiallySelectedIds]);

  if (!open) return null;

  const toggle = (id: string) => {
    const next = new Set(selectedIds);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelectedIds(next);
  };

  const confirm = () => {
    const chosen = versions.filter(v => selectedIds.has(v.id));
    onConfirm(chosen);
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-lg max-h-[80vh] flex flex-col">
        <div className="px-5 py-3 border-b font-semibold">Select HDS sources</div>
        <div className="flex-1 overflow-y-auto p-2">
          {versions.length === 0 && <div className="p-4 text-gray-500">No indexed HDS versions yet.</div>}
          {versions.map(v => (
            <label key={v.id} className="flex items-start gap-3 p-3 rounded hover:bg-gray-50 cursor-pointer">
              <input type="checkbox" checked={selectedIds.has(v.id)} onChange={() => toggle(v.id)} className="mt-1" />
              <div>
                <div className="font-mono">{v.versionLabel} {v.revisionYear && `(${v.revisionYear})`}</div>
                <div className="text-xs text-gray-500">{v.fileName} · {v.chunkCount ?? "?"} chunks</div>
              </div>
            </label>
          ))}
        </div>
        <div className="px-5 py-3 border-t flex justify-end gap-2">
          <button onClick={onCancel} className="px-3 py-1 text-gray-600 hover:bg-gray-100 rounded">Cancel</button>
          <button onClick={confirm} disabled={selectedIds.size === 0}
            className="px-3 py-1 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50">
            Use {selectedIds.size} selected
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/components/ai/HdsScopeSelectorModal.tsx
git commit -m "feat(hds): HDS scope selector modal (multi-select)"
```

### Task B.3 — Wire scope chip + selector into `AiChatPanel.tsx` (scope-chip slot only)

**Files:**
- Modify: `frontend/src/components/ai/AiChatPanel.tsx` (header slot + request-wiring; NOT the message-body renderer — that's Track C)

- [ ] **Step 1: Add state + chip render**

Near the top of `AiChatPanel`:
```tsx
const [hdsScope, setHdsScope] = useState<HdsVersion[]>([]);
const [scopeModalOpen, setScopeModalOpen] = useState(false);
```

In the JSX, above the message list (find an existing header area or add one):
```tsx
<div className="px-4 py-2 border-b flex items-center gap-2">
  <HdsScopeChip
    selected={hdsScope}
    onEdit={() => setScopeModalOpen(true)}
    onClear={() => setHdsScope([])}
  />
</div>

<HdsScopeSelectorModal
  open={scopeModalOpen}
  initiallySelectedIds={hdsScope.map(v => v.id)}
  onCancel={() => setScopeModalOpen(false)}
  onConfirm={(vs) => { setHdsScope(vs); setScopeModalOpen(false); }}
/>
```

Imports:
```tsx
import HdsScopeChip from "./HdsScopeChip";
import HdsScopeSelectorModal from "./HdsScopeSelectorModal";
import { HdsVersion } from "@/lib/api/hdsApi";
```

- [ ] **Step 2: Pass `hdsVersionIds` in chat request**

Find the place where `aiApi.streamChat` is called and add `hdsVersionIds`:
```tsx
const req: ChatRequest = {
  projectId,
  conversationId,
  module,
  message: userMsg,
  imageUrl,
  hdsVersionIds: hdsScope.map(v => v.id),
};
```

Also update `ChatRequest` in `frontend/src/lib/api/aiApi.ts` to include `hdsVersionIds?: string[]`.

- [ ] **Step 3: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/components/ai/AiChatPanel.tsx frontend/src/lib/api/aiApi.ts
git commit -m "feat(hds): chat panel — scope chip + selector modal + request wiring"
```

---

## Track C — Citation rendering + progress labels

**Owns**: `frontend/src/components/ai/HdsCitationCard.tsx`, citation renderer additions inside `AiChatPanel.tsx` (message body only), `TOOL_PROGRESS_LABELS` map extension. **Does NOT touch** the scope chip / modal / request-wiring (Track B).

### Task C.1 — Citation card

**Files:**
- Create: `frontend/src/components/ai/HdsCitationCard.tsx`

- [ ] **Step 1: Component**

```tsx
"use client";

import { useState } from "react";

export interface CitationData {
  marker: string;
  chunkId: string;
  versionId: string;
  versionLabel: string;
  sectionPath: string;
  pageStart: number;
  pageEnd: number;
  excerpt: string;
}

interface Props {
  citation: CitationData;
  onOpenPdf?: (versionId: string, page: number) => void;
}

export default function HdsCitationCard({ citation, onOpenPdf }: Props) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="border rounded p-3 text-sm bg-gray-50">
      <div className="flex items-center justify-between">
        <button onClick={() => setExpanded(!expanded)} className="font-mono text-blue-700 hover:underline">
          [{citation.marker}] {citation.versionLabel} — {citation.sectionPath} — p. {citation.pageStart}
        </button>
        {onOpenPdf && (
          <button onClick={() => onOpenPdf(citation.versionId, citation.pageStart)}
            className="text-xs text-blue-600 hover:underline">Open</button>
        )}
      </div>
      {expanded && (
        <div className="mt-2 text-xs text-gray-700 italic">"{citation.excerpt}"</div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/components/ai/HdsCitationCard.tsx
git commit -m "feat(hds): citation card component"
```

### Task C.2 — Citation renderer inside `AiChatPanel.tsx` message body

**Files:**
- Modify: `frontend/src/components/ai/AiChatPanel.tsx` (message-body area only)

- [ ] **Step 1: Render citations after each assistant message that carries them**

The SSE `tool_complete` event for `search_hds_standards` carries `citations: CitationData[]` in its payload. Extend the message model to optionally hold a `hdsCitations?: CitationData[]` field. When rendering an assistant message, after the markdown body, render:
```tsx
{msg.hdsCitations && msg.hdsCitations.length > 0 && (
  <div className="mt-3 space-y-2">
    <div className="text-xs font-semibold text-gray-600">▼ Sources</div>
    {msg.hdsCitations.map((c) => (
      <HdsCitationCard key={c.marker} citation={c} />
    ))}
  </div>
)}
```

In the SSE-handling code path, when an event of type `tool_complete` arrives with `data.tool === "search_hds_standards"`, attach `data.citations` to the in-progress assistant message before pushing.

- [ ] **Step 2: Markdown citation-marker highlighting (optional)**

Replace `[c1]`, `[c2]`, etc. in the rendered markdown with bold badges. If the existing markdown renderer (`react-markdown`) is in use, a transformer plugin or a `components.text` override can wrap matches in `<span className="font-mono text-blue-700">[cN]</span>`. Skip if it introduces complexity — the source card list provides the same information.

- [ ] **Step 3: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/components/ai/AiChatPanel.tsx
git commit -m "feat(hds): chat message body renders HDS source cards from tool_complete event"
```

### Task C.3 — Tool-progress label additions

**Files:**
- Modify: wherever `TOOL_PROGRESS_LABELS` is defined inside `AiChatPanel.tsx` (or a shared `src/components/ai/toolLabels.ts` if extracted)

- [ ] **Step 1: Add labels**

```ts
const TOOL_PROGRESS_LABELS: Record<string, string> = {
  // ...existing entries
  "search_hds_standards: planning": "Planning HDS retrieval…",
  "search_hds_standards: retrieving (round 1 of 2)": "Searching HDS standards…",
  "search_hds_standards: retrieving (round 2 of 2)": "Searching HDS standards (deeper)…",
  "search_hds_standards: drafting answer": "Drafting answer…",
  "search_hds_standards: verifying grounding": "Verifying citations…",
};
```

- [ ] **Step 2: Commit**
```bash
(cd frontend && pnpm typecheck && pnpm lint)
git add frontend/src/components/ai/AiChatPanel.tsx
git commit -m "feat(hds): add progress labels for search_hds_standards phases"
```

---

## Phase 4 verify gate

```bash
(cd frontend && pnpm typecheck && pnpm lint && pnpm build)
```
Build must complete cleanly. The pages should be reachable in dev:
```bash
(cd frontend && pnpm dev) &
sleep 8
# Eyeball:
#   http://localhost:3000/admin/hds-library
#   http://localhost:3000/admin/hds-library/new
# (chat page is wherever AiChatPanel is mounted)
kill %1
```

Visual smoke is officially run in Phase 5.

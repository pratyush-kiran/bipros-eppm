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
        <button
          onClick={() => setExpanded(!expanded)}
          className="font-mono text-blue-700 hover:underline"
        >
          [{citation.marker}] {citation.versionLabel} — {citation.sectionPath} — p. {citation.pageStart}
        </button>
        {onOpenPdf && (
          <button
            onClick={() => onOpenPdf(citation.versionId, citation.pageStart)}
            className="text-xs text-blue-600 hover:underline"
          >
            Open
          </button>
        )}
      </div>
      {expanded && (
        <div className="mt-2 text-xs text-gray-700 italic">&ldquo;{citation.excerpt}&rdquo;</div>
      )}
    </div>
  );
}

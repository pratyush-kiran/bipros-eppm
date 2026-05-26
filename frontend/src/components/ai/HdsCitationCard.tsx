"use client";

import { Fragment, useState } from "react";

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

function splitPath(raw: string): { trail: string[]; leaf: string } {
  if (!raw) return { trail: [], leaf: "" };
  const parts = raw
    .split(" > ")
    .map((s) => s.trim())
    .filter(Boolean);
  if (parts.length === 0) return { trail: [], leaf: "" };
  return { trail: parts.slice(0, -1), leaf: parts[parts.length - 1] };
}

function splitNumber(segment: string): { number: string | null; title: string } {
  const m = segment.match(/^(\d+(?:\.\d+)*)\s+(.+)$/);
  if (m) return { number: m[1], title: m[2].trim() };
  return { number: null, title: segment };
}

function trimDangle(s: string): string {
  return s.replace(/[\s\-–—:;,.]+$/u, "").trim();
}

function truncate(s: string, max: number): string {
  if (s.length <= max) return s;
  return s.slice(0, max - 1).trimEnd() + "…";
}

export default function HdsCitationCard({ citation, onOpenPdf }: Props) {
  const [expanded, setExpanded] = useState(true);
  const { trail, leaf } = splitPath(citation.sectionPath);
  const leafClean = trimDangle(leaf);
  const leafParts = splitNumber(leafClean);
  const pageLabel =
    citation.pageEnd && citation.pageEnd !== citation.pageStart
      ? `${citation.pageStart}–${citation.pageEnd}`
      : String(citation.pageStart);

  return (
    <article className="group/cite relative overflow-hidden rounded-md border border-hairline bg-ivory transition-colors duration-200 hover:border-gold/40 hover:bg-parchment/30">
      {/* Inset gold rule — like a leather-bound book's gilt edge */}
      <span
        aria-hidden
        className="pointer-events-none absolute inset-y-2 left-0 w-[2px] rounded-r-sm bg-gradient-to-b from-gold/0 via-gold to-gold/0"
      />
      <div className="relative pl-4 pr-3 py-2.5">
        {/* Header: marker tag + meta + actions */}
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
          <span
            className="inline-flex h-[20px] min-w-[32px] items-center justify-center rounded-[3px] bg-gradient-to-b from-[#262626] to-[#161616] px-1.5 font-display text-[11px] font-semibold tabular-nums tracking-[0.08em] text-gold ring-1 ring-inset ring-gold/35 shadow-[inset_0_1px_0_rgba(245,231,181,0.10),0_1px_0_rgba(0,0,0,0.15)]"
            aria-label={`Citation ${citation.marker}`}
          >
            {citation.marker}
          </span>

          <span className="text-[10px] uppercase tracking-[0.18em] font-medium text-slate">
            HDS&nbsp;
            <span className="text-charcoal/85 tabular-nums">
              {citation.versionLabel}
            </span>
            <span aria-hidden className="mx-1.5 text-gold/50">
              ·
            </span>
            p
            <span className="text-charcoal/85 tabular-nums">{pageLabel}</span>
          </span>

          <div className="ml-auto flex items-center gap-1">
            {onOpenPdf && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onOpenPdf(citation.versionId, citation.pageStart);
                }}
                className="inline-flex items-center gap-1 rounded-[3px] px-1.5 py-0.5 text-[10px] uppercase tracking-[0.14em] font-medium text-gold-ink transition-colors hover:bg-gold-tint/40 hover:text-charcoal focus:outline-none focus-visible:ring-1 focus-visible:ring-gold focus-visible:ring-offset-1 focus-visible:ring-offset-ivory"
                aria-label="Open document at this page"
              >
                <span>Open</span>
                <svg
                  width="9"
                  height="9"
                  viewBox="0 0 10 10"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden
                >
                  <path d="M3.2 6.8 6.8 3.2M6.8 3.2H4M6.8 3.2V6" />
                </svg>
              </button>
            )}
            <button
              type="button"
              onClick={() => setExpanded((e) => !e)}
              aria-expanded={expanded}
              aria-label={expanded ? "Hide excerpt" : "Show excerpt"}
              className="inline-flex h-5 w-5 items-center justify-center rounded-[3px] text-ash transition-colors hover:bg-parchment hover:text-gold-ink focus:outline-none focus-visible:ring-1 focus-visible:ring-gold focus-visible:ring-offset-1 focus-visible:ring-offset-ivory"
            >
              <svg
                width="9"
                height="9"
                viewBox="0 0 10 10"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden
                className={`transition-transform duration-200 ${expanded ? "rotate-180" : ""}`}
              >
                <path d="M2.5 4 5 6.5 7.5 4" />
              </svg>
            </button>
          </div>
        </div>

        {/* Section heading + breadcrumb trail */}
        <button
          type="button"
          onClick={() => setExpanded((e) => !e)}
          className="mt-2 block w-full text-left focus:outline-none"
        >
          <div className="flex items-baseline gap-2">
            {leafParts.number && (
              <span className="shrink-0 font-mono text-[11px] font-semibold tabular-nums text-gold-ink">
                §{leafParts.number}
              </span>
            )}
            <h4 className="font-display text-[14px] font-medium leading-snug text-charcoal">
              {leafParts.title || leafClean || "Untitled reference"}
            </h4>
          </div>

          {trail.length > 0 && (
            <p className="mt-1 text-[11px] leading-relaxed text-slate">
              {trail.map((seg, i) => {
                const { number, title } = splitNumber(trimDangle(seg));
                const label = truncate(number ? `${number} ${title}` : title, 52);
                return (
                  <Fragment key={i}>
                    {i > 0 && (
                      <span aria-hidden className="mx-1.5 text-gold/60">
                        ›
                      </span>
                    )}
                    <span>{label}</span>
                  </Fragment>
                );
              })}
            </p>
          )}
        </button>

        {/* Excerpt as pulled quote */}
        {expanded && citation.excerpt && (
          <div className="mt-2.5 flex items-stretch gap-2.5">
            <span
              aria-hidden
              className="w-[3px] shrink-0 rounded-full bg-gradient-to-b from-gold/0 via-gold/55 to-gold/0"
            />
            <blockquote className="flex-1 font-display text-[12.5px] italic leading-relaxed text-charcoal/85">
              <span aria-hidden className="mr-0.5 text-gold-ink/70">
                &ldquo;
              </span>
              {trimDangle(citation.excerpt)}
              <span aria-hidden className="ml-0.5 text-gold-ink/70">
                &rdquo;
              </span>
            </blockquote>
          </div>
        )}
      </div>
    </article>
  );
}

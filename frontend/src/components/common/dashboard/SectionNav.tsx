"use client";

import { useEffect, useState } from "react";

export interface SectionNavItem {
  id: string;
  label: string;
}

interface SectionNavProps {
  sections: SectionNavItem[];
}

/**
 * Sticky pill navigator for a single-canvas page. Pass section anchors (id + label)
 * and render `<section id="…" className="scroll-mt-20">` in the page body.
 * Highlights the active pill as the user scrolls via IntersectionObserver.
 */
export function SectionNav({ sections }: SectionNavProps) {
  const [active, setActive] = useState(sections[0]?.id);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio);
        if (visible[0]) setActive(visible[0].target.id);
      },
      { rootMargin: "-20% 0px -60% 0px", threshold: [0, 0.25, 0.5, 0.75, 1] },
    );
    sections.forEach((s) => {
      const el = document.getElementById(s.id);
      if (el) observer.observe(el);
    });
    return () => observer.disconnect();
  }, [sections]);

  const jumpTo = (id: string) => {
    const el = document.getElementById(id);
    if (!el) return;
    const y = el.getBoundingClientRect().top + window.scrollY - 72;
    window.scrollTo({ top: y, behavior: "smooth" });
  };

  return (
    <nav
      aria-label="Page sections"
      className="sticky top-0 z-10 -mx-6 mb-6 border-b border-hairline bg-paper/85 px-6 py-3 backdrop-blur-md"
    >
      <div className="flex flex-wrap gap-1.5">
        {sections.map((s) => {
          const isActive = active === s.id;
          return (
            <button
              key={s.id}
              onClick={() => jumpTo(s.id)}
              className={`group relative rounded-full px-3.5 py-1.5 text-[11px] font-semibold uppercase tracking-[0.1em] transition-all duration-200 ${
                isActive
                  ? "bg-gradient-to-br from-gold to-gold-deep text-paper shadow-[0_4px_12px_-4px_rgba(0,88,202,0.45)]"
                  : "border border-transparent text-slate hover:border-hairline hover:bg-ivory hover:text-charcoal"
              }`}
            >
              {s.label}
            </button>
          );
        })}
      </div>
    </nav>
  );
}

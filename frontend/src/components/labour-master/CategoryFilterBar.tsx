"use client";

import type {
  LabourCategory,
  LabourCategoryReference,
  LabourGrade,
} from "@/lib/api/labourMasterApi";

type Props = {
  categories: LabourCategoryReference[];
  selectedCategory: LabourCategory | null;
  onCategoryChange: (c: LabourCategory | null) => void;
  selectedGrade: LabourGrade | null;
  onGradeChange: (g: LabourGrade | null) => void;
  query: string;
  onQueryChange: (q: string) => void;
};

const GRADES: LabourGrade[] = ["A", "B", "C", "D", "E"];

const baseChip =
  "rounded-md border px-3 py-1.5 text-[12px] font-medium transition";
const idleChip =
  "border-hairline bg-paper text-slate hover:text-charcoal hover:border-gold/30";
const activeChip =
  "border-gold/40 bg-gold-tint/45 text-gold-deep shadow-[0_0_0_1px_rgba(0,88,202,0.25)]";

export function CategoryFilterBar({
  categories,
  selectedCategory,
  onCategoryChange,
  selectedGrade,
  onGradeChange,
  query,
  onQueryChange,
}: Props) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-xl border border-hairline bg-paper p-3">
      <button
        type="button"
        onClick={() => onCategoryChange(null)}
        className={`${baseChip} ${selectedCategory == null ? activeChip : idleChip}`}
      >
        All
      </button>
      {categories.map((c) => {
        const active = selectedCategory === c.category;
        return (
          <button
            key={c.category}
            type="button"
            onClick={() => onCategoryChange(c.category)}
            className={`${baseChip} ${active ? activeChip : idleChip}`}
          >
            <span className="font-mono text-[11px] tracking-[0.04em] mr-1.5 text-gold-ink">{c.codePrefix}</span>
            {c.displayName}
          </button>
        );
      })}

      <div className="mx-1 h-6 border-l border-hairline" />

      <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">Grade</span>
      <button
        type="button"
        onClick={() => onGradeChange(null)}
        className={`${baseChip} px-2.5 ${selectedGrade == null ? activeChip : idleChip}`}
      >
        Any
      </button>
      {GRADES.map((g) => {
        const active = selectedGrade === g;
        return (
          <button
            key={g}
            type="button"
            onClick={() => onGradeChange(g)}
            className={`${baseChip} px-2.5 ${active ? activeChip : idleChip}`}
          >
            {g}
          </button>
        );
      })}

      <div className="ml-auto flex-1 min-w-[200px] max-w-md">
        <input
          type="search"
          placeholder="Search code, designation, trade…"
          value={query}
          onChange={(e) => onQueryChange(e.target.value)}
          className="w-full rounded-md border border-hairline bg-ivory px-3 py-1.5 text-[13px] text-charcoal placeholder:text-ash focus:border-gold/50 focus:outline-none focus:ring-1 focus:ring-gold/40"
        />
      </div>
    </div>
  );
}

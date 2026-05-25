"use client";

/**
 * Compact chip that surfaces the user's current HDS retrieval scope at the top
 * of the chat panel. With nothing selected, it acts as a CTA to open the
 * selector modal; once one or more versions are picked it summarises them in a
 * blue pill and exposes an "edit" / "clear" affordance.
 *
 * The `HdsVersion` type is imported from `@/lib/api/hdsApi` (Track A). If that
 * file has not landed yet at compile time, we fall back to a structural
 * minimum so this component still type-checks on its own — Track A's commit
 * will simply augment the shape rather than replace it.
 */

// Track A owns `hdsApi.ts`. If it has not landed yet we accept any object with
// `id` + `versionLabel` so this component stays self-contained.
type HdsVersionLike = {
  id: string;
  versionLabel: string;
};

interface Props {
  selected: HdsVersionLike[];
  onEdit: () => void;
  onClear: () => void;
}

export default function HdsScopeChip({ selected, onEdit, onClear }: Props) {
  if (selected.length === 0) {
    return (
      <button
        type="button"
        onClick={onEdit}
        className="inline-flex items-center gap-1 px-3 py-1 text-xs border border-dashed border-border rounded-full text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors"
        title="Select HDS publications to ground answers in cited content"
      >
        <span aria-hidden>📚</span>
        <span>Select HDS sources</span>
      </button>
    );
  }

  const label =
    selected.length <= 2
      ? selected.map((v) => v.versionLabel).join(", ")
      : `${selected[0].versionLabel} + ${selected.length - 1} more`;

  return (
    <div className="inline-flex items-center gap-2 px-3 py-1 text-xs bg-info/10 border border-info/30 text-info rounded-full">
      <span className="truncate max-w-[180px]" title={selected.map((v) => v.versionLabel).join(", ")}>
        <span aria-hidden>📚</span> HDS: <strong className="font-mono">{label}</strong>
      </span>
      <button
        type="button"
        onClick={onEdit}
        className="text-info hover:underline"
        title="Edit HDS selection"
      >
        edit
      </button>
      <button
        type="button"
        onClick={onClear}
        className="text-text-muted hover:underline"
        title="Clear HDS scope"
      >
        clear
      </button>
    </div>
  );
}

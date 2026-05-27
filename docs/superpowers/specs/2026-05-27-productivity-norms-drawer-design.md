# Productivity Norms — Edit Drawer + Helper Text Refresh

**Date:** 2026-05-27
**Scope:** `frontend/src/app/(app)/admin/productivity-norms/page.tsx`
**Driver:** When a user scrolls down the norms list and clicks **Edit** on a row, the inline form renders at the top of the page. The page scrolls back to the top, but on a long list the user often can't tell anything happened. They lose context (which row they were editing, where in the list they were) and sometimes click Edit again thinking nothing happened.

## Goals

1. Replace the inline at-top form with a right-side **drawer** so the list stays in place under it. The drawer pattern already exists for Resource Roles (`admin/resource-roles/page.tsx:344-384`) — match that pattern exactly for consistency.
2. Highlight the **helper text** under each field so users can read it at a glance. Today it's `text-xs text-text-muted` and disappears against the surface; users miss the guidance that explains scope chains, CPWD baselines, and the Output/Day-vs-multiplication rule.

## Non-goals

- No changes to the form fields, validation, or API contract.
- No changes to the list grouping, columns, or scope-badge logic.
- No new endpoints, no backend changes.

## Drawer pattern (mirrors Resource Roles)

```
┌── backdrop (fixed inset-0, z-30, bg-black/50, click closes) ──┐
│                                                ┌─ aside ────┐ │
│                                                │ Header     │ │
│                                                │ Edit Norm  │ │
│                                                │      [ ✕ ] │ │
│                                                ├────────────┤ │
│   (norms list stays here)                      │ scrollable │ │
│                                                │ form body  │ │
│                                                │            │ │
│                                                │            │ │
│                                                ├────────────┤ │
│                                                │ [Cancel] [Save Norm] (sticky)
│                                                └────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

- `<aside>` is `fixed right-0 top-0 z-40 flex h-screen w-full flex-col border-l border-border bg-paper shadow-xl md:w-[720px] lg:w-[880px]` — same widths as the Resource Roles drawer.
- Header: title + close `✕` button (matches existing drawer header markup).
- Body: `flex-1 overflow-y-auto px-5 py-4` — the form fields live here.
- Footer: a sticky action row at the bottom (Cancel + Save/Update) so the primary action stays visible while the user scrolls the long form. Pattern: `<div className="border-t border-hairline bg-paper px-5 py-3 flex justify-end gap-2">`.
- Drop the auto-scroll-to-top in `handleEdit` — no longer needed since the drawer overlays in place.
- ESC key + backdrop click both call `cancelForm()`.

## Helper text — subtle info card

Today each helper paragraph looks like:

```tsx
<p className="text-xs text-text-muted mt-1">
  What ONE worker produces in a normal 8-hour day…
</p>
```

Replace with a small reusable inline component:

```tsx
function FieldHint({ children }: { children: React.ReactNode }) {
  return (
    <div className="mt-1.5 flex gap-2 rounded-md border-l-2 border-info/40 bg-info/5 px-2.5 py-1.5">
      <Info size={13} className="mt-0.5 flex-shrink-0 text-info/70" strokeWidth={1.75} />
      <p className="text-xs leading-relaxed text-text-secondary">{children}</p>
    </div>
  );
}
```

Why:
- The tinted background (`bg-info/5`) and 2-px left bar give it visual weight without shouting.
- `text-text-secondary` instead of `text-text-muted` raises contrast so the body is actually readable.
- The `ℹ` icon from `lucide-react` (already imported across the codebase) signals "this is guidance, not validation".
- Apply to **every** helper paragraph in the form: Work Activity, Scope (the long resolver-chain explanation), Unit, Output/Man/Day, Crew Size, Output/Day, Equipment Spec, and the two cross-field info boxes (the bigger ones already use `bg-info/5` — leave those, but they get the same Info icon for consistency).

The existing scope-radio explainer (the `<p>` after the radios that explains `variant → role → unscoped`) gets the same treatment. The two existing pale-blue cross-field info blocks (Manpower's "Fill Output per Man per Day + Crew Size…" and Equipment's "Enter the daily norm directly…") stay as-is structurally but pick up the same `Info` icon for visual consistency with the new field hints.

## What stays the same

- All field labels, ordering, validation, and API payloads.
- The grouped list rendering below the form area.
- The `Add Norm` / `Cancel` toggle on the page header — clicking it opens the drawer in create mode (same behavior, just rendered as a drawer now).
- The "scrolled-down → click Edit → drawer opens over your current scroll position" is the new behavior we want; no scroll restoration needed.

## Risks / edge cases

- The form is ~400 lines of JSX. The diff is mostly mechanical (wrap the existing `<form>` in the drawer shell, swap each helper `<p>` for `<FieldHint>`). No state-management changes.
- Need to make sure the existing `cancelForm()` is wired to backdrop + ✕ + ESC. Resource Roles already handles this — we copy.
- Need to confirm `Info` is imported from `lucide-react` at the top of the file (the page already imports `Pencil`, `Trash2`; we add `Info`).

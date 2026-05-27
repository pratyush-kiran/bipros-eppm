/**
 * Material Symbols icon — used on the rebuilt project hub to match the M3 mockup.
 * The font is loaded in `app/layout.tsx`; the base `.material-symbols-outlined`
 * rule lives in `globals.css`. Decorative by default (aria-hidden); pass a label
 * via the surrounding element when the icon conveys meaning.
 */
export function MIcon({
  name,
  className = "",
  filled = false,
}: {
  name: string;
  className?: string;
  filled?: boolean;
}) {
  return (
    <span
      aria-hidden="true"
      className={`material-symbols-outlined select-none leading-none ${className}`}
      style={filled ? { fontVariationSettings: "'FILL' 1" } : undefined}
    >
      {name}
    </span>
  );
}

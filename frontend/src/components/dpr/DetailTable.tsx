"use client";

type AccentKey = "emerald" | "bronze" | "steel";

interface Props {
  title: string;
  empty: string;
  headers: string[];
  rows: Array<Array<string | number>>;
  /** Index where numeric columns start (right-aligned, tabular-nums). */
  numericFromIndex?: number;
  /** Category accent — drives left stripe, header tint, and title dot. */
  accent?: AccentKey;
}

const ACCENT_VAR: Record<AccentKey, string> = {
  emerald: "var(--emerald)",
  bronze: "var(--bronze-warn)",
  steel: "var(--steel)",
};

/**
 * Small tabular detail block used inside a DPR work-front expansion (manpower /
 * equipment / material). Each table reads as a labelled "tray": a 2px accent
 * stripe on the left, a tinted header row, and zebra body rows so entries stay
 * legible in both light and dark themes.
 */
export function DetailTable({
  title,
  empty,
  headers,
  rows,
  numericFromIndex,
  accent = "steel",
}: Props) {
  const accentColor = ACCENT_VAR[accent];
  const headerBg = `color-mix(in srgb, ${accentColor} 12%, transparent)`;
  const isNumeric = (i: number) =>
    numericFromIndex != null && i >= numericFromIndex;

  const titleBlock = (
    <div className="mb-1 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-slate">
      <span
        aria-hidden
        className="inline-block h-1.5 w-1.5 rounded-full"
        style={{ backgroundColor: accentColor }}
      />
      {title}
    </div>
  );

  if (rows.length === 0) {
    return (
      <div>
        {titleBlock}
        <div className="text-xs text-ash">{empty}</div>
      </div>
    );
  }

  return (
    <div>
      {titleBlock}
      <div
        className="overflow-x-auto rounded-md border border-hairline border-l-2"
        style={{ borderLeftColor: accentColor }}
      >
        <table className="w-full text-xs">
          <thead style={{ backgroundColor: headerBg }}>
            <tr>
              {headers.map((h, i) => (
                <th
                  key={h}
                  className={`px-2 py-1.5 font-semibold uppercase tracking-wide text-[11px] text-charcoal/80 ${
                    isNumeric(i) ? "text-right" : "text-left"
                  }`}
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr
                key={i}
                className="border-t border-hairline odd:bg-paper even:bg-ivory/50 transition-colors hover:bg-[var(--accent-glow)]"
              >
                {r.map((c, j) => {
                  const numeric = isNumeric(j);
                  const base = "px-2 py-1.5 text-charcoal";
                  const cls = numeric
                    ? `${base} text-right tabular-nums font-medium`
                    : j === 0
                      ? `${base} font-medium`
                      : base;
                  return (
                    <td key={j} className={cls}>
                      {c}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

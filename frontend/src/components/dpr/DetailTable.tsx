"use client";

interface Props {
  title: string;
  empty: string;
  headers: string[];
  rows: Array<Array<string | number>>;
}

/**
 * Small tabular detail block used inside a DPR work-front expansion (manpower /
 * equipment / material). Lifted out of the deprecated {@code DprActivityCard} so the
 * new {@code DprWorkFrontRow} can reuse it without dragging in the rest of that card.
 *
 * <p>Visual tone matches the Site-Ledger aesthetic: hairline border, ivory header
 * row, charcoal body, small caps title.
 */
export function DetailTable({ title, empty, headers, rows }: Props) {
  if (rows.length === 0) {
    return (
      <div className="text-xs text-ash">
        <span className="font-semibold text-slate">{title}:</span> {empty}
      </div>
    );
  }
  return (
    <div>
      <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate">
        {title}
      </div>
      <div className="overflow-x-auto rounded-md border border-hairline">
        <table className="w-full text-xs">
          <thead className="bg-ivory/60">
            <tr>
              {headers.map((h) => (
                <th
                  key={h}
                  className="px-2 py-1 text-left font-semibold text-slate"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="border-t border-hairline">
                {r.map((c, j) => (
                  <td key={j} className="px-2 py-1 text-charcoal">
                    {c}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

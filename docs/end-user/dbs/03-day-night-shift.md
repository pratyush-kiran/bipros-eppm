# Day & Night Shift Tracking

## Why it matters
Many road / heavy-civil projects run 24-hour operations. Equipment-day and
manpower-day rates depend on shift, and the Excel "Eqpmnt & MP Days" report
splits utilisation by Day / Night.

## How to log a shift
On the DPR form, each manpower row and each equipment row now has a
**Shift** selector (default DAY).
- For supervisors who only run a day shift: leave it on DAY.
- For mixed-shift days: add the night-shift entries as separate rows with
  the same equipment type, set Shift = NIGHT.

## How it shows up
- **Equipment Register** (PM tab) — Day and Night columns per CM.
- **Cumulative Days** — Day-days vs Night-days summed since project start.
- **Cost sections** — shift doesn't change the per-line rate today; it's a
  reporting dimension only. If your site needs shift-differential rates,
  configure them on the equipment / manpower rate master and select the
  variant on the DPR row.

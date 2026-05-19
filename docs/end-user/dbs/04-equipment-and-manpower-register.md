# Equipment & Manpower Register

## What you see
On the PM tab, scroll past the totals to find two panels:

### Equipment Register
| Equipment | Total | CM₁ Day | CM₁ Night | CM₂ Day | CM₂ Night |
|---|---|---|---|---|---|
| Grader | 3 | 1 | 0 | 1 | 1 |
| Excavator | 2 | 1 | 0 | 0 | 1 |
| … | | | | | |

### Manpower Register
| Trade | Total | CM₁ Day | CM₁ Night | CM₂ Day | CM₂ Night |
|---|---|---|---|---|---|
| Mason | 8 | 5 | 0 | 3 | 0 |
| Carpenter | 4 | 2 | 0 | 2 | 0 |
| … | | | | | |

## What the numbers mean
Each cell = how many of that equipment / how many people of that trade
were deployed *on this day* by supervisors who report up to that CM.

## Cumulative Days
Toggle "**Cumulative Days**" at the top of the panel to switch from
"deployed today" to "total days deployed since project start" — this is
what the Excel "Eqpmnt & MP Days" sheet produces.

## API endpoints (for integrators / verification)
| Purpose | Method + Path |
|---|---|
| Today's equipment register | `GET /v1/projects/{projectId}/dbs/register/equipment?date=YYYY-MM-DD` |
| Today's manpower register | `GET /v1/projects/{projectId}/dbs/register/manpower?date=YYYY-MM-DD` |
| Cumulative days | `GET /v1/projects/{projectId}/dbs/register/cumulative?asOf=YYYY-MM-DD` |

Each accepts an optional `cmUserId` query parameter to filter to a single CM's
downline.

## Where the data comes from
The register is recomputed automatically every time:
- A DPR is submitted (or updated).
- An equipment / labour deployment is logged.
- A material consumption is logged.

If a number looks wrong, you can force a recompute from the PM tab —
click the ⟳ button (admin / PM only).

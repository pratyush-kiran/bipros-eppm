# Preliminaries (PRE)

## What are Preliminaries?
In civil construction contracts, **Preliminaries** are BOQ line items in
**Section 1** that aren't direct production. Examples (from a typical road
project):
- Mobilization & demobilization (BOQ 1.4.3(i))
- Soil investigation & report (BOQ 1.3.5(i)a)
- Maintenance of contractor's site office (BOQ 1.4.3(ii))
- Maintenance & protection of diversions (BOQ 1.7.6(i)a)
- Sub-base on diversions (BOQ 1.7.6(ii))
- Bituminous course on diversions (BOQ 1.7.6(iii))
- Bonds, insurance, performance guarantees
- Site facilities, signage, lighting

Prelims are real, payable BOQ items — the client pays the contractor for
them — but they represent **overhead**, not direct production. They're
usually 5–15 % of contract value and are typically managed by the PM/CM,
not by individual supervisors.

## How to flag a BOQ item as Preliminary
1. **Admin → Activities → <your activity>**
2. Tick the **"Preliminary item (BOQ Section 1)"** checkbox.
3. Save.

This sets the activity's `is_preliminary` column to `true`. The DPR flow does
not change. Supervisors continue to log progress against preliminary BOQ items
in the normal way. The system simply tags the resulting cost as `prelim_cost`
instead of `direct_cost` when rolling up.

## Where Preliminaries show up
On every DBS tab (Supervisor → PM) you'll see two columns under BOQ:
- **Direct Cost** (`direct_cost`) — sum across non-preliminary activities.
- **Prelim Cost** (`prelim_cost`) — sum across preliminary activities.

The PM tab adds a top-line tile:
- **Cost incl Prelims** (`total_cost_incl_prelims`) = Direct Cost + Prelim Cost

This is the figure the "Summary-Financial" sheet of the Excel template
calls "Cost to date including prelims".

## Why this matters
Without the prelim split, a project that's behind on direct production can
look healthy because mobilization expenses front-load the cost curve. The
split lets the PM see Direct contribution % separately from total.

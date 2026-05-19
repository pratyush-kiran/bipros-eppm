# Construction Manager (CM) Role

## Who is a CM?
A Construction Manager owns a portfolio of Site Managers (or, on smaller projects,
Engineers directly). The CM sits between the Project Manager and the Site
Managers in the reporting chain.

Reporting chain:
**PM → Construction Manager → Site Manager → Engineer → Supervisor**

The CM is optional. If your project doesn't use CMs, Site Managers report
directly to the PM and the DBS rollup skips the CM tier automatically.

## How to assign a CM
1. Open the project → **Team** in the left rail.
2. In the **Construction Manager** section, click **+ Add**.
3. Pick the user; their "Reports To" defaults to the PM.
4. Save. Existing Site Managers in this project can now be re-pointed to
   report to this CM (edit each Site Manager row → change "Reports To").

## How CMs see DBS
A user with the CONSTRUCTION_MANAGER role can:
- View the DBS Construction Manager tab and see their own slice.
- View Site Manager / Engineer / Supervisor tabs filtered to their downline.
- Export their slice as Excel / PDF.

A CM cannot view another CM's slice unless they are also the project's PM.

## How equipment/manpower attribution to a CM works
We don't ask supervisors to pick a CM when logging equipment. Instead the
system walks the supervisor's reporting chain at the end of each day and
assigns every piece of equipment/labour to the CM at the top of that chain.

This means: if you re-org the team mid-project, **historical DBS reports
stay stable** — they remember which CM owned the work at the time it was
logged. Future logs flow to the new CM automatically.

The resolved CM is stored on every supervisor's daily row as
`construction_manager_user_id`, so historical CM attribution is a snapshot,
not a live walk of the team.

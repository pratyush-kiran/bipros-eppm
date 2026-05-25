import { expect, test } from "@playwright/test";
import { login } from "./fixtures/auth.fixture";

/**
 * End-to-end roundtrip for the DPR sub-contractor workdone feature:
 *
 *   1. Plan a sub-contractor on an activity (Activities tab → Resource Plan).
 *   2. Create a DPR for that activity with qtyExecuted = 80 and an SC row qty = 30.
 *   3. Re-open the activity drawer and confirm the Resource Plan shows
 *      actualUnits = 30 and remainingUnits = 70 for that SC row.
 *
 * SCAFFOLD NOTE — the selectors below are placeholders and almost certainly need
 * adjustment against the actual rendered markup. The test depends on seeded data:
 *   - A project visible on /projects (name contains NHAI / ICPMS / Oman / Khasab).
 *   - An activity on that project whose workActivity.defaultUnit matches a
 *     sub-contractor mapping unit (Apex Concrete Solutions has Cum mappings).
 *   - The sub-contractor master "Apex Concrete Solutions" with a Cum-unit
 *     work-activity mapping that lines up with the chosen activity.
 *
 * Run with: pnpm test:e2e dpr-subcontractor.spec.ts
 */

test.describe("DPR sub-contractor workdone roundtrip", () => {
  test("plan + DPR sub-contractor reflects in Resource Plan", async ({ page }) => {
    // 1. Log in as admin/admin123 using the shared fixture (seeds cookie + store).
    await login(page, "admin", "admin123");

    // 2. Navigate to the projects list and open a seeded project.
    //    SCAFFOLD: project name regex is permissive; adjust to your seed.
    await page.goto("/projects");
    await page
      .getByText(/NHAI|ICPMS|Oman|Khasab/i)
      .first()
      .click();

    // 3. Open the Activities tab and pick the first activity.
    //    SCAFFOLD: the tab name and row index may differ; ideally find an
    //    activity whose unit matches a seeded sub-contractor mapping (e.g. "Cum").
    await page.getByRole("tab", { name: /Activities/i }).click();
    await page.getByRole("row").nth(1).click();

    // 4. Plan a sub-contractor (e.g. Apex Concrete, plannedUnits = 100).
    //    SCAFFOLD: this entire block is the placeholder area most likely to need
    //    rework — the activity drawer wraps SubContractorSection inside
    //    RoleDemandSections; the exact button label and modal flow depends on
    //    the live markup. Adjust against `RoleDemandSections.tsx`.
    await page
      .getByRole("button", { name: /Sub-Contractor|Add sub-contractor/i })
      .first()
      .click();
    await page
      .getByRole("combobox", { name: /Sub-contractor/i })
      .first()
      .click();
    await page.getByText(/Apex Concrete/i).first().click();
    await page
      .getByRole("combobox", { name: /Work activity/i })
      .first()
      .click();
    // Pick the first mapping with matching unit.
    await page.getByRole("option").first().click();
    await page.getByLabel(/Planned units|Planned qty/i).fill("100");
    await page.getByRole("button", { name: /Save|Add/i }).click();

    // 5. Create a DPR for this activity with qtyExecuted = 80.
    //    SCAFFOLD: the form opens in a drawer; the exact label of the workdone
    //    input is "WORKDONE QUANTITY" in DprActivityForm.tsx today.
    await page.getByRole("button", { name: /Create DPR|New DPR/i }).click();
    await page.getByLabel(/WORKDONE QUANTITY/i).fill("80");

    // 6. Switch to the Sub-Contractor tab and add a row with quantity = 30.
    //    SCAFFOLD: the SC tab is rendered conditionally inside DprActivityForm.
    await page.getByRole("tab", { name: /Sub-Contractor/i }).click();
    await page.getByRole("button", { name: /Add sub-contractor/i }).click();
    // Pick the planned assignment we just created.
    await page
      .getByRole("combobox")
      .filter({ hasText: /planned|Pick planned/ })
      .first()
      .click();
    await page.getByText(/Apex Concrete.*planned/i).first().click();
    // Fill the Qty cell — last numeric input in the SC grid row.
    await page
      .locator('input[type="number"]')
      .last()
      .fill("30");

    // 7. Save the DPR.
    await page.getByRole("button", { name: /Save changes|Save DPR|Save/i }).click();

    // 8. Re-open the activity drawer and confirm the Resource Plan shows
    //    actualUnits = 30 and remainingUnits = 70 for the Apex row.
    //    SCAFFOLD: the activity drawer may already be re-opened by the save flow,
    //    or you may need to navigate back to Activities and click the row again.
    await page.getByRole("tab", { name: /Activities/i }).click();
    await page.getByRole("row").nth(1).click();

    const apexRow = page
      .getByText(/Apex Concrete/i)
      .first()
      .locator("xpath=ancestor::tr");
    await expect(apexRow).toContainText("30"); // actualUnits
    await expect(apexRow).toContainText("70"); // remainingUnits = 100 - 30
  });
});

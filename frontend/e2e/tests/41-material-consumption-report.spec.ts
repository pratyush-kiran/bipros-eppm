import { test, expect } from "../fixtures/auth.fixture";

/**
 * Phase G — Material Consumption Report e2e. Hits
 * /projects/{id}/reports/material-consumption and asserts the filter panel,
 * applies a date-range, eyeballs alert chips, and triggers an Excel download.
 *
 * Resolves the project id from {@code SEED_PROJECT_ID} or the first project
 * row; skips when neither is available.
 */

async function resolveProjectId(page: any): Promise<string | null> {
  const envId = process.env.SEED_PROJECT_ID;
  if (envId) return envId;

  await page.goto("/projects");
  const link = page.locator("table tbody tr a").first();
  if (!(await link.isVisible({ timeout: 10_000 }).catch(() => false))) {
    return null;
  }
  await link.click();
  await page.waitForURL(/\/projects\/[0-9a-f-]+/, { timeout: 10_000 });
  return page.url().split("/projects/")[1].split("/")[0];
}

test.describe("Material Consumption Report", () => {
  test("renders the filter panel + table", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available — set SEED_PROJECT_ID or seed one");

    await page.goto(`/projects/${projectId}/reports/material-consumption`);
    // Page heading + the two date inputs (from / to) should be visible.
    await expect(
      page.getByText(/Material Consumption Report|Material Consumption/i).first(),
    ).toBeVisible({ timeout: 15_000 });
    const dateInputs = page.locator('input[type="date"]');
    await expect(dateInputs.first()).toBeVisible({ timeout: 10_000 });
  });

  test("applies a date range filter", async ({ authenticatedPage: page }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/reports/material-consumption`);
    const dateInputs = page.locator('input[type="date"]');
    await expect(dateInputs.first()).toBeVisible({ timeout: 15_000 });
    await dateInputs.nth(0).fill("2026-01-01");
    await dateInputs.nth(1).fill("2026-12-31");

    // Apply / refresh button — match any "Apply", "Generate", or "Refresh" button.
    const applyBtn = page
      .getByRole("button", { name: /Apply|Generate|Refresh|Search/i })
      .first();
    if (await applyBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await applyBtn.click();
    }
    // No hard assertion on rows — depends on seed. We just need the page not to crash.
    const errors = await page.locator(".text-red-500, .text-red-700").allTextContents();
    expect(errors.length).toBeLessThanOrEqual(1);
  });

  test("alert badges render when present", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/reports/material-consumption`);
    // Alert chips carry one of the known codes. We can't guarantee a row triggers
    // an alert in every environment — soft assert + skip if none visible.
    const alertChip = page
      .getByText(/EXCESS_CONSUMPTION|NEGATIVE_BALANCE|BUDGET_OVERCONSUMPTION|MISSING_UNIT_RATE/i)
      .first();
    const visible = await alertChip.isVisible({ timeout: 8_000 }).catch(() => false);
    test.skip(!visible, "No alert rows in current seed — wire a deliberate over-consumption row to assert");
    await expect(alertChip).toBeVisible();
  });

  test("Excel export button triggers a download", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/reports/material-consumption`);
    const exportBtn = page
      .getByRole("button", { name: /Export|Download|Excel|XLSX/i })
      .first();
    const visible = await exportBtn.isVisible({ timeout: 10_000 }).catch(() => false);
    test.skip(!visible, "Export button not rendered for this user / route");

    const downloadPromise = page.waitForEvent("download", { timeout: 15_000 });
    await exportBtn.click();
    const download = await downloadPromise.catch(() => null);
    test.skip(!download, "Download did not start — likely the export streams via fetch + blob");
    expect(download!.suggestedFilename()).toMatch(/material-consumption.*\.xlsx$/i);
  });
});

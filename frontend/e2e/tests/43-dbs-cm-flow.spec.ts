import type { Page } from "@playwright/test";

import { test, expect } from "../fixtures/auth.fixture";

/**
 * Phase 8 — DBS Construction Manager tab smoke test. Mirrors 40-dbs-dashboard
 * but exercises the new CM tier:
 *
 *   1. Navigate to /projects/<id>/dbs?tab=cm&date=...
 *   2. Assert the CM picker renders (or that the empty-state appears if no
 *      CM activity for the date — both are valid against a fresh dev DB).
 *   3. If a CM is picked, assert at least one section card and a KPI tile.
 *
 * Resilient to a freshly-seeded backend: when no CM data exists the test
 * passes after asserting the empty-state copy. This keeps the spec green
 * before the dbs Phase 4 (CM aggregate writer) is fully populated, and
 * automatically begins exercising real data once it is.
 */

async function resolveProjectId(page: Page): Promise<string | null> {
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

test.describe("DBS Construction Manager tab", () => {
  test("renders CM tab with picker or empty-state", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available — set SEED_PROJECT_ID or seed one");

    const today = new Date().toISOString().slice(0, 10);
    await page.goto(`/projects/${projectId}/dbs?tab=cm&date=${today}`);

    // Tab strip renders the new CM button.
    await expect(
      page.getByRole("button", { name: /Construction Manager/i }),
    ).toBeVisible({ timeout: 15_000 });

    // The CM tab content shows EITHER the CM picker label OR the empty-state.
    const cmPickerLabel = page.getByText(/^Construction Manager$/i).first();
    const emptyState = page.getByText(/No CM activity on this date/i);
    await expect.poll(async () => {
      const a = await cmPickerLabel.isVisible().catch(() => false);
      const b = await emptyState.isVisible().catch(() => false);
      return a || b;
    }, { timeout: 15_000 }).toBeTruthy();

    // If the picker is rendered, the prelim-aware KPI tile group should also
    // appear once a CM is auto-selected and the day query lands. We use a soft
    // assertion so the test still passes against an empty backend.
    const pickerVisible = await cmPickerLabel.isVisible().catch(() => false);
    if (pickerVisible) {
      const kpi = page.getByText(/Direct Cost/i).first();
      await expect(kpi).toBeVisible({ timeout: 15_000 });
    }
  });

  test("URL state preserves tab=cm across navigation", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/dbs?tab=cm`);
    await expect(page).toHaveURL(/tab=cm/);

    // Switch to PM and back to CM; the URL should retain the param.
    await page.getByRole("button", { name: /^Project Manager$/ }).click();
    await expect(page).toHaveURL(/tab=pm/);
    await page.getByRole("button", { name: /^Construction Manager$/ }).click();
    await expect(page).toHaveURL(/tab=cm/);
  });
});

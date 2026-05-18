import { test, expect } from "../fixtures/auth.fixture";

/**
 * Phase G — DBS Dashboard smoke tests. Mirrors the page-load assertion pattern
 * from 13-reports.spec.ts but exercises the URL-state tab/period/date wiring on
 * the Daily Balance Sheet page.
 *
 * Each test resolves a project id from {@code SEED_PROJECT_ID} (set by the
 * Phase G seeder) or the first project in the projects list. When neither is
 * available the test is skipped — keeps the spec safe to run against a fresh
 * dev DB without crashing the whole suite.
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

test.describe("DBS Dashboard", () => {
  test("shows three tabs and switches between them", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available — set SEED_PROJECT_ID or seed one");

    await page.goto(`/projects/${projectId}/dbs`);
    await expect(
      page.getByRole("heading", { name: /Daily Balance Sheet/i, level: 1 }),
    ).toBeVisible({ timeout: 15_000 });

    // All three tab buttons render on first paint.
    for (const label of [
      /Supervisor/i,
      /Engineer.*Site Manager/i,
      /Project Manager/i,
    ]) {
      await expect(page.getByRole("button", { name: label })).toBeVisible();
    }

    await page.getByRole("button", { name: /Engineer.*Site Manager/i }).click();
    await expect(page).toHaveURL(/tab=engineer/);

    await page.getByRole("button", { name: /Project Manager/i }).click();
    await expect(page).toHaveURL(/tab=pm/);
  });

  test("date picker pushes the selected date into the URL", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/dbs`);
    const dateInput = page.locator('input[type="date"]').first();
    await expect(dateInput).toBeVisible({ timeout: 15_000 });
    await dateInput.fill("2026-01-15");
    await expect(page).toHaveURL(/date=2026-01-15/);
  });

  test("period toggle switches between DAY / WEEK / MONTH in the URL", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/dbs`);
    await page.getByRole("button", { name: /^Week$/ }).click();
    await expect(page).toHaveURL(/period=WEEK/);

    await page.getByRole("button", { name: /^Month$/ }).click();
    await expect(page).toHaveURL(/period=MONTH/);
  });

  test("PM tab renders a totals / summary panel", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/dbs?tab=pm`);
    // The PM tab paints a heading + tiles; the tiles are bordered cards rendered
    // by PmDbsTab. We just need to see *something* PM-specific.
    await expect(
      page
        .getByText(/Revenue|BOQ for the Day|Cost|EBIT|Margin/i)
        .first(),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("Recompute button opens a confirm dialog", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available");

    await page.goto(`/projects/${projectId}/dbs?tab=pm`);
    const recompute = page.getByRole("button", { name: /Recompute/i }).first();
    const exists = await recompute.isVisible({ timeout: 10_000 }).catch(() => false);
    test.skip(!exists, "PM Recompute button not available for this user / project state");

    await recompute.click();
    await expect(page.getByRole("dialog")).toBeVisible({ timeout: 5_000 });
  });
});

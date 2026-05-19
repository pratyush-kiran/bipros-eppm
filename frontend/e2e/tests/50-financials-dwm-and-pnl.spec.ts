import { test, expect } from "../fixtures/auth.fixture";
import type { Page } from "@playwright/test";

/**
 * Smoke tests for the three new project-financials pages added in this branch:
 *   1. Performance (Daily / Weekly / Monthly) dashboard — /projects/[id]/performance
 *   2. P&L vs Budgeted Unit Rates — /projects/[id]/pnl/budgeted
 *   3. P&L vs BOQ Rates — /projects/[id]/pnl/boq
 *
 * Goal: prove that the pages mount, the cadence toggle wires through to the API,
 * and the KPI / table chrome renders. Numeric reconciliation lives in the backend
 * integration tests; this spec is the click-through layer.
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

test.describe("Financials — Performance & P&L", () => {
  test("Performance D/W/M dashboard renders KPIs and cadence toggle", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No project available — set SEED_PROJECT_ID or seed one");

    await page.goto(`/projects/${projectId}/performance`);

    await expect(
      page.getByRole("heading", { name: /^Performance$/i, level: 1 }),
    ).toBeVisible({ timeout: 15_000 });

    // Cadence toggle — all three options present, Monthly is the default.
    for (const label of [/Daily/i, /Weekly/i, /Monthly/i]) {
      await expect(page.getByRole("button", { name: label })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: /Monthly/i })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    // KPI tiles render their labels even when the underlying numbers are zero.
    for (const label of [/Actual Cost/i, /Earned Value/i, /Planned Value/i, /^CPI$/i, /^SPI$/i]) {
      await expect(page.getByText(label).first()).toBeVisible();
    }

    // Toggling cadence triggers a fresh fetch — the new request is enough proof
    // that the toggle is wired up.
    const dailyRequest = page.waitForRequest(
      (req) => req.url().includes(`/v1/projects/${projectId}/performance`) &&
              req.url().includes("periodType=D"),
      { timeout: 10_000 },
    );
    await page.getByRole("button", { name: /Daily/i }).click();
    await dailyRequest;
    await expect(page.getByRole("button", { name: /Daily/i })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  test("P&L vs Budgeted Rates hits all four endpoints", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No project available");

    const seen = new Set<string>();
    page.on("request", (req) => {
      const u = req.url();
      const m = u.match(/\/v1\/projects\/[^/]+\/pnl\/budgeted\/(items|activities|periods|summary)/);
      if (m) seen.add(m[1]);
    });

    await page.goto(`/projects/${projectId}/pnl/budgeted`);

    await expect(
      page.getByRole("heading", { name: /P&L vs Budgeted Unit Rates/i, level: 1 }),
    ).toBeVisible({ timeout: 15_000 });

    // The view fans out to four endpoints on first paint. Give react-query a
    // beat to settle, then verify every read fired.
    await expect.poll(() => seen.size, { timeout: 10_000 }).toBeGreaterThanOrEqual(4);
    for (const segment of ["items", "activities", "periods", "summary"]) {
      expect(seen).toContain(segment);
    }

    // The four KPI tiles are present.
    for (const label of [/Budgeted Revenue/i, /Actual Cost/i, /^Margin$/i, /Margin %/i]) {
      await expect(page.getByText(label).first()).toBeVisible();
    }

    // The three detail tables (period / activity / BOQ-item) render their headers
    // even on an empty dataset.
    await expect(page.getByRole("columnheader", { name: /^Activity$/i })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: /^Item No$/i })).toBeVisible();
  });

  test("P&L vs BOQ Rates renders the BOQ-rate variant", async ({
    authenticatedPage: page,
  }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No project available");

    await page.goto(`/projects/${projectId}/pnl/boq`);

    await expect(
      page.getByRole("heading", { name: /P&L vs BOQ Rates/i, level: 1 }),
    ).toBeVisible({ timeout: 15_000 });

    // Revenue label flips from "Budgeted Revenue" to "BOQ Revenue" on this page.
    await expect(page.getByText(/BOQ Revenue/i).first()).toBeVisible();

    // Toggling to Weekly issues a request against the BOQ scope, not the budgeted one.
    const weeklyRequest = page.waitForRequest(
      (req) => req.url().includes(`/v1/projects/${projectId}/pnl/boq/periods`) &&
              req.url().includes("periodType=W"),
      { timeout: 10_000 },
    );
    await page.getByRole("button", { name: /Weekly/i }).click();
    await weeklyRequest;
  });
});

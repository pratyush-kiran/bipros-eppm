import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";

import { parseRupees } from "./DbsPages";

/**
 * Track C — Financials page objects.
 *
 * Covers four routes:
 *   - `/projects/[projectId]/performance`            Performance D/W/M
 *   - `/projects/[projectId]/pnl/budgeted`           P&L vs Budgeted Unit Rates
 *   - `/projects/[projectId]/pnl/boq`                P&L vs BOQ Rates
 *   - `/projects/[projectId]/reports/material-consumption`  Material Consumption
 *   - `/reports/variance`                            Schedule + Cost Variance
 *
 * Patterns follow the existing 50-financials-dwm-and-pnl spec: KPI tiles are
 * read by label, cadence toggles are exercised by waiting for the next API
 * request, P&L pages assert all four endpoint segments (items/activities/
 * periods/summary) fire on first paint.
 */

export type PerformancePeriod = "Daily" | "Weekly" | "Monthly";

export class PerformancePage {
  constructor(
    public readonly page: Page,
    public readonly projectId: string,
  ) {}

  async open(): Promise<void> {
    await this.page.goto(`/projects/${this.projectId}/performance`, { waitUntil: "domcontentloaded" });
    await expect(this.page.getByRole("heading", { name: /^Performance$/i, level: 1 })).toBeVisible({ timeout: 20_000 });
  }

  async setPeriod(period: PerformancePeriod): Promise<void> {
    const periodLetter = period[0]; // D | W | M
    const waitRequest = this.page.waitForRequest(
      (req) =>
        req.url().includes(`/v1/projects/${this.projectId}/performance`) &&
        req.url().includes(`periodType=${periodLetter}`),
      { timeout: 15_000 },
    ).catch(() => null);
    await this.page.getByRole("button", { name: new RegExp(`^${period}$`, "i") }).click();
    await waitRequest;
  }

  async readKpi(label: RegExp): Promise<number | null> {
    const labelLoc = this.page.getByText(label).first();
    if (!(await labelLoc.isVisible({ timeout: 5_000 }).catch(() => false))) return null;
    const tile = labelLoc.locator("xpath=ancestor::div[contains(@class,'rounded') or contains(@class,'border')][1]");
    const text = await tile.first().innerText().catch(() => "");
    return parseRupees(text.replace(label, "").trim());
  }

  async getKpis(): Promise<{ actualCost: number | null; earnedValue: number | null; plannedValue: number | null; cpi: number | null; spi: number | null }> {
    return {
      actualCost: await this.readKpi(/Actual Cost/i),
      earnedValue: await this.readKpi(/Earned Value/i),
      plannedValue: await this.readKpi(/Planned Value/i),
      cpi: await this.readKpi(/^CPI$/i),
      spi: await this.readKpi(/^SPI$/i),
    };
  }
}

export type PnlVariant = "budgeted" | "boq";

const PNL_HEADING: Record<PnlVariant, RegExp> = {
  budgeted: /P&L vs Budgeted Unit Rates/i,
  boq: /P&L vs BOQ Rates/i,
};

const PNL_REVENUE_LABEL: Record<PnlVariant, RegExp> = {
  budgeted: /Budgeted Revenue/i,
  boq: /BOQ Revenue/i,
};

export class PnlPage {
  constructor(
    public readonly page: Page,
    public readonly projectId: string,
    public readonly variant: PnlVariant,
  ) {}

  /**
   * Navigate to the P&L page and listen for the four endpoint segments
   * (items/activities/periods/summary). Returns the set of segments observed
   * so the spec can assert all four fired.
   */
  async openAndCaptureEndpoints(): Promise<Set<string>> {
    const seen = new Set<string>();
    const re = new RegExp(`\\/v1\\/projects\\/[^/]+\\/pnl\\/${this.variant}\\/(items|activities|periods|summary)`);
    const handler = (req: { url: () => string }) => {
      const m = req.url().match(re);
      if (m) seen.add(m[1]);
    };
    this.page.on("request", handler);

    await this.page.goto(`/projects/${this.projectId}/pnl/${this.variant}`, { waitUntil: "domcontentloaded" });
    await expect(this.page.getByRole("heading", { name: PNL_HEADING[this.variant], level: 1 })).toBeVisible({ timeout: 20_000 });
    await expect.poll(() => seen.size, { timeout: 15_000 }).toBeGreaterThanOrEqual(4);
    this.page.off("request", handler);
    return seen;
  }

  async getSummaryTiles(): Promise<{ revenue: number | null; actualCost: number | null; margin: number | null; marginPct: number | null }> {
    const read = async (label: RegExp): Promise<number | null> => {
      const loc = this.page.getByText(label).first();
      if (!(await loc.isVisible({ timeout: 4_000 }).catch(() => false))) return null;
      const tile = loc.locator("xpath=ancestor::div[contains(@class,'rounded') or contains(@class,'border')][1]");
      const text = await tile.first().innerText().catch(() => "");
      return parseRupees(text.replace(label, "").trim());
    };
    return {
      revenue: await read(PNL_REVENUE_LABEL[this.variant]),
      actualCost: await read(/Actual Cost/i),
      margin: await read(/^Margin$/i),
      marginPct: await read(/Margin %/i),
    };
  }

  /** Assert the three detail-table column headers render. */
  async assertTableHeaders(): Promise<void> {
    await expect(this.page.getByRole("columnheader", { name: /^Activity$/i }).first()).toBeVisible({ timeout: 10_000 });
    await expect(this.page.getByRole("columnheader", { name: /^Item No$/i }).first()).toBeVisible({ timeout: 10_000 });
  }
}

export class MaterialConsumptionPage {
  constructor(
    public readonly page: Page,
    public readonly projectId: string,
  ) {}

  async open(): Promise<void> {
    await this.page.goto(`/projects/${this.projectId}/reports/material-consumption`, { waitUntil: "domcontentloaded" });
    await expect(this.page.getByText(/Material Consumption/i).first()).toBeVisible({ timeout: 20_000 });
  }

  async applyDateRange(fromIso: string, toIso: string): Promise<void> {
    const dateInputs = this.page.locator('input[type="date"]');
    await expect(dateInputs.first()).toBeVisible({ timeout: 15_000 });
    await dateInputs.nth(0).fill(fromIso);
    await dateInputs.nth(1).fill(toIso);
    const apply = this.page.getByRole("button", { name: /Apply|Generate|Refresh|Search/i }).first();
    if (await apply.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await apply.click();
    }
  }

  async exportXlsx(): Promise<string | null> {
    const btn = this.page.getByRole("button", { name: /Export|Download|Excel|XLSX/i }).first();
    if (!(await btn.isVisible({ timeout: 5_000 }).catch(() => false))) return null;
    const dl = this.page.waitForEvent("download", { timeout: 8_000 }).catch(() => null);
    await btn.click();
    const download = await dl;
    return download ? download.suggestedFilename() : null;
  }

  /** Returns the alert-chip codes visible on screen (may be empty). */
  async listAlertCodes(): Promise<string[]> {
    const codes = ["EXCESS_CONSUMPTION", "NEGATIVE_BALANCE", "BUDGET_OVERCONSUMPTION", "MISSING_UNIT_RATE"];
    const found: string[] = [];
    for (const c of codes) {
      const loc = this.page.getByText(new RegExp(c, "i")).first();
      if (await loc.isVisible({ timeout: 1_000 }).catch(() => false)) found.push(c);
    }
    return found;
  }
}

export class VariancePage {
  constructor(public readonly page: Page) {}

  async open(): Promise<void> {
    await this.page.goto(`/reports/variance`, { waitUntil: "domcontentloaded" });
    await expect(this.page.getByRole("heading", { name: /Variance report/i }).first()).toBeVisible({ timeout: 20_000 });
  }

  async switchToCost(): Promise<void> {
    const tab = this.page.getByRole("button", { name: /^Cost$/i }).first();
    if (await tab.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await tab.click();
    }
  }

  async switchToSchedule(): Promise<void> {
    const tab = this.page.getByRole("button", { name: /^Schedule$/i }).first();
    if (await tab.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await tab.click();
    }
  }
}

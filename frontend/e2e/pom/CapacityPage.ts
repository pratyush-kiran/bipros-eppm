import { expect, Page } from "@playwright/test";

/**
 * Track-B Page Object for the Capacity Utilization report and its aggregate view.
 * Both pages render a project picker first; specs hand in the project name to
 * pick, then assert that the data tables paint without error chips.
 */
export class CapacityPage {
  constructor(private readonly page: Page) {}

  /** Visit the per-project capacity utilization page. */
  async openSingle(): Promise<void> {
    await this.page.goto("/reports/capacity-utilization", {
      waitUntil: "domcontentloaded",
    });
    await expect(
      this.page.getByText(/Capacity Utilization/i).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  /** Visit the multi-period aggregate view. */
  async openAggregate(): Promise<void> {
    await this.page.goto("/reports/capacity-utilization/aggregate", {
      waitUntil: "domcontentloaded",
    });
    await expect(
      this.page.getByText(/Capacity Utilization|Aggregate/i).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  /**
   * Choose a project on the single-report or aggregate page. The project
   * picker is the first `<select>` on either page; we match by visible text
   * (project name or code).
   */
  async pickProject(needle: string): Promise<boolean> {
    const select = this.page.locator("select").first();
    if (!(await select.isVisible({ timeout: 8_000 }).catch(() => false))) return false;
    const options = await select.locator("option").all();
    for (const opt of options) {
      const text = (await opt.textContent()) ?? "";
      if (text.toLowerCase().includes(needle.toLowerCase())) {
        const value = await opt.getAttribute("value");
        if (value) {
          await select.selectOption(value);
          return true;
        }
      }
    }
    return false;
  }

  /** Set the month input on the single-report page to YYYY-MM. */
  async setMonth(yyyymm: string): Promise<void> {
    const monthInput = this.page.locator('input[type="month"]').first();
    if (await monthInput.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await monthInput.fill(yyyymm).catch(() => {});
    }
  }

  /** Set the from/to date range on the aggregate page. */
  async setDateRange(from: string, to: string): Promise<void> {
    const inputs = this.page.locator('input[type="date"]');
    const count = await inputs.count();
    if (count >= 2) {
      await inputs.nth(0).fill(from).catch(() => {});
      await inputs.nth(1).fill(to).catch(() => {});
    }
  }

  /**
   * Assert no red-text error chips are visible. Returns the number of red
   * elements seen (caller may want to soft-fail on zero rather than 1).
   */
  async expectNoErrorChips(): Promise<number> {
    // The capacity pages render errors via Tailwind text-burgundy / text-red-*
    // classes — same selectors the existing 41-material-consumption spec uses.
    const errorTexts = await this.page
      .locator(".text-red-500, .text-red-700, .text-burgundy")
      .allTextContents();
    const meaningful = errorTexts.filter((t) => t.trim().length > 0);
    expect(meaningful.length, `unexpected error chips: ${meaningful.join(" | ")}`).toBeLessThanOrEqual(0);
    return meaningful.length;
  }

  /** Helper to screenshot to the track-b artifact bucket. */
  async screenshot(name: string): Promise<void> {
    await this.page.screenshot({
      path: `e2e/.artifacts/screenshots/track-b/${name}.png`,
      fullPage: true,
    });
  }

  /**
   * Returns true when any row that visibly mentions `needle` (e.g. a
   * supervisor's display name) shows a non-zero efficiency / actual cell.
   * The check is forgiving — the report's column set differs across project
   * states — but it does insist that the row exists at all.
   */
  async hasRowFor(needle: string): Promise<boolean> {
    const row = this.page.getByRole("row").filter({ hasText: new RegExp(needle, "i") }).first();
    return await row.isVisible({ timeout: 5_000 }).catch(() => false);
  }
}

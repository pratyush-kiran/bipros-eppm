import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";

/**
 * Track C — DBS page object for `/projects/[projectId]/dbs`.
 *
 * The DBS page is a four-tab dashboard (supervisor / engineer / cm / pm) backed
 * by URL state: `?tab=...&date=YYYY-MM-DD&period=DAY|WEEK|MONTH&supervisor=...`
 * etc. This POM hides the URL-state plumbing behind imperative methods so the
 * specs read like a user clicking through:
 *
 *   const dbs = new DbsPages(page, projectId);
 *   await dbs.open({ tab: "supervisor", date: "2026-04-27", period: "DAY" });
 *   await dbs.switchTab("engineer");
 *   const totals = await dbs.getTotals();
 *
 * Numeric extraction relies on the labels rendered by `TotalsPanel` (Total
 * Expense / Total Income / Contribution / Material / Manpower / Admin /
 * Machinery / Fuel / Sub-Contractor). When a label is not visible — for
 * example because the user landed on an empty CM day — the getter returns
 * `null` rather than throwing. The spec layer is responsible for deciding
 * whether the absence is a bug or expected.
 *
 * All numeric parsing is forgiving: leading currency symbols (₹, $), commas,
 * Indian lakh formatting (1,00,000), parentheses for negatives, and trailing
 * "Cr/L/k" suffixes are all stripped.
 */

export type DbsTab = "supervisor" | "engineer" | "cm" | "pm";
export type DbsPeriod = "DAY" | "WEEK" | "MONTH";

export interface DbsTotals {
  totalExpense: number | null;
  totalIncome: number | null;
  contribution: number | null;
  contributionPct: number | null;
  material: number | null;
  manpower: number | null;
  admin: number | null;
  machinery: number | null;
  fuel: number | null;
  subcontract: number | null;
}

const TAB_LABEL: Record<DbsTab, RegExp> = {
  supervisor: /^Supervisor$/,
  engineer: /^Engineer\s*\/\s*Site Manager$/,
  cm: /^Construction Manager$/,
  pm: /^Project Manager$/,
};

const PERIOD_LABEL: Record<DbsPeriod, RegExp> = {
  DAY: /^Day$/,
  WEEK: /^Week$/,
  MONTH: /^Month$/,
};

export class DbsPages {
  constructor(
    public readonly page: Page,
    public readonly projectId: string,
  ) {}

  /** URL helpers — exposed so specs can `await page.request.get(dbs.apiUrl(...))`. */
  url(params: Partial<{ tab: DbsTab; date: string; period: DbsPeriod; supervisor: string; engineer: string; cm: string }> = {}): string {
    const u = new URLSearchParams();
    if (params.tab) u.set("tab", params.tab);
    if (params.date) u.set("date", params.date);
    if (params.period) u.set("period", params.period);
    if (params.supervisor) u.set("supervisor", params.supervisor);
    if (params.engineer) u.set("engineer", params.engineer);
    if (params.cm) u.set("cm", params.cm);
    const qs = u.toString();
    return `/projects/${this.projectId}/dbs${qs ? `?${qs}` : ""}`;
  }

  apiUrl(scope: "project" | { kind: "supervisor"; userId: string } | { kind: "engineer"; userId: string } | { kind: "cm"; userId: string }, date: string, period: DbsPeriod = "DAY"): string {
    const base = `/v1/projects/${this.projectId}/dbs`;
    const qs = `?date=${date}&periodType=${period}`;
    if (scope === "project") return `${base}/project${qs}`;
    if (scope.kind === "supervisor") return `${base}/supervisor/${scope.userId}${qs}`;
    if (scope.kind === "engineer") return `${base}/engineer/${scope.userId}${qs}`;
    return `${base}/cm/${scope.userId}${qs}`;
  }

  /** Navigate to DBS with the given URL state. Waits for the page heading to paint. */
  async open(params: Partial<{ tab: DbsTab; date: string; period: DbsPeriod; supervisor: string; engineer: string; cm: string }> = {}): Promise<void> {
    await this.page.goto(this.url(params), { waitUntil: "domcontentloaded" });
    await expect(this.page.getByRole("heading", { name: /Daily Balance Sheet/i, level: 1 })).toBeVisible({ timeout: 20_000 });
  }

  async switchTab(tab: DbsTab): Promise<void> {
    await this.page.getByRole("button", { name: TAB_LABEL[tab] }).click();
    await expect(this.page).toHaveURL(new RegExp(`tab=${tab}`));
    // Let react-query refetch settle.
    await this.page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {/* networkidle is best-effort */});
  }

  async setPeriod(period: DbsPeriod): Promise<void> {
    await this.page.getByRole("button", { name: PERIOD_LABEL[period] }).click();
    await expect(this.page).toHaveURL(new RegExp(`period=${period}`));
    await this.page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
  }

  async setDate(iso: string): Promise<void> {
    const dateInput = this.page.locator('input[type="date"]').first();
    await expect(dateInput).toBeVisible({ timeout: 10_000 });
    await dateInput.fill(iso);
    await expect(this.page).toHaveURL(new RegExp(`date=${iso}`));
    await this.page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
  }

  /**
   * Click the PM tab's Recompute button and confirm the dialog.
   * Returns true when a dialog appeared and was confirmed; false if the button
   * was not visible (e.g. for a non-admin role).
   */
  async recompute(): Promise<boolean> {
    const btn = this.page.getByRole("button", { name: /Recompute/i }).first();
    if (!(await btn.isVisible({ timeout: 5_000 }).catch(() => false))) return false;
    await btn.click();
    const dialog = this.page.getByRole("dialog");
    if (!(await dialog.isVisible({ timeout: 5_000 }).catch(() => false))) return false;
    const confirm = dialog.getByRole("button", { name: /Recompute|Confirm|Yes/i }).first();
    if (await confirm.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await confirm.click();
    } else {
      // No explicit confirm button — close via Escape so the spec doesn't hang.
      await this.page.keyboard.press("Escape");
    }
    return true;
  }

  /**
   * Click the export button (Excel / XLSX). Returns the suggested filename
   * when a download started, or `null` when nothing happened in 8s — that
   * usually means the page exports via a blob fetch we can't observe.
   */
  async exportXlsx(): Promise<string | null> {
    const btn = this.page.getByRole("button", { name: /Export|Download|Excel|XLSX/i }).first();
    if (!(await btn.isVisible({ timeout: 5_000 }).catch(() => false))) return null;
    const dl = this.page.waitForEvent("download", { timeout: 8_000 }).catch(() => null);
    await btn.click();
    const download = await dl;
    return download ? download.suggestedFilename() : null;
  }

  /**
   * Best-effort extraction of the numeric totals from the visible TotalsPanel.
   * Reads each labelled tile and parses its currency-formatted value.
   */
  async getTotals(): Promise<DbsTotals> {
    return {
      totalExpense: await this.readTile(/^Total Expense$/i),
      totalIncome: await this.readTile(/^Total Income$/i),
      contribution: await this.readTile(/^Contribution$/i),
      contributionPct: await this.readTile(/^Contribution %$/i),
      material: await this.readTile(/^Material$/i),
      manpower: await this.readTile(/^Manpower$/i),
      admin: await this.readTile(/^Admin\s*\/\s*Catering$/i),
      machinery: await this.readTile(/^Machinery$/i),
      fuel: await this.readTile(/^Fuel$/i),
      subcontract: await this.readTile(/^Sub-?Contractor$/i),
    };
  }

  /**
   * Look up a KPI tile by its label and return its numeric value, or `null`
   * when the tile is absent. KpiTile renders label + value in adjacent nodes;
   * we walk up to the closest tile container and grab the largest text node.
   */
  private async readTile(label: RegExp): Promise<number | null> {
    const labelLoc = this.page.getByText(label, { exact: false }).first();
    if (!(await labelLoc.isVisible({ timeout: 3_000 }).catch(() => false))) return null;
    // KpiTile structure: <div><div class="...label...">Label</div><div class="...value...">₹ 1,23,456</div></div>
    const tile = labelLoc.locator("xpath=ancestor::div[contains(@class,'rounded') or contains(@class,'border') or contains(@class,'tile')][1]");
    const text = await tile.first().innerText().catch(() => "");
    return parseRupees(text.replace(label, "").trim());
  }

  /** Public accessor in case a spec needs to read a single value by label. */
  async readKpiByLabel(label: RegExp): Promise<number | null> {
    return this.readTile(label);
  }
}

/**
 * Parse the rupee-formatted strings the DBS UI emits. Tolerates:
 *   "₹ 1,23,456.78"  (Indian lakh format)
 *   "$1,234.56"
 *   "(₹ 1,000)"      → negative
 *   "₹ 12.3 Cr"      → ×10,000,000
 *   "₹ 1.5 L"        → ×100,000
 *   "₹ 4.2k"         → ×1,000
 *   "12.34%"         → 12.34
 *   "—" / "-" / ""   → null
 */
export function parseRupees(raw: string): number | null {
  if (!raw) return null;
  let s = raw.replace(/\s+/g, " ").trim();
  if (!s || s === "—" || s === "-" || /^N\/?A$/i.test(s)) return null;
  let sign = 1;
  if (/^\(.*\)$/.test(s)) {
    sign = -1;
    s = s.slice(1, -1);
  }
  // Strip currency glyphs and percent signs.
  s = s.replace(/[₹$€£,]/g, "").replace(/%/g, "");
  // Suffix multipliers.
  let mult = 1;
  const suffix = s.match(/([CcLlKk][Rr]?)\s*$/);
  if (suffix) {
    const tag = suffix[1].toLowerCase();
    if (tag.startsWith("c")) mult = 10_000_000;
    else if (tag.startsWith("l")) mult = 100_000;
    else if (tag.startsWith("k")) mult = 1_000;
    s = s.slice(0, suffix.index).trim();
  }
  const n = Number(s.trim());
  if (!Number.isFinite(n)) return null;
  return sign * n * mult;
}

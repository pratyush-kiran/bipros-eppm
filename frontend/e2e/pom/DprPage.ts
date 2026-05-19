import { expect, Page, APIRequestContext } from "@playwright/test";

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/**
 * Track-B Page Object for the DPR screen.
 *
 * The DPR form is a 4-tab drawer driven by debounced productivity-preview
 * queries; reliably filling it via UI for 20 deterministic submissions is
 * extremely flaky. Instead this POM combines two strategies:
 *
 *   - UI helpers (`openDay`, `clickAdd`, `setWeather`, `setDelays`, screenshot)
 *     so specs can visit the page, demonstrate the journey, and screenshot
 *     real renders.
 *   - `submitViaApi(...)` which posts a complete DPR payload through the
 *     authenticated backend (using the access token already seeded by
 *     `loginAsSeeded`). This is the deterministic source of truth for the
 *     20-DPR write that Tracks C and DA consume downstream.
 *
 * Calling code is expected to compute its own per-day quantities from
 * `DPR_DAY_FACTORS` — the POM is intentionally dumb about scenario logic.
 */
export class DprPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  url(date?: string): string {
    const base = `/projects/${this.projectId}/dpr`;
    return date ? `${base}?date=${date}` : base;
  }

  /** Visit the DPR list page (optionally seed a from-date filter).
   *
   * Tolerates the "NO ACCESS" gate that supervisors currently hit: GET /projects/{id}
   * returns 403 for SUPERVISOR team members in this build, so the project layout
   * renders a no-access card instead of the DPR heading. DA finding DA-RBAC-01.
   * We still take a screenshot of whichever page rendered so the campaign records
   * the behavior.
   */
  async open(date?: string): Promise<void> {
    await this.page.goto(this.url(date), { waitUntil: "domcontentloaded" });
    const heading = this.page.getByRole("heading", {
      name: /Daily Progress Report/i,
      level: 1,
    });
    const noAccess = this.page.getByText(/No access/i).first();
    await Promise.race([
      heading.waitFor({ state: "visible", timeout: 15_000 }).catch(() => undefined),
      noAccess.waitFor({ state: "visible", timeout: 15_000 }).catch(() => undefined),
    ]);
  }

  /**
   * Seed the from-date filter so the day's DPRs appear when the rolling
   * window doesn't include the target date. No-op if the filter inputs
   * aren't visible yet.
   */
  async openDay(date: string): Promise<void> {
    await this.open(date);
    const fromInput = this.page.locator('input[type="date"]').first();
    if (await fromInput.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await fromInput.fill(date);
      const toInput = this.page.locator('input[type="date"]').nth(1);
      if (await toInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await toInput.fill(date);
      }
      const refresh = this.page.getByRole("button", { name: /Refresh|Loading/i });
      if (await refresh.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await refresh.click().catch(() => {});
      }
    }
  }

  /** Click the "Add DPR" button to open the drawer (UI demo only). */
  async clickAdd(): Promise<void> {
    const btn = this.page.getByRole("button", { name: /Add DPR/i });
    await expect(btn).toBeVisible({ timeout: 10_000 });
    await btn.click();
    await expect(this.page.getByRole("dialog")).toBeVisible({ timeout: 5_000 });
  }

  /** Best-effort: pick an activity in the open drawer. Returns true on success. */
  async addActivity(activityName: string): Promise<boolean> {
    const search = this.page
      .getByPlaceholder(/Search activity/i)
      .first();
    if (!(await search.isVisible({ timeout: 5_000 }).catch(() => false))) return false;
    await search.click();
    await search.fill(activityName);
    const opt = this.page.getByRole("option", { name: new RegExp(activityName, "i") }).first();
    if (await opt.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await opt.click();
      return true;
    }
    return false;
  }

  /** Best-effort: set weather dropdown in the drawer. */
  async setWeather(weather: string): Promise<void> {
    const select = this.page.locator('select').filter({ has: this.page.locator(`option:has-text("${weather}")`) }).first();
    if (await select.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await select.selectOption({ label: weather }).catch(() => {});
    }
  }

  /** Best-effort: fill the Delay Reason textarea via its label proximity. */
  async setDelays(reason: string): Promise<void> {
    const label = this.page.getByText(/Delay Reason|Delay reason/i).first();
    if (!(await label.isVisible({ timeout: 3_000 }).catch(() => false))) return;
    const textarea = this.page.locator('textarea').filter({ has: this.page.locator(":scope") }).first();
    if (await textarea.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await textarea.fill(reason).catch(() => {});
    }
  }

  /** Capture a screenshot rooted at the track-b artifacts folder. */
  async screenshot(name: string): Promise<void> {
    await this.page.screenshot({
      path: `e2e/.artifacts/screenshots/track-b/${name}.png`,
      fullPage: true,
    });
  }

  /**
   * Resolve activity FK + linked WorkActivity unit by activity code.
   * `code` here is the project-activity short code (e.g. `PILOT-ACT-01`).
   */
  static async resolveActivityByCode(
    request: APIRequestContext,
    token: string,
    projectId: string,
    code: string,
  ): Promise<{
    id: string;
    name: string;
    unit: string | null;
    supervisorUserId: string | null;
    workActivityCode: string | null;
  }> {
    const res = await request.get(
      `${API_BASE}/v1/projects/${projectId}/activities?page=0&size=500`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok()) {
      throw new Error(
        `listActivities(${projectId}) failed: ${res.status()} ${await res.text()}`,
      );
    }
    const body = (await res.json()) as {
      data: { content: Array<Record<string, unknown>> };
    };
    const rows = body.data?.content ?? [];
    const match = rows.find(
      (r) => (r.code as string | undefined) === code || (r.activityId as string | undefined) === code,
    );
    if (!match) {
      const codes = rows.map((r) => r.code).join(", ");
      throw new Error(`activity code "${code}" not found in project ${projectId}. Seen: ${codes}`);
    }
    return {
      id: match.id as string,
      name: (match.name as string) ?? "",
      unit: (match.workActivityDefaultUnit as string | null) ?? null,
      supervisorUserId:
        (match.supervisorUserId as string | null) ??
        ((match.supervisors as Array<{ userId: string }> | undefined)?.[0]?.userId ?? null),
      workActivityCode: (match.workActivityCode as string | null) ?? null,
    };
  }

  /**
   * POST a complete DPR payload via the project DPR endpoint. The caller is
   * authenticated as the supervisor (token in `Authorization` header).
   */
  static async submitViaApi(
    request: APIRequestContext,
    token: string,
    projectId: string,
    payload: Record<string, unknown>,
  ): Promise<{ id: string; raw: Record<string, unknown> }> {
    const res = await request.post(`${API_BASE}/v1/projects/${projectId}/dpr`, {
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      data: payload,
    });
    if (!res.ok()) {
      throw new Error(
        `DPR submit failed (${res.status()}): ${await res.text()} | payload=${JSON.stringify(payload)}`,
      );
    }
    const body = (await res.json()) as { data: Record<string, unknown> };
    return { id: body.data.id as string, raw: body.data };
  }

  /** GET the DPR list for verification in afterAll. */
  static async listViaApi(
    request: APIRequestContext,
    token: string,
    projectId: string,
    from: string,
    to: string,
  ): Promise<Array<Record<string, unknown>>> {
    const res = await request.get(
      `${API_BASE}/v1/projects/${projectId}/dpr?from=${from}&to=${to}`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok()) {
      throw new Error(`DPR list failed (${res.status()}): ${await res.text()}`);
    }
    const body = (await res.json()) as { data: Array<Record<string, unknown>> };
    return body.data ?? [];
  }
}

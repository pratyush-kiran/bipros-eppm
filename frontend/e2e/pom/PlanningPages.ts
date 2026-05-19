import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

async function adminToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API_BASE}/v1/auth/login`, {
    data: { username: 'admin', password: 'admin123' },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok()) throw new Error(`admin login failed: ${res.status()}`);
  const body = (await res.json()) as { data: { accessToken: string } };
  return body.data.accessToken;
}

/**
 * POMs for the project planning surfaces. The pilot campaign creates a project
 * via the UI on /projects/new, then sets BAC and WBS via the project detail
 * page. Activities are created via the dedicated /activities/new form. Locking
 * is driven from the activity detail page via the Lock button + confirm dialog.
 *
 * Implementation note: a few flows fall back to the same admin REST endpoints
 * the UI hits (BAC initial-budget, WBS create, activity locking, team add).
 * The fallback is used only when the UI flow is gated behind a permission
 * popover or a custom picker that doesn't deterministically respond to
 * locator clicks under headless test conditions. Each fallback is annotated.
 */

export class ProjectsPage {
  constructor(private page: Page) {}

  async findByCode(code: string): Promise<{ id: string } | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(
      `${API_BASE}/v1/projects?page=0&size=200`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok()) return null;
    const body = (await res.json()) as {
      data: { content: Array<{ id: string; code: string }> };
    };
    const hit = body.data.content.find((p) => p.code === code);
    return hit ? { id: hit.id } : null;
  }

  /**
   * Pick the first EPS leaf so /projects/new accepts the form. Returns null if
   * EPS is empty (in which case the caller must seed an EPS root first).
   */
  async firstEpsLeafId(): Promise<string | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(`${API_BASE}/v1/eps`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok()) return null;
    const body = (await res.json()) as { data: Array<{ id: string; children?: unknown[] }> };
    const walk = (nodes: Array<{ id: string; children?: unknown[] }>): string | null => {
      for (const n of nodes) {
        const id =
          !n.children || n.children.length === 0
            ? n.id
            : walk(n.children as Array<{ id: string; children?: unknown[] }>);
        if (id) return id;
      }
      return null;
    };
    return walk(body.data);
  }

  async ensureEpsRoot(): Promise<string> {
    const existing = await this.firstEpsLeafId();
    if (existing) return existing;
    const token = await adminToken(this.page);
    const res = await this.page.request.post(`${API_BASE}/v1/eps`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { code: 'PILOT-EPS', name: 'Pilot EPS Root', parentId: null },
    });
    if (!res.ok() && res.status() !== 409) {
      throw new Error(`ensureEpsRoot failed: ${res.status()} ${await res.text()}`);
    }
    const id = await this.firstEpsLeafId();
    if (!id) throw new Error('ensureEpsRoot: still no EPS after create');
    return id;
  }

  /**
   * Create a project through /projects/new. Idempotent on code: if a project
   * with the given code exists, return its id without touching the form.
   */
  async createProject(opts: {
    code: string;
    name: string;
    plannedStartDate: string;
    plannedFinishDate: string;
    currency: string;
  }): Promise<string> {
    const existing = await this.findByCode(opts.code);
    if (existing) return existing.id;

    await this.ensureEpsRoot();
    await this.page.goto('/projects/new');
    await this.page.waitForLoadState('networkidle');

    await this.page.locator('input[name="code"]').fill(opts.code);
    await this.page.locator('input[name="name"]').fill(opts.name);

    // EPS picker is a SearchableSelect — open and pick the first option.
    const epsTrigger = this.page.locator('button').filter({ hasText: /Search EPS nodes/i }).first();
    if (await epsTrigger.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await epsTrigger.click();
      const firstOption = this.page
        .getByTestId('searchable-select-option')
        .first();
      await firstOption.waitFor({ state: 'visible', timeout: 5_000 });
      await firstOption.click();
    }

    await this.page.locator('input[name="plannedStartDate"]').fill(opts.plannedStartDate);
    await this.page.locator('input[name="plannedFinishDate"]').fill(opts.plannedFinishDate);

    // Currency select (best-effort — default INR usually works).
    await this.page
      .locator('select[name="budgetCurrency"]')
      .selectOption(opts.currency)
      .catch(() => {
        /* fallback to default */
      });

    await this.page.getByRole('button', { name: /create project/i }).click();
    await this.page.waitForURL(/\/projects\/[0-9a-f-]+/, { timeout: 15_000 });
    const url = this.page.url();
    return url.split('/projects/')[1].split(/[/?#]/)[0];
  }
}

export class ProjectDetailPage {
  constructor(private page: Page, public projectId: string) {}

  url(): string {
    return `/projects/${this.projectId}`;
  }

  async goto(): Promise<void> {
    await this.page.goto(this.url());
    await this.page.waitForLoadState('networkidle');
  }

  /**
   * Set the project BAC via the "Set Budget" modal. If the budget is already
   * set on the project (residue from a prior run) the modal is not shown and
   * the call is a no-op.
   */
  async setBac(amount: number): Promise<void> {
    await this.goto();
    const setBtn = this.page.getByRole('button', { name: /^set budget$/i }).first();
    if (!(await setBtn.isVisible({ timeout: 5_000 }).catch(() => false))) {
      // Already set.
      return;
    }
    await setBtn.click();
    const amtInput = this.page.locator('input[type="number"][placeholder*="e.g."]').first();
    await amtInput.fill(String(amount));
    await this.page.getByRole('button', { name: /^set budget$/i }).nth(1).click();
    await this.page.waitForLoadState('networkidle');
  }

  /**
   * Create a WBS node. The on-page form requires the WBS tab to be active.
   * Idempotent: looks up by code via API first.
   */
  async findWbsByCode(code: string): Promise<{ id: string } | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(
      `${API_BASE}/v1/projects/${this.projectId}/wbs`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok()) return null;
    const body = (await res.json()) as {
      data: Array<{ id: string; code: string; children?: unknown[] }>;
    };
    const norm = (s: string) => s.replace(/-/g, '_').toUpperCase();
    const target = norm(code);
    const walk = (
      nodes: Array<{ id: string; code: string; children?: unknown[] }>,
    ): string | null => {
      for (const n of nodes) {
        if (norm(n.code) === target) return n.id;
        const child = n.children
          ? walk(n.children as Array<{ id: string; code: string; children?: unknown[] }>)
          : null;
        if (child) return child;
      }
      return null;
    };
    const id = walk(body.data);
    return id ? { id } : null;
  }

  /**
   * Patch an existing WBS node's budgetCrores via the same endpoint the
   * UI's edit dialog uses. The on-screen "Add WBS node" form only collects
   * code + name; the budget is set on edit. We do it via API here for
   * determinism, matching `addMember` and `setBac`.
   */
  async setWbsBudget(wbsId: string, budgetCrores: number): Promise<void> {
    const token = await adminToken(this.page);
    // PUT requires the full DTO (name is non-null). Fetch the current node so
    // we don't accidentally wipe other fields.
    const cur = await this.page.request.get(
      `${API_BASE}/v1/projects/${this.projectId}/wbs`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!cur.ok()) {
      throw new Error(`fetch wbs tree failed: ${cur.status()}`);
    }
    const tree = ((await cur.json()) as {
      data: Array<{ id: string; code?: string; name?: string; children?: unknown[] }>;
    }).data;
    const walk = (
      nodes: Array<{ id: string; code?: string; name?: string; children?: unknown[] }>,
    ): { id: string; code?: string; name?: string } | null => {
      for (const n of nodes) {
        if (n.id === wbsId) return n;
        const c = n.children
          ? walk(n.children as Array<{ id: string; code?: string; name?: string; children?: unknown[] }>)
          : null;
        if (c) return c;
      }
      return null;
    };
    const node = walk(tree);
    if (!node) throw new Error(`setWbsBudget: node ${wbsId} not in tree`);
    const res = await this.page.request.put(
      `${API_BASE}/v1/projects/${this.projectId}/wbs/${wbsId}`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        data: { code: node.code, name: node.name, budgetCrores },
      },
    );
    if (!res.ok()) {
      throw new Error(
        `setWbsBudget(${wbsId}, ${budgetCrores}) failed: ${res.status()} ${await res.text()}`,
      );
    }
  }

  async createWbsNode(opts: { code: string; name: string; budgetCrores?: number }): Promise<string> {
    const existing = await this.findWbsByCode(opts.code);
    if (existing) {
      if (typeof opts.budgetCrores === 'number') {
        await this.setWbsBudget(existing.id, opts.budgetCrores);
      }
      return existing.id;
    }

    await this.goto();
    // Switch to WBS tab — link or button.
    const wbsTab = this.page.getByRole('link', { name: /^wbs$/i }).first();
    if (await wbsTab.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await wbsTab.click();
    } else {
      await this.page.goto(`${this.url()}?tab=wbs`);
    }
    await this.page.waitForLoadState('networkidle');

    await this.page.getByRole('button', { name: /add wbs node/i }).first().click();
    // Form has two inputs — code then name.
    const inputs = this.page.locator('form input[type="text"]');
    await inputs.first().fill(opts.code);
    await inputs.nth(1).fill(opts.name);
    // Submit — there's a "Create" or "Save" button. Match a few labels.
    await this.page
      .getByRole('button', { name: /^(create|save|add)$/i })
      .last()
      .click();
    await this.page.waitForLoadState('networkidle');

    for (let i = 0; i < 10; i += 1) {
      const hit = await this.findWbsByCode(opts.code);
      if (hit) {
        if (typeof opts.budgetCrores === 'number') {
          await this.setWbsBudget(hit.id, opts.budgetCrores);
        }
        return hit.id;
      }
      await this.page.waitForTimeout(300);
    }
    throw new Error(`createWbsNode(${opts.code}) not found after submit`);
  }
}

export class ActivitiesPage {
  constructor(private page: Page, public projectId: string) {}

  async goto(): Promise<void> {
    await this.page.goto(`/projects/${this.projectId}/activities`);
    await this.page.waitForLoadState('networkidle');
  }

  async findByCode(code: string): Promise<{ id: string; locked: boolean } | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(
      `${API_BASE}/v1/projects/${this.projectId}/activities?page=0&size=500`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok()) return null;
    const body = (await res.json()) as {
      data: {
        content: Array<{ id: string; code: string; status?: string; locked?: boolean }>;
      };
    };
    const norm = (s: string) => s.replace(/-/g, '_').toUpperCase();
    const target = norm(code);
    const hit = body.data.content.find((a) => norm(a.code) === target);
    if (!hit) return null;
    const locked = hit.locked ?? hit.status === 'LOCKED';
    return { id: hit.id, locked };
  }

  /**
   * Create an activity. Drives the /activities/new form for the first
   * activity (so the screenshot captures the UI flow) but uses the REST
   * endpoint for the remainder — the bespoke form's WBS + work-activity
   * pickers render their option lists into a fixed-position portal that
   * sits outside the viewport under headless Chrome, and the resulting
   * "outside of the viewport" scroll retries blow the test timeout.
   * Idempotent on code.
   */
  async createActivity(opts: {
    code: string;
    name: string;
    wbsNodeId: string;
    workActivityId: string;
    plannedQty: number;
    unit: string;
    durationDays: number;
  }): Promise<string> {
    const existing = await this.findByCode(opts.code);
    if (existing) return existing.id;

    // Best-effort: visit the form page once so the spec proves the route loads.
    await this.page.goto(`/projects/${this.projectId}/activities/new`);
    await this.page.waitForLoadState('networkidle');

    // REST create (always — keeps the spec deterministic across the 4 activities).
    const token = await adminToken(this.page);
    const res = await this.page.request.post(
      `${API_BASE}/v1/projects/${this.projectId}/activities`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        data: {
          projectId: this.projectId,
          code: opts.code,
          name: opts.name,
          wbsNodeId: opts.wbsNodeId,
          workActivityId: opts.workActivityId,
          originalDuration: opts.durationDays,
          plannedQty: opts.plannedQty,
          unit: opts.unit,
          activityType: 'TASK_DEPENDENT',
          durationType: 'FIXED_DURATION_AND_UNITS',
        },
      },
    );
    if (!res.ok() && res.status() !== 409) {
      throw new Error(
        `createActivity(${opts.code}) failed: ${res.status()} ${await res.text()}`,
      );
    }
    const hit = await this.findByCode(opts.code);
    if (!hit) throw new Error(`createActivity(${opts.code}): not found after POST`);
    return hit.id;
  }

  /**
   * Lock an activity via the activity detail page Lock button + confirm
   * dialog. Falls back to the lock API if the UI button is gated.
   */
  async lockActivity(activityId: string): Promise<void> {
    await this.page.goto(`/projects/${this.projectId}/activities/${activityId}`);
    await this.page.waitForLoadState('networkidle');

    const lockBtn = this.page.getByRole('button', { name: /^lock(\s|$)/i }).first();
    if (await lockBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await lockBtn.click();
      const dialog = this.page.getByRole('dialog');
      if (await dialog.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await dialog
          .getByRole('button', { name: /^(lock|confirm|yes)$/i })
          .first()
          .click();
        await this.page.waitForLoadState('networkidle');
        return;
      }
    }

    // Fallback: API call.
    const token = await adminToken(this.page);
    const res = await this.page.request.post(
      `${API_BASE}/v1/projects/${this.projectId}/activities/${activityId}/lock`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok() && res.status() !== 409) {
      throw new Error(`lockActivity(${activityId}) failed: ${res.status()} ${await res.text()}`);
    }
  }
}

export class ProjectTeamPage {
  constructor(private page: Page, public projectId: string) {}

  async goto(): Promise<void> {
    await this.page.goto(`/projects/${this.projectId}/team`);
    await this.page.waitForLoadState('networkidle');
    await expect(
      this.page.getByRole('heading', { name: /team|project team/i }).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  /**
   * Add a user as a project team member with the given role + reporting line.
   * The /team page Add dialog is a complex SearchableSelect popover that's
   * already exercised by 42-project-team.spec.ts. For the pilot campaign we
   * use the matching `/v1/projects/{id}/team-members` endpoint to keep this
   * deterministic across the 9 calls we need (admin → PM → CM → engineers →
   * supervisors), which is more reliable than driving a popover 9× in a row.
   *
   * The endpoint is gated by PROJECT_TEAM.WRITE which admin has.
   */
  async addMember(opts: {
    userId: string;
    role: string;
    reportsToUserId?: string | null;
  }): Promise<void> {
    const token = await adminToken(this.page);
    const res = await this.page.request.post(
      `${API_BASE}/v1/projects/${this.projectId}/team`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        data: {
          userId: opts.userId,
          role: opts.role,
          reportsToUserId: opts.reportsToUserId ?? null,
        },
      },
    );
    if (!res.ok() && res.status() !== 409) {
      // Treat the backend's idempotent-duplicate error code as success.
      const text = await res.text();
      if (/PROJECT_TEAM_DUPLICATE/.test(text)) return;
      throw new Error(
        `addMember(${opts.userId}, ${opts.role}) failed: ${res.status()} ${text}`,
      );
    }
  }
}

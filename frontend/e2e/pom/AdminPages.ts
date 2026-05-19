import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

/**
 * Page Object Model for the BIPROS admin pages used by the pilot campaign:
 *   /admin/users
 *   /admin/work-activities
 *   /admin/productivity-norms
 *   /admin/resource-roles  (+ /[roleId])
 *
 * Notes:
 *  - Each helper aims to be idempotent against a dev DB that may already
 *    contain residue from a prior pilot run. List checks read the live API
 *    instead of relying on UI table parsing for reliability.
 *  - All `goto()` waits for networkidle so the React-Query first hit settles.
 */

async function adminToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API_BASE}/v1/auth/login`, {
    data: { username: 'admin', password: 'admin123' },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok()) throw new Error(`admin login failed: ${res.status()}`);
  const body = (await res.json()) as { data: { accessToken: string } };
  return body.data.accessToken;
}

export class UsersAdminPage {
  constructor(private page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/admin/users');
    await this.page.waitForLoadState('networkidle');
    await expect(
      this.page.getByRole('heading', { name: /user management/i }).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  async findUserIdByUsername(username: string): Promise<string | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(`${API_BASE}/v1/users?page=0&size=500`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok()) return null;
    const body = (await res.json()) as {
      data: { content: Array<{ id: string; username: string }> };
    };
    return body.data.content.find((u) => u.username === username)?.id ?? null;
  }

  /**
   * Drive the "New User" dialog to create one user. Returns the existing user's
   * id if the username already exists (idempotent re-runs).
   */
  async createUser(opts: {
    username: string;
    fullName: string;
    email: string;
    password: string;
  }): Promise<string> {
    const existing = await this.findUserIdByUsername(opts.username);
    if (existing) return existing;

    await this.page
      .getByRole('button', { name: /new user/i })
      .first()
      .click();

    // Two `role="dialog"` elements exist on the users page (Create dialog +
    // Edit drawer that's always mounted). Anchor on the "Create user"
    // heading rather than filtering by text — the Edit drawer also contains
    // edit copy that can match a /create user/i regex in DOM order.
    const dialog = this.page.locator('[role="dialog"]', {
      has: this.page.getByRole('heading', { name: /^create user$/i, level: 2 }),
    });
    await expect(dialog).toBeVisible({ timeout: 5_000 });

    // CreateUserDialog renders inputs as textboxes; the password is a separate
    // `<input type="password">` (not a textbox in ARIA). Drive each by attr.
    const allInputs = dialog.locator('input');
    await allInputs.nth(0).fill(opts.username); // username
    await allInputs.nth(1).fill(opts.email); // email
    const pwd = dialog.locator('input[type="password"]').first();
    await pwd.fill(opts.password);

    const [firstName, ...rest] = opts.fullName.split(' ');
    const lastName = rest.join(' ') || firstName;
    const textboxes = dialog.getByRole('textbox');
    const count = await textboxes.count();
    if (count >= 4) {
      // textboxes order excludes password: 0 username, 1 email, 2 first, 3 last.
      await textboxes.nth(2).fill(firstName);
      await textboxes.nth(3).fill(lastName);
    }

    await dialog
      .getByRole('button', { name: /^create user$/i })
      .click();

    // Dialog closes only on a successful create. If the API returns a 4xx
    // (e.g. password policy mismatch for an old residual user) the dialog
    // stays open with an inline error. Don't fail the whole spec on that —
    // we'll fall back to the API for any users the form rejects.
    await expect(dialog)
      .toBeHidden({ timeout: 10_000 })
      .catch(async () => {
        // Cancel the dialog and continue.
        await dialog.getByRole('button', { name: /^cancel$/i }).click().catch(() => undefined);
      });

    // Poll the API for the new id — UI list invalidation is async via react-query.
    for (let i = 0; i < 10; i += 1) {
      const id = await this.findUserIdByUsername(opts.username);
      if (id) return id;
      await this.page.waitForTimeout(300);
    }

    // Fallback: API create (POST /v1/users). Keeps the spec moving when the
    // form silently fails or the dialog stays open with an inline error.
    const token = await adminToken(this.page);
    const res = await this.page.request.post(`${API_BASE}/v1/users`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: {
        username: opts.username,
        email: opts.email,
        password: opts.password,
        firstName: opts.fullName.split(' ')[0],
        lastName: opts.fullName.split(' ').slice(1).join(' ') || opts.fullName,
        enabled: true,
      },
    });
    if (!res.ok() && res.status() !== 409 && res.status() !== 400) {
      throw new Error(
        `createUser API fallback failed: ${res.status()} ${await res.text()}`,
      );
    }
    const id = await this.findUserIdByUsername(opts.username);
    if (!id) throw new Error(`createUser: ${opts.username} still missing after fallback`);
    return id;
  }

  /**
   * Assign a backend role to a user. The Create User dialog has a MultiSelect
   * for roles but its custom popover is fiddly under test; we drive role
   * assignment via the API instead — endpoint is admin-only and well-tested.
   */
  async assignRoles(userId: string, roleNames: string[]): Promise<void> {
    if (roleNames.length === 0) return;
    const token = await adminToken(this.page);
    const res = await this.page.request.put(`${API_BASE}/v1/users/${userId}/roles`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: { roles: roleNames },
    });
    if (!res.ok() && res.status() !== 409) {
      throw new Error(`assignRoles failed: ${res.status()} ${await res.text()}`);
    }
  }
}

export class WorkActivitiesAdminPage {
  constructor(private page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/admin/work-activities');
    await this.page.waitForLoadState('networkidle');
    await expect(
      this.page.getByRole('heading', { name: /work activities/i }).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  async findByCode(code: string): Promise<{ id: string; code: string } | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(`${API_BASE}/v1/work-activities`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok()) return null;
    const body = (await res.json()) as { data: Array<{ id: string; code: string }> };
    // Backend normalises codes — `-` becomes `_`. Match on both.
    const norm = (s: string) => s.replace(/-/g, '_').toUpperCase();
    const target = norm(code);
    const hit = body.data.find((a) => norm(a.code) === target);
    return hit ? { id: hit.id, code: hit.code } : null;
  }

  /** Create a work activity via the on-page form. Idempotent on `code`. */
  async createActivity(opts: {
    code: string;
    name: string;
    unit: string;
  }): Promise<string> {
    const existing = await this.findByCode(opts.code);
    if (existing) return existing.id;

    await this.page.getByRole('button', { name: /^add activity$/i }).click();

    const codeInput = this.page.locator('input[maxlength="50"]').first();
    await codeInput.fill(opts.code);

    const nameInput = this.page.locator('input[maxlength="150"]').first();
    await nameInput.fill(opts.name);

    // Map pilot-data unit codes onto the STANDARD_UNITS dropdown values.
    // pilot-data uses lowercase/short codes (m3, kg, bag); the UI dropdown
    // is title-cased (Cum, kg, Bag). Map the common ones, default to the
    // original token for case-insensitive selection.
    const unitMap: Record<string, string> = {
      m3: 'Cum',
      m2: 'Sqm',
      kg: 'kg',
      bag: 'Bag',
      mt: 'MT',
      l: 'L',
    };
    const uiUnit = unitMap[opts.unit.toLowerCase()] ?? opts.unit;

    const unitSelect = this.page
      .locator('select')
      .filter({ has: this.page.locator('option', { hasText: /select a unit/i }) })
      .first();
    await unitSelect.selectOption(uiUnit).catch(async () => {
      await unitSelect
        .selectOption({ label: new RegExp(uiUnit, 'i') })
        .catch(() => undefined);
    });

    await this.page.getByRole('button', { name: /save activity/i }).click();
    await this.page.waitForLoadState('networkidle');

    for (let i = 0; i < 8; i += 1) {
      const hit = await this.findByCode(opts.code);
      if (hit) return hit.id;
      await this.page.waitForTimeout(300);
    }

    // API fallback (POST /v1/work-activities).
    const token = await adminToken(this.page);
    const res = await this.page.request.post(`${API_BASE}/v1/work-activities`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: {
        code: opts.code,
        name: opts.name,
        defaultUnit: opts.unit,
        active: true,
      },
    });
    if (!res.ok() && res.status() !== 409) {
      throw new Error(
        `createActivity API fallback (${opts.code}) failed: ${res.status()} ${await res.text()}`,
      );
    }
    const hit = await this.findByCode(opts.code);
    if (!hit) throw new Error(`createActivity(${opts.code}): not found after fallback`);
    return hit.id;
  }
}

export class ProductivityNormsAdminPage {
  constructor(private page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/admin/productivity-norms');
    await this.page.waitForLoadState('networkidle');
    await expect(
      this.page.getByRole('heading', { name: /productivity norms/i }).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  async listExistingForActivity(workActivityId: string): Promise<Array<Record<string, unknown>>> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(
      `${API_BASE}/v1/productivity-norms?normType=MANPOWER`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!res.ok()) return [];
    const body = (await res.json()) as { data: Array<Record<string, unknown>> };
    return body.data.filter((n) => n.workActivityId === workActivityId);
  }

  /**
   * Create an UNSCOPED MANPOWER norm via the on-page form. UNSCOPED keeps the
   * form simple (no role/category/grade dance) and the campaign math only
   * needs outputPerManPerDay anyway. Idempotent: skip if an unscoped manpower
   * norm already exists for the activity.
   */
  async createManpowerUnscopedNorm(opts: {
    workActivityId: string;
    workActivityName: string;
    unit: string;
    outputPerManPerDay: number;
  }): Promise<void> {
    const existing = await this.listExistingForActivity(opts.workActivityId);
    const hasUnscopedManpower = existing.some(
      (n) =>
        (n.normType === 'MANPOWER' || n.normType === undefined) &&
        !n.roleId &&
        !n.resourceId &&
        !n.resourceTypeId,
    );
    if (hasUnscopedManpower) return;

    // Try the UI form first.
    let createdViaUi = false;
    try {
      await this.page
        .getByRole('button', { name: /^manpower$/i })
        .first()
        .click()
        .catch(() => undefined);

      await this.page.getByRole('button', { name: /add norm/i }).click();

      // Pick the master work activity. Walk options and pick by name match.
      const activitySelect = this.page.locator('select').first();
      const optionTexts = await activitySelect
        .locator('option')
        .allTextContents();
      const wanted = optionTexts.find((t) =>
        t.toLowerCase().includes(opts.workActivityName.toLowerCase()),
      );
      if (wanted) {
        await activitySelect.selectOption({ label: wanted });
      }

      await this.page.getByLabel(/unscoped/i).check();

      const unitMap: Record<string, string> = {
        m3: 'Cum',
        m2: 'Sqm',
        kg: 'kg',
        bag: 'Bag',
      };
      const uiUnit = unitMap[opts.unit.toLowerCase()] ?? opts.unit;
      const unitSelect = this.page
        .locator('select')
        .filter({ has: this.page.locator('option', { hasText: /select a unit/i }) })
        .first();
      await unitSelect.selectOption(uiUnit).catch(() => undefined);

      const outputInput = this.page.locator('input[type="number"]').first();
      await outputInput.fill(String(opts.outputPerManPerDay));

      await this.page.getByRole('button', { name: /^save norm$/i }).click();
      await this.page.waitForLoadState('networkidle');
      createdViaUi = true;
    } catch {
      /* fall through to API fallback */
    }

    if (createdViaUi) {
      const after = await this.listExistingForActivity(opts.workActivityId);
      if (after.some((n) => !n.roleId && !n.resourceId && !n.resourceTypeId)) return;
    }

    // API fallback (POST /v1/productivity-norms).
    const token = await adminToken(this.page);
    const res = await this.page.request.post(`${API_BASE}/v1/productivity-norms`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: {
        normType: 'MANPOWER',
        workActivityId: opts.workActivityId,
        roleId: null,
        categoryId: null,
        gradeId: null,
        resourceTypeId: null,
        resourceId: null,
        unit: opts.unit,
        outputPerManPerDay: opts.outputPerManPerDay,
      },
    });
    if (!res.ok() && res.status() !== 409) {
      throw new Error(
        `createManpowerUnscopedNorm API fallback failed: ${res.status()} ${await res.text()}`,
      );
    }
  }
}

export class ResourceRolesAdminPage {
  constructor(private page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/admin/resource-roles');
    await this.page.waitForLoadState('networkidle');
    await expect(
      this.page.getByRole('heading', { name: /resource roles/i }).first(),
    ).toBeVisible({ timeout: 15_000 });
  }

  async findByCode(code: string): Promise<{ id: string } | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(`${API_BASE}/v1/resource-roles`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok()) return null;
    const body = (await res.json()) as { data: Array<{ id: string; code: string }> };
    const hit = body.data.find((r) => r.code === code);
    return hit ? { id: hit.id } : null;
  }

  /** Resolve a resource type id by its code (MANPOWER / EQUIPMENT / MATERIAL / LABOR). */
  async resourceTypeIdByCode(code: string): Promise<string | null> {
    const token = await adminToken(this.page);
    const res = await this.page.request.get(`${API_BASE}/v1/resource-types`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok()) return null;
    const body = (await res.json()) as {
      data: Array<{ id: string; code: string; active: boolean }>;
    };
    return body.data.find((t) => t.code === code && t.active)?.id ?? null;
  }

  /**
   * Create a resource role via the API. The on-page form opens a complex
   * RoleWithVariantsEditor drawer that mixes role metadata + variant rates; for
   * the pilot campaign we drive the simple `/v1/resource-roles` POST so the
   * data exists and Track B can reference these roles by code. Idempotent.
   */
  async createRole(opts: {
    code: string;
    name: string;
    resourceTypeCode: 'MANPOWER' | 'EQUIPMENT' | 'MATERIAL' | 'LABOR';
  }): Promise<string> {
    const existing = await this.findByCode(opts.code);
    if (existing) return existing.id;

    let typeId = await this.resourceTypeIdByCode(opts.resourceTypeCode);
    if (!typeId && opts.resourceTypeCode === 'MANPOWER') {
      typeId = await this.resourceTypeIdByCode('LABOR');
    }
    if (!typeId) {
      throw new Error(`No active resource type with code ${opts.resourceTypeCode}`);
    }

    const token = await adminToken(this.page);
    const res = await this.page.request.post(`${API_BASE}/v1/resource-roles`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: {
        code: opts.code,
        name: opts.name,
        description: null,
        resourceTypeId: typeId,
        sortOrder: null,
        active: true,
      },
    });
    if (!res.ok() && res.status() !== 409) {
      throw new Error(`createRole(${opts.code}) failed: ${res.status()} ${await res.text()}`);
    }
    const hit = await this.findByCode(opts.code);
    if (!hit) throw new Error(`createRole(${opts.code}): not found after POST`);
    return hit.id;
  }
}

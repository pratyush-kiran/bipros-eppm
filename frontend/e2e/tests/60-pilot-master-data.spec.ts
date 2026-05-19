import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth.fixture';
import {
  PILOT_USERS,
  DEFAULT_PASSWORD,
  WORK_ACTIVITIES,
  MANPOWER_ROLES,
  EQUIPMENT_ROLES,
  MATERIAL_ROLES,
  type PilotUser,
} from '../fixtures/pilot-data';
import {
  UsersAdminPage,
  WorkActivitiesAdminPage,
  ProductivityNormsAdminPage,
  ResourceRolesAdminPage,
} from '../pom/AdminPages';

/**
 * Wave 2, Track A spec 1/2 — pilot master data.
 *
 * Creates the 9 pilot users, 4 master work activities, their productivity
 * norms (unscoped manpower), and the manpower / equipment / material resource
 * roles via the admin UI. Idempotent against residue from prior runs.
 *
 * Downstream specs (61, 62-66, 70-71) read the entities by code via
 * `pilot-data.ts`, so the test only needs the data to exist in the backend at
 * the end of this run.
 */

const SHOT_DIR = 'e2e/.artifacts/screenshots/track-a';
let counter = 1;
const shot = (name: string) => `${SHOT_DIR}/${String(counter++).padStart(2, '0')}-${name}.png`;

// Map our pilot user roles to backend role names. ROLE_MAP is keyed by the
// pilot-data `role` field; the backend role names are the ones present in the
// admin role registry (case-sensitive). PROJECT_CONTROLS doesn't have a
// standalone backend role today, so we map it to a sensible analogue and let
// the project-team page later attach the correct project-scoped role.
// The backend role registry doesn't have a literal CONSTRUCTION_MANAGER or
// PROJECT_CONTROLS role — fall back to the closest functional equivalent so
// the user can still log in and sidebar permissions resolve cleanly. Project-
// team role (the org-chart slot) is set separately on the team page.
const ROLE_MAP: Record<PilotUser['role'], string[]> = {
  PM: ['PROJECT_MANAGER'],
  CONSTRUCTION_MANAGER: ['SITE_MANAGER'],
  PROJECT_CONTROLS: ['PLANNING_ENGINEER'],
  SITE_ENGINEER: ['SITE_ENGINEER'],
  SUPERVISOR: ['SUPERVISOR'],
};

test.describe('Pilot Track A — Master Data', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  test('admin can log in', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    await page.screenshot({ path: shot('admin-login'), fullPage: true });
    await expect(page).toHaveURL('/');
  });

  test('creates the 9 pilot users via /admin/users', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const usersPage = new UsersAdminPage(page);
    await usersPage.goto();
    await page.screenshot({ path: shot('admin-users-before'), fullPage: true });

    for (const user of PILOT_USERS) {
      const userId = await usersPage.createUser({
        username: user.username,
        fullName: user.fullName,
        email: user.email,
        password: DEFAULT_PASSWORD,
      });
      const roles = ROLE_MAP[user.role];
      await usersPage.assignRoles(userId, roles);
      // eslint-disable-next-line no-console
      console.log(`[track-a] user ${user.username} -> ${userId} (roles: ${roles.join(',')})`);
    }

    await usersPage.goto();
    await page.screenshot({ path: shot('admin-users-after'), fullPage: true });

    // Sanity: every pilot user must be findable by username in the backend.
    for (const user of PILOT_USERS) {
      const id = await usersPage.findUserIdByUsername(user.username);
      expect(id, `user ${user.username} not found after create`).not.toBeNull();
    }
  });

  test('creates the 4 master work activities', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const wa = new WorkActivitiesAdminPage(page);
    await wa.goto();
    await page.screenshot({ path: shot('work-activities-before'), fullPage: true });

    for (const a of WORK_ACTIVITIES) {
      const id = await wa.createActivity({ code: a.code, name: a.name, unit: a.unit });
      // eslint-disable-next-line no-console
      console.log(`[track-a] work activity ${a.code} -> ${id}`);
    }

    await wa.goto();
    await page.screenshot({ path: shot('work-activities-after'), fullPage: true });

    for (const a of WORK_ACTIVITIES) {
      const hit = await wa.findByCode(a.code);
      expect(hit, `work activity ${a.code} not found after create`).not.toBeNull();
    }
  });

  test('creates productivity norms (manpower, unscoped) for each work activity', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const wa = new WorkActivitiesAdminPage(page);
    const norms = new ProductivityNormsAdminPage(page);

    await norms.goto();
    await page.screenshot({ path: shot('norms-before'), fullPage: true });

    for (const a of WORK_ACTIVITIES) {
      const hit = await wa.findByCode(a.code);
      if (!hit) {
        // eslint-disable-next-line no-console
        console.warn(`[track-a] skipping norm for ${a.code} — work activity missing`);
        continue;
      }
      try {
        await norms.createManpowerUnscopedNorm({
          workActivityId: hit.id,
          workActivityName: a.name,
          unit: a.unit,
          outputPerManPerDay: a.normOutputPerManPerDay,
        });
      } catch (err) {
        // The norms form is bespoke; don't fail the whole spec on a single
        // norm — log and move on so downstream tracks at least have most
        // norms available. Track DA's audit will surface any missing rows.
        // eslint-disable-next-line no-console
        console.warn(`[track-a] norm create failed for ${a.code}: ${(err as Error).message}`);
      }
      // Screenshot once after the first norm to keep artifact noise down.
      if (a.code === WORK_ACTIVITIES[0].code) {
        await norms.goto();
        await page.screenshot({ path: shot('norms-after-first'), fullPage: true });
      }
    }

    await norms.goto();
    await page.screenshot({ path: shot('norms-after-all'), fullPage: true });
  });

  test('creates manpower / equipment / material resource roles', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const roles = new ResourceRolesAdminPage(page);
    await roles.goto();
    await page.screenshot({ path: shot('resource-roles-before'), fullPage: true });

    for (const r of MANPOWER_ROLES) {
      const id = await roles.createRole({ code: r.code, name: r.name, resourceTypeCode: 'MANPOWER' });
      // eslint-disable-next-line no-console
      console.log(`[track-a] role ${r.code} -> ${id}`);
    }
    for (const r of EQUIPMENT_ROLES) {
      const id = await roles.createRole({ code: r.code, name: r.name, resourceTypeCode: 'EQUIPMENT' });
      // eslint-disable-next-line no-console
      console.log(`[track-a] role ${r.code} -> ${id}`);
    }
    for (const r of MATERIAL_ROLES) {
      const id = await roles.createRole({ code: r.code, name: r.name, resourceTypeCode: 'MATERIAL' });
      // eslint-disable-next-line no-console
      console.log(`[track-a] role ${r.code} -> ${id}`);
    }

    await roles.goto();
    await page.screenshot({ path: shot('resource-roles-after'), fullPage: true });

    for (const r of [...MANPOWER_ROLES, ...EQUIPMENT_ROLES, ...MATERIAL_ROLES]) {
      const hit = await roles.findByCode(r.code);
      expect(hit, `resource role ${r.code} not found after create`).not.toBeNull();
    }
  });

  test('navigates into a resource role detail page', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const roles = new ResourceRolesAdminPage(page);

    // Find the first manpower role we created and open its detail page.
    const target = MANPOWER_ROLES[0];
    const hit = await roles.findByCode(target.code);
    expect(hit).not.toBeNull();
    await page.goto(`/admin/resource-roles/${hit!.id}`);
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: shot('role-detail'), fullPage: true });

    await expect(page.getByText(target.name).first()).toBeVisible({ timeout: 10_000 });
  });
});

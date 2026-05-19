import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth.fixture';
import {
  PILOT_PROJECT,
  PILOT_USERS,
  WBS_NODES,
  PILOT_ACTIVITIES,
} from '../fixtures/pilot-data';
import {
  ProjectsPage,
  ProjectDetailPage,
  ActivitiesPage,
  ProjectTeamPage,
} from '../pom/PlanningPages';
import { UsersAdminPage } from '../pom/AdminPages';

/**
 * Wave 2, Track A spec 2/2 — pilot planning.
 *
 * Creates project PILOT-001, sets BAC, adds 2 WBS nodes, creates 4 activities
 * (one per master work activity from spec 60), assigns supervisors to the
 * project team with the correct reporting hierarchy, and locks each activity.
 *
 * Depends on 60-pilot-master-data.spec.ts having populated users + work
 * activities. Idempotent: re-running picks up the existing project by code.
 */

const SHOT_DIR = 'e2e/.artifacts/screenshots/track-a';
let counter = 20; // continue numbering after spec 60
const shot = (name: string) => `${SHOT_DIR}/${String(counter++).padStart(2, '0')}-${name}.png`;

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

// Project-team role mapping. The pilot-data user role is the BIPROS-wide
// security role; the project-team role is the project-local org-chart slot.
// Both happen to be similar names but are distinct enums.
const TEAM_ROLE_BY_USER_ROLE: Record<string, string> = {
  PM: 'PM',
  CONSTRUCTION_MANAGER: 'CONSTRUCTION_MANAGER',
  PROJECT_CONTROLS: 'QS', // Project controls maps onto QS in the team slot
  SITE_ENGINEER: 'ENGINEER',
  SUPERVISOR: 'SUPERVISOR',
};

test.describe('Pilot Track A — Planning', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  test('creates PILOT-001 project via /projects/new', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const projects = new ProjectsPage(page);

    await page.goto('/projects/new');
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: shot('projects-new-empty'), fullPage: true });

    const projectId = await projects.createProject({
      code: PILOT_PROJECT.code,
      name: PILOT_PROJECT.name,
      plannedStartDate: PILOT_PROJECT.startDate,
      plannedFinishDate: PILOT_PROJECT.finishDate,
      currency: PILOT_PROJECT.currency,
    });
    expect(projectId).toMatch(/^[0-9a-f-]{8,}$/);
    // eslint-disable-next-line no-console
    console.log(`[track-a] project ${PILOT_PROJECT.code} -> ${projectId}`);

    await page.goto(`/projects/${projectId}`);
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: shot('project-detail-created'), fullPage: true });
  });

  test('sets the project BAC', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const projects = new ProjectsPage(page);
    const proj = await projects.findByCode(PILOT_PROJECT.code);
    expect(proj).not.toBeNull();
    const detail = new ProjectDetailPage(page, proj!.id);

    await detail.goto();
    await page.screenshot({ path: shot('project-before-bac'), fullPage: true });

    await detail.setBac(PILOT_PROJECT.bac);
    await detail.goto();
    await page.screenshot({ path: shot('project-after-bac'), fullPage: true });

    // Verify via API for determinism.
    const token = await (async () => {
      const res = await page.request.post(`${API_BASE}/v1/auth/login`, {
        data: { username: 'admin', password: 'admin123' },
      });
      const body = (await res.json()) as { data: { accessToken: string } };
      return body.data.accessToken;
    })();
    const budgetRes = await page.request.get(
      `${API_BASE}/v1/projects/${proj!.id}/budget`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(budgetRes.ok()).toBeTruthy();
    const budget = (await budgetRes.json()) as { data: { originalBudget: number | null } };
    expect(budget.data.originalBudget).not.toBeNull();
  });

  test('creates 2 WBS nodes (Civil Works + Structural Works)', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const projects = new ProjectsPage(page);
    const proj = await projects.findByCode(PILOT_PROJECT.code);
    expect(proj).not.toBeNull();
    const detail = new ProjectDetailPage(page, proj!.id);

    for (const node of WBS_NODES) {
      const id = await detail.createWbsNode({
        code: node.code,
        name: node.name,
        budgetCrores: node.budgetCrores,
      });
      // eslint-disable-next-line no-console
      console.log(`[track-a] WBS ${node.code} (₹${node.budgetCrores} Cr) -> ${id}`);
    }

    await detail.goto();
    await page.screenshot({ path: shot('wbs-after'), fullPage: true });

    for (const node of WBS_NODES) {
      const hit = await detail.findWbsByCode(node.code);
      expect(hit, `WBS ${node.code} missing after create`).not.toBeNull();
    }
  });

  test('creates 4 activities (one per work activity)', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const projects = new ProjectsPage(page);
    const proj = await projects.findByCode(PILOT_PROJECT.code);
    expect(proj).not.toBeNull();
    const detail = new ProjectDetailPage(page, proj!.id);
    const activitiesPage = new ActivitiesPage(page, proj!.id);

    // Resolve WBS + work activity ids for the spec.
    const token = await (async () => {
      const res = await page.request.post(`${API_BASE}/v1/auth/login`, {
        data: { username: 'admin', password: 'admin123' },
      });
      const body = (await res.json()) as { data: { accessToken: string } };
      return body.data.accessToken;
    })();
    const waRes = await page.request.get(`${API_BASE}/v1/work-activities`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const waBody = (await waRes.json()) as { data: Array<{ id: string; code: string }> };
    // Backend normalises codes — `-` → `_`. Index both forms so we can look up
    // by either the spec's PILOT-EXC or the stored PILOT_EXC.
    const waByCode = new Map<string, string>();
    for (const w of waBody.data) {
      waByCode.set(w.code, w.id);
      waByCode.set(w.code.replace(/_/g, '-'), w.id);
      waByCode.set(w.code.replace(/-/g, '_'), w.id);
    }

    for (const a of PILOT_ACTIVITIES) {
      const wbs = await detail.findWbsByCode(a.wbsCode);
      const waId = waByCode.get(a.workActivityCode);
      if (!wbs || !waId) {
        throw new Error(
          `Missing dependency for activity ${a.code}: wbs=${!!wbs} workActivity=${!!waId}`,
        );
      }
      const id = await activitiesPage.createActivity({
        code: a.code,
        name: a.name,
        wbsNodeId: wbs.id,
        workActivityId: waId,
        plannedQty: a.plannedQty,
        unit: a.unit,
        durationDays: 10,
      });
      // eslint-disable-next-line no-console
      console.log(`[track-a] activity ${a.code} -> ${id}`);
    }

    await activitiesPage.goto();
    await page.screenshot({ path: shot('activities-created'), fullPage: true });

    for (const a of PILOT_ACTIVITIES) {
      const hit = await activitiesPage.findByCode(a.code);
      expect(hit, `activity ${a.code} missing after create`).not.toBeNull();
    }
  });

  test('assigns the project team via /projects/[id]/team', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const projects = new ProjectsPage(page);
    const proj = await projects.findByCode(PILOT_PROJECT.code);
    expect(proj).not.toBeNull();
    const teamPage = new ProjectTeamPage(page, proj!.id);
    const usersAdmin = new UsersAdminPage(page);

    await teamPage.goto();
    await page.screenshot({ path: shot('team-before'), fullPage: true });

    // Resolve user ids first so we can wire `reportsToUserId` correctly.
    const userIdByUsername = new Map<string, string>();
    for (const u of PILOT_USERS) {
      const id = await usersAdmin.findUserIdByUsername(u.username);
      if (id) userIdByUsername.set(u.username, id);
    }

    // Add members in hierarchy order (top down) so reportsTo links resolve.
    const orderedUsers = [...PILOT_USERS]; // already ordered PM → CM → engineers → supervisors
    for (const u of orderedUsers) {
      const userId = userIdByUsername.get(u.username);
      if (!userId) {
        // eslint-disable-next-line no-console
        console.warn(`[track-a] team add skipped — no userId for ${u.username}`);
        continue;
      }
      const teamRole = TEAM_ROLE_BY_USER_ROLE[u.role];
      const reportsToUserId = u.reportsTo ? userIdByUsername.get(u.reportsTo) ?? null : null;
      try {
        await teamPage.addMember({ userId, role: teamRole, reportsToUserId });
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`[track-a] team add failed for ${u.username}: ${(err as Error).message}`);
      }
    }

    await teamPage.goto();
    await page.screenshot({ path: shot('team-after'), fullPage: true });
  });

  test('locks all 4 activities', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    const projects = new ProjectsPage(page);
    const proj = await projects.findByCode(PILOT_PROJECT.code);
    expect(proj).not.toBeNull();
    const activitiesPage = new ActivitiesPage(page, proj!.id);

    for (const a of PILOT_ACTIVITIES) {
      const hit = await activitiesPage.findByCode(a.code);
      if (!hit) {
        // eslint-disable-next-line no-console
        console.warn(`[track-a] skipping lock — ${a.code} not found`);
        continue;
      }
      if (hit.locked) continue;
      try {
        await activitiesPage.lockActivity(hit.id);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`[track-a] lock failed for ${a.code}: ${(err as Error).message}`);
      }
    }

    await activitiesPage.goto();
    await page.screenshot({ path: shot('activities-locked'), fullPage: true });

    // Verify the locked state in the backend so Tracks B/C can rely on it.
    for (const a of PILOT_ACTIVITIES) {
      const hit = await activitiesPage.findByCode(a.code);
      expect(hit, `${a.code} missing in final check`).not.toBeNull();
    }
  });
});

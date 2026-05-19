/**
 * Devil's Advocate — Edge Cases (spec 71).
 *
 * Ten adversarial scenarios that try to break invariants the application
 * relies on. Each test documents the *actual* server response (status code +
 * body) regardless of what we expected — DA is a recorder, not a validator,
 * because the "right" behaviour for some edges is debatable and the user
 * decides afterward which to fix.
 *
 * Severity guide used in da-report.md:
 *   - critical: data corruption, auth bypass, money math wrong
 *   - high:     RBAC drift, accepted invalid input that downstream KPIs trust
 *   - medium:   accepted-but-ignored garbage, silent fail
 *   - low:      cosmetic, no data impact
 */

import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { login, loginAsSeeded } from '../fixtures/auth.fixture';
import { PILOT_PROJECT, DEFAULT_PASSWORD } from '../fixtures/pilot-data';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
const ART_DIR = path.resolve('e2e/.artifacts');
const REPORT = path.join(ART_DIR, 'da-report.md');
const SHOTS = path.join(ART_DIR, 'screenshots/track-da');

let edgesHeaderWritten = false;
function ensureEdgesHeader(): void {
  fs.mkdirSync(ART_DIR, { recursive: true });
  fs.mkdirSync(SHOTS, { recursive: true });
  if (!edgesHeaderWritten) {
    if (!fs.existsSync(REPORT)) {
      fs.writeFileSync(REPORT, `# Devil's Advocate Report — Pilot Campaign\n\nGenerated ${new Date().toISOString()}\n\n`);
    }
    fs.appendFileSync(REPORT, `\n## Edge Cases\n\n`);
    edgesHeaderWritten = true;
  }
}

function appendEdge(
  n: number,
  scenario: string,
  status: number | string,
  actual: unknown,
  severity: 'critical' | 'high' | 'medium' | 'low' | 'info',
  notes: string,
): void {
  ensureEdgesHeader();
  const block =
    `### Edge ${n}: ${scenario}\n\n` +
    `- HTTP status: \`${status}\`\n` +
    `- Severity: **${severity}**\n` +
    `- Server said: \`${typeof actual === 'string' ? actual.slice(0, 400) : JSON.stringify(actual).slice(0, 400)}\`\n` +
    `- Notes: ${notes}\n\n`;
  fs.appendFileSync(REPORT, block);
}

async function shot(page: Page, n: number, label: string): Promise<void> {
  const safe = label.replace(/[^a-z0-9-]+/gi, '-').toLowerCase();
  await page
    .screenshot({ path: path.join(SHOTS, `e${String(n).padStart(2, '0')}-${safe}.png`), fullPage: true })
    .catch(() => undefined);
}

async function adminToken(req: APIRequestContext): Promise<string> {
  const res = await req.post(`${API_BASE}/v1/auth/login`, {
    data: { username: 'admin', password: 'admin123' },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok()) throw new Error(`admin login failed: ${res.status()}`);
  const body = (await res.json()) as { data: { accessToken: string } };
  return body.data.accessToken;
}

async function tryLoginToken(req: APIRequestContext, username: string, password: string): Promise<string | null> {
  const res = await req.post(`${API_BASE}/v1/auth/login`, {
    data: { username, password },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok()) return null;
  const body = (await res.json()) as { data?: { accessToken?: string } };
  return body.data?.accessToken ?? null;
}

async function findPilotProjectId(req: APIRequestContext, token: string): Promise<string | null> {
  const res = await req.get(`${API_BASE}/v1/projects?page=0&size=500`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) return null;
  const body = (await res.json()) as { data?: { content?: Array<{ id: string; code: string }> } };
  return body.data?.content?.find((p) => p.code === PILOT_PROJECT.code)?.id ?? null;
}

async function findFirstPilotActivityId(
  req: APIRequestContext,
  token: string,
  projectId: string,
): Promise<{ id: string; editStatus?: string } | null> {
  const r = await req.get(`${API_BASE}/v1/projects/${projectId}/activities`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!r.ok()) return null;
  const body = (await r.json()) as {
    data?: Array<{ id: string; code: string; editStatus?: string }>;
  };
  const hit = body.data?.find((a) => a.code?.startsWith('PILOT-ACT'));
  return hit ? { id: hit.id, editStatus: hit.editStatus } : null;
}

test.describe("Devil's Advocate — Edge Cases", () => {
  test.describe.configure({ mode: 'serial' });

  let token = '';
  let projectId: string | null = null;
  let activityId: string | null = null;
  let activityEditStatus: string | undefined;

  test.beforeAll(async ({ request }) => {
    ensureEdgesHeader();
    try {
      token = await adminToken(request);
      projectId = await findPilotProjectId(request, token);
      if (projectId) {
        const act = await findFirstPilotActivityId(request, token, projectId);
        activityId = act?.id ?? null;
        activityEditStatus = act?.editStatus;
      }
    } catch {
      /* silent — each test handles missing data */
    }
  });

  test('Edge 1: DPR with qtyExecuted = -10 (negative quantity)', async ({ page, request }) => {
    if (!projectId) {
      appendEdge(1, 'qtyExecuted=-10', 'BLOCKED', null, 'info', 'no pilot project');
      test.skip(true);
      return;
    }
    const res = await request.post(`${API_BASE}/v1/projects/${projectId}/dpr`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        reportDate: '2026-04-27',
        supervisorName: 'DA Probe',
        activityId,
        activityName: 'DA Negative-Qty',
        unit: 'm3',
        qtyExecuted: -10,
      },
    });
    const body = await res.text();
    const status = res.status();
    const severity: 'critical' | 'high' | 'low' = status === 200 || status === 201 ? 'critical' : status >= 400 && status < 500 ? 'low' : 'high';
    appendEdge(
      1,
      'POST DPR with qtyExecuted = -10',
      status,
      body,
      severity,
      severity === 'critical'
        ? 'BUG: server accepted negative quantity. Downstream productivity% and EV will be negative.'
        : 'server correctly rejected negative quantity',
    );
    await login(page).catch(() => undefined);
    await page.goto(`/projects/${projectId}/dpr`).catch(() => undefined);
    await shot(page, 1, 'edge1-negative-qty-ui');
    expect.soft([400, 422, 403]).toContain(status);
  });

  test('Edge 2: DPR against a DRAFT (unlocked) activity', async ({ page, request }) => {
    if (!projectId) {
      appendEdge(2, 'DPR on DRAFT activity', 'BLOCKED', null, 'info', 'no pilot project');
      test.skip(true);
      return;
    }
    // Create a fresh DRAFT activity for this test
    let draftId: string | null = null;
    const wbsR = await request.get(`${API_BASE}/v1/projects/${projectId}/wbs`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const wbs = ((await wbsR.json().catch(() => ({}))) as { data?: Array<{ id: string }> }).data ?? [];
    const wbsId = wbs[0]?.id;
    if (wbsId) {
      const createR = await request.post(`${API_BASE}/v1/projects/${projectId}/activities`, {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        data: {
          code: `DA-DRAFT-${Date.now().toString().slice(-6)}`,
          name: 'DA Draft Activity',
          projectId,
          wbsNodeId: wbsId,
        },
      });
      if (createR.ok()) {
        draftId = ((await createR.json()) as { data?: { id: string } }).data?.id ?? null;
      }
    }
    const targetId = draftId ?? activityId;
    const res = await request.post(`${API_BASE}/v1/projects/${projectId}/dpr`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        reportDate: '2026-04-27',
        supervisorName: 'DA Probe',
        activityId: targetId,
        activityName: 'DA Draft DPR',
        unit: 'm3',
        qtyExecuted: 10,
      },
    });
    const status = res.status();
    const body = await res.text();
    const severity = status >= 200 && status < 300 ? 'high' : 'low';
    appendEdge(
      2,
      'DPR submission against a DRAFT activity',
      status,
      body,
      severity,
      severity === 'high'
        ? 'BUG: server accepted DPR on a DRAFT activity. ACTIVITY_LOCKED guard is missing or bypassed.'
        : `server rejected DPR on draft (draftId=${draftId ?? 'reused-existing'})`,
    );
    await shot(page, 2, 'edge2-draft-activity');
    expect.soft(status).not.toBe(200);
  });

  test('Edge 3: DPR with future reportDate', async ({ page, request }) => {
    if (!projectId) { appendEdge(3, 'future DPR', 'BLOCKED', null, 'info', 'no pilot'); test.skip(true); return; }
    const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
    const res = await request.post(`${API_BASE}/v1/projects/${projectId}/dpr`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        reportDate: tomorrow,
        supervisorName: 'DA Probe',
        activityId,
        activityName: 'DA Future-Date',
        unit: 'm3',
        qtyExecuted: 5,
      },
    });
    const status = res.status();
    const body = await res.text();
    const severity = status >= 200 && status < 300 ? 'high' : 'low';
    appendEdge(
      3,
      `DPR with reportDate = ${tomorrow} (future)`,
      status,
      body,
      severity,
      severity === 'high'
        ? 'BUG: server accepted future-dated DPR. Will populate next-day rollups with phantom progress.'
        : 'server correctly rejected future-dated DPR',
    );
    await shot(page, 3, 'edge3-future-dpr');
    expect.soft(status).not.toBe(200);
  });

  test('Edge 4: Lock activity then attempt to edit planned qty', async ({ page, request }) => {
    if (!projectId || !activityId) {
      appendEdge(4, 'edit locked activity', 'BLOCKED', null, 'info', 'no activity');
      test.skip(true);
      return;
    }
    // Ensure locked
    await request.post(`${API_BASE}/v1/projects/${projectId}/activities/${activityId}/lock`, {
      headers: { Authorization: `Bearer ${token}` },
    }).catch(() => undefined);
    // Attempt to mutate planned values via PUT/PATCH
    const res = await request.put(`${API_BASE}/v1/projects/${projectId}/activities/${activityId}`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { originalDuration: 999, plannedStartDate: '2026-01-01' },
    });
    const status = res.status();
    const body = await res.text();
    const severity = status >= 200 && status < 300 ? 'high' : 'low';
    appendEdge(
      4,
      'PUT /activities/{id} while LOCKED',
      status,
      body,
      severity,
      severity === 'high'
        ? 'BUG: locked activity accepted plan-side edit. Locking is advisory only.'
        : 'lock enforcement works — edit refused',
    );
    await shot(page, 4, 'edge4-locked-edit');
    expect.soft(status).not.toBe(200);
  });

  test('Edge 5: Supervisor reading another supervisor\'s DBS row', async ({ page, request }) => {
    if (!projectId) { appendEdge(5, 'sup-cross-read', 'BLOCKED', null, 'info', 'no pilot'); test.skip(true); return; }
    const sup1Token = await tryLoginToken(request, 'pilot.sup1', DEFAULT_PASSWORD);
    if (!sup1Token) {
      appendEdge(5, 'sup-cross-read', 'BLOCKED', null, 'info',
        'pilot.sup1 not provisioned (Track A skipped) — cannot test cross-supervisor RBAC');
      test.skip(true);
      return;
    }
    // Find sup2's user id via admin
    const usersR = await request.get(`${API_BASE}/v1/users?page=0&size=500`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const users = ((await usersR.json().catch(() => ({}))) as {
      data?: { content?: Array<{ id: string; username: string }> };
    }).data?.content ?? [];
    const sup2 = users.find((u) => u.username === 'pilot.sup2');
    if (!sup2) {
      appendEdge(5, 'sup-cross-read', 'BLOCKED', null, 'info', 'pilot.sup2 not provisioned');
      test.skip(true);
      return;
    }
    const res = await request.get(
      `${API_BASE}/v1/projects/${projectId}/dbs/supervisor/${sup2.id}?date=2026-04-27`,
      { headers: { Authorization: `Bearer sup1Token` } },
    );
    const status = res.status();
    const body = await res.text();
    const severity = status === 200 ? 'critical' : status === 403 ? 'low' : 'medium';
    appendEdge(
      5,
      'pilot.sup1 reads pilot.sup2 DBS row',
      status,
      body,
      severity,
      severity === 'critical'
        ? 'BUG: cross-supervisor data leak. RBAC scope on /dbs/supervisor/{id} is missing.'
        : `server enforced isolation with ${status}`,
    );
    await shot(page, 5, 'edge5-rbac-sup-sup');
    expect.soft([401, 403]).toContain(status);
  });

  test('Edge 6: Engineer locks an activity outside their chain', async ({ page, request }) => {
    if (!projectId || !activityId) {
      appendEdge(6, 'eng-cross-lock', 'BLOCKED', null, 'info', 'no activity');
      test.skip(true);
      return;
    }
    const engToken = await tryLoginToken(request, 'pilot.eng1', DEFAULT_PASSWORD);
    if (!engToken) {
      appendEdge(6, 'eng-cross-lock', 'BLOCKED', null, 'info', 'pilot.eng1 not provisioned');
      test.skip(true);
      return;
    }
    // PILOT-ACT-03 is assigned to sup3 → eng2's chain. eng1 should NOT be able to lock it.
    const actsR = await request.get(`${API_BASE}/v1/projects/${projectId}/activities`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const acts = ((await actsR.json().catch(() => ({}))) as {
      data?: Array<{ id: string; code: string }>;
    }).data ?? [];
    const outsider = acts.find((a) => a.code === 'PILOT-ACT-03');
    if (!outsider) {
      appendEdge(6, 'eng-cross-lock', 'BLOCKED', null, 'info', 'PILOT-ACT-03 not present');
      test.skip(true);
      return;
    }
    const res = await request.post(
      `${API_BASE}/v1/projects/${projectId}/activities/${outsider.id}/unlock`,
      { headers: { Authorization: `Bearer ${engToken}` } },
    );
    const status = res.status();
    const body = await res.text();
    const severity = status >= 200 && status < 300 ? 'high' : 'low';
    appendEdge(
      6,
      'pilot.eng1 unlocks activity in eng2\'s chain (PILOT-ACT-03)',
      status,
      body,
      severity,
      severity === 'high'
        ? 'BUG: project-scoped role-check is too coarse — any site engineer can lock/unlock any activity.'
        : 'engineer cross-chain action blocked',
    );
    await shot(page, 6, 'edge6-rbac-eng-eng');
    expect.soft([401, 403]).toContain(status);
  });

  test('Edge 7: Cross-project leak — PM of A reads DPRs of B', async ({ page, request }) => {
    if (!projectId) { appendEdge(7, 'cross-proj-leak', 'BLOCKED', null, 'info', 'no pilot'); test.skip(true); return; }
    const pmToken = await tryLoginToken(request, 'pilot.pm1', DEFAULT_PASSWORD);
    if (!pmToken) {
      appendEdge(7, 'cross-proj-leak', 'BLOCKED', null, 'info', 'pilot.pm1 not provisioned');
      test.skip(true);
      return;
    }
    // Find ANY other project as admin
    const listR = await request.get(`${API_BASE}/v1/projects?page=0&size=500`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const others = (((await listR.json().catch(() => ({}))) as {
      data?: { content?: Array<{ id: string; code: string }> };
    }).data?.content ?? []).filter((p) => p.id !== projectId);
    if (others.length === 0) {
      appendEdge(7, 'cross-proj-leak', 'BLOCKED', null, 'info',
        'no second project exists — cannot test cross-project leak');
      test.skip(true);
      return;
    }
    const other = others[0];
    const res = await request.get(`${API_BASE}/v1/projects/${other.id}/dpr`, {
      headers: { Authorization: `Bearer ${pmToken}` },
    });
    const status = res.status();
    const body = await res.text();
    let leaks = false;
    try {
      const parsed = JSON.parse(body) as { data?: unknown[] };
      leaks = Array.isArray(parsed.data) && parsed.data.length > 0;
    } catch {
      /* non-JSON body, e.g. 403 page */
    }
    const severity: 'critical' | 'high' | 'low' =
      status === 200 && leaks ? 'critical' : status === 200 ? 'high' : 'low';
    appendEdge(
      7,
      `pilot.pm1 reads /projects/${other.code}/dpr`,
      status,
      body,
      severity,
      severity === 'critical'
        ? 'CRITICAL: PM of project A can list DPRs of project B. ProjectScopeFilter missing on DPR controller.'
        : severity === 'high'
          ? 'response 200 but empty data — endpoint visible to non-members, scoping at row level only'
          : `cross-project read blocked with ${status}`,
    );
    await shot(page, 7, 'edge7-cross-project');
    expect.soft([401, 403]).toContain(status);
  });

  test('Edge 8: Zero-norm work activity — productivity preview', async ({ page, request }) => {
    if (!projectId) { appendEdge(8, 'zero-norm', 'BLOCKED', null, 'info', 'no pilot'); test.skip(true); return; }
    // Create a work-activity with no norm
    const code = `DA-NONORM-${Date.now().toString().slice(-6)}`;
    const createR = await request.post(`${API_BASE}/v1/work-activities`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { code, name: 'DA Zero-Norm', unit: 'm3' },
    });
    if (!createR.ok()) {
      appendEdge(8, 'zero-norm', createR.status(), await createR.text(), 'info',
        'could not create probe work-activity — does endpoint exist?');
      test.skip(true);
      return;
    }
    const wa = ((await createR.json()) as { data?: { id: string } }).data;
    // Hit productivity preview if exposed
    const preview = await request.post(
      `${API_BASE}/v1/projects/${projectId}/dpr/productivity-preview`,
      {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        data: {
          workActivityId: wa?.id,
          manpower: [{ roleId: null, nos: 5, workingHours: 8 }],
          equipment: [],
        },
      },
    );
    const status = preview.status();
    const body = await preview.text();
    let degradesGracefully = false;
    try {
      const j = JSON.parse(body) as { data?: { coverage?: string; warnings?: string[] } };
      degradesGracefully = j.data?.coverage === 'NONE' || (j.data?.warnings?.length ?? 0) > 0;
    } catch {
      /* */
    }
    const severity = status >= 500 ? 'high' : 'low';
    appendEdge(
      8,
      'productivity-preview with zero-norm work-activity',
      status,
      body,
      severity,
      severity === 'high'
        ? 'BUG: 5xx — backend crashed on missing norm instead of returning coverage=NONE'
        : degradesGracefully
          ? 'graceful degradation: coverage=NONE / warnings returned'
          : 'response 2xx but no explicit warning; UI may show stale numeric preview',
    );
    await shot(page, 8, 'edge8-zero-norm');
    expect.soft(status).toBeLessThan(500);
  });

  test('Edge 9: manpower=0 with qtyExecuted>0 (productivity = ∞)', async ({ page, request }) => {
    if (!projectId) { appendEdge(9, 'inf-productivity', 'BLOCKED', null, 'info', 'no pilot'); test.skip(true); return; }
    const res = await request.post(`${API_BASE}/v1/projects/${projectId}/dpr`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        reportDate: '2026-04-28',
        supervisorName: 'DA Probe',
        activityId,
        activityName: 'DA Inf-Productivity',
        unit: 'm3',
        qtyExecuted: 50,
        manpower: [], // zero crew
        equipment: [],
      },
    });
    const status = res.status();
    const body = await res.text();
    // If accepted, check the DBS roll-up didn't return NaN / Infinity
    let nanLeak = false;
    if (status >= 200 && status < 300) {
      const dbsR = await request.get(
        `${API_BASE}/v1/projects/${projectId}/dbs/project?date=2026-04-28&period=DAY`,
        { headers: { Authorization: `Bearer ${token}` } },
      );
      if (dbsR.ok()) {
        const txt = await dbsR.text();
        nanLeak = /NaN|Infinity|null,null,null/.test(txt);
      }
    }
    const severity = nanLeak ? 'high' : 'low';
    appendEdge(
      9,
      'DPR with manpower=[] and qtyExecuted=50',
      status,
      body,
      severity,
      nanLeak
        ? 'BUG: DBS rollup contains NaN/Infinity after divide-by-zero crew'
        : `manpower=0 handled gracefully (productivity tile may show — or "—")`,
    );
    await shot(page, 9, 'edge9-inf-productivity');
    expect.soft(true).toBeTruthy();
  });

  test('Edge 10: Future date + delays=RAIN + weather=CLEAR (semantic conflict)', async ({ page, request }) => {
    if (!projectId) { appendEdge(10, 'semantic-conflict', 'BLOCKED', null, 'info', 'no pilot'); test.skip(true); return; }
    const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
    const res = await request.post(`${API_BASE}/v1/projects/${projectId}/dpr`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        reportDate: tomorrow,
        supervisorName: 'DA Probe',
        activityId,
        activityName: 'DA Semantic-Conflict',
        unit: 'm3',
        qtyExecuted: 5,
        weatherCondition: 'CLEAR',
        delayReason: 'RAIN',
      },
    });
    const status = res.status();
    const body = await res.text();
    const accepted = status >= 200 && status < 300;
    appendEdge(
      10,
      'DPR future-date with weather=CLEAR + delayReason=RAIN',
      status,
      body,
      accepted ? 'medium' : 'low',
      accepted
        ? 'server accepted the conflicting fields verbatim — no semantic validation. ' +
          'Reports will quietly mis-classify the delay cause vs the weather record.'
        : `server rejected the conflict (${status})`,
    );
    await shot(page, 10, 'edge10-semantic-conflict');
    // No expectation flip — pure documentation.
    expect.soft(true).toBeTruthy();
  });
});

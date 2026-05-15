import { test as base, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { TEST_USERS_FILE, type ProvisionedFixture } from './test-users';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

/**
 * Shared login helper. We bypass the form entirely and seed the access_token cookie + the
 * Zustand-persisted auth store directly. This keeps the test deterministic against the real
 * backend without depending on the form's hydration timing.
 */
export async function login(page: Page, username = 'admin', password = 'admin123') {
  // Step 1: get tokens directly from the backend
  const loginRes = await page.request.post(`${API_BASE}/v1/auth/login`, {
    data: { username, password },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!loginRes.ok()) {
    throw new Error(`login(${username}) failed: ${loginRes.status()} ${await loginRes.text()}`);
  }
  const loginBody = (await loginRes.json()) as {
    data: { accessToken: string; refreshToken: string };
  };
  const { accessToken, refreshToken } = loginBody.data;

  // Step 2: fetch the canonical UserResponse so the persisted store mirrors what the real
  // login flow would have produced (Sidebar reads `user.roles` from this).
  const meRes = await page.request.get(`${API_BASE}/v1/users/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!meRes.ok()) {
    throw new Error(`/v1/users/me failed for ${username}: ${meRes.status()}`);
  }
  const meBody = (await meRes.json()) as { data: unknown };
  const user = meBody.data;

  // Step 3: seed the cookie the middleware reads, plus localStorage entries the axios
  // interceptor + Zustand auth store expect. Doing this before the page loads guarantees the
  // very first render of `/` sees an authenticated context.
  await page.context().addCookies([
    {
      name: 'access_token',
      value: accessToken,
      domain: 'localhost',
      path: '/',
      sameSite: 'Strict',
    },
  ]);
  await page.addInitScript(
    ({ access, refresh, userObj }) => {
      try {
        localStorage.setItem('access_token', access);
        localStorage.setItem('refresh_token', refresh);
        localStorage.setItem(
          'bipros-auth',
          JSON.stringify({
            state: { user: userObj, accessToken: access, refreshToken: refresh },
            version: 0,
          }),
        );
      } catch {
        /* test-fixture only */
      }
    },
    { access: accessToken, refresh: refreshToken, userObj: user },
  );

  // Step 4: navigate; middleware sees the cookie and lets the dashboard render.
  await page.goto('/');
  await page.waitForURL(/\/$|\/$/, { timeout: 15_000 });
  await expect(page).toHaveURL('/');
}

export const test = base.extend<{ authenticatedPage: Page }>({
  authenticatedPage: async ({ page }, use) => {
    await login(page);
    await use(page);
  },
});

/**
 * Log in as the e2e test user provisioned for the given profile code.
 * Reads credentials from the JSON globalSetup wrote; throws with a clear
 * message if globalSetup didn't run or the requested profile isn't there.
 */
export async function loginAs(page: Page, profileCode: string): Promise<void> {
  const filePath = path.resolve(TEST_USERS_FILE);
  if (!fs.existsSync(filePath)) {
    throw new Error(
      `[loginAs] ${TEST_USERS_FILE} not found. Did Playwright globalSetup run? ` +
        `Backend must be reachable at ${API_BASE} before tests start.`,
    );
  }
  const fixture = JSON.parse(fs.readFileSync(filePath, 'utf-8')) as ProvisionedFixture;
  const user = fixture.users.find((u) => u.profileCode === profileCode);
  if (!user) {
    throw new Error(
      `[loginAs] No e2e user provisioned for profile "${profileCode}". ` +
        `Available: ${fixture.users.map((u) => u.profileCode).join(', ')}`,
    );
  }
  await login(page, user.username, user.password);
}

/**
 * Returns the project ID that globalSetup enrolled the e2e test users into.
 * Tests that hit role-aware AI need this so AiAccessGuard.canChat() passes.
 */
export function getE2eProjectId(): string | null {
  const filePath = path.resolve(TEST_USERS_FILE);
  if (!fs.existsSync(filePath)) return null;
  const fixture = JSON.parse(fs.readFileSync(filePath, 'utf-8')) as ProvisionedFixture;
  return fixture.projectId;
}

/**
 * Log in as a backend-seeded user (typically from IcpmsPhaseASeeder).
 * Returns the raw access token and the `/v1/users/me` body so callers can
 * decode the JWT or assert against the canonical UserResponse without a
 * second round trip.
 *
 * Default password matches IcpmsPhaseASeeder's `ChangeMe@2026`.
 */
export interface LoginAsSeededResult {
  accessToken: string;
  refreshToken: string;
  user: Record<string, unknown>;
}

export async function loginAsSeeded(
  page: Page,
  username: string,
  password = 'ChangeMe@2026',
): Promise<LoginAsSeededResult> {
  const loginRes = await page.request.post(`${API_BASE}/v1/auth/login`, {
    data: { username, password },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!loginRes.ok()) {
    throw new Error(
      `loginAsSeeded(${username}) failed: ${loginRes.status()} ${await loginRes.text()}`,
    );
  }
  const body = (await loginRes.json()) as {
    data: { accessToken: string; refreshToken: string };
  };
  const { accessToken, refreshToken } = body.data;

  const meRes = await page.request.get(`${API_BASE}/v1/users/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!meRes.ok()) {
    throw new Error(`/v1/users/me failed for ${username}: ${meRes.status()}`);
  }
  const meBody = (await meRes.json()) as { data: Record<string, unknown> };
  const user = meBody.data;

  await page.context().addCookies([
    {
      name: 'access_token',
      value: accessToken,
      domain: 'localhost',
      path: '/',
      sameSite: 'Strict',
    },
  ]);
  await page.addInitScript(
    ({ access, refresh, userObj }) => {
      try {
        localStorage.setItem('access_token', access);
        localStorage.setItem('refresh_token', refresh);
        localStorage.setItem(
          'bipros-auth',
          JSON.stringify({
            state: { user: userObj, accessToken: access, refreshToken: refresh },
            version: 0,
          }),
        );
      } catch {
        /* test-fixture only */
      }
    },
    { access: accessToken, refresh: refreshToken, userObj: user },
  );

  return { accessToken, refreshToken, user };
}

/**
 * Decode a JWT without signature verification — sufficient for asserting
 * claims (`perms`, `roles`, `sub`) on a token the test just received from
 * the backend in the same request. Do NOT use this to authenticate or
 * trust an arbitrary token.
 */
export function decodeJwt<T = Record<string, unknown>>(token: string): T {
  const parts = token.split('.');
  if (parts.length !== 3) {
    throw new Error(`decodeJwt: expected 3 segments, got ${parts.length}`);
  }
  const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
  const padded = payload + '='.repeat((4 - (payload.length % 4)) % 4);
  return JSON.parse(Buffer.from(padded, 'base64').toString('utf-8')) as T;
}

export { expect };

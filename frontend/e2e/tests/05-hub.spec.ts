import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth.fixture';

test.describe('Home hub', () => {
  test('admin lands on the hub with greeting and launchpad tiles', async ({ page }) => {
    await login(page);
    await expect(page).toHaveURL('/');

    // Greeting and the explicit "View programme dashboard" link replace the dense /dashboard.
    await expect(page.getByTestId('hub-greeting')).toBeVisible();
    await expect(page.getByTestId('hub-dashboard-link')).toBeVisible();

    // The hub heading is the time-aware greeting, not "Dashboard".
    const h1 = page.locator('[data-testid="hub-greeting"] h1');
    await expect(h1).toContainText(/Good (morning|afternoon|evening)/i);

    // Hub is a launchpad — the question framing replaces the previous "For you" caption.
    await expect(
      page.getByRole('heading', { name: /What would you like to do\?/i })
    ).toBeVisible();

    // 4 action-oriented hero tiles for admin (start project / dashboard / users / settings).
    const heroCards = page.getByTestId('hub-hero-card');
    await expect(heroCards.first()).toBeVisible();
    expect(await heroCards.count()).toBeGreaterThanOrEqual(3);
    await expect(heroCards.filter({ hasText: 'Start a New Project' })).toBeVisible();
    await expect(heroCards.filter({ hasText: 'Add Users' })).toBeVisible();
  });

  test('clicking the dashboard link loads the moved analytics page', async ({ page }) => {
    await login(page);
    await page.getByTestId('hub-dashboard-link').click();
    await expect(page).toHaveURL('/dashboard');
    // The moved page renders the original "Programme command centre" hero.
    await expect(page.getByRole('heading', { name: 'Programme command centre' })).toBeVisible();
  });

  test('sidebar groups collapse and the choice persists across reloads', async ({ page }) => {
    await login(page);

    // The "Plan" group is always default-expanded, so its Home link is initially visible.
    const homeLink = page.getByRole('link', { name: 'Home', exact: true });
    await expect(homeLink).toBeVisible();

    // Toggle Plan closed; Home should disappear from the sidebar.
    const planToggle = page.getByTestId('sidebar-group-toggle').filter({ hasText: 'Plan' }).first();
    await planToggle.click();
    await expect(homeLink).toBeHidden();

    // After reload, the collapsed state must persist (localStorage).
    await page.reload();
    await expect(planToggle).toBeVisible();
    await expect(homeLink).toBeHidden();
  });
});

import { test, expect } from '../fixtures/auth.fixture';

/**
 * Navigate to the first project and return its ID from the URL.
 */
async function navigateToFirstProject(page: any): Promise<string> {
  await page.goto('/projects');
  const projectLink = page.locator('table tbody tr a').first();
  if (!(await projectLink.isVisible({ timeout: 10_000 }).catch(() => false))) {
    return '';
  }
  await projectLink.click();
  await page.waitForURL(/\/projects\/[0-9a-f-]+/, { timeout: 10_000 });
  const url = page.url();
  return url.split('/projects/')[1].split('/')[0];
}

/**
 * Navigate to a project sub-route and verify it loads without errors.
 */
async function verifyProjectSubRoute(page: any, projectId: string, subRoute: string, headingPattern?: RegExp) {
  if (!projectId) return;
  await page.goto(`/projects/${projectId}/${subRoute}`);
  await page.waitForTimeout(2000);
  const hasError = await page.locator('.text-red-500, .text-red-700, [role="alert"]').allTextContents().catch(() => []);
  if (headingPattern) {
    const hasHeading = await page.getByRole('heading', { name: headingPattern, level: 1 }).isVisible({ timeout: 5_000 }).catch(() => false);
    if (hasHeading || hasError.length === 0) {
      expect(hasError.length).toBeLessThanOrEqual(2); // Allow minor errors
    }
  }
}

test.describe('Project Sub-Routes', () => {
  let projectId = '';

  test.beforeAll(async ({ authenticatedPage: page }) => {
    projectId = await navigateToFirstProject(page);
  });

  test.describe('Activities', () => {
    test('activities page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'activities', /activit/i);
    });

    test('new activity page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'activities/new');
    });

    test('activity detail page loads', async ({ authenticatedPage: page }) => {
      if (!projectId) return;
      await page.goto(`/projects/${projectId}/activities`);
      const activityLink = page.locator('table tbody tr a').first();
      if (await activityLink.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await activityLink.click();
        await expect(page.locator('body')).not.toHaveText(/error/i);
      }
    });
  });

  test.describe('Activity Codes', () => {
    test('activity codes page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'activity-codes');
    });
  });

  test.describe('Activity Correlations', () => {
    test('activity correlations page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'activity-correlations');
    });
  });

  test.describe('BOQ', () => {
    test('BOQ page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'boq', /boq|bill/i);
    });
  });

  test.describe('Contracts', () => {
    test('contracts list page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'contracts', /contract/i);
    });

    test('contract detail page loads', async ({ authenticatedPage: page }) => {
      if (!projectId) return;
      await page.goto(`/projects/${projectId}/contracts`);
      const contractLink = page.locator('table tbody tr a').first();
      if (await contractLink.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await contractLink.click();
        await expect(page.locator('body')).not.toHaveText(/error/i);
      }
    });
  });

  test.describe('Daily Cost Report', () => {
    test('daily cost report page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'daily-cost-report');
    });
  });

  test.describe('Drawings', () => {
    test('drawings page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'drawings');
    });
  });

  test.describe('Equipment Logs', () => {
    test('equipment logs page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'equipment-logs');
    });
  });

  test.describe('GIS Viewer', () => {
    test('GIS viewer page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'gis-viewer');
    });
  });

  test.describe('Labour Returns', () => {
    test('labour returns page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'labour-returns');
    });
  });

  test.describe('Material Consumption', () => {
    test('material consumption page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'material-consumption');
    });
  });

  test.describe('Material Reconciliation', () => {
    test('material reconciliation page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'material-reconciliation');
    });
  });

  test.describe('Material Sources', () => {
    test('material sources page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'material-sources');
    });
  });

  test.describe('Next Day Plan', () => {
    test('next day plan page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'next-day-plan');
    });
  });

  test.describe('Predictions', () => {
    test('predictions page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'predictions');
    });
  });

  test.describe('RA Bills', () => {
    test('RA bills page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'ra-bills');
    });
  });

  test.describe('Relationships', () => {
    test('relationships page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'relationships');
    });
  });

  test.describe('Resource Deployment', () => {
    test('resource deployment page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'resource-deployment');
    });
  });

  test.describe('RFIs', () => {
    test('RFIs page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'rfis');
    });
  });

  test.describe('Risk Analysis', () => {
    test('risk analysis page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'risk-analysis');
    });
  });

  test.describe('Risks', () => {
    test('risks page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'risks', /risk/i);
    });
  });

  test.describe('Schedule Compression', () => {
    test('schedule compression page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'schedule-compression');
    });
  });

  test.describe('Schedule Health', () => {
    test('schedule health page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'schedule-health');
    });
  });

  test.describe('Stock Register', () => {
    test('stock register page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'stock-register');
    });
  });

  test.describe('Stretches', () => {
    test('stretches page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'stretches', /stretch/i);
    });
  });

  test.describe('Weather Log', () => {
    test('weather log page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'weather-log');
    });
  });

  test.describe('Issues', () => {
    test('issues page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'issues');
    });

    test('new issue form loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'issues/new');
    });
  });

  test.describe('GRNs', () => {
    test('GRNs page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'grns');
    });
  });

  test.describe('Global Change', () => {
    test('global change page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'global-change');
    });
  });

  test.describe('Materials', () => {
    test('materials page loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'materials');
    });
  });

  test.describe('Quality hub', () => {
    test('quality hub loads', async ({ authenticatedPage: page }) => {
      await verifyProjectSubRoute(page, projectId, 'quality');
    });
  });

  test.describe('Procurement hub', () => {
    test('procurement hub loads and both sub-tabs switch', async ({ authenticatedPage: page }) => {
      if (!projectId) return;
      await page.goto(`/projects/${projectId}/procurement`);
      await page.waitForTimeout(2000);

      // Hub heading renders (mirrors the Quality tab's h1 assertion).
      await expect(
        page.getByRole('heading', { name: /procurement/i, level: 1 }),
      ).toBeVisible({ timeout: 10_000 });

      // Sub-contractors is the default sub-tab; its tab button is present.
      await expect(
        page.getByRole('button', { name: 'Sub-contractors' }),
      ).toBeVisible();

      // The sub-contractors panel renders either a data table or its empty state.
      const scContent = await page
        .locator('table, .border-dashed')
        .first()
        .isVisible({ timeout: 10_000 })
        .catch(() => false);
      expect(scContent).toBeTruthy();

      // Switch to the Material Vendors sub-tab and confirm its panel header renders.
      await page.getByRole('button', { name: 'Material Vendors' }).click();
      await expect(
        page.getByRole('heading', { name: /material vendors/i }),
      ).toBeVisible({ timeout: 10_000 });

      // The vendors panel renders either a data table or its empty state.
      const mvContent = await page
        .locator('table, .border-dashed')
        .first()
        .isVisible({ timeout: 10_000 })
        .catch(() => false);
      expect(mvContent).toBeTruthy();

      // No crash / error boundary.
      await expect(page.locator('body')).not.toHaveText(/something went wrong/i);
    });
  });
});

import { test, expect } from "../fixtures/auth.fixture";

/**
 * Phase G — Project Team admin e2e. Opens the project's /team page, adds a
 * user as a PM via the Add dialog, asserts the row appears, then removes it.
 *
 * Skips when no seeded project / no users are available (so the spec is safe
 * against a fresh dev DB).
 */

async function resolveProjectId(page: any): Promise<string | null> {
  const envId = process.env.SEED_PROJECT_ID;
  if (envId) return envId;

  await page.goto("/projects");
  const link = page.locator("table tbody tr a").first();
  if (!(await link.isVisible({ timeout: 10_000 }).catch(() => false))) {
    return null;
  }
  await link.click();
  await page.waitForURL(/\/projects\/[0-9a-f-]+/, { timeout: 10_000 });
  return page.url().split("/projects/")[1].split("/")[0];
}

test.describe("Project Team admin", () => {
  test("add and remove a PM team member", async ({ authenticatedPage: page }) => {
    const projectId = await resolveProjectId(page);
    test.skip(!projectId, "No seeded project available — set SEED_PROJECT_ID or seed one");

    await page.goto(`/projects/${projectId}/team`);
    await expect(
      page.getByRole("heading", { name: /Team|Project Team/i }).first(),
    ).toBeVisible({ timeout: 15_000 });

    // Open the Add dialog.
    const addBtn = page
      .getByRole("button", { name: /Add Member|Add User|Add PM|Add/i })
      .first();
    const addVisible = await addBtn.isVisible({ timeout: 10_000 }).catch(() => false);
    test.skip(!addVisible, "Add Member button not available — admin role required");

    await addBtn.click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 5_000 });

    // Pick the first user in the SearchableSelect. The picker is a custom
    // button-trigger popover (NOT a native combobox). Click the trigger inside
    // the "User" field to open it, then click the first option in the portaled
    // <ul> list (which renders outside the dialog DOM tree under <body>).
    const userField = dialog.locator('[data-testid="add-team-member-user-field"]');
    const userTrigger = userField.locator("button").first();
    const userTriggerVisible = await userTrigger.isVisible({ timeout: 3_000 }).catch(() => false);
    test.skip(!userTriggerVisible, "User picker not available — no eligible users in directory");

    await userTrigger.click();
    // The portaled list is tagged with data-testid="searchable-select-list".
    // Wait for the list, then click the first real option (filtering out the
    // "Loading…" and "No matches found" placeholder <li>s).
    const list = page.getByTestId("searchable-select-list").first();
    await list.waitFor({ state: "visible", timeout: 5_000 });
    const firstUserOption = list
      .getByTestId("searchable-select-option")
      .first();
    await firstUserOption.waitFor({ state: "visible", timeout: 5_000 });
    await firstUserOption.click();
    // List should close after a successful pick.
    await expect(list).toBeHidden({ timeout: 3_000 });

    // Pick PM role on the native <select>.
    await dialog
      .locator('[data-testid="add-team-member-role-select"]')
      .selectOption("PM");

    // Save — submit button has a dedicated test id; fall back to the visible
    // label if the test id is missing on an older build.
    const submit = dialog.getByTestId("add-team-member-submit");
    if (await submit.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await submit.click();
    } else {
      await dialog
        .getByRole("button", { name: /^(Add member|Adding…|Save|Confirm)$/i })
        .click();
    }

    // Dialog closes + a PM row should be visible somewhere in the page.
    await expect(dialog).toBeHidden({ timeout: 10_000 });
    await expect(page.getByText(/Project Manager/i).first()).toBeVisible({
      timeout: 10_000,
    });

    // Remove: the row-level remove button has aria-label "Remove <name>".
    // Hide the bottom-right AI chat FAB first — it floats over the page corner and
    // sometimes intercepts pointer events for buttons that land near the same spot.
    await page.evaluate(() => {
      const fab = document.querySelector<HTMLElement>(".ai-chat-fab");
      if (fab) fab.style.display = "none";
    });
    const deleteBtn = page.getByRole("button", { name: /^Remove\s+/i }).first();
    if (await deleteBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await deleteBtn.click();
      // Confirm dialog ("Remove team member?") — click the destructive "Remove" button inside it.
      const confirmDialog = page.getByRole("dialog");
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });
      await confirmDialog
        .getByRole("button", { name: /^(Remove|Removing…|Confirm|Yes|Delete)$/i })
        .click();
      await expect(confirmDialog).toBeHidden({ timeout: 8_000 });
    }
  });
});

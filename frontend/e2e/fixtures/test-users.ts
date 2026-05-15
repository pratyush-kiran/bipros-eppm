export interface E2eTestUser {
  username: string;
  password: string;
  profileCode: string;
  email: string;
  firstName: string;
  lastName: string;
}

/**
 * The four profiles that DataSeeder does NOT seed users for. globalSetup
 * provisions one demo user per profile via the admin API; the spec under
 * 30-ai-role-awareness.spec.ts impersonates them through loginAs(profileCode).
 *
 * Usernames are prefixed `e2e_` so they're easy to identify and disable in
 * the admin UI if a teardown is skipped or fails.
 */
export const E2E_TEST_USERS: ReadonlyArray<E2eTestUser> = [
  {
    username: 'e2e_smanager',
    password: 'e2e-Site!123',
    profileCode: 'SITE_MANAGER',
    email: 'e2e_smanager@bipros.local',
    firstName: 'E2E',
    lastName: 'SiteManager',
  },
  {
    username: 'e2e_pengineer',
    password: 'e2e-Eng!123',
    profileCode: 'PROJECT_ENGINEER',
    email: 'e2e_pengineer@bipros.local',
    firstName: 'E2E',
    lastName: 'ProjectEngineer',
  },
  {
    username: 'e2e_qcmanager',
    password: 'e2e-Qc!123',
    profileCode: 'QA_QC_ENGINEER',
    email: 'e2e_qcmanager@bipros.local',
    firstName: 'E2E',
    lastName: 'QcManager',
  },
  {
    username: 'e2e_bimcoord',
    password: 'e2e-Bim!123',
    profileCode: 'BIM_DATA_COORDINATOR',
    email: 'e2e_bimcoord@bipros.local',
    firstName: 'E2E',
    lastName: 'BimCoord',
  },
];

export interface ProvisionedUser extends E2eTestUser {
  id: string | null;
}

export interface ProvisionedFixture {
  /** Project the e2e test users have been added to as TEAM_MEMBER. */
  projectId: string | null;
  users: ProvisionedUser[];
}

export const TEST_USERS_FILE = 'e2e/.auth/test-users.json';

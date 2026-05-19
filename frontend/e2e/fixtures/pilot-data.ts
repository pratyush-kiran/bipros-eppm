/**
 * Shared constants for the pilot-project end-to-end campaign (specs 60-71).
 *
 * Track A creates these entities via the UI; Tracks B/C and the Devil's
 * Advocate read them by name/code. Keep this file the single source of truth
 * for usernames, codes, rates, norms, and quantities — if a value changes
 * here, all downstream specs see the change without edits.
 */

export const PILOT_PROJECT = {
  code: 'PILOT-001',
  name: 'Pilot Construction Project (E2E)',
  bac: 5_000_000, // ₹50,00,000
  currency: 'INR',
  /** ISO date — start of the DPR target week (Monday). Set to 4 weeks ago so DBS roll-ups have a clean week-of-data. */
  startDate: '2026-04-20',
  finishDate: '2026-07-20',
} as const;

export const DEFAULT_PASSWORD = 'ChangeMe@2026';

export type PilotUser = {
  username: string;
  fullName: string;
  email: string;
  role: 'PM' | 'PROJECT_CONTROLS' | 'SITE_ENGINEER' | 'CONSTRUCTION_MANAGER' | 'SUPERVISOR';
  reportsTo?: string;
};

export const PILOT_USERS: ReadonlyArray<PilotUser> = [
  { username: 'pilot.pm1', fullName: 'Pilot PM One', email: 'pilot.pm1@bipros.test', role: 'PM' },
  { username: 'pilot.cm1', fullName: 'Pilot CM One', email: 'pilot.cm1@bipros.test', role: 'CONSTRUCTION_MANAGER', reportsTo: 'pilot.pm1' },
  { username: 'pilot.pce1', fullName: 'Pilot Controls One', email: 'pilot.pce1@bipros.test', role: 'PROJECT_CONTROLS', reportsTo: 'pilot.pm1' },
  { username: 'pilot.eng1', fullName: 'Pilot Engineer One', email: 'pilot.eng1@bipros.test', role: 'SITE_ENGINEER', reportsTo: 'pilot.cm1' },
  { username: 'pilot.eng2', fullName: 'Pilot Engineer Two', email: 'pilot.eng2@bipros.test', role: 'SITE_ENGINEER', reportsTo: 'pilot.cm1' },
  { username: 'pilot.sup1', fullName: 'Pilot Supervisor One', email: 'pilot.sup1@bipros.test', role: 'SUPERVISOR', reportsTo: 'pilot.eng1' },
  { username: 'pilot.sup2', fullName: 'Pilot Supervisor Two', email: 'pilot.sup2@bipros.test', role: 'SUPERVISOR', reportsTo: 'pilot.eng1' },
  { username: 'pilot.sup3', fullName: 'Pilot Supervisor Three', email: 'pilot.sup3@bipros.test', role: 'SUPERVISOR', reportsTo: 'pilot.eng2' },
  { username: 'pilot.sup4', fullName: 'Pilot Supervisor Four', email: 'pilot.sup4@bipros.test', role: 'SUPERVISOR', reportsTo: 'pilot.eng2' },
] as const;

export const WORK_ACTIVITIES = [
  { code: 'PILOT-EXC', name: 'Pilot Excavation', unit: 'm3', normOutputPerManPerDay: 10 },
  { code: 'PILOT-PCC', name: 'Pilot PCC', unit: 'm3', normOutputPerManPerDay: 5 },
  { code: 'PILOT-REB', name: 'Pilot Reinforcement', unit: 'kg', normOutputPerManPerDay: 100 },
  { code: 'PILOT-CON', name: 'Pilot Concreting', unit: 'm3', normOutputPerManPerDay: 8 },
] as const;

export const MANPOWER_ROLES = [
  { code: 'PILOT-MASON', name: 'Pilot Mason', dailyRate: 800 },
  { code: 'PILOT-HELPER', name: 'Pilot Helper', dailyRate: 500 },
  { code: 'PILOT-BARBENDER', name: 'Pilot Bar Bender', dailyRate: 1000 },
  { code: 'PILOT-CARPENTER', name: 'Pilot Carpenter', dailyRate: 900 },
] as const;

export const EQUIPMENT_ROLES = [
  { code: 'PILOT-EXCAVATOR', name: 'Pilot Excavator', dailyRate: 15000 },
  { code: 'PILOT-MIXER', name: 'Pilot Concrete Mixer', dailyRate: 3000 },
  { code: 'PILOT-VIBRATOR', name: 'Pilot Vibrator', dailyRate: 500 },
] as const;

export const MATERIAL_ROLES = [
  { code: 'PILOT-CEMENT', name: 'Pilot Cement', unit: 'bag', dailyRate: 400 },
  { code: 'PILOT-STEEL', name: 'Pilot Steel', unit: 'kg', dailyRate: 65 },
  { code: 'PILOT-SAND', name: 'Pilot Sand', unit: 'm3', dailyRate: 1500 },
  { code: 'PILOT-AGGREGATE', name: 'Pilot Aggregate', unit: 'm3', dailyRate: 1800 },
] as const;

export const WBS_NODES = [
  { code: 'PILOT-WBS-01', name: 'Civil Works', budgetCrores: 0.25 },
  { code: 'PILOT-WBS-02', name: 'Structural Works', budgetCrores: 0.25 },
] as const;

export type PilotActivity = {
  code: string;
  name: string;
  wbsCode: string;
  workActivityCode: string;
  plannedQty: number;
  unit: string;
  supervisorUsername: string;
};

export const PILOT_ACTIVITIES: ReadonlyArray<PilotActivity> = [
  { code: 'PILOT-ACT-01', name: 'Foundation Excavation', wbsCode: 'PILOT-WBS-01', workActivityCode: 'PILOT-EXC', plannedQty: 500, unit: 'm3', supervisorUsername: 'pilot.sup1' },
  { code: 'PILOT-ACT-02', name: 'Footing PCC',            wbsCode: 'PILOT-WBS-01', workActivityCode: 'PILOT-PCC', plannedQty: 100, unit: 'm3', supervisorUsername: 'pilot.sup2' },
  { code: 'PILOT-ACT-03', name: 'Column Rebar',           wbsCode: 'PILOT-WBS-02', workActivityCode: 'PILOT-REB', plannedQty: 8000, unit: 'kg', supervisorUsername: 'pilot.sup3' },
  { code: 'PILOT-ACT-04', name: 'Column Concreting',      wbsCode: 'PILOT-WBS-02', workActivityCode: 'PILOT-CON', plannedQty: 80, unit: 'm3', supervisorUsername: 'pilot.sup4' },
] as const;

/** Five-day DPR scenario relative to a target Monday; Track B uses these as multipliers on the daily planned output. */
export const DPR_DAY_FACTORS = [0.8, 1.05, 0.5, 1.0, 1.2] as const;

/** ISO date strings for the five-day DPR window. Pick a window inside PILOT_PROJECT.startDate..finishDate. */
export const DPR_WINDOW = {
  monday: '2026-04-27',
  tuesday: '2026-04-28',
  wednesday: '2026-04-29',
  thursday: '2026-04-30',
  friday: '2026-05-01',
} as const;

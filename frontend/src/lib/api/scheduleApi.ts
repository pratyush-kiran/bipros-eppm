export interface ScheduleResultResponse {
  id: string;
  projectId: string;
  dataDate: string;
  projectStartDate: string;
  projectFinishDate: string;
  criticalPathLength: number | null;
  totalActivities: number;
  criticalActivities: number;
  schedulingOption: string;
  calculatedAt: string;
  durationSeconds: number | null;
  status: string;
  warnings: string[];
  /**
   * Phase 1.6 status breakdown — null on cached/historical results that pre-date the
   * scheduler change. UI should render "—" rather than 0 in that case.
   */
  notStartedActivities: number | null;
  inProgressActivities: number | null;
  completedActivities: number | null;
}

-- ClickHouse bootstrap: create database if not exists (the docker env CLICKHOUSE_DB already does this,
-- but this script is idempotent and can be rerun manually).
CREATE DATABASE IF NOT EXISTS bipros_analytics;

-- Dimension tables (small, refreshed nightly)
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_project (
    project_id UUID,
    code String,
    name String,
    status LowCardinality(String),
    portfolio_id Nullable(UUID),
    org_id Nullable(UUID),
    start_date Date,
    finish_date Date,
    currency LowCardinality(String),
    obs_node_id Nullable(UUID),
    updated_at DateTime,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY project_id;

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_wbs (
    wbs_id UUID,
    project_id UUID,
    parent_wbs_id Nullable(UUID),
    code String,
    name String,
    level UInt8,
    weight Float64,
    path String,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, wbs_id);

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_activity (
    activity_id UUID,
    project_id UUID,
    wbs_id UUID,
    code String,
    name String,
    activity_type LowCardinality(String),
    edit_status LowCardinality(String) DEFAULT 'LOCKED',
    uom LowCardinality(String),
    bq_quantity Float64,
    planned_start Nullable(Date),
    planned_finish Nullable(Date),
    chainage_from_m Nullable(Float64),
    chainage_to_m Nullable(Float64),
    is_critical UInt8,
    -- Cached supervisor of this activity. Soft FK to OLTP resource.resources.id.
    -- Populated by DimensionSyncJob from Activity.responsibleResourceId.
    responsible_resource_id Nullable(UUID),
    responsible_resource_name String DEFAULT '',
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, activity_id);

-- Idempotent for existing deployments: bring dim_activity up to schema with supervisor cols.
ALTER TABLE bipros_analytics.dim_activity
    ADD COLUMN IF NOT EXISTS responsible_resource_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS responsible_resource_name String DEFAULT '';

ALTER TABLE bipros_analytics.dim_activity
    ADD COLUMN IF NOT EXISTS edit_status LowCardinality(String) DEFAULT 'LOCKED';

-- planned_start/planned_finish are optional: unscheduled activities (e.g. freshly
-- created BOQ items) have no dates. The columns were originally non-nullable Date, so
-- the ETL upsert failed with "Cannot set null to non-nullable column". Convert them to
-- Nullable(Date) — idempotent: a no-op once the column is already nullable.
ALTER TABLE bipros_analytics.dim_activity
    MODIFY COLUMN planned_start Nullable(Date),
    MODIFY COLUMN planned_finish Nullable(Date);

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_resource (
    resource_id UUID,
    project_id Nullable(UUID),
    resource_type LowCardinality(String),
    -- role_code/role_name denormalised from operational Resource.role for AI supervisor queries
    role_code String DEFAULT '',
    role_name String DEFAULT '',
    code String,
    name String,
    uom LowCardinality(String),
    unit_rate Decimal(18,4),
    is_subcontractor UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (resource_type, resource_id);

-- Idempotent for existing deployments: bring dim_resource up to schema with role cols.
ALTER TABLE bipros_analytics.dim_resource
    ADD COLUMN IF NOT EXISTS role_code String DEFAULT '',
    ADD COLUMN IF NOT EXISTS role_name String DEFAULT '';

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_cost_account (
    cost_account_id UUID,
    project_id UUID,
    code String,
    name String,
    parent_id Nullable(UUID),
    category LowCardinality(String),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, cost_account_id);

-- Baseline snapshots — one row per Baseline. is_active doubles as a soft-delete tombstone:
-- a BaselineDeactivatedEvent emits an is_active=0 row with a strictly newer _version, so
-- ReplacingMergeTree converges to the deactivated state on merge.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_baseline (
    baseline_id UUID,
    project_id UUID,
    name String,
    description String DEFAULT '',
    baseline_type LowCardinality(String),
    baseline_date Date,
    is_active UInt8,
    total_activities Nullable(Int32),
    total_cost Nullable(Decimal(18,4)),
    project_duration Nullable(Float64),
    project_start_date Nullable(Date),
    project_finish_date Nullable(Date),
    updated_at DateTime,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, baseline_id);

-- Schedule run history — one row per ScheduleResult emitted by the CPM scheduler.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_schedule_run (
    schedule_run_id UUID,
    project_id UUID,
    data_date Date,
    project_start_date Nullable(Date),
    project_finish_date Nullable(Date),
    critical_path_length Nullable(Float64),
    total_activities Int32 DEFAULT 0,
    critical_activities Int32 DEFAULT 0,
    scheduling_option LowCardinality(String),
    status LowCardinality(String),
    duration_seconds Nullable(Float64),
    calculated_at DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, schedule_run_id);

-- Variation Orders denormalised as the "contract change" dim. One row per approved VO.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_contract (
    vo_id UUID,
    contract_id UUID,
    project_id UUID,
    vo_number String,
    description String DEFAULT '',
    vo_value Nullable(Decimal(18,4)),
    impact_on_budget Nullable(Decimal(18,4)),
    impact_on_schedule_days Nullable(Int32),
    status LowCardinality(String),
    approved_by String DEFAULT '',
    approved_at Nullable(DateTime),
    updated_at DateTime,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, vo_id);

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_calendar (
    date Date,
    year UInt16,
    quarter UInt8,
    month UInt8,
    week UInt8,
    iso_week UInt8,
    day_of_week UInt8,
    is_business_day UInt8,
    fiscal_period UInt8
) ENGINE = MergeTree
  ORDER BY date;

-- Calendar seed: backfill 10 years from 2020-01-01 to 2029-12-31
-- ClickHouse does not support INSERT ... SELECT from generate_series directly in the same way as Postgres.
-- We rely on the Java application to seed the calendar table on first boot, or a one-time insert via VALUES.
-- For now, the ETL job will handle calendar seeding.

-- ========================================================================
-- Fact tables
-- ========================================================================

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_activity_progress_daily (
    project_id UUID,
    activity_id UUID,
    date Date,
    pct_complete_physical Float32,
    pct_complete_duration Float32,
    qty_executed Float64,
    cumulative_qty Float64,
    chainage_from_m Nullable(Float64),
    chainage_to_m Nullable(Float64),
    source LowCardinality(String),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(date)
  ORDER BY (project_id, activity_id, date)
  TTL date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_resource_usage_daily (
    project_id UUID,
    activity_id UUID,
    resource_id UUID,
    resource_type LowCardinality(String),
    date Date,
    hours_worked Float32,
    days_worked Float32,
    qty_executed Float64,
    productivity_actual Float32,
    productivity_norm Float32,
    cost Decimal(18,4),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(date)
  ORDER BY (project_id, activity_id, resource_id, date)
  TTL date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_cost_daily (
    project_id UUID,
    wbs_id UUID,
    activity_id UUID,
    date Date,
    cost_account_id UUID,
    labor_cost Decimal(18,4),
    material_cost Decimal(18,4),
    equipment_cost Decimal(18,4),
    expense_cost Decimal(18,4),
    total_actual Decimal(18,4),
    total_planned Decimal(18,4),
    total_earned Decimal(18,4),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(date)
  ORDER BY (project_id, wbs_id, activity_id, cost_account_id, date)
  TTL date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_evm_daily (
    project_id UUID,
    wbs_id Nullable(UUID),
    activity_id Nullable(UUID),
    date Date,
    bac Decimal(18,4),
    pv Decimal(18,4),
    ev Decimal(18,4),
    ac Decimal(18,4),
    cv Decimal(18,4),
    sv Decimal(18,4),
    cpi Float64,
    spi Float64,
    tcpi Float64,
    eac Decimal(18,4),
    etc_cost Decimal(18,4),
    vac Decimal(18,4),
    period_source LowCardinality(String),
    interpolation LowCardinality(String),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(date)
  ORDER BY (project_id,
            coalesce(wbs_id, toUUID('00000000-0000-0000-0000-000000000000')),
            coalesce(activity_id, toUUID('00000000-0000-0000-0000-000000000000')),
            date)
  TTL date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_dpr_logs (
    project_id UUID,
    activity_id UUID,
    dpr_id UUID,
    report_date Date,
    -- Holds the supervisor's RESOURCE id (FK back to OLTP resources.id). Column
    -- name predates the resource/user split. Join to resource.crews on
    -- crew_lead_resource_id for crew-level rollups.
    supervisor_user_id UUID,
    supervisor_name String,
    chainage_from_m Nullable(Float64),
    chainage_to_m Nullable(Float64),
    qty_executed Float64,
    cumulative_qty Float64,
    weather LowCardinality(String),
    temperature_c Nullable(Float32),
    remarks_text String,
    remarks_embedding Array(Float32),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(report_date)
  ORDER BY (project_id, activity_id, report_date, dpr_id)
  TTL report_date + INTERVAL 7 YEAR;

-- DPR resource line items: manpower, equipment, material. One row per DPR child row,
-- ingested by AnalyticsEtlService.insertDpr{Manpower,Equipment,Material}Daily.
CREATE TABLE IF NOT EXISTS bipros_analytics.fact_dpr_manpower_daily (
    project_id UUID,
    activity_id UUID,
    dpr_id UUID,
    manpower_row_id UUID,
    report_date Date,
    trade String,
    category String,
    contractor_name String,
    nos UInt16,
    working_hours Float32,
    ot_hours Float32,
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(report_date)
  ORDER BY (project_id, dpr_id, manpower_row_id)
  TTL report_date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_dpr_equipment_daily (
    project_id UUID,
    activity_id UUID,
    dpr_id UUID,
    equipment_row_id UUID,
    report_date Date,
    equipment_type String,
    fleet_no String,
    ownership String,
    nos UInt16,
    working_hours Float32,
    idle_hours Float32,
    breakdown_hours Float32,
    fuel_litres Float32,
    operator_name String,
    availability_status String,
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(report_date)
  ORDER BY (project_id, dpr_id, equipment_row_id)
  TTL report_date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_dpr_material_daily (
    project_id UUID,
    activity_id UUID,
    dpr_id UUID,
    material_row_id UUID,
    report_date Date,
    material_name String,
    unit String,
    quantity Float64,
    source String,
    vendor_name String,
    batch_no String,
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(report_date)
  ORDER BY (project_id, dpr_id, material_row_id)
  TTL report_date + INTERVAL 7 YEAR;

-- Field-issue log entries attached to a DPR row. Sort key (project_id, dpr_id, issue_id)
-- matches the OLTP layout in project.dpr_issues and lets the AI tool answer "issues per
-- activity / supervisor / category" with simple GROUP BYs. _version is currentTimeMillis()
-- so a PATCH that flips status always produces a strictly newer row that wins on FINAL.
CREATE TABLE IF NOT EXISTS bipros_analytics.fact_dpr_issues_daily (
    project_id UUID,
    dpr_id UUID,
    issue_id UUID,
    activity_id Nullable(UUID),
    activity_name String,
    supervisor_resource_id Nullable(UUID),
    supervisor_name String,
    assigned_to_resource_id Nullable(UUID),
    assigned_to_name String,
    report_date Date,
    opened_at DateTime64(3),
    resolved_at Nullable(DateTime64(3)),
    resolution_age_hours Nullable(Float32),
    category LowCardinality(String),
    severity LowCardinality(String),
    status LowCardinality(String),
    title String,
    description String,
    chainage_from_m Nullable(Float64),
    chainage_to_m Nullable(Float64),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(report_date)
  ORDER BY (project_id, dpr_id, issue_id)
  TTL report_date + INTERVAL 7 YEAR;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_risk_snapshot_daily (
    project_id UUID,
    risk_id UUID,
    date Date,
    probability Float32,
    impact_cost Decimal(18,4),
    impact_days Int32,
    rag LowCardinality(String),
    status LowCardinality(String),
    monte_carlo_p50 Decimal(18,4),
    monte_carlo_p80 Decimal(18,4),
    monte_carlo_p95 Decimal(18,4),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(date)
  ORDER BY (project_id, risk_id, date)
  TTL date + INTERVAL 5 YEAR;

-- Phase: extend risk snapshot with full P6 fields. Idempotent ALTERs.
ALTER TABLE bipros_analytics.fact_risk_snapshot_daily
    ADD COLUMN IF NOT EXISTS risk_score Nullable(Float64),
    ADD COLUMN IF NOT EXISTS residual_risk_score Nullable(Float64),
    ADD COLUMN IF NOT EXISTS risk_type LowCardinality(String) DEFAULT 'THREAT',
    ADD COLUMN IF NOT EXISTS owner_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS category_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS post_response_probability Nullable(Float32),
    ADD COLUMN IF NOT EXISTS post_response_impact_cost Nullable(Int32),
    ADD COLUMN IF NOT EXISTS post_response_impact_schedule Nullable(Int32),
    ADD COLUMN IF NOT EXISTS pre_response_exposure_cost Nullable(Decimal(18,4)),
    ADD COLUMN IF NOT EXISTS post_response_exposure_cost Nullable(Decimal(18,4)),
    ADD COLUMN IF NOT EXISTS exposure_start_date Nullable(Date),
    ADD COLUMN IF NOT EXISTS exposure_finish_date Nullable(Date),
    ADD COLUMN IF NOT EXISTS response_type LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS trend LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS identified_date Nullable(Date),
    ADD COLUMN IF NOT EXISTS identified_by_id Nullable(UUID);

-- ========================================================================
-- Risk dimension
-- ========================================================================

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_risk (
    risk_id UUID,
    project_id UUID,
    code String,
    title String,
    risk_type LowCardinality(String),
    category_id Nullable(UUID),
    category_name String,
    owner_id Nullable(UUID),
    owner_name String,
    status LowCardinality(String),
    rag LowCardinality(String),
    trend LowCardinality(String),
    response_type LowCardinality(String),
    identified_date Nullable(Date),
    identified_by_id Nullable(UUID),
    closed_date Nullable(Date),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, risk_id);

-- ========================================================================
-- Permit dimensions and lifecycle fact
-- ========================================================================

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_permit_type (
    permit_type_template_id UUID,
    code String,
    name String,
    color_hex String,
    icon_key String,
    max_duration_hours Int32,
    requires_gas_test UInt8,
    requires_isolation UInt8,
    jsa_required UInt8,
    blasting_required UInt8,
    diving_required UInt8,
    default_risk_level LowCardinality(String),
    night_work_policy LowCardinality(String),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY permit_type_template_id;

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_permit (
    permit_id UUID,
    project_id UUID,
    permit_code String,
    permit_type_template_id UUID,
    parent_permit_id Nullable(UUID),
    status LowCardinality(String),
    risk_level LowCardinality(String),
    shift LowCardinality(String),
    contractor_org_id Nullable(UUID),
    location_zone String,
    chainage_marker String,
    supervisor_name String,
    start_at DateTime64(3),
    end_at DateTime64(3),
    valid_from Nullable(DateTime64(3)),
    valid_to Nullable(DateTime64(3)),
    declaration_accepted_at Nullable(DateTime64(3)),
    closed_at Nullable(DateTime64(3)),
    closed_by Nullable(UUID),
    revoked_at Nullable(DateTime64(3)),
    revoked_by Nullable(UUID),
    expired_at Nullable(DateTime64(3)),
    suspended_at Nullable(DateTime64(3)),
    total_approvals_required Int32,
    approvals_completed Int32,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, permit_id);

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_permit_lifecycle (
    project_id UUID,
    permit_id UUID,
    permit_type_template_id UUID,
    event_type LowCardinality(String),
    occurred_at DateTime64(3),
    occurred_date Date MATERIALIZED toDate(occurred_at),
    actor_user_id Nullable(UUID),
    risk_level LowCardinality(String),
    permit_status LowCardinality(String),
    payload_json String,
    duration_hours_to_event Nullable(Float32),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(occurred_at)
  ORDER BY (project_id, permit_id, occurred_at, event_type)
  TTL toDate(occurred_at) + INTERVAL 7 YEAR;

-- ========================================================================
-- Labour dimension and daily fact
-- ========================================================================

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_labour_designation (
    designation_id UUID,
    code String,
    designation String,
    category LowCardinality(String),
    trade String,
    grade LowCardinality(String),
    nationality LowCardinality(String),
    experience_years_min Int16,
    default_daily_rate Decimal(18,4),
    skills Array(String),
    certifications Array(String),
    status LowCardinality(String),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY designation_id;

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_labour_daily (
    project_id UUID,
    labour_return_id Nullable(UUID),
    deployment_id Nullable(UUID),
    designation_id Nullable(UUID),
    skill_category LowCardinality(String),
    contractor_name String,
    contractor_org_id Nullable(UUID),
    wbs_id Nullable(UUID),
    site_location String,
    date Date,
    head_count Int32,
    man_days Float32,
    planned_head_count Nullable(Int32),
    daily_rate Nullable(Decimal(18,4)),
    daily_cost Nullable(Decimal(18,4)),
    source LowCardinality(String),
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(date)
  ORDER BY (project_id, date, contractor_name, skill_category,
            coalesce(designation_id, toUUID('00000000-0000-0000-0000-000000000000')))
  TTL date + INTERVAL 7 YEAR;

-- ========================================================================
-- Materialized Views
-- ========================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS bipros_analytics.mv_project_kpi_daily
ENGINE = SummingMergeTree
PARTITION BY toYYYYMM(date) ORDER BY (project_id, date)
AS SELECT project_id, date,
       sum(total_actual) AS ac, sum(total_planned) AS pv, sum(total_earned) AS ev,
       count() AS rows
FROM bipros_analytics.fact_cost_daily GROUP BY project_id, date;

CREATE MATERIALIZED VIEW IF NOT EXISTS bipros_analytics.mv_portfolio_scurve_weekly
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(week_start)
ORDER BY (portfolio_id, week_start)
AS SELECT assumeNotNull(p.portfolio_id) AS portfolio_id, toMonday(e.date) AS week_start,
          sumState(e.pv) AS pv_state, sumState(e.ev) AS ev_state, sumState(e.ac) AS ac_state
FROM bipros_analytics.fact_evm_daily e INNER JOIN bipros_analytics.dim_project p ON e.project_id = p.project_id
WHERE p.portfolio_id IS NOT NULL
GROUP BY portfolio_id, week_start;

CREATE MATERIALIZED VIEW IF NOT EXISTS bipros_analytics.mv_activity_weekly
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(week_start) ORDER BY (project_id, activity_id, week_start)
AS SELECT project_id, activity_id, toMonday(date) AS week_start,
          maxState(pct_complete_physical) AS pct_state,
          sumState(qty_executed) AS qty_state
FROM bipros_analytics.fact_activity_progress_daily GROUP BY project_id, activity_id, week_start;

-- ========================================================================
-- Role-owned rate book dimensions (Phase 2 — 2026-05-14)
-- Mirrors OLTP entities introduced in the 2026-05-13 role rate book rollout.
-- ResourceRole + per-variant rate rows + per-project overrides + work activity
-- master + productivity norms + users. Populated by event listeners and the
-- nightly DimensionSyncJob; backfill is idempotent via _version.
-- ========================================================================

CREATE TABLE IF NOT EXISTS bipros_analytics.dim_resource_role (
    role_id UUID,
    code String,
    name String,
    description String DEFAULT '',
    resource_type LowCardinality(String),     -- MANPOWER | EQUIPMENT | MATERIAL
    sort_order Int32 DEFAULT 0,
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY role_id;

-- One row per (role, category, grade). Manpower rate book.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_manpower_role_rate (
    manpower_role_rate_id UUID,
    role_id UUID,
    role_code String,
    role_name String,
    category_id UUID,
    category_name String,
    grade_id UUID,
    grade_name String,
    unit LowCardinality(String),
    rate Decimal(19,4),
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (role_id, manpower_role_rate_id);

-- One row per (role, make, model). Equipment variant rate book.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_equipment_role_variant (
    equipment_role_variant_id UUID,
    role_id UUID,
    role_code String,
    role_name String,
    make String,
    model String,
    unit LowCardinality(String),
    rate Decimal(19,4),
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (role_id, equipment_role_variant_id);

-- One row per (role, spec_grade). Material variant rate book.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_material_role_variant (
    material_role_variant_id UUID,
    role_id UUID,
    role_code String,
    role_name String,
    spec_grade String,
    unit LowCardinality(String),
    rate Decimal(19,4),
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (role_id, material_role_variant_id);

-- Per-project rate overrides for ANY of the three variant families. One unified
-- table keyed by variant_type so AI queries don't have to UNION three tables for
-- "is there any override on this project". variant_id points to the matching
-- dim_*_role_*'s PK.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_project_rate_override (
    override_id UUID,
    project_id UUID,
    variant_type LowCardinality(String),       -- MANPOWER | EQUIPMENT | MATERIAL
    variant_id UUID,                            -- FK to dim_manpower_role_rate / equipment_role_variant / material_role_variant
    role_id UUID,                               -- denormalised for filter speed
    role_code String,
    override_rate Decimal(19,4),
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (project_id, variant_type, variant_id);

-- WorkActivity master library (Blinding, Excavation, …). Shared across projects.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_work_activity (
    work_activity_id UUID,
    code String,
    name String,
    default_unit LowCardinality(String),
    discipline LowCardinality(String),
    sort_order Int32 DEFAULT 0,
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY work_activity_id;

-- Productivity norms keyed on (work_activity, role, variant qualifier). scope
-- carries the resolver tier so the AI can quote "VARIANT-level" vs "ROLE-level"
-- vs "UNSCOPED" when explaining an expected output.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_productivity_norm (
    productivity_norm_id UUID,
    work_activity_id UUID,
    work_activity_code String,
    work_activity_name String,
    norm_type LowCardinality(String),          -- MANPOWER | EQUIPMENT | MATERIAL
    scope LowCardinality(String),               -- VARIANT | ROLE | UNSCOPED
    role_id Nullable(UUID),
    role_code String DEFAULT '',
    category_id Nullable(UUID),                 -- manpower variant only
    category_name String DEFAULT '',
    grade_id Nullable(UUID),                    -- manpower variant only
    grade_name String DEFAULT '',
    make String DEFAULT '',                     -- equipment variant only
    model String DEFAULT '',                    -- equipment variant only
    unit LowCardinality(String),
    output_per_man_per_day Nullable(Decimal(19,4)),
    crew_size Nullable(Int32),
    output_per_day Nullable(Decimal(19,4)),
    output_per_hour Nullable(Decimal(19,4)),
    active UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY (work_activity_id, productivity_norm_id);

-- User dimension (auth.users). Source of truth for supervisor identity.
CREATE TABLE IF NOT EXISTS bipros_analytics.dim_user (
    user_id UUID,
    username String,
    first_name String,
    last_name String,
    display_name String,                        -- "<first_name> <last_name>" precomputed
    designation String DEFAULT '',
    organisation_id Nullable(UUID),
    enabled UInt8,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  ORDER BY user_id;

-- ========================================================================
-- ALTERs to existing dim_activity + fact_dpr_* tables (Phase 2 — 2026-05-14).
-- Idempotent — ClickHouse ADD COLUMN IF NOT EXISTS no-ops if the column exists.
-- ========================================================================

ALTER TABLE bipros_analytics.dim_activity
    ADD COLUMN IF NOT EXISTS supervisor_user_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS supervisor_user_name String DEFAULT '',
    ADD COLUMN IF NOT EXISTS work_activity_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS work_activity_code String DEFAULT '';

-- fact_dpr_logs already has a supervisor_user_id column whose target diverges
-- across the 2026-05-13 cutover (legacy rows hold Resource.id; new rows hold
-- User.id). We add an explicit supervisor_user_name and a legacy fallback
-- column so the AI can disambiguate post-backfill.
ALTER TABLE bipros_analytics.fact_dpr_logs
    ADD COLUMN IF NOT EXISTS legacy_supervisor_resource_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS supervisor_user_name_v2 String DEFAULT '';

-- DPR resource line items: add role + variant + effective_rate + line_cost +
-- supervisor_user_id. Equipment also gains make/model; material gains spec_grade.
ALTER TABLE bipros_analytics.fact_dpr_manpower_daily
    ADD COLUMN IF NOT EXISTS role_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS role_code String DEFAULT '',
    ADD COLUMN IF NOT EXISTS role_name String DEFAULT '',
    ADD COLUMN IF NOT EXISTS manpower_role_rate_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS category_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS grade_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS unit LowCardinality(String) DEFAULT 'Day',
    ADD COLUMN IF NOT EXISTS unit_rate Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS effective_rate Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS line_cost Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS supervisor_user_id Nullable(UUID);

ALTER TABLE bipros_analytics.fact_dpr_equipment_daily
    ADD COLUMN IF NOT EXISTS role_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS role_code String DEFAULT '',
    ADD COLUMN IF NOT EXISTS role_name String DEFAULT '',
    ADD COLUMN IF NOT EXISTS equipment_role_variant_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS make String DEFAULT '',
    ADD COLUMN IF NOT EXISTS model String DEFAULT '',
    ADD COLUMN IF NOT EXISTS unit LowCardinality(String) DEFAULT 'Day',
    ADD COLUMN IF NOT EXISTS unit_rate Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS effective_rate Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS line_cost Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS supervisor_user_id Nullable(UUID);

ALTER TABLE bipros_analytics.fact_dpr_material_daily
    ADD COLUMN IF NOT EXISTS role_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS role_code String DEFAULT '',
    ADD COLUMN IF NOT EXISTS role_name String DEFAULT '',
    ADD COLUMN IF NOT EXISTS material_role_variant_id Nullable(UUID),
    ADD COLUMN IF NOT EXISTS spec_grade String DEFAULT '',
    ADD COLUMN IF NOT EXISTS unit_rate Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS effective_rate Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS line_cost Nullable(Decimal(19,4)),
    ADD COLUMN IF NOT EXISTS supervisor_user_id Nullable(UUID);

-- ========================================================================
-- Activity cost daily fact (Phase 2 — 2026-05-14)
-- Pre-aggregated at (project, activity, date, role) grain. The engine behind
-- "total cost of activity X" and "spend on day D for activity X" questions.
-- Sourced from resource_assignments (planned) + dpr_manpower/equipment/material
-- (actual) by ResourceAssignmentChangedListener / DprSubmittedListener.
-- ReplacingMergeTree on _version — listeners can re-emit safely.
-- ========================================================================

CREATE TABLE IF NOT EXISTS bipros_analytics.fact_activity_cost_daily (
    project_id UUID,
    activity_id UUID,
    activity_code String,
    report_date Date,
    role_id Nullable(UUID),
    role_code String DEFAULT '',
    resource_type LowCardinality(String),       -- MANPOWER | EQUIPMENT | MATERIAL | ALL
    planned_units Decimal(19,4) DEFAULT 0,
    actual_units Decimal(19,4) DEFAULT 0,
    remaining_units Decimal(19,4) DEFAULT 0,
    planned_cost Decimal(19,4) DEFAULT 0,
    actual_cost Decimal(19,4) DEFAULT 0,
    remaining_cost Decimal(19,4) DEFAULT 0,
    supervisor_user_id Nullable(UUID),
    supervisor_user_name String DEFAULT '',
    event_ts DateTime64(3),
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
  PARTITION BY toYYYYMM(report_date)
  ORDER BY (project_id, activity_id, report_date,
            coalesce(role_id, toUUID('00000000-0000-0000-0000-000000000000')),
            resource_type)
  TTL report_date + INTERVAL 7 YEAR;

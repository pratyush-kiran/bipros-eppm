package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "projects", schema = "project", uniqueConstraints = {
    @UniqueConstraint(columnNames = "code")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Project extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "eps_node_id", nullable = false)
    private UUID epsNodeId;

    @Column(name = "obs_node_id")
    private UUID obsNodeId;

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_finish_date")
    private LocalDate plannedFinishDate;

    @Column(name = "data_date")
    private LocalDate dataDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.PLANNED;

    /** Industry pack key driving HSE/PTW templating (ROAD, OIL_GAS, BUILDING, ...). */
    @Column(name = "industry_code", length = 30)
    private String industryCode;

    @Column(name = "must_finish_by_date")
    private LocalDate mustFinishByDate;

    @Column(nullable = false)
    private Integer priority = 50;

    // ── Master Data Screen 01 fields (PMS MasterData UI Screens Final) ────────────

    @Column(name = "category", length = 30)
    private String category;

    /** MoRTH/NHAI category code linked to {@link #category}. Free text to allow sub-codes. */
    @Column(name = "morth_code", length = 20)
    private String morthCode;

    /** Start chainage in metres (e.g. 145 km +000 = 145_000). */
    @Column(name = "from_chainage_m")
    private Long fromChainageM;

    /** End chainage in metres. */
    @Column(name = "to_chainage_m")
    private Long toChainageM;

    /** Free-text place name for the start chainage. */
    @Column(name = "from_location", length = 120)
    private String fromLocation;

    /** Free-text place name for the end chainage. */
    @Column(name = "to_location", length = 120)
    private String toLocation;

    /**
     * Total corridor length in km. Persisted (rather than always-derived) so exports and
     * dashboards don't have to recompute from chainage metres. Kept in sync by the service
     * whenever {@link #fromChainageM} / {@link #toChainageM} change.
     */
    @Column(name = "total_length_km", precision = 10, scale = 3)
    private BigDecimal totalLengthKm;

    /**
     * Soft pointer to the project's currently-active baseline (P6's "Project Baseline").
     * Variance reports default to this when no baselineId is supplied. Soft FK — it lives in
     * the {@code baseline} schema, so we deliberately don't declare a JPA relationship to
     * avoid coupling the {@code project} module to {@code bipros-baseline}.
     *
     * <p>Phase 3 of the baseline-progress roadmap: this column is now a <strong>read-only
     * mirror of {@link #primaryBaselineId}</strong>. It stays for one release so AI tools,
     * EVM, and event listeners that read {@code activeBaselineId} keep working. New code
     * should read {@link #primaryBaselineId} directly. Both columns are kept in lockstep by
     * {@code BaselineService.assignBaselineToSlot} when the PRIMARY slot changes.
     */
    @Column(name = "active_baseline_id")
    private UUID activeBaselineId;

    /**
     * Phase 3: P6-style three-slot baseline assignment. The Primary slot is what variance,
     * Gantt overlay, and EVM all use by default. Secondary / Tertiary are additional
     * comparison points the planner can switch to without losing the Primary.
     *
     * <p>Soft FK — same reasoning as {@link #activeBaselineId}.
     */
    @Column(name = "primary_baseline_id")
    private UUID primaryBaselineId;

    /** Secondary slot — see {@link #primaryBaselineId}. */
    @Column(name = "secondary_baseline_id")
    private UUID secondaryBaselineId;

    /** Tertiary slot — see {@link #primaryBaselineId}. */
    @Column(name = "tertiary_baseline_id")
    private UUID tertiaryBaselineId;

    /**
     * Set to true by listeners (e.g. VariationOrderApprovedListener) when something material
     * changed that should be reflected in a fresh baseline. Cleared when a new baseline is
     * taken via BaselineService. UI surfaces a "needs re-baseline" banner on the Baselines tab.
     */
    @Column(name = "requires_rebaseline", nullable = false)
    private boolean requiresRebaseline = false;

    /**
     * Optional project owner — soft FK to {@code public.users.id}. Used by ABAC ownership
     * checks (e.g. CLIENT-role users see projects they own without needing OBS assignment).
     * Nullable: legacy projects created before owner tracking will leave this empty.
     */
    @Column(name = "owner_id")
    private UUID ownerId;

    /**
     * Default calendar for this project. Activities created under this project inherit this
     * calendar when no explicit override is supplied (P6-style project-calendar cascade).
     */
    @Column(name = "calendar_id")
    private UUID calendarId;

    /**
     * Soft-delete timestamp. Non-null means the project is archived: hidden from default
     * lists ({@code GET /v1/projects}) but visible at {@code GET /v1/projects/archived} and
     * restorable via {@code POST /v1/projects/{id}/restore}. Hard delete is not exposed by
     * the API — archive is the only deletion verb users can invoke.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Soft FK to {@code public.users.id} — who archived the project. */
    @Column(name = "archived_by")
    private UUID archivedBy;

    // ── P6-style budget fields ────────────────────────────────────────────────

    /** Immutable approved budget. Set once via {@code ProjectBudgetService.setInitialBudget()}. */
    @Column(name = "original_budget", precision = 19, scale = 2)
    private BigDecimal originalBudget;

    /** Current budget = original + approved additions − approved reductions. Recomputed on every approved change. */
    @Column(name = "current_budget", precision = 19, scale = 2)
    private BigDecimal currentBudget;

    /** ISO 4217 currency code for all budget values. Defaults to INR. */
    @Column(name = "budget_currency", length = 3)
    private String budgetCurrency = "INR";

    /** Timestamp of the last approved budget change. */
    @Column(name = "budget_updated_at")
    private Instant budgetUpdatedAt;

    /**
     * Per-project approval threshold for DBS manual expenses. Expenses with absolute
     * amount &gt;= this value land as PENDING and need a project-update permission holder
     * to approve before they roll up into DBS / Activity AC. {@code null} = fall back to
     * the global default from {@code bipros.dbs.expense.approval-threshold} (currently
     * ₹10,000).
     */
    @Column(name = "expense_approval_threshold", precision = 14, scale = 2)
    private BigDecimal expenseApprovalThreshold;
}

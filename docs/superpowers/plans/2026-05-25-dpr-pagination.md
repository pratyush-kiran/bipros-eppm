# DPR Tab Pagination — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the DPR tab load fast by paginating reports day-by-day (infinite scroll), returning a slim aggregate payload for the list, and fetching full child detail only when a row is expanded.

**Architecture:** Backend adds a day-cursor paginated list method returning a slim `DprSummaryResponse` (parent fields + precomputed child aggregates, no child-row hydration, no sub-contractor native query). The existing `GET /dpr/{id}` (full detail) becomes the lazy-detail source on expand. Frontend switches the list to `useInfiniteQuery`, threads slim rows through the day/activity/work-front components, fetches detail lazily on expand, memoizes the grouping, and keeps scroll smooth with CSS `content-visibility` + an IntersectionObserver sentinel.

**Tech Stack:** Spring Boot 3.5 / Java 23 (JPA, JPQL, Mockito + JUnit 5), Next.js 16 / React 19, TanStack Query v5, vitest.

**Spec:** `docs/superpowers/specs/2026-05-25-dpr-pagination-design.md`

> **Rendering note (deviation from spec):** The spec named `@tanstack/react-virtual`. During planning we found the DPR layout uses **sticky day headers in normal document flow**, which react-virtual's absolutely-positioned virtual items fight. We instead use CSS `content-visibility: auto` per day section — the browser skips layout/paint for off-screen sections, preserving sticky headers and the nested grouping with near-zero complexity. Same "rendered smoothly" outcome, lower risk. Flagged to the user at handoff.

---

## File structure

**Backend (`backend/bipros-project`):**
- Create: `src/main/java/com/bipros/project/application/dto/DprSummaryResponse.java` — slim list row (record).
- Create: `src/main/java/com/bipros/project/application/dto/DprPage.java` — `{ items, nextCursor, hasMore }` envelope (record).
- Modify: `src/main/java/com/bipros/project/domain/repository/DailyProgressReportRepository.java` — distinct-dates-desc query + rows-for-dates query.
- Modify: `src/main/java/com/bipros/project/domain/repository/Dpr{Manpower,Equipment,Material,Attachment,Issue}Repository.java` — aggregate queries.
- Modify: `src/main/java/com/bipros/project/application/service/DailyProgressReportService.java` — `listPaged(...)` + slim builder.
- Modify: `src/main/java/com/bipros/project/api/DailyProgressReportController.java` — list endpoint params + return type.
- Create: `src/test/java/com/bipros/project/application/service/DailyProgressReportServicePaginationTest.java`.

**Frontend (`frontend`):**
- Modify: `src/lib/types/dpr.ts` — `DprSummaryRow`, `DprPage`.
- Modify: `src/lib/api/dprApi.ts` — paged `list` + filter params.
- Modify: `src/app/(app)/projects/[projectId]/dpr/page.tsx` — `useInfiniteQuery`, slim rows, async edit, sentinel.
- Modify: `src/components/dpr/DprDayList.tsx` — slim types, memoized grouping, `content-visibility`, `projectId` prop.
- Modify: `src/components/dpr/DprActivityGroup.tsx` — slim types, `React.memo`, `projectId` pass-through.
- Modify: `src/components/dpr/DprWorkFrontRow.tsx` — slim types, aggregate chip reads, lazy detail query, `React.memo`.
- Modify: `src/app/(app)/projects/[projectId]/dbs/components/SupervisorDbsTab.tsx:77` — read `.items`.
- Create: `src/components/dpr/groupByDayThenActivity.test.ts` — vitest unit test for the pure grouping helper.

---

## Task 1: Slim DTOs

**Files:**
- Create: `backend/bipros-project/src/main/java/com/bipros/project/application/dto/DprSummaryResponse.java`
- Create: `backend/bipros-project/src/main/java/com/bipros/project/application/dto/DprPage.java`

- [ ] **Step 1: Create `DprSummaryResponse`**

```java
package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.Side;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Slim DPR list row for the paginated DPR tab. Carries only the parent fields the
 * Day -> Activity -> Work-front grouping and the collapsed row need, plus precomputed
 * child aggregates (counts / sums) so the frontend does not hydrate full child arrays
 * just to render count chips. Full child detail comes from GET /dpr/{id} on expand.
 *
 * <p>cumulativeQty is deliberately absent: it is a project-to-date running figure that
 * cannot be computed correctly from a paginated subset; the detail GET computes it.
 */
public record DprSummaryResponse(
    UUID id,
    UUID projectId,
    LocalDate reportDate,
    UUID supervisorUserId,
    String supervisorName,
    Long chainageFromM,
    Long chainageToM,
    UUID activityId,
    String activityName,
    String boqItemNo,
    String unit,
    BigDecimal qtyExecuted,
    Side side,
    DprApprovalStatus approvalStatus,
    String weatherCondition,
    long manpowerNos,
    long equipmentNos,
    int materialCount,
    int photoCount,
    int issueCount,
    int openIssueCount,
    boolean hasCriticalOpen
) {}
```

- [ ] **Step 2: Create `DprPage`**

```java
package com.bipros.project.application.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One page of the day-cursored DPR list. {@code nextCursor} is the oldest report date in
 * this batch; the next request passes it as {@code before} (exclusive) to fetch older days.
 * Null when {@code hasMore} is false.
 */
public record DprPage(
    List<DprSummaryResponse> items,
    LocalDate nextCursor,
    boolean hasMore
) {}
```

- [ ] **Step 3: Compile**

Run: `cd backend && mvn -q -pl bipros-project -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/bipros-project/src/main/java/com/bipros/project/application/dto/DprSummaryResponse.java \
        backend/bipros-project/src/main/java/com/bipros/project/application/dto/DprPage.java
git commit -m "feat(dpr): add slim DprSummaryResponse + DprPage DTOs"
```

---

## Task 2: Repository queries (distinct dates, rows-for-dates, aggregates)

**Files:**
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DailyProgressReportRepository.java`
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DprManpowerRepository.java`
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DprEquipmentRepository.java`
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DprMaterialRepository.java`
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DprAttachmentRepository.java`
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/domain/repository/DprIssueRepository.java`

- [ ] **Step 1: Add the distinct-dates + rows-for-dates queries to `DailyProgressReportRepository`**

Add these methods inside the interface (after `findByProjectIdAndActivityNameIgnoreCaseOrderByReportDateAsc`). Add `import org.springframework.data.domain.Pageable;` and `import java.util.Collection;` to the imports.

```java
  /**
   * Most-recent distinct report dates for the project within an optional [from,to] window and
   * strictly older than an optional {@code before} cursor, optionally narrowed to one activity.
   * Ordered newest-first; pass a {@code Pageable} of size {@code days+1} to detect "has more".
   */
  @Query("""
      select distinct d.reportDate from DailyProgressReport d
      where d.projectId = :projectId
        and (:from is null or d.reportDate >= :from)
        and (:to is null or d.reportDate <= :to)
        and (:before is null or d.reportDate < :before)
        and (:activity is null or lower(d.activityName) = lower(:activity))
      order by d.reportDate desc
      """)
  List<LocalDate> findDistinctReportDatesDesc(
      @Param("projectId") UUID projectId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("before") LocalDate before,
      @Param("activity") String activity,
      Pageable pageable);

  /** All DPR rows for the given set of report dates, newest-first, optionally one activity. */
  @Query("""
      select d from DailyProgressReport d
      where d.projectId = :projectId
        and d.reportDate in :dates
        and (:activity is null or lower(d.activityName) = lower(:activity))
      order by d.reportDate desc, d.id asc
      """)
  List<DailyProgressReport> findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(
      @Param("projectId") UUID projectId,
      @Param("dates") Collection<LocalDate> dates,
      @Param("activity") String activity);
```

- [ ] **Step 2: Add the sum-nos aggregate to `DprManpowerRepository`**

Open the file, ensure it imports `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.util.Collection`, `java.util.List`, `java.util.UUID`, then add inside the interface:

```java
  /** Per-DPR sum of manpower headcount (nos) for the given dpr ids. Returns [dprId (UUID), total (Long)]. */
  @Query("select m.dprId, coalesce(sum(m.nos), 0) from DprManpower m where m.dprId in :ids group by m.dprId")
  List<Object[]> sumNosByDprIdIn(@Param("ids") Collection<UUID> ids);
```

- [ ] **Step 3: Add the sum-nos aggregate to `DprEquipmentRepository`**

```java
  /** Per-DPR sum of equipment count (nos) for the given dpr ids. Returns [dprId (UUID), total (Long)]. */
  @Query("select e.dprId, coalesce(sum(e.nos), 0) from DprEquipment e where e.dprId in :ids group by e.dprId")
  List<Object[]> sumNosByDprIdIn(@Param("ids") Collection<UUID> ids);
```

- [ ] **Step 4: Add the count aggregate to `DprMaterialRepository`**

```java
  /** Per-DPR material line count. Returns [dprId (UUID), count (Long)]. */
  @Query("select m.dprId, count(m) from DprMaterial m where m.dprId in :ids group by m.dprId")
  List<Object[]> countByDprIdIn(@Param("ids") Collection<UUID> ids);
```

- [ ] **Step 5: Add the count aggregate to `DprAttachmentRepository`**

```java
  /** Per-DPR photo/attachment count. Returns [dprId (UUID), count (Long)]. */
  @Query("select a.dprId, count(a) from DprAttachment a where a.dprId in :ids group by a.dprId")
  List<Object[]> countByDprIdIn(@Param("ids") Collection<UUID> ids);
```

- [ ] **Step 6: Add the issue status/severity projection to `DprIssueRepository`**

```java
  /** Lightweight issue rows (status + severity only) for aggregating live/open/critical counts.
   *  Returns [dprId (UUID), status (IssueStatus), severity (IssueSeverity)]. */
  @Query("select i.dprId, i.status, i.severity from DprIssue i where i.dprId in :ids")
  List<Object[]> findStatusSeverityByDprIdIn(@Param("ids") Collection<UUID> ids);
```

- [ ] **Step 7: Compile**

Run: `cd backend && mvn -q -pl bipros-project -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/bipros-project/src/main/java/com/bipros/project/domain/repository/
git commit -m "feat(dpr): repo queries for day-cursor pagination + child aggregates"
```

---

## Task 3: Service `listPaged()` — write the failing test first

**Files:**
- Test: `backend/bipros-project/src/test/java/com/bipros/project/application/service/DailyProgressReportServicePaginationTest.java`
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/application/service/DailyProgressReportService.java`

- [ ] **Step 1: Write the failing test**

Create the test file. It mirrors the Mockito harness in `DailyProgressReportServiceChildrenTest` (constructor takes `null` for the EntityManager — `listPaged` never uses it).

```java
package com.bipros.project.application.service;

import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.DprPage;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprAttachmentRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.infrastructure.storage.DprAttachmentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DailyProgressReportService — day-cursor pagination + slim aggregates")
class DailyProgressReportServicePaginationTest {

  @Mock private DailyProgressReportRepository dprRepository;
  @Mock private DprManpowerRepository manpowerRepository;
  @Mock private DprEquipmentRepository equipmentRepository;
  @Mock private DprMaterialRepository materialRepository;
  @Mock private DprSubContractorRepository subContractorRepository;
  @Mock private DprAttachmentRepository attachmentRepository;
  @Mock private DprIssueRepository issueRepository;
  @Mock private DprAttachmentStorageService attachmentStorage;
  @Mock private ProjectRepository projectRepository;
  @Mock private DailyActivityResourceOutputService ledgerService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private BoqItemRepository boqItemRepository;

  private DailyProgressReportService service;

  private final UUID projectId = UUID.randomUUID();
  private final UUID dprA = UUID.randomUUID();
  private final UUID dprB = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new DailyProgressReportService(
        dprRepository, manpowerRepository, equipmentRepository, materialRepository,
        subContractorRepository, attachmentRepository, issueRepository, attachmentStorage,
        projectRepository, ledgerService, auditService, eventPublisher, null, boqItemRepository);
    lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
    // No child rows by default; tests that care stub specific aggregates.
    lenient().when(manpowerRepository.sumNosByDprIdIn(any())).thenReturn(List.of());
    lenient().when(equipmentRepository.sumNosByDprIdIn(any())).thenReturn(List.of());
    lenient().when(materialRepository.countByDprIdIn(any())).thenReturn(List.of());
    lenient().when(attachmentRepository.countByDprIdIn(any())).thenReturn(List.of());
    lenient().when(issueRepository.findStatusSeverityByDprIdIn(any())).thenReturn(List.of());
  }

  private DailyProgressReport dpr(UUID id, LocalDate date, String activity, BigDecimal qty) {
    DailyProgressReport d = new DailyProgressReport();
    d.setId(id);
    d.setProjectId(projectId);
    d.setReportDate(date);
    d.setActivityName(activity);
    d.setUnit("Cum");
    d.setQtyExecuted(qty);
    d.setApprovalStatus(DprApprovalStatus.SUBMITTED);
    d.setSupervisorName("Ravi");
    return d;
  }

  @Test
  @DisplayName("first page: returns slim rows for the requested days and reports hasMore when extra days exist")
  void firstPage_hasMore() {
    LocalDate d1 = LocalDate.of(2026, 3, 10);
    LocalDate d2 = LocalDate.of(2026, 3, 9);
    // days=2 → query asks for 3 distinct dates; 3 returned ⇒ hasMore, drop the oldest extra.
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of(d1, d2, LocalDate.of(2026, 3, 8)));
    when(dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(eq(projectId), eq(List.of(d1, d2)), isNull()))
        .thenReturn(List.of(dpr(dprA, d1, "Earthworks", new BigDecimal("100")),
                            dpr(dprB, d2, "Earthworks", new BigDecimal("50"))));

    DprPage page = service.listPaged(projectId, null, null, null, null, 2);

    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isEqualTo(d2);            // oldest date IN the batch
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).reportDate()).isEqualTo(d1); // newest first
    assertThat(page.items().get(0).qtyExecuted()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("last page: fewer days than requested ⇒ hasMore false, nextCursor null")
  void lastPage_noMore() {
    LocalDate d1 = LocalDate.of(2026, 3, 1);
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of(d1));
    when(dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(eq(projectId), eq(List.of(d1)), isNull()))
        .thenReturn(List.of(dpr(dprA, d1, "Earthworks", new BigDecimal("10"))));

    DprPage page = service.listPaged(projectId, null, null, null, null, 14);

    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.items()).hasSize(1);
  }

  @Test
  @DisplayName("empty: no dates ⇒ empty page")
  void empty() {
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of());

    DprPage page = service.listPaged(projectId, null, null, null, null, 14);

    assertThat(page.items()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("aggregates: nos sums, counts, and issue live/open/critical flags map onto the right dpr")
  void aggregates() {
    LocalDate d1 = LocalDate.of(2026, 3, 10);
    when(dprRepository.findDistinctReportDatesDesc(eq(projectId), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(List.of(d1));
    when(dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(eq(projectId), eq(List.of(d1)), isNull()))
        .thenReturn(List.of(dpr(dprA, d1, "Earthworks", new BigDecimal("100"))));
    when(manpowerRepository.sumNosByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 12L}));
    when(equipmentRepository.sumNosByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 3L}));
    when(materialRepository.countByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 2L}));
    when(attachmentRepository.countByDprIdIn(any())).thenReturn(List.<Object[]>of(new Object[]{dprA, 4L}));
    when(issueRepository.findStatusSeverityByDprIdIn(any())).thenReturn(List.<Object[]>of(
        new Object[]{dprA, IssueStatus.OPEN, IssueSeverity.CRITICAL},   // live, open, critical
        new Object[]{dprA, IssueStatus.RESOLVED, IssueSeverity.LOW},    // live, not open
        new Object[]{dprA, IssueStatus.CANCELLED, IssueSeverity.HIGH}));// excluded

    var row = service.listPaged(projectId, null, null, null, null, 14).items().get(0);

    assertThat(row.manpowerNos()).isEqualTo(12);
    assertThat(row.equipmentNos()).isEqualTo(3);
    assertThat(row.materialCount()).isEqualTo(2);
    assertThat(row.photoCount()).isEqualTo(4);
    assertThat(row.issueCount()).isEqualTo(2);       // OPEN + RESOLVED (CANCELLED excluded)
    assertThat(row.openIssueCount()).isEqualTo(1);   // OPEN only
    assertThat(row.hasCriticalOpen()).isTrue();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -q -pl bipros-project test -Dtest=DailyProgressReportServicePaginationTest`
Expected: FAIL to compile — `service.listPaged(...)` does not exist yet.

- [ ] **Step 3: Implement `listPaged()` + slim builder in `DailyProgressReportService`**

Add these imports if not present: `import com.bipros.project.application.dto.DprPage;`, `import com.bipros.project.application.dto.DprSummaryResponse;`, `import com.bipros.project.domain.model.IssueSeverity;`, `import com.bipros.project.domain.model.IssueStatus;`, `import org.springframework.data.domain.PageRequest;`, `import java.util.Collection;`. (`HashMap`, `Map`, `ArrayList`, `List`, `UUID`, `LocalDate`, `BigDecimal` are already imported.)

Add the public method next to `list(...)` (around line 365):

```java
  /**
   * Day-cursored, slim DPR list for the DPR tab. Returns the most-recent {@code days} distinct
   * report dates within the optional [from,to] window and strictly older than {@code before},
   * with all rows for those days. Child detail is NOT hydrated — only cheap aggregates — so the
   * collapsed list is light; full detail comes from {@link #get(UUID, UUID)} on expand.
   */
  @Transactional(readOnly = true)
  public DprPage listPaged(UUID projectId, LocalDate from, LocalDate to, String activityName,
                           LocalDate before, int days) {
    ensureProjectExists(projectId);
    int batch = days <= 0 ? 14 : days;
    String activity = (activityName != null && !activityName.isBlank()) ? activityName : null;

    List<LocalDate> dates = dprRepository.findDistinctReportDatesDesc(
        projectId, from, to, before, activity, PageRequest.of(0, batch + 1));
    boolean hasMore = dates.size() > batch;
    if (hasMore) {
      dates = dates.subList(0, batch);
    }
    if (dates.isEmpty()) {
      return new DprPage(List.of(), null, false);
    }
    LocalDate nextCursor = dates.get(dates.size() - 1); // oldest date in this batch

    List<DailyProgressReport> rows =
        dprRepository.findByProjectIdAndReportDateInOrderByReportDateDescIdAsc(projectId, dates, activity);
    List<DprSummaryResponse> items = toSummaryRows(rows);
    return new DprPage(items, hasMore ? nextCursor : null, hasMore);
  }

  /** Build slim rows for the given DPRs using cheap GROUP BY aggregate queries (no child hydration). */
  private List<DprSummaryResponse> toSummaryRows(List<DailyProgressReport> rows) {
    if (rows.isEmpty()) return List.of();
    List<UUID> ids = rows.stream().map(DailyProgressReport::getId).toList();

    Map<UUID, Long> manpowerNos = toLongMap(manpowerRepository.sumNosByDprIdIn(ids));
    Map<UUID, Long> equipmentNos = toLongMap(equipmentRepository.sumNosByDprIdIn(ids));
    Map<UUID, Long> materialCount = toLongMap(materialRepository.countByDprIdIn(ids));
    Map<UUID, Long> photoCount = toLongMap(attachmentRepository.countByDprIdIn(ids));

    Map<UUID, int[]> issueAgg = new HashMap<>(); // [issueCount, openIssueCount, hasCriticalOpen(0/1)]
    for (Object[] r : issueRepository.findStatusSeverityByDprIdIn(ids)) {
      UUID id = (UUID) r[0];
      IssueStatus status = (IssueStatus) r[1];
      IssueSeverity severity = (IssueSeverity) r[2];
      if (status == IssueStatus.CANCELLED) continue;                 // not "live"
      int[] a = issueAgg.computeIfAbsent(id, k -> new int[3]);
      a[0]++;                                                         // live count
      boolean open = status != IssueStatus.RESOLVED && status != IssueStatus.CLOSED;
      if (open) {
        a[1]++;
        if (severity == IssueSeverity.CRITICAL) a[2] = 1;
      }
    }

    List<DprSummaryResponse> out = new ArrayList<>(rows.size());
    for (DailyProgressReport r : rows) {
      UUID id = r.getId();
      int[] ia = issueAgg.getOrDefault(id, new int[3]);
      out.add(new DprSummaryResponse(
          id, r.getProjectId(), r.getReportDate(),
          r.getSupervisorUserId(), r.getSupervisorName(),
          r.getChainageFromM(), r.getChainageToM(),
          r.getActivityId(), r.getActivityName(), r.getBoqItemNo(),
          r.getUnit(), r.getQtyExecuted(),
          r.getSide(), r.getApprovalStatus(), r.getWeatherCondition(),
          manpowerNos.getOrDefault(id, 0L),
          equipmentNos.getOrDefault(id, 0L),
          materialCount.getOrDefault(id, 0L).intValue(),
          photoCount.getOrDefault(id, 0L).intValue(),
          ia[0], ia[1], ia[2] == 1));
    }
    return out;
  }

  private static Map<UUID, Long> toLongMap(List<Object[]> rows) {
    Map<UUID, Long> m = new HashMap<>();
    for (Object[] r : rows) {
      m.put((UUID) r[0], ((Number) r[1]).longValue());
    }
    return m;
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn -q -pl bipros-project test -Dtest=DailyProgressReportServicePaginationTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/bipros-project/src/main/java/com/bipros/project/application/service/DailyProgressReportService.java \
        backend/bipros-project/src/test/java/com/bipros/project/application/service/DailyProgressReportServicePaginationTest.java
git commit -m "feat(dpr): listPaged() day-cursor pagination + slim aggregate builder"
```

---

## Task 4: Controller — switch list endpoint to paginated slim response

**Files:**
- Modify: `backend/bipros-project/src/main/java/com/bipros/project/api/DailyProgressReportController.java:67-75`

- [ ] **Step 1: Replace the `list` mapping**

Add `import com.bipros.project.application.dto.DprPage;` to the imports. Replace the existing `@GetMapping … list(...)` block (lines 67-75) with:

```java
  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<DprPage>> list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String activity,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before,
      @RequestParam(defaultValue = "14") int days) {
    return ResponseEntity.ok(ApiResponse.ok(service.listPaged(projectId, from, to, activity, before, days)));
  }
```

(The old `DailyProgressReportService.list(...)` method stays — it is still exercised by the existing children/sub-contractor/issue service tests — but is no longer wired to the HTTP endpoint.)

- [ ] **Step 2: Compile + run the project module tests**

Run: `cd backend && mvn -q -pl bipros-project -am test -Dtest='DailyProgressReportService*'`
Expected: BUILD SUCCESS — existing children/sub-contractor/issue tests still pass, plus the new pagination test.

- [ ] **Step 3: Commit**

```bash
git add backend/bipros-project/src/main/java/com/bipros/project/api/DailyProgressReportController.java
git commit -m "feat(dpr): paginate DPR list endpoint (before+days), return DprPage"
```

---

## Task 5: Frontend types + paginated `dprApi.list` + fix the DBS caller

**Files:**
- Modify: `frontend/src/lib/types/dpr.ts`
- Modify: `frontend/src/lib/api/dprApi.ts:18-22,62-72`
- Modify: `frontend/src/app/(app)/projects/[projectId]/dbs/components/SupervisorDbsTab.tsx:74,77`

- [ ] **Step 1: Add slim types to `dpr.ts`**

Append after the `DailyProgressReportResponse` interface (end of file):

```ts
/**
 * Slim DPR list row returned by the paginated list endpoint. Carries parent fields used by the
 * Day → Activity → Work-front grouping + collapsed row, plus precomputed child aggregates. Full
 * child detail (manpower/equipment/material/sub-contractor/issue rows, cumulativeQty, landmark,
 * remarks) comes from GET /dpr/{id} on row expand — see DailyProgressReportResponse.
 */
export interface DprSummaryRow {
  id: string;
  projectId: string;
  reportDate: string;
  supervisorUserId?: string | null;
  supervisorName: string;
  chainageFromM?: number | null;
  chainageToM?: number | null;
  activityId?: string | null;
  activityName: string;
  boqItemNo?: string | null;
  unit: string;
  qtyExecuted?: number | null;
  side?: Side | null;
  approvalStatus?: DprApprovalStatus | null;
  weatherCondition?: string | null;
  manpowerNos: number;
  equipmentNos: number;
  materialCount: number;
  photoCount: number;
  issueCount: number;
  openIssueCount: number;
  hasCriticalOpen: boolean;
}

/** One page of the day-cursored DPR list. */
export interface DprPage {
  items: DprSummaryRow[];
  /** Oldest report date in this batch; pass as `before` for the next page. Null when no more. */
  nextCursor: string | null;
  hasMore: boolean;
}
```

- [ ] **Step 2: Update `dprApi.list` to paginate**

In `frontend/src/lib/api/dprApi.ts`, add `before`/`days` to the filters and change the return type. Replace the `DprListFilters` interface (lines 18-22) and the `list` function (lines 63-72):

```ts
export interface DprListFilters {
  from?: string;
  to?: string;
  activity?: string;
  /** Exclusive day cursor — fetch reports strictly older than this date. */
  before?: string;
  /** Number of distinct days to fetch in this page. Defaults to 14 server-side. */
  days?: number;
}
```

```ts
  list: (projectId: string, filters: DprListFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.activity) params.set("activity", filters.activity);
    if (filters.before) params.set("before", filters.before);
    if (filters.days != null) params.set("days", String(filters.days));
    const qs = params.toString() ? `?${params.toString()}` : "";
    return apiClient
      .get<ApiResponse<DprPage>>(`/v1/projects/${projectId}/dpr${qs}`)
      .then((r) => r.data);
  },
```

Add `DprPage` to the type import block at the top of `dprApi.ts`:

```ts
import type {
  CreateDailyProgressReportRequest,
  DailyProgressReportResponse,
  DprAttachment,
  DprPage,
  UpdateDailyProgressReportRequest,
} from "../types/dpr";
```

- [ ] **Step 3: Fix the `SupervisorDbsTab` caller**

In `frontend/src/app/(app)/projects/[projectId]/dbs/components/SupervisorDbsTab.tsx`, the probe now receives a `DprPage`. Change line 77:

```ts
  const dprsForDate = dprData?.data?.items ?? [];
```

(It only reads `dprsForDate.length` at line 148, so reading `.items` is the whole fix. The single-day `{ from: date, to: date }` call returns one day, well under the 14-day default.)

- [ ] **Step 4: Typecheck**

Run: `cd frontend && pnpm lint`
Expected: no new errors in the three touched files. (The DPR page itself is updated in Task 6; it may report type errors until then — that's fine, proceed.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/types/dpr.ts frontend/src/lib/api/dprApi.ts \
        "frontend/src/app/(app)/projects/[projectId]/dbs/components/SupervisorDbsTab.tsx"
git commit -m "feat(dpr): paginated list API types + fix DBS empty-state probe"
```

---

## Task 6: Grouping over slim rows — memoize + unit test

**Files:**
- Modify: `frontend/src/components/dpr/DprDayList.tsx`
- Modify: `frontend/src/components/dpr/DprActivityGroup.tsx`
- Create: `frontend/src/components/dpr/groupByDayThenActivity.test.ts`

- [ ] **Step 1: Extract `groupByDayThenActivity` to its own module (so it is unit-testable) and retype to slim rows**

Create `frontend/src/components/dpr/groupByDayThenActivity.ts` by moving the helper out of `DprDayList.tsx`. Replace `DailyProgressReportResponse` with `DprSummaryRow` throughout:

```ts
import type { DprSummaryRow } from "@/lib/types/dpr";

export interface ActivityGroup {
  key: string;
  boqItemNo: string | null;
  activityName: string;
  unit: string;
  totalQty: number;
  uniqueSupervisors: Array<{ id: string; name: string }>;
  rows: DprSummaryRow[]; // one per work-front, sorted by chainage asc
}

export interface DayGroup {
  date: string;
  weather: string | null;
  supervisorCount: number;
  totalActivities: number;
  activityGroups: ActivityGroup[];
}

const activityKey = (row: DprSummaryRow): string =>
  row.boqItemNo
    ? `boq:${row.boqItemNo}`
    : row.activityId
      ? `aid:${row.activityId}`
      : `name:${(row.activityName ?? "").toLowerCase()}`;

const compareByChainage = (a: DprSummaryRow, b: DprSummaryRow): number => {
  const ax = a.chainageFromM ?? Number.POSITIVE_INFINITY;
  const bx = b.chainageFromM ?? Number.POSITIVE_INFINITY;
  if (ax !== bx) return ax - bx;
  return (a.supervisorName ?? "").localeCompare(b.supervisorName ?? "");
};

export const groupByDayThenActivity = (rows: DprSummaryRow[]): DayGroup[] => {
  const byDay = new Map<string, DprSummaryRow[]>();
  for (const r of rows) {
    if (!r.reportDate) continue;
    const list = byDay.get(r.reportDate) ?? [];
    list.push(r);
    byDay.set(r.reportDate, list);
  }

  const days: DayGroup[] = [];
  for (const [date, dayRows] of byDay.entries()) {
    const byActivity = new Map<string, DprSummaryRow[]>();
    for (const r of dayRows) {
      const key = activityKey(r);
      const list = byActivity.get(key) ?? [];
      list.push(r);
      byActivity.set(key, list);
    }

    const activityGroups: ActivityGroup[] = [];
    for (const [key, list] of byActivity.entries()) {
      const sorted = [...list].sort(compareByChainage);
      const first = sorted[0];
      const totalQty = sorted.reduce(
        (acc, r) => acc + (typeof r.qtyExecuted === "number" ? r.qtyExecuted : 0),
        0,
      );
      const seen = new Set<string>();
      const uniqueSupervisors: Array<{ id: string; name: string }> = [];
      for (const r of sorted) {
        const name = (r.supervisorName ?? "").trim();
        if (!name) continue;
        const dedupKey = (r.supervisorUserId ?? `name:${name.toLowerCase()}`).toString();
        if (seen.has(dedupKey)) continue;
        seen.add(dedupKey);
        uniqueSupervisors.push({ id: dedupKey, name });
      }
      activityGroups.push({
        key,
        boqItemNo: first.boqItemNo ?? null,
        activityName: first.activityName,
        unit: first.unit,
        totalQty,
        uniqueSupervisors,
        rows: sorted,
      });
    }

    activityGroups.sort((a, b) => {
      const ka = a.boqItemNo ?? a.activityName;
      const kb = b.boqItemNo ?? b.activityName;
      return ka.localeCompare(kb, undefined, { numeric: true, sensitivity: "base" });
    });

    const supervisorNames = new Set<string>();
    for (const g of activityGroups) {
      for (const s of g.uniqueSupervisors) supervisorNames.add(s.name);
    }
    const weather = dayRows.find((r) => r.weatherCondition)?.weatherCondition ?? null;

    days.push({
      date,
      weather,
      supervisorCount: supervisorNames.size,
      totalActivities: dayRows.length,
      activityGroups,
    });
  }

  days.sort((a, b) => b.date.localeCompare(a.date));
  return days;
};
```

- [ ] **Step 2: Write the failing unit test**

Create `frontend/src/components/dpr/groupByDayThenActivity.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { groupByDayThenActivity } from "./groupByDayThenActivity";
import type { DprSummaryRow } from "@/lib/types/dpr";

const row = (over: Partial<DprSummaryRow>): DprSummaryRow => ({
  id: Math.random().toString(36).slice(2),
  projectId: "p",
  reportDate: "2026-03-10",
  supervisorName: "Ravi",
  activityName: "Earthworks",
  unit: "Cum",
  qtyExecuted: 0,
  manpowerNos: 0,
  equipmentNos: 0,
  materialCount: 0,
  photoCount: 0,
  issueCount: 0,
  openIssueCount: 0,
  hasCriticalOpen: false,
  ...over,
});

describe("groupByDayThenActivity", () => {
  it("groups by day (newest first) then activity, summing qty and sorting fronts by chainage", () => {
    const days = groupByDayThenActivity([
      row({ reportDate: "2026-03-09", boqItemNo: "1.1", chainageFromM: 200, qtyExecuted: 5 }),
      row({ reportDate: "2026-03-10", boqItemNo: "1.1", chainageFromM: 100, qtyExecuted: 10 }),
      row({ reportDate: "2026-03-10", boqItemNo: "1.1", chainageFromM: 50, qtyExecuted: 20 }),
    ]);

    expect(days.map((d) => d.date)).toEqual(["2026-03-10", "2026-03-09"]); // newest first
    const mar10 = days[0].activityGroups[0];
    expect(mar10.totalQty).toBe(30);                       // 10 + 20
    expect(mar10.rows.map((r) => r.chainageFromM)).toEqual([50, 100]); // chainage asc
  });

  it("keeps distinct activities on the same day as separate groups", () => {
    const days = groupByDayThenActivity([
      row({ boqItemNo: "1.1", activityName: "Earthworks" }),
      row({ boqItemNo: "2.3", activityName: "Paving" }),
    ]);
    expect(days[0].activityGroups).toHaveLength(2);
  });
});
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `cd frontend && pnpm vitest run src/components/dpr/groupByDayThenActivity.test.ts`
Expected: PASS (2 tests). (If vitest reports no config, run `pnpm vitest run --config ./vitest.config.ts` or the path the repo uses; the import alias `@/` resolves via tsconfig paths.)

- [ ] **Step 4: Update `DprDayList.tsx` to import the helper, memoize it, and apply `content-visibility`**

In `DprDayList.tsx`: remove the in-file `activityKey`/`compareByChainage`/`groupByDayThenActivity`/`DayGroup`/`ActivityGroup` definitions (now imported), add `projectId` to props, memoize the grouping, and add `content-visibility` to each day section. Replace the component's imports and body:

```tsx
"use client";

import { useMemo } from "react";
import { CalendarDays, CloudSun, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DprSummaryRow } from "@/lib/types/dpr";
import { DprActivityGroup } from "./DprActivityGroup";
import { groupByDayThenActivity } from "./groupByDayThenActivity";

interface Props {
  rows: DprSummaryRow[];
  projectId: string;
  onEdit: (row: DprSummaryRow) => void;
  onDelete: (row: DprSummaryRow) => void;
  stickyOffset?: number;
}

const fmtDate = (iso: string): string => {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString(undefined, {
    weekday: "short",
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
};

export function DprDayList({ rows, projectId, onEdit, onDelete, stickyOffset = 0 }: Props) {
  const days = useMemo(() => groupByDayThenActivity(rows), [rows]);

  if (days.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-hairline bg-ivory/30 px-6 py-12 text-center">
        <CalendarDays className="mx-auto h-8 w-8 text-slate" />
        <p className="mt-2 text-sm text-slate">
          No daily progress logged in this range. Tap{" "}
          <span className="font-semibold text-gold-ink">Add Activity</span> to start.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {days.map((day) => (
        <section
          key={day.date}
          className="space-y-3"
          // Skip layout/paint for off-screen day sections so long ranges stay smooth without
          // virtualization fighting the sticky day headers. Estimate keeps the scrollbar stable.
          style={{ contentVisibility: "auto", containIntrinsicSize: "0 480px" }}
        >
          <header
            className="sticky z-10 flex flex-wrap items-center gap-3 border-b border-b-gold/30 bg-ivory/85 px-4 py-2.5 backdrop-blur-sm"
            style={{ top: stickyOffset }}
          >
            <div className="flex items-center gap-2 font-display text-base font-semibold tracking-tight text-charcoal">
              <CalendarDays className="h-4 w-4 text-gold-deep" />
              {fmtDate(day.date)}
            </div>
            <Badge variant="gold">
              {day.activityGroups.length}{" "}
              {day.activityGroups.length === 1 ? "activity" : "activities"}
            </Badge>
            {day.totalActivities !== day.activityGroups.length && (
              <span className="text-xs text-slate">
                · {day.totalActivities}{" "}
                {day.totalActivities === 1 ? "front" : "fronts"}
              </span>
            )}
            {day.supervisorCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full bg-paper/80 px-2 py-0.5 text-xs text-slate">
                <Users className="h-3 w-3 text-gold-deep" />
                {day.supervisorCount}{" "}
                {day.supervisorCount === 1 ? "supervisor" : "supervisors"}
              </span>
            )}
            {day.weather && (
              <span className="inline-flex items-center gap-1 text-xs text-slate">
                <CloudSun className="h-3 w-3" /> {day.weather}
              </span>
            )}
          </header>
          <div className="space-y-2">
            {day.activityGroups.map((group) => (
              <DprActivityGroup
                key={group.key}
                group={group}
                projectId={projectId}
                onEditRow={onEdit}
                onDeleteRow={onDelete}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

export function DprDaySkeleton() {
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-3 border-b border-b-gold/20 bg-ivory/60 px-4 py-2.5">
        <div className="h-4 w-4 animate-pulse rounded bg-parchment" />
        <div className="h-4 w-40 animate-pulse rounded bg-parchment" />
        <div className="h-5 w-20 animate-pulse rounded-full bg-parchment" />
      </div>
      <div className="space-y-2">
        <div className="h-16 animate-pulse rounded-lg bg-parchment/60" />
        <div className="h-16 animate-pulse rounded-lg bg-parchment/60" />
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Update `DprActivityGroup.tsx` — import the shared `ActivityGroup` type, add `projectId`, wrap in `React.memo`, retype callbacks**

Replace the top of the file and the export. Change the `import` of `DprWorkFrontRow` to keep, drop the local `ActivityGroup` interface (now imported), and add `projectId`:

```tsx
"use client";

import { memo, useState } from "react";
import { ChevronDown, ChevronRight, Layers } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { DprSummaryRow } from "@/lib/types/dpr";
import type { ActivityGroup } from "./groupByDayThenActivity";
import { AvatarStack } from "./AvatarStack";
import { DprWorkFrontRow } from "./DprWorkFrontRow";

interface Props {
  group: ActivityGroup;
  projectId: string;
  onEditRow: (row: DprSummaryRow) => void;
  onDeleteRow: (row: DprSummaryRow) => void;
}

const fmt = (n: number, digits = 2) =>
  n.toLocaleString(undefined, { maximumFractionDigits: digits });

function DprActivityGroupImpl({ group, projectId, onEditRow, onDeleteRow }: Props) {
  const multiFront = group.rows.length > 1;
  const [open, setOpen] = useState(multiFront);
```

Keep the existing JSX body unchanged **except** the work-front mapping, which must pass `projectId`:

```tsx
          {group.rows.map((row, i) => (
            <DprWorkFrontRow
              key={row.id}
              row={row}
              projectId={projectId}
              index={i}
              total={group.rows.length}
              onEdit={() => onEditRow(row)}
              onDelete={() => onDeleteRow(row)}
            />
          ))}
```

Add the export wrapper at the end of the file (replacing the old `export function` name):

```tsx
export const DprActivityGroup = memo(DprActivityGroupImpl);
```

(Remove the now-duplicate `export interface ActivityGroup { … }` block from this file — it lives in `groupByDayThenActivity.ts`.)

- [ ] **Step 6: Lint**

Run: `cd frontend && pnpm lint`
Expected: no new errors in `DprDayList.tsx`, `DprActivityGroup.tsx`, `groupByDayThenActivity.ts`. (`DprWorkFrontRow.tsx` and `page.tsx` still pending — Tasks 7 & 8 — may error; OK.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/dpr/DprDayList.tsx \
        frontend/src/components/dpr/DprActivityGroup.tsx \
        frontend/src/components/dpr/groupByDayThenActivity.ts \
        frontend/src/components/dpr/groupByDayThenActivity.test.ts
git commit -m "feat(dpr): slim-typed memoized grouping + content-visibility day sections"
```

---

## Task 7: Work-front row — slim chips + lazy detail on expand

**Files:**
- Modify: `frontend/src/components/dpr/DprWorkFrontRow.tsx`

- [ ] **Step 1: Retype props to slim row + add `projectId`; read aggregates from the row**

Replace the imports, `Props`, and the aggregate-derivation block at the top of `DprWorkFrontRow.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  Briefcase,
  ChevronDown,
  ChevronRight,
  HardHat,
  Image as ImageIcon,
  MapPin,
  Package,
  Pencil,
  Trash2,
} from "lucide-react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { ResourceAvatar } from "@/components/resource/supervisor-assign/ResourceAvatar";
import { chainageLabel } from "@/lib/format/chainage";
import { dprApi } from "@/lib/api/dprApi";
import type { DprApprovalStatus, DprSummaryRow } from "@/lib/types/dpr";
import {
  SEVERITY_VARIANT,
  STATUS_VARIANT as ISSUE_STATUS_VARIANT,
  categoryLabel,
} from "./IssueBadges";
import { DetailTable } from "./DetailTable";

interface Props {
  row: DprSummaryRow;
  projectId: string;
  index: number;
  total: number;
  onEdit: () => void;
  onDelete: () => void;
}
```

Replace the count-derivation block (old lines 74-90, the `manpowerCount = …` through `issueChipClass`) with reads straight off the slim row:

```tsx
  const manpowerCount = row.manpowerNos;
  const equipmentCount = row.equipmentNos;
  const materialCount = row.materialCount;
  const photoCount = row.photoCount;
  const issueCount = row.issueCount;
  const openIssueCount = row.openIssueCount;
  const hasCriticalOpen = row.hasCriticalOpen;
  const issueChipClass = hasCriticalOpen
    ? "border-burgundy/30 bg-burgundy/10 text-burgundy"
    : openIssueCount > 0
      ? "border-bronze-warn/30 bg-bronze-warn/15 text-bronze-warn"
      : "border-hairline bg-ivory text-slate";
```

Keep `length`, `avatarKey`, the `STATUS_VARIANT`/`SIDE_LABEL` maps, `fmt`, `lengthLabel`, and the entire collapsed header JSX (lines 95-212) unchanged — they reference only fields present on `DprSummaryRow`.

- [ ] **Step 2: Add the lazy detail query (fires only when the row is open)**

Immediately after `const [open, setOpen] = useState(false);`, add:

```tsx
  const {
    data: detailData,
    isLoading: detailLoading,
    isError: detailError,
    refetch: refetchDetail,
  } = useQuery({
    queryKey: ["dpr-detail", projectId, row.id],
    queryFn: () => dprApi.get(projectId, row.id),
    enabled: open,
    staleTime: 1000 * 60 * 5,
  });
  const detail = detailData?.data;
  const liveIssues = (detail?.issues ?? []).filter((i) => i.status !== "CANCELLED");
```

(Remove the old `liveIssues` derivation that read `row.issues` — issues now come from `detail`. The header issue chip uses the slim `issueCount`/`openIssueCount`/`hasCriticalOpen`, so it does not depend on `detail`.)

- [ ] **Step 3: Replace the expanded panel to render from `detail` with loading/error states**

Replace the entire `{open && ( … )}` block (old lines 214-340) with:

```tsx
      {open && (
        <div className="space-y-3 border-t border-hairline bg-ivory/30 px-3 py-3 md:px-4">
          {detailLoading && (
            <div className="space-y-2">
              <div className="h-4 w-40 animate-pulse rounded bg-parchment" />
              <div className="h-16 animate-pulse rounded bg-parchment/60" />
              <div className="h-16 animate-pulse rounded bg-parchment/60" />
            </div>
          )}

          {detailError && (
            <div className="flex items-center gap-2 text-xs text-burgundy">
              Failed to load detail.
              <button
                type="button"
                onClick={() => refetchDetail()}
                className="rounded border border-burgundy/30 px-2 py-0.5 font-semibold hover:bg-burgundy/10"
              >
                Retry
              </button>
            </div>
          )}

          {detail && (
            <>
              {detail.cumulativeQty != null && (
                <div className="text-xs text-slate">
                  <span className="font-semibold text-charcoal">Cumulative:</span>{" "}
                  <span className="tabular-nums">
                    {fmt(detail.cumulativeQty)} {detail.unit}
                  </span>
                </div>
              )}
              {detail.landmark && (
                <div className="text-xs text-slate">
                  <span className="font-semibold text-charcoal">Landmark:</span> {detail.landmark}
                </div>
              )}
              {detail.remarks && (
                <div className="rounded-md bg-paper/80 p-2 text-xs text-charcoal">
                  <span className="font-semibold">Remarks: </span>
                  {detail.remarks}
                </div>
              )}

              <DetailTable
                title="Manpower"
                empty="No manpower"
                headers={["Role · Category / Grade", "Nos", "Hours"]}
                rows={(detail.manpower ?? []).map((m) => [m.trade, fmt(m.nos, 0), fmt(m.workingHours)])}
                accent="emerald"
                numericFromIndex={1}
              />
              <DetailTable
                title="Equipment / PMV"
                empty="No equipment"
                headers={["Equipment · Make / Model", "Fleet #", "Nos", "Hours"]}
                rows={(detail.equipment ?? []).map((e) => [
                  e.equipmentType,
                  e.fleetNo ?? "—",
                  fmt(e.nos, 0),
                  fmt(e.workingHours),
                ])}
                accent="bronze"
                numericFromIndex={1}
              />
              <DetailTable
                title="Material"
                empty="No material"
                headers={["Material · Spec / Grade", "Qty"]}
                rows={(detail.materials ?? []).map((m) => [m.materialName, fmt(m.quantity, 3)])}
                accent="steel"
                numericFromIndex={1}
              />
              <DetailTable
                title="Sub-Contractor"
                empty="No sub-contractor"
                headers={["Sub-Contractor", "Qty", "Unit", "Rate", "Cost", "Remarks"]}
                rows={(detail.subContractors ?? []).map((s) => [
                  s.subContractorName ?? "—",
                  fmt(s.quantity, 3),
                  s.unit ?? "—",
                  fmt(s.ratePerUnit),
                  fmt(s.lineCost),
                  s.remarks?.trim() ? s.remarks : "—",
                ])}
                accent="slate"
                numericFromIndex={1}
              />

              {liveIssues.length > 0 && (
                <div>
                  <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate">
                    Issues
                  </div>
                  <div className="overflow-x-auto rounded-md border border-hairline">
                    <table className="w-full text-xs">
                      <thead className="bg-ivory/60">
                        <tr>
                          <th className="px-2 py-1 text-left font-semibold text-slate">Title</th>
                          <th className="px-2 py-1 text-left font-semibold text-slate">Reason</th>
                          <th className="px-2 py-1 text-left font-semibold text-slate">Severity</th>
                          <th className="px-2 py-1 text-left font-semibold text-slate">Status</th>
                          <th className="px-2 py-1 text-left font-semibold text-slate">Assigned</th>
                        </tr>
                      </thead>
                      <tbody>
                        {liveIssues.map((i) => (
                          <tr key={i.id ?? i.title} className="border-t border-hairline">
                            <td className="px-2 py-1 text-charcoal">{i.title}</td>
                            <td className="px-2 py-1 text-charcoal">{categoryLabel(i.category)}</td>
                            <td className="px-2 py-1">
                              <Badge variant={SEVERITY_VARIANT[i.severity]}>{i.severity}</Badge>
                            </td>
                            <td className="px-2 py-1">
                              <Badge variant={ISSUE_STATUS_VARIANT[i.status]} withDot>
                                {i.status}
                              </Badge>
                            </td>
                            <td className="px-2 py-1 text-charcoal">{i.assignedToName ?? "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {(detail.delayReason ||
                detail.safetyObservation ||
                detail.safetyIncidentType === "INCIDENT" ||
                detail.safetyIncidentType === "NEAR_MISS") && (
                <div className="rounded-md border border-burgundy/20 bg-burgundy/5 p-3 text-xs text-charcoal">
                  <div className="mb-1 font-semibold text-burgundy">Safety & Delay</div>
                  {detail.safetyIncidentType && detail.safetyIncidentType !== "NONE" && (
                    <div>Incident: {detail.safetyIncidentType.replace("_", " ")}</div>
                  )}
                  {detail.delayReason && <div>Delay: {detail.delayReason}</div>}
                  {detail.safetyObservation && <div>Observation: {detail.safetyObservation}</div>}
                </div>
              )}
            </>
          )}
        </div>
      )}
```

- [ ] **Step 4: Wrap the component in `React.memo`**

Change the declaration `export function DprWorkFrontRow(...)` to `function DprWorkFrontRowImpl(...)`, add `import { memo } from "react";` (merge with the existing react import: `import { memo, useState } from "react";`), and at the end of the file add:

```tsx
export const DprWorkFrontRow = memo(DprWorkFrontRowImpl);
```

- [ ] **Step 5: Lint**

Run: `cd frontend && pnpm lint`
Expected: no new errors in `DprWorkFrontRow.tsx`. (`page.tsx` updated next.)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/dpr/DprWorkFrontRow.tsx
git commit -m "feat(dpr): collapsed row reads slim aggregates, lazy-loads detail on expand"
```

---

## Task 8: DPR page — infinite query, async edit, scroll sentinel

**Files:**
- Modify: `frontend/src/app/(app)/projects/[projectId]/dpr/page.tsx`

- [ ] **Step 1: Swap `useQuery` for `useInfiniteQuery` and derive accumulated rows**

In `page.tsx`, change the React Query import (line 5) to include the hooks and refs:

```tsx
import { useEffect, useMemo, useRef, useState } from "react";
```
```tsx
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
```

Add the slim row type to the dpr types import (line 8-11):

```tsx
import type {
  DailyProgressReportResponse,
  DprBaseFields,
  DprSummaryRow,
} from "@/lib/types/dpr";
```

Replace the DPR list query (lines 220-226) with:

```tsx
  const {
    data: listPages,
    isLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteQuery({
    queryKey: ["dpr", projectId, from, to],
    queryFn: ({ pageParam }) =>
      dprApi.list(projectId, { from, to, before: pageParam ?? undefined, days: 14 }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) =>
      lastPage.data?.hasMore ? lastPage.data.nextCursor ?? undefined : undefined,
    enabled: !!projectId && !!from && !!to,
  });

  const rows: DprSummaryRow[] = useMemo(
    () => (listPages?.pages ?? []).flatMap((p) => p.data?.items ?? []),
    [listPages],
  );
```

(Note: `isFetching` is no longer destructured. In the Refresh button label at line 346, replace `{isFetching ? "Loading…" : "Refresh"}` with `{isFetchingNextPage ? "Loading…" : "Refresh"}`.)

- [ ] **Step 2: Make `openEdit` fetch the full record (slim rows lack children)**

Replace `openEdit` (lines 241-246) with an async version that hydrates the full DPR before opening the drawer:

```tsx
  const openEdit = async (row: DprSummaryRow) => {
    setPageError(null);
    setPrefill(null);
    try {
      const full = await dprApi.get(projectId, row.id);
      setEditing(full.data ?? null);
      setShowForm(true);
    } catch (err: unknown) {
      setPageError(getErrorMessage(err, "Failed to load DPR for editing"));
    }
  };
```

- [ ] **Step 3: Retype `handleDelete` to the slim row**

Change the signature (line 267) from `(row: DailyProgressReportResponse)` to `(row: DprSummaryRow)`. The body uses only `row.activityName`, `row.reportDate`, `row.id` — all present on `DprSummaryRow` — so no other change.

```tsx
  const handleDelete = async (row: DprSummaryRow) => {
```

- [ ] **Step 4: Add the infinite-scroll sentinel + observer**

After the other `useEffect`s (e.g. after the `useStickyMeasure` line ~216), add:

```tsx
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasNextPage && !isFetchingNextPage) {
          fetchNextPage();
        }
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);
```

- [ ] **Step 5: Pass `projectId` to `DprDayList` and render the sentinel**

Replace the `<DprDayList ... />` block (lines 381-386) with:

```tsx
        <DprDayList
          rows={rows}
          projectId={projectId}
          onEdit={openEdit}
          onDelete={handleDelete}
          stickyOffset={dayStickyOffset}
        />
        <div ref={sentinelRef} aria-hidden className="h-1" />
        {isFetchingNextPage && (
          <div className="py-6 text-center text-sm text-slate">Loading older days…</div>
        )}
```

- [ ] **Step 6: Lint + build**

Run: `cd frontend && pnpm lint && pnpm build`
Expected: lint clean for the DPR files; `pnpm build` (Next.js typecheck) succeeds.

- [ ] **Step 7: Commit**

```bash
git add "frontend/src/app/(app)/projects/[projectId]/dpr/page.tsx"
git commit -m "feat(dpr): infinite-scroll-by-day DPR list, async edit hydration, scroll sentinel"
```

---

## Task 9: Manual verification on Khasab

**Files:** none (verification only). Requires backend + frontend running and the Khasab project seeded (see `docs/superpowers/specs/2026-05-24-fresh-env-and-khasab-import-design.md`).

- [ ] **Step 1: Start the stack**

```bash
docker compose up -d
(cd backend && mvn spring-boot:run -pl bipros-api -am)   # -am so sibling jars rebuild (stale-M2 gotcha)
(cd frontend && pnpm dev)
```

- [ ] **Step 2: Measure load — before/after**

Open the Khasab project → DPR tab with the full planned window. In Chrome DevTools → Network, record the `GET …/dpr?...` response size and time. Expected vs the pre-change build: first request now carries only ~14 days of slim rows (no `manpower`/`equipment`/`materials`/`subContractors`/`issues`/`attachments` arrays), payload an order of magnitude smaller, no spinner stall.

- [ ] **Step 3: Verify chip correctness**

For 3-4 collapsed work-front rows, confirm the manpower/equipment/material/photo/issue chips match what the expanded detail shows (expand to cross-check the counts).

- [ ] **Step 4: Verify lazy detail**

Expand several rows: detail tables (manpower/equipment/material/sub-contractor/issues), Cumulative, Landmark, Remarks, and Safety & Delay render correctly and match the report. Confirm a brief skeleton appears on first expand and not on re-expand (cached).

- [ ] **Step 5: Verify infinite scroll**

Scroll to the bottom: older days auto-load ("Loading older days…" appears, then more day sections). Continue to the oldest day — loading stops (no infinite spinner, `hasMore` false). Confirm no duplicate day sections.

- [ ] **Step 6: Verify mutations refresh**

Add a DPR (recent date), edit it (drawer opens with full data after a brief fetch), delete it. Each action refreshes the list. Confirm the `SupervisorDbsTab` empty-state Recompute prompt still appears when a day has DPRs but the DBS roster is empty.

- [ ] **Step 7: Capture evidence**

Save before/after Network screenshots to `frontend/e2e/.artifacts/screenshots/` and note payload size + load time in the PR description.

---

## Self-Review

**1. Spec coverage**
- Day-cursor pagination (`before`/`days`, distinct-days, `hasMore`, `nextCursor`) → Tasks 2, 3, 4, 8. ✓
- Slim `DprSummaryResponse` with aggregates, no child hydration, SC native query dropped from list path → Tasks 1, 2, 3 (`listPaged` never calls the native SC enrichment). ✓
- `cumulativeQty` excluded from slim payload, sourced from detail GET → Task 1 (DTO omits it), Task 7 (`detail.cumulativeQty`). ✓
- `GET /dpr/{id}` unchanged as detail source → untouched; consumed in Task 7. ✓
- Caller check before changing list shape → done during planning: prod caller = controller only; frontend callers = `dpr/page.tsx` (Task 8) + `SupervisorDbsTab` (Task 5). ✓
- `useInfiniteQuery` + flatten → Task 8. ✓
- Slim types + aggregate reads → Tasks 5, 7. ✓
- Lazy detail on expand → Task 7. ✓
- Memoized grouping → Task 6. ✓
- "Virtualize" / smooth render → Task 6 via `content-visibility` (documented deviation from react-virtual; rationale in header). ✓
- `React.memo` on `DprActivityGroup`/`DprWorkFrontRow` → Tasks 6, 7. ✓
- Verification on Khasab → Task 9. ✓
- Open item "exact rows-for-dates query" → resolved: `findByProjectIdAndReportDateInOrderByReportDateDescIdAsc` (`IN (:dates)`). ✓

**2. Placeholder scan** — No TBD/TODO/"handle edge cases"; every code step shows complete code. ✓

**3. Type consistency**
- Backend: `listPaged(projectId, from, to, activityName, before, days)` defined Task 3, called identically in controller Task 4 and tests Task 3. Repo methods `findDistinctReportDatesDesc`, `findByProjectIdAndReportDateInOrderByReportDateDescIdAsc`, `sumNosByDprIdIn`, `countByDprIdIn`, `findStatusSeverityByDprIdIn` defined Task 2, used Task 3. ✓
- Frontend: `DprSummaryRow`/`DprPage` defined Task 5; `dprApi.list` returns `ApiResponse<DprPage>` Task 5; consumed via `.data?.items`/`.data?.hasMore`/`.data?.nextCursor` in Task 8 and `SupervisorDbsTab` Task 5. `DprDayList`/`DprActivityGroup`/`DprWorkFrontRow` props all take `DprSummaryRow` + `projectId` (Tasks 6, 7); `onEdit`/`onDelete` typed `(row: DprSummaryRow) => void` consistently across Tasks 6, 7, 8. `groupByDayThenActivity` + `ActivityGroup`/`DayGroup` exported from one module (Task 6), imported by `DprDayList` and `DprActivityGroup`. ✓

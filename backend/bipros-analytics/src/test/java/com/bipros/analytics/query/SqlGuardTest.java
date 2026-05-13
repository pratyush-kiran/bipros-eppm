package com.bipros.analytics.query;

import com.bipros.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

  private SqlGuard guard;
  private String scopedA;
  private String scopedB;
  private String outOfScope;

  @BeforeEach
  void setUp() {
    guard = new SqlGuard();
    scopedA = UUID.randomUUID().toString();
    scopedB = UUID.randomUUID().toString();
    outOfScope = UUID.randomUUID().toString();
  }

  private void assertOutOfScope(String sql, List<String> scope) {
    assertThatThrownBy(() -> guard.validate(sql, scope))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            ex -> assertThat(ex.getRuleCode()).isEqualTo("SQL_PROJECT_OUT_OF_SCOPE"));
  }

  private void assertRule(String sql, List<String> scope, String expectedRuleCode) {
    assertThatThrownBy(() -> guard.validate(sql, scope))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            ex -> assertThat(ex.getRuleCode()).isEqualTo(expectedRuleCode));
  }

  @Test
  void acceptsEqualsWithInScopeProjectId() {
    String sql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs WHERE project_id = '"
            + scopedA
            + "' LIMIT 10";
    guard.validate(sql, List.of(scopedA, scopedB));
  }

  @Test
  void acceptsDprIssuesFactTable() {
    String sql =
        "SELECT category, count() c FROM bipros_analytics.fact_dpr_issues_daily FINAL "
            + "WHERE project_id = '"
            + scopedA
            + "' GROUP BY category LIMIT 100";
    guard.validate(sql, List.of(scopedA, scopedB));
  }

  @Test
  void rejectsEqualsWithOutOfScopeProjectId() {
    String sql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs WHERE project_id = '"
            + outOfScope
            + "' LIMIT 10";
    assertOutOfScope(sql, List.of(scopedA, scopedB));
  }

  @Test
  void acceptsInListWithAllInScope() {
    String sql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs WHERE project_id IN ('"
            + scopedA
            + "', '"
            + scopedB
            + "') LIMIT 10";
    guard.validate(sql, List.of(scopedA, scopedB));
  }

  @Test
  void rejectsInListWithAnyOutOfScope() {
    String sql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs WHERE project_id IN ('"
            + scopedA
            + "', '"
            + outOfScope
            + "') LIMIT 10";
    assertOutOfScope(sql, List.of(scopedA, scopedB));
  }

  @Test
  void acceptsQualifiedProjectIdColumn() {
    String sql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs d "
            + "WHERE d.project_id = '"
            + scopedA
            + "' LIMIT 10";
    guard.validate(sql, List.of(scopedA, scopedB));
  }

  @Test
  void rejectsQualifiedOutOfScope() {
    String sql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs d "
            + "WHERE d.project_id = '"
            + outOfScope
            + "' LIMIT 10";
    assertOutOfScope(sql, List.of(scopedA, scopedB));
  }

  @Test
  void allowsColumnToColumnInJoinOn() {
    String sql =
        "SELECT a.code FROM bipros_analytics.fact_dpr_logs f "
            + "LEFT JOIN bipros_analytics.dim_activity a "
            + "  ON f.project_id = a.project_id AND f.activity_id = a.activity_id "
            + "WHERE f.project_id = '"
            + scopedA
            + "' LIMIT 10";
    guard.validate(sql, List.of(scopedA, scopedB));
  }

  @Test
  void rejectsTablesOutsideAllowList() {
    String sql = "SELECT * FROM secret.classified_table WHERE project_id = '" + scopedA + "'";
    assertRule(sql, List.of(scopedA), "SQL_TABLE_NOT_ALLOWED");
  }

  @Test
  void rejectsMissingProjectIdFilter() {
    String sql = "SELECT * FROM bipros_analytics.dim_resource LIMIT 10";
    assertRule(sql, List.of(scopedA), "SQL_MISSING_PROJECT_FILTER");
  }

  @Test
  void singleProjectScopeLocksToThatProject() {
    String wrongSql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs WHERE project_id = '" + scopedB + "'";
    assertOutOfScope(wrongSql, List.of(scopedA));

    String rightSql =
        "SELECT * FROM bipros_analytics.fact_dpr_logs WHERE project_id = '" + scopedA + "'";
    guard.validate(rightSql, List.of(scopedA));
  }

  @Test
  void rejectingOltpDprIssuesNamesTheJpaTool() {
    String sql =
        "SELECT activity_name, count() FROM project.dpr_issues "
            + "WHERE project_id = '" + scopedA + "' GROUP BY activity_name";
    assertThatThrownBy(() -> guard.validate(sql, List.of(scopedA)))
        .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
          assertThat(ex.getRuleCode()).isEqualTo("SQL_TABLE_NOT_ALLOWED");
          assertThat(ex.getMessage()).contains("list_issues");
          assertThat(ex.getMessage()).contains("activity_health_snapshot");
        });
  }

  @Test
  void rejectingOltpDprNamesTheJpaTool() {
    String sql =
        "SELECT count() FROM project.daily_progress_reports WHERE project_id = '" + scopedA + "'";
    assertThatThrownBy(() -> guard.validate(sql, List.of(scopedA)))
        .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
          assertThat(ex.getRuleCode()).isEqualTo("SQL_TABLE_NOT_ALLOWED");
          assertThat(ex.getMessage()).contains("query_dpr");
        });
  }

  @Test
  void rejectingGenericOltpSchemaSuggestsJpaTools() {
    String sql =
        "SELECT * FROM cost.cost_accounts WHERE project_id = '" + scopedA + "'";
    assertThatThrownBy(() -> guard.validate(sql, List.of(scopedA)))
        .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
          assertThat(ex.getRuleCode()).isEqualTo("SQL_TABLE_NOT_ALLOWED");
          assertThat(ex.getMessage()).contains("OLTP");
        });
  }

  /**
   * Portfolio-mode hygiene: a non-admin with N≥10 scoped projects must be able to run
   * an IN(...) filter listing all of them. This protects against any future regression
   * that imposes a smaller IN-list cap.
   */
  @Test
  void acceptsTenProjectInList() {
    int n = 10;
    List<String> scope = new java.util.ArrayList<>(n);
    StringBuilder inList = new StringBuilder();
    for (int i = 0; i < n; i++) {
      String id = UUID.randomUUID().toString();
      scope.add(id);
      if (i > 0) inList.append(", ");
      inList.append("'").append(id).append("'");
    }
    String sql =
        "SELECT count() FROM bipros_analytics.fact_dpr_logs "
            + "WHERE project_id IN (" + inList + ") LIMIT 10";
    guard.validate(sql, scope);
  }
}

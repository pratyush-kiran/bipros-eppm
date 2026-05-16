package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.ConcretePour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConcretePourRepository extends JpaRepository<ConcretePour, UUID> {

  List<ConcretePour> findByProjectIdOrderByPourDateAscIdAsc(UUID projectId);

  boolean existsByProjectId(UUID projectId);

  List<ConcretePour> findByProjectIdAndPourDateBetween(UUID projectId, LocalDate from, LocalDate to);

  List<ConcretePour> findByProjectIdAndSite(UUID projectId, String site);

  @Query("SELECT SUM(c.quantityM3) FROM ConcretePour c "
      + "WHERE c.projectId = :pid AND c.pourDate BETWEEN :from AND :to")
  BigDecimal sumQuantityBetween(
      @Param("pid") UUID projectId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("SELECT c.gradeCode, SUM(c.quantityM3) FROM ConcretePour c "
      + "WHERE c.projectId = :pid GROUP BY c.gradeCode")
  List<Object[]> sumByGrade(@Param("pid") UUID projectId);

  @Query("SELECT c.site, SUM(c.quantityM3) FROM ConcretePour c "
      + "WHERE c.projectId = :pid GROUP BY c.site")
  List<Object[]> sumBySite(@Param("pid") UUID projectId);
}

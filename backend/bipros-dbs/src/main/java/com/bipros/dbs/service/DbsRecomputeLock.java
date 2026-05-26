package com.bipros.dbs.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Serialises DBS recompute of a single {@code (project, date)} across the parallel
 * AFTER_COMMIT recompute threads fired by {@code DbsRecomputeListener}.
 *
 * <p>Without serialisation, two DPRs committed concurrently for the same {@code (project, date)}
 * race on the shared rollup rows: a first-time INSERT race trips the {@code (project, date)}
 * unique key, a concurrent UPDATE race trips optimistic locking, and the register delete-insert
 * races the {@code uk_dbs_*_register} unique keys. The unique keys + listener retry recover
 * from these, but Hibernate still logs the collisions at ERROR. Taking a Postgres
 * <em>transaction-scoped</em> advisory lock keyed on {@code (project, date)} at the top of each
 * recompute serialises the threads outright, so the races never occur and the logs stay clean.
 *
 * <p>The lock is held until the current transaction commits/rolls back (each {@code recomputeXxxDay}
 * is {@code REQUIRES_NEW}, so it is its own short-lived transaction). It must therefore be called
 * from within an active transaction, and must NOT be taken inside
 * {@code RegisterAggregationService.recompute()}: that runs {@code REQUIRES_NEW} from within an
 * already-lock-holding {@code recomputeProjectDay}, so a second acquire on its own connection would
 * self-deadlock. The register is already serialised transitively — it is only ever reached by the
 * single thread currently inside the lock-protected {@code recomputeProjectDay(project, date)}.
 */
@Slf4j
@Component
public class DbsRecomputeLock {

    @PersistenceContext
    private EntityManager em;

    /**
     * Acquire a transaction-scoped advisory lock for {@code (projectId, date)}. Blocks until any
     * other transaction holding the same key commits. Hash collisions across distinct keys only
     * cause occasional extra serialisation — never incorrect results.
     */
    public void lock(UUID projectId, LocalDate date) {
        int k1 = projectId.hashCode();
        int k2 = (int) date.toEpochDay();
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k1, :k2)")
            .setParameter("k1", k1)
            .setParameter("k2", k2)
            .getResultList();
    }
}

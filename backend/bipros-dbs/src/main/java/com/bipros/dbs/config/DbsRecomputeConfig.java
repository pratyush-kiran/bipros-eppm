package com.bipros.dbs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor configuration for the DBS background recompute feature.
 *
 * <p>The {@code dbsRecomputeDayExecutor} is a bounded pool (default size 4, configurable
 * via {@code bipros.dbs.recomputeConcurrency}) that backs the per-day
 * {@link java.util.concurrent.CompletableFuture}s submitted by
 * {@link com.bipros.dbs.service.recompute.DbsRecomputeRunner}. The pool size is kept below
 * the Hikari max (10) so day workers always get a DB connection. The queue is large enough
 * to hold all days for a multi-year project.
 *
 * <p>The orchestrator thread ({@code @Async} on {@code DbsRecomputeRunner.runAsync}) uses
 * Spring's default async executor — it does not consume a slot from this pool.
 */
@Configuration
public class DbsRecomputeConfig {

    @Bean("dbsRecomputeDayExecutor")
    public ThreadPoolTaskExecutor dbsRecomputeDayExecutor(DbsProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(props.getRecomputeConcurrency());
        ex.setMaxPoolSize(props.getRecomputeConcurrency());
        ex.setQueueCapacity(10_000);
        ex.setThreadNamePrefix("dbs-recompute-");
        ex.initialize();
        return ex;
    }
}

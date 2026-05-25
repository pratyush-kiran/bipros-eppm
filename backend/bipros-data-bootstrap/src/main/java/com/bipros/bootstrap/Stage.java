package com.bipros.bootstrap;

/**
 * Contract for every bootstrap stage. Each StageN bean implements this and is
 * dispatched by {@link BootstrapApplication#runStage(Class, String[])}.
 *
 * <p>Stages must be idempotent: a second invocation should be a no-op or an
 * upsert, never a duplicate insert.
 */
public interface Stage {
    void run();
}

package com.bipros.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * One Spring Boot context shared by every stage. Each StageN class still
 * has its own static {@code main(...)} so the user can right-click → Run
 * in their IDE; that main calls {@link #runStage(Class, String[])}, which
 * boots this application, looks the stage bean up by class, runs it, and
 * shuts the context down.
 *
 * <p>Scanning {@code com.bipros} so every domain module's repositories,
 * services, and entities are picked up. JPA repositories live in many
 * sub-packages; we enable them with broad base packages.
 */
@SpringBootApplication(scanBasePackages = "com.bipros")
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = "com.bipros")
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = "com.bipros")
// Audited entities (createdAt / updatedAt / createdBy / updatedBy via BaseEntity)
// rely on Spring Data's auditing listener — without this annotation those columns
// are left null and inserts fail the NOT NULL constraint.
@org.springframework.data.jpa.repository.config.EnableJpaAuditing
public class BootstrapApplication {

    public static <S extends Stage> void runStage(Class<S> stageClass, String[] args) {
        long start = System.currentTimeMillis();
        System.out.println("=== bipros-data-bootstrap :: " + stageClass.getSimpleName() + " ===");
        try (ConfigurableApplicationContext ctx = SpringApplication.run(BootstrapApplication.class, args)) {
            S stage = ctx.getBean(stageClass);
            stage.run();
        } catch (Throwable t) {
            System.err.println("Stage failed: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
        long elapsedMs = System.currentTimeMillis() - start;
        System.out.println("=== Done in " + (elapsedMs / 1000.0) + "s ===");
    }

    /** Used by RunAll to chain stages without restarting the Spring context. */
    public static void runStagesInSingleContext(String[] args, Class<? extends Stage>... stages) {
        long start = System.currentTimeMillis();
        try (ConfigurableApplicationContext ctx = SpringApplication.run(BootstrapApplication.class, args)) {
            for (Class<? extends Stage> stageClass : stages) {
                System.out.println("--- Running " + stageClass.getSimpleName() + " ---");
                ctx.getBean(stageClass).run();
            }
        } catch (Throwable t) {
            System.err.println("Pipeline failed: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
        System.out.println("=== Pipeline done in " + ((System.currentTimeMillis() - start) / 1000.0) + "s ===");
    }
}

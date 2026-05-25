package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;

/**
 * Convenience driver: runs stages 1 → 12 in order in a single Spring context.
 *
 * <p>The fixture {@code bootstrap-data.json} is loaded from the classpath, so
 * there is no Stage 0 anymore. To re-extract the fixture from the source
 * workbook, run {@code scripts/extract.py}.
 *
 * <p>Resume from a specific stage with {@code --from=N} (default N=1).
 * Example: {@code --from=2} skips cleanup and starts at resource roles.
 */
public class RunAll {

    private static final Class<? extends com.bipros.bootstrap.Stage>[] STAGES;

    static {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends com.bipros.bootstrap.Stage>[] arr =
                (Class<? extends com.bipros.bootstrap.Stage>[]) new Class[] {
                        Stage1Cleanup.class,
                        Stage2ResourceRoles.class,
                        Stage3WorkActivities.class,
                        Stage4ProductivityNorms.class,
                        Stage5Project.class,
                        Stage6Wbs.class,
                        Stage7Activities.class,
                        Stage8ActivitySupervisors.class,
                        Stage9ResourcePlan.class,
                        Stage10LockActivities.class,
                        Stage11BoqItems.class,
                        Stage12Dprs.class
                };
        STAGES = arr;
    }

    public static void main(String[] args) {
        int from = Math.max(1, parseFrom(args));
        int offset = from - 1;
        if (offset >= STAGES.length) {
            System.out.println("Nothing to run — --from=" + from + " exceeds last stage (12).");
            return;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends com.bipros.bootstrap.Stage>[] slice =
                (Class<? extends com.bipros.bootstrap.Stage>[]) new Class[STAGES.length - offset];
        System.arraycopy(STAGES, offset, slice, 0, slice.length);
        BootstrapApplication.runStagesInSingleContext(args, slice);
    }

    private static int parseFrom(String[] args) {
        for (String a : args) {
            if (a != null && a.startsWith("--from=")) {
                try {
                    return Integer.parseInt(a.substring("--from=".length()));
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return 1;
    }
}

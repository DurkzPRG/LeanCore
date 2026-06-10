package com.durkz.leancore.runtime;

/**
 * Marks the current thread as executing {@code MemoryRuntime.tickGovernor} on a world thread.
 * Nested {@code world.execute} calls from this context are replaced with inline runs so tasks
 * cannot outlive shutdown.
 */
public final class GovernorWorldContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private GovernorWorldContext() {
    }

    public static void enter() {
        ACTIVE.set(true);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}

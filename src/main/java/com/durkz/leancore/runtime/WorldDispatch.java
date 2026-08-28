package com.durkz.leancore.runtime;

import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorldDispatch {

    private static final long TIMEOUT_SEC = 3L;

    private WorldDispatch() {
    }

    /**
     * Runs {@code task} on the world thread, blocking up to {@link #TIMEOUT_SEC}. Returns true only if
     * it completed in time. A timed-out task is not cancelled and may still run, so do not trust state
     * it mutates unless this returned true.
     */
    public static boolean run(World world, Runnable task) {
        if (world == null || !world.isAlive() || task == null || !RuntimeGuard.active()) {
            return false;
        }
        if (shouldRunInline(world)) {
            task.run();
            return true;
        }
        DispatchTask done = new DispatchTask(task);
        try {
            world.execute(done);
        } catch (RuntimeException ignored) {
            return false;
        }
        try {
            done.get(TIMEOUT_SEC, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException timeout) {
            DiagnosticLog.infoOnChange("worlddispatch-timeout",
                    "world task exceeded " + TIMEOUT_SEC + "s; its result is skipped this tick");
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            DiagnosticLog.infoOnChange("worlddispatch-error",
                    "world task failed: " + ex.getClass().getSimpleName());
            return false;
        }
    }

    static boolean shouldRunInline(World world) {
        if (world != null && world.isInThread()) {
            return true;
        }
        return GovernorWorldContext.matchesWorld(world);
    }

    /** One queued object replaces the per-dispatch CompletableFuture and wrapper lambda. */
    private static final class DispatchTask extends CompletableFuture<Void> implements Runnable {

        private final Runnable task;

        private DispatchTask(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                if (RuntimeGuard.active()) {
                    task.run();
                }
            } finally {
                complete(null);
            }
        }
    }
}

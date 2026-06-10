package com.durkz.leancore.runtime;

import com.hypixel.hytale.server.core.universe.world.World;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorldDispatch {

    private static final long TIMEOUT_SEC = 3L;

    private WorldDispatch() {
    }

    public static void run(World world, Runnable task) {
        if (world == null || !world.isAlive() || task == null || !RuntimeGuard.active()) {
            return;
        }
        if (shouldRunInline(world)) {
            task.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    if (RuntimeGuard.active()) {
                        task.run();
                    }
                } finally {
                    done.complete(null);
                }
            });
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            done.get(TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
            done.complete(null);
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldRunInline(World world) {
        return GovernorWorldContext.isActive() || world.isInThread();
    }
}

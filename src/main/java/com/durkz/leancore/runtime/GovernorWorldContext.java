package com.durkz.leancore.runtime;

import com.hypixel.hytale.server.core.universe.world.World;

import java.util.UUID;

/**
 * Marks governor work tied to a specific world on the current thread.
 * Nested {@code WorldDispatch.run} may inline only for that world.
 */
public final class GovernorWorldContext {

    private static final ThreadLocal<UUID> ACTIVE_WORLD = new ThreadLocal<>();

    private GovernorWorldContext() {
    }

    public static void enter(UUID worldUuid) {
        ACTIVE_WORLD.set(worldUuid);
    }

    public static void exit() {
        ACTIVE_WORLD.remove();
    }

    public static boolean isActive() {
        return ACTIVE_WORLD.get() != null;
    }

    public static boolean matchesWorld(World world) {
        if (world == null) {
            return false;
        }
        UUID active = ACTIVE_WORLD.get();
        if (active == null) {
            return false;
        }
        return active.equals(world.getWorldConfig().getUuid());
    }
}

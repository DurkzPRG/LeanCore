package com.durkz.leancore.probe;

import com.durkz.leancore.runtime.WorldDispatch;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Collection;
import java.util.Locale;
/**
 * Throttled S4 regional entity pressure for bandit context (not probe-only).
 * Must run on the world thread — governor ticks dispatch here via {@code world.execute}.
 */
public final class RegionalPressureCache {

    private static final double ENTITY_NORM = 128.0D;

    private volatile double lastPressure;
    private volatile int lastRegionalEntities;
    private volatile long lastSampleMs;

    public double pressure() {
        return lastPressure;
    }

    public int lastRegionalEntities() {
        return lastRegionalEntities;
    }

    public long lastSampleMs() {
        return lastSampleMs;
    }

    public void maybeSample(Collection<PlayerRef> online, int intervalSeconds, long nowMs) {
        int intervalMs = Math.max(15, intervalSeconds) * 1000;
        if (lastSampleMs > 0L && nowMs - lastSampleMs < intervalMs) {
            return;
        }

        RegionalEntityProbe.RegionalEntitySample best = null;
        for (PlayerRef ref : online) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            World world = resolveWorld(ref);
            if (world == null) {
                continue;
            }
            // RegionalEntityProbe reads the world's chunk store, so run it on the world thread and
            // skip this player on a timed-out dispatch.
            RegionalEntityProbe.RegionalEntitySample[] holder = new RegionalEntityProbe.RegionalEntitySample[1];
            boolean done = WorldDispatch.run(world, () -> holder[0] = RegionalEntityProbe.read(ref, world));
            if (!done) {
                continue;
            }
            RegionalEntityProbe.RegionalEntitySample sample = holder[0];
            if (sample == null || sample.zone() == null) {
                continue;
            }
            if (best == null || sample.regionalEntities() > best.regionalEntities()) {
                best = sample;
            }
        }

        if (best == null) {
            lastPressure = 0.0D;
            lastRegionalEntities = 0;
        } else {
            lastRegionalEntities = best.regionalEntities();
            lastPressure = Math.min(1.0D, best.regionalEntities() / ENTITY_NORM);
        }
        lastSampleMs = nowMs;
    }

    public String statusLine() {
        if (lastSampleMs <= 0L) {
            return "regionalPressure=pending";
        }
        return String.format(Locale.ROOT,
                "regionalPressure=%.2f entities=%d",
                lastPressure,
                lastRegionalEntities);
    }

    private static World resolveWorld(PlayerRef ref) {
        if (ref.getWorldUuid() == null) {
            return null;
        }
        World world = Universe.get().getWorld(ref.getWorldUuid());
        if (world == null || !world.isAlive()) {
            return null;
        }
        return world;
    }
}

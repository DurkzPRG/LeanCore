package com.durkz.leancore.runtime;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;

/**
 * Whether QuantumHy is loaded. When it is, QuantumHy owns the per-player chunk send rate (its
 * anti-stutter smoothing) unless {@code chunkThroughputGovernanceEnabled} hands it to LeanCore, so
 * LeanCore's pressure brake stays out. Cached for 10s; plugins can load after LeanCore.
 */
public final class QuantumHyPresence {

    private static final PluginIdentifier ID = new PluginIdentifier("durkz", "QuantumHy");
    private static final long CACHE_NANOS = 10_000_000_000L;

    private static volatile boolean present;
    private static volatile long checkedAtNanos;

    private QuantumHyPresence() {
    }

    public static boolean present() {
        long now = System.nanoTime();
        long checkedAt = checkedAtNanos;
        if (checkedAt != 0L && now - checkedAt < CACHE_NANOS) {
            return present;
        }
        boolean found;
        try {
            PluginManager manager = PluginManager.get();
            found = manager != null && manager.getPlugin(ID) != null;
        } catch (RuntimeException | LinkageError e) {
            found = false;
        }
        present = found;
        checkedAtNanos = now == 0L ? 1L : now;
        return found;
    }
}

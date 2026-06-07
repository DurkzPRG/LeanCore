package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.session.SessionMode;

import java.util.Map;
import java.util.UUID;

public class RetentionAllocator {

    private static final int MB_PER_DORMANT_ZONE = 6;

    private final LeanCoreConfig config;

    private int lastFootprintMb;
    private int lastBudgetMb;
    private int lastDemotedZones;
    private int lastReclaimedMb;

    public RetentionAllocator(LeanCoreConfig config) {
        this.config = config;
    }

    public int reconcile(
            GovernorPreset preset,
            SessionMode mode,
            MemorySnapshot sample,
            Map<UUID, PlayerBehavior> behaviors,
            ZoneDormancyMap dormancyMap
    ) {
        lastFootprintMb = sumFootprintMb(preset, behaviors);
        lastBudgetMb = resolveBudgetMb(mode, sample);
        lastDemotedZones = 0;
        lastReclaimedMb = 0;

        if (lastBudgetMb <= 0 || lastFootprintMb <= lastBudgetMb) {
            return 0;
        }

        int overflowMb = lastFootprintMb - lastBudgetMb;
        int zonesNeeded = (int) Math.ceil((double) overflowMb / MB_PER_DORMANT_ZONE);
        lastDemotedZones = dormancyMap.demoteFarthestDormant(zonesNeeded);
        lastReclaimedMb = lastDemotedZones * MB_PER_DORMANT_ZONE;
        return lastDemotedZones;
    }

    public int lastFootprintMb() {
        return lastFootprintMb;
    }

    public int lastBudgetMb() {
        return lastBudgetMb;
    }

    public int lastDemotedZones() {
        return lastDemotedZones;
    }

    public int lastReclaimedMb() {
        return lastReclaimedMb;
    }

    private int sumFootprintMb(GovernorPreset preset, Map<UUID, PlayerBehavior> behaviors) {
        int total = 0;
        for (PlayerBehavior behavior : behaviors.values()) {
            total += footprintFor(behavior, preset);
        }
        return total;
    }

    private static int footprintFor(PlayerBehavior behavior, GovernorPreset preset) {
        int base = switch (behavior) {
            case EXPLORER -> 48;
            case BUILDER -> 64;
            case FIGHTER -> 56;
            case AFK -> 12;
            case SOCIAL -> 32;
            case UNKNOWN -> 40;
        };
        return Math.max(8, (int) Math.round(base * preset.footprintScale()));
    }

    private int resolveBudgetMb(SessionMode mode, MemorySnapshot sample) {
        if (config.memoryBudgetMb > 0) {
            return config.memoryBudgetMb;
        }
        long heapMb = sample.heapMaxBytes() / (1024 * 1024);
        double share = switch (mode) {
            case SOLO -> 0.12D;
            case FRIENDS -> 0.15D;
            case SERVER -> 0.18D;
        };
        return Math.max(32, (int) Math.round(heapMb * share));
    }
}

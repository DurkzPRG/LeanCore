package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.RetentionDemand;
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
            Map<UUID, RetentionDemand> demands,
            ZoneDormancyMap dormancyMap
    ) {
        lastFootprintMb = sumFootprintMb(preset, demands);
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

    private int sumFootprintMb(GovernorPreset preset, Map<UUID, RetentionDemand> demands) {
        int total = 0;
        for (RetentionDemand demand : demands.values()) {
            total += footprintFor(demand, preset);
        }
        return total;
    }

    private static int footprintFor(RetentionDemand demand, GovernorPreset preset) {
        int scaled = (int) Math.round(demand.retentionMb() * preset.footprintScale());
        return Math.max(RetentionDemand.MIN_MB, scaled);
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

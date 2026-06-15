package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneDormancyMapTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final ZoneKey ZONE_A = new ZoneKey(WORLD, 1, 2);
    private static final ZoneKey ZONE_B = new ZoneKey(WORLD, 3, 4);

    @Test
    void demotesIdleZoneWhenPlayerLeaves() {
        ZoneDormancyMap map = new ZoneDormancyMap(new LeanCoreConfig());
        long now = 1_000_000L;

        map.refreshFromPlayerZones(List.of(ZONE_A), now);
        assertEquals(ZoneState.HOT, map.stateOf(ZONE_A));

        map.refreshFromPlayerZones(List.of(), now + 9 * 60_000L);
        assertEquals(ZoneState.DORMANT, map.stateOf(ZONE_A));
    }

    @Test
    void keepsPinnedZoneHotWithoutPlayer() {
        LeanCoreConfig config = new LeanCoreConfig();
        ZoneDormancyMap map = new ZoneDormancyMap(config);
        long now = 2_000_000L;

        map.pinZone(ZONE_B);
        map.refreshFromPlayerZones(List.of(), now + 30 * 60_000L);

        assertEquals(ZoneState.HOT, map.stateOf(ZONE_B));
        assertTrue(map.isPinned(ZONE_B));
    }

    @Test
    void resolvesIdleStatesFromConfigThresholds() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.dormantAfterMinutes = 8;
        config.frozenAfterMinutes = 20;
        ZoneDormancyMap map = new ZoneDormancyMap(config);

        assertEquals(ZoneState.WARM, map.idleStateForMinutes(3));
        assertEquals(ZoneState.DORMANT, map.idleStateForMinutes(10));
        assertEquals(ZoneState.FROZEN, map.idleStateForMinutes(25));
    }

    @Test
    void liteUnloadIncludesDormantAtWatch() {
        assertTrue(ZoneDormancyMap.qualifiesForUnload(ZoneState.FROZEN, MemoryTier.COMFORT, MemoryTier.WATCH));
        assertTrue(ZoneDormancyMap.qualifiesForUnload(ZoneState.DORMANT, MemoryTier.WATCH, MemoryTier.WATCH));
        assertFalse(ZoneDormancyMap.qualifiesForUnload(ZoneState.DORMANT, MemoryTier.COMFORT, MemoryTier.WATCH));
    }

    @Test
    void standardUnloadRequiresTightForDormant() {
        assertFalse(ZoneDormancyMap.qualifiesForUnload(ZoneState.DORMANT, MemoryTier.WATCH, MemoryTier.TIGHT));
        assertTrue(ZoneDormancyMap.qualifiesForUnload(ZoneState.DORMANT, MemoryTier.TIGHT, MemoryTier.TIGHT));
    }
}

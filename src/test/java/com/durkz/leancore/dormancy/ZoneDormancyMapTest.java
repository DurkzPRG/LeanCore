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

    @Test
    void predictedPositionBiasesUnloadTowardZonesBehind() {
        ZoneKey ahead = new ZoneKey(WORLD, 2, 0);
        ZoneKey behind = new ZoneKey(WORLD, -2, 0);

        // Player at origin: the zone behind is physically closer than the zone ahead.
        List<ZoneDormancyMap.PlayerPos> current = List.of(new ZoneDormancyMap.PlayerPos(WORLD, 0.0D, 0.0D));
        assertTrue(ZoneDormancyMap.minDistanceToPlayers(behind, current)
                < ZoneDormancyMap.minDistanceToPlayers(ahead, current));

        // With the predicted position shifted forward (+x), the zone behind becomes the farthest,
        // so it ranks first for unload while the zone ahead is protected.
        List<ZoneDormancyMap.PlayerPos> predicted = List.of(new ZoneDormancyMap.PlayerPos(WORLD, 200.0D, 0.0D));
        assertTrue(ZoneDormancyMap.minDistanceToPlayers(behind, predicted)
                > ZoneDormancyMap.minDistanceToPlayers(ahead, predicted));
    }

    @Test
    void distanceIgnoresPlayersInOtherWorlds() {
        UUID worldB = UUID.randomUUID();
        ZoneKey zoneInA = new ZoneKey(WORLD, 0, 0);

        // Player sitting on the zone center but in a different world must not protect it.
        List<ZoneDormancyMap.PlayerPos> otherWorld =
                List.of(new ZoneDormancyMap.PlayerPos(worldB, 0.0D, 0.0D));
        assertEquals(Double.MAX_VALUE, ZoneDormancyMap.minDistanceToPlayers(zoneInA, otherWorld));

        // Same coords, same world -> finite distance (protected).
        List<ZoneDormancyMap.PlayerPos> sameWorld =
                List.of(new ZoneDormancyMap.PlayerPos(WORLD, 0.0D, 0.0D));
        assertTrue(ZoneDormancyMap.minDistanceToPlayers(zoneInA, sameWorld) < Double.MAX_VALUE);
    }

    @Test
    void emptyWorldZoneEvictsBeforeOccupiedWorldZone() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.zoneReuseModelEnabled = false;
        ZoneDormancyMap map = new ZoneDormancyMap(config);

        UUID emptyWorld = UUID.randomUUID();
        ZoneKey occupiedWorldZone = new ZoneKey(WORLD, 10, 0);
        ZoneKey emptyWorldZone = new ZoneKey(emptyWorld, 0, 0);

        // Only WORLD has a player online; emptyWorld has nobody.
        List<ZoneDormancyMap.PlayerPos> players =
                List.of(new ZoneDormancyMap.PlayerPos(WORLD, 160.0D, 0.0D));

        assertTrue(map.evictionPriority(emptyWorldZone, players, 1_000L)
                        > map.evictionPriority(occupiedWorldZone, players, 1_000L),
                "a zone in a world with no players online ranks first for eviction");
    }

    @Test
    void reuseRankingProtectsFrequentlyRevisitedZone() {
        LeanCoreConfig config = new LeanCoreConfig();
        ZoneDormancyMap map = new ZoneDormancyMap(config);
        ZoneReuseModel model = new ZoneReuseModel();
        map.setZoneReuseModel(model);

        ZoneKey frequentFar = new ZoneKey(WORLD, 5, 0);
        ZoneKey rareNear = new ZoneKey(WORLD, 4, 0);
        long t = 0L;
        for (int i = 0; i < 26; i++) {
            model.noteHot(frequentFar, t);
            t += 30_000L;
        }
        long evalTime = t - 30_000L;
        model.noteHot(rareNear, evalTime);

        List<ZoneDormancyMap.PlayerPos> players = List.of(new ZoneDormancyMap.PlayerPos(WORLD, 0.0D, 0.0D));

        config.zoneReuseModelEnabled = false;
        assertTrue(map.evictionPriority(frequentFar, players, evalTime)
                        > map.evictionPriority(rareNear, players, evalTime),
                "with reuse off, the farther zone evicts first");

        config.zoneReuseModelEnabled = true;
        config.zoneReuseRankWeight = 0.5D;
        assertTrue(map.evictionPriority(rareNear, players, evalTime)
                        > map.evictionPriority(frequentFar, players, evalTime),
                "with reuse on, the frequently revisited far zone is protected");
    }

    @Test
    void adaptiveThresholdExtendsForFrequentZone() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.zoneReuseModelEnabled = true;
        ZoneDormancyMap map = new ZoneDormancyMap(config);
        ZoneReuseModel model = new ZoneReuseModel();
        map.setZoneReuseModel(model);

        ZoneKey frequent = new ZoneKey(WORLD, 1, 1);
        long t = 0L;
        for (int i = 0; i < 25; i++) {
            model.noteHot(frequent, t);
            t += 30_000L;
        }
        assertTrue(map.thresholdScale(frequent) > 1.0D, "frequent zone gets longer thresholds");
        assertEquals(1.0D, map.thresholdScale(new ZoneKey(WORLD, 9, 9)), 1e-9, "unseen zone stays neutral");
    }

    @Test
    void frequentZoneResistsDormancy() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.zoneReuseModelEnabled = true;
        config.dormantAfterMinutes = 8;
        config.frozenAfterMinutes = 20;
        ZoneDormancyMap map = new ZoneDormancyMap(config);
        ZoneReuseModel model = new ZoneReuseModel();
        map.setZoneReuseModel(model);

        ZoneKey frequent = new ZoneKey(WORLD, 7, 7);
        long t = 0L;
        for (int i = 0; i < 25; i++) {
            map.refreshFromPlayerZones(List.of(frequent), t);
            t += 30_000L;
            map.refreshFromPlayerZones(List.of(), t);
            t += 30_000L;
        }

        // 10 minutes idle: base config would demote to DORMANT, but the scaled threshold keeps it WARM.
        map.refreshFromPlayerZones(List.of(), t + 10 * 60_000L);
        assertEquals(ZoneState.WARM, map.stateOf(frequent));
    }
}

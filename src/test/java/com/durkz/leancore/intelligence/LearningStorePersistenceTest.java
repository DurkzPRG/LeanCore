package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningStorePersistenceTest {

    @TempDir
    Path dataDir;

    @Test
    void compactRoundTripStaysSmall() throws Exception {
        LeanCoreConfig config = configWithLearning();
        LearningStore store = new LearningStore(dataDir, config);
        UUID playerId = UUID.randomUUID();

        PlayerFeatureState features = new PlayerFeatureState(playerId);
        features.onBlockBroken(new BlockActionContext(
                ActionKind.MINE,
                "pickaxe",
                "tool_pickaxe_copper",
                "ore_copper",
                "ore",
                false
        ));
        store.savePlayerFeatures(features);
        store.noteDemands(Map.of(playerId, RetentionDemand.coldStart(PlayerBehavior.MINER)));
        store.policyBandit().update("SERVER_DENSE:COMFORT", new double[]{
                0.1D, 0.2D, 0.3D, 0.0D, 0.5D, 0.0D, 0.1D
        }, 0.2D);
        store.flush(true, java.util.Set.of(playerId));

        Path gzip = dataDir.resolve(LearningStateCodec.GZ_FILE);
        assertTrue(Files.exists(gzip));
        assertTrue(Files.size(gzip) < 16_384L, "solo snapshot should stay under 16KB, was " + Files.size(gzip));

        LearningStore reloaded = new LearningStore(dataDir, config);
        assertEquals(PlayerBehavior.MINER, reloaded.demandFor(playerId).debugLabel());
        assertTrue(reloaded.policyBandit().armCount() >= 1);
        assertTrue(reloaded.statusLine().contains("learning=v7"));
    }

    @Test
    void migratesLegacyPropertiesAndDeletesOnFlush() throws Exception {
        LeanCoreConfig config = configWithLearning();
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        Properties props = new Properties();
        props.setProperty("schema", "6");
        props.setProperty("playerCount", "1");
        props.setProperty("demand.w.0", "0.12");
        props.setProperty("activity.w.0.0", "0.5");
        props.setProperty("player." + playerId + ".demand", "0.7");
        props.setProperty("player." + playerId + ".confidence", "0.4");
        props.setProperty("player." + playerId + ".retentionMb", "128");
        props.setProperty("player." + playerId + ".label", "MINER");
        props.setProperty("player." + playerId + ".observedSec", "120");
        Path legacy = dataDir.resolve("learning.state");
        try (var out = Files.newOutputStream(legacy)) {
            props.store(out, "legacy");
        }

        LearningStore store = new LearningStore(dataDir, config);
        assertEquals(PlayerBehavior.MINER, store.demandFor(playerId).debugLabel());
        store.flush(true, java.util.Set.of(playerId));

        assertTrue(Files.exists(dataDir.resolve(LearningStateCodec.GZ_FILE)));
        assertFalse(Files.exists(legacy));
        assertTrue(Files.size(dataDir.resolve(LearningStateCodec.GZ_FILE)) < 16_384L);
    }

    @Test
    void prunesStalePlayersBeyondLimit() {
        LeanCoreConfig config = configWithLearning();
        config.learningMaxPersistedPlayers = 2;
        config.learningPlayerTtlDays = 0;
        LearningStore store = new LearningStore(dataDir, config);

        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            PlayerFeatureState state = new PlayerFeatureState(id);
            store.savePlayerFeatures(state);
            store.noteDemands(Map.of(id, RetentionDemand.coldStart(PlayerBehavior.UNKNOWN)));
            sleepQuietly(2L);
        }

        store.flush(true, java.util.Set.of());
        assertTrue(store.statusLine().contains("players=2"));
        assertTrue(store.statusLine().contains("pruned=3"));
    }

    private static LeanCoreConfig configWithLearning() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.learningEnabled = true;
        config.persistIntervalSeconds = 300;
        config.learningMaxPersistedPlayers = 512;
        config.learningPlayerTtlDays = 90;
        return config;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

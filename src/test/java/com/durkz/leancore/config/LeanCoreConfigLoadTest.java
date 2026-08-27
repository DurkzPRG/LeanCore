package com.durkz.leancore.config;

import com.durkz.leancore.runtime.RuntimeActivationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeanCoreConfigLoadTest {

    @Test
    void clampsExtremeViewRadius(@TempDir Path dataDir) {
        LeanCoreConfig config = new LeanCoreConfig();
        config.minClientViewRadius = 80;
        config.maxClientViewRadius = 10_000;
        config.normalizeDefaults();

        assertEquals(64, config.maxClientViewRadius);
        assertEquals(64, config.minClientViewRadius);
    }

    @Test
    void clampsExtremeUnloadSweep() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.unloadMaxChunksPerSweep = 100_000;
        config.normalizeDefaults();

        assertEquals(64, config.unloadMaxChunksPerSweep);
    }

    @Test
    void tunedMotionBoostSurvivesSanitize() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.motionViewRadiusMaxBoost = 1.6D;
        config.normalizeDefaults();

        assertEquals(1.6D, config.motionViewRadiusMaxBoost, 1e-9);
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyLocalHostPassiveModeMigratesToPassive() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.localHostPassiveMode = true;
        config.normalizeDefaults();

        assertEquals(RuntimeActivationPolicy.MODE_PASSIVE, config.localHostMode);
        assertTrue(RuntimeActivationPolicy.isFullyPassive(config));
    }

    @Test
    void invalidJsonFallsBackToDefaults(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("LeanCore.json"), "{not json", StandardCharsets.UTF_8);

        LeanCoreConfig config = LeanCoreConfig.load(dataDir);

        assertTrue(config.enabled);
        assertFalse(config.governEnabled);
        assertTrue(Files.list(dataDir).anyMatch(
                path -> path.getFileName().toString().startsWith("LeanCore.json.corrupt.")));
    }

    @Test
    void remapsColumnEraUnloadHoldDefaultToSectionBacklog(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("LeanCore.json"),
                "{\"unloadHoldWhenLoadingAbove\": 16}", StandardCharsets.UTF_8);

        LeanCoreConfig config = LeanCoreConfig.load(dataDir);

        assertEquals(80, config.unloadHoldWhenLoadingAbove);
        assertTrue(Files.readString(dataDir.resolve("LeanCore.json")).contains("\"unloadHoldWhenLoadingAbove\": 80"));
    }

    @Test
    void preservesCustomUnloadHoldAboveOldDefault(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("LeanCore.json"),
                "{\"unloadHoldWhenLoadingAbove\": 40}", StandardCharsets.UTF_8);

        LeanCoreConfig config = LeanCoreConfig.load(dataDir);

        assertEquals(40, config.unloadHoldWhenLoadingAbove);
    }
}

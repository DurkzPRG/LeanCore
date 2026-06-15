package com.durkz.leancore.config;

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
    void invalidJsonFallsBackToDefaults(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("LeanCore.json"), "{not json", StandardCharsets.UTF_8);

        LeanCoreConfig config = LeanCoreConfig.load(dataDir);

        assertTrue(config.enabled);
        assertFalse(config.governEnabled);
        assertTrue(Files.list(dataDir).anyMatch(
                path -> path.getFileName().toString().startsWith("LeanCore.json.corrupt.")));
    }
}

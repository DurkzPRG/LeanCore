package com.durkz.leancore.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesOnceOnDedicatedHost() {
        LeanCoreConfig config = LeanCoreConfig.load(tempDir);
        config.dedicatedServerMode = true;
        config.dedicatedBootstrapEnabled = true;
        config.dedicatedBootstrapApplied = false;

        assertTrue(DedicatedBootstrap.applyIfNeeded(config));
        assertTrue(config.governEnabled);
        assertTrue(config.viewRadiusGovernanceEnabled);
        assertTrue(config.learningEnabled);
        assertFalse(config.unloadEnabled);
        assertTrue(config.dedicatedBootstrapApplied);

        config.governEnabled = false;
        assertFalse(DedicatedBootstrap.applyIfNeeded(config));
        assertFalse(config.governEnabled);
    }

    @Test
    void skipsWhenNotDedicated() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.dedicatedServerMode = false;
        assertFalse(DedicatedBootstrap.applyIfNeeded(config));
    }
}

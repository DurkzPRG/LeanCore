package com.durkz.leancore.probe;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnloadProbeGateTest {

    @Test
    void blocksWhenUnloadEnabledAndProbeNotPassed() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.unloadEnabled = true;
        config.unloadProbeGateEnabled = true;
        config.probePassedAtMs = 0L;
        assertTrue(UnloadProbeGate.blocksUnload(config));
    }

    @Test
    void openAfterProbePassed() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.unloadEnabled = true;
        config.unloadProbeGateEnabled = true;
        config.probePassedAtMs = System.currentTimeMillis();
        assertFalse(UnloadProbeGate.blocksUnload(config));
    }

    @Test
    void overrideWhenGateDisabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.unloadEnabled = true;
        config.unloadProbeGateEnabled = false;
        config.probePassedAtMs = 0L;
        assertFalse(UnloadProbeGate.blocksUnload(config));
    }

    @Test
    void inactiveWhenUnloadDisabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.unloadEnabled = false;
        config.unloadProbeGateEnabled = true;
        assertFalse(UnloadProbeGate.blocksUnload(config));
        assertTrue(UnloadProbeGate.statusLine(config, 0L).contains("n/a"));
    }

    @Test
    void statusLineShowsBlockedAndOpen() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.unloadEnabled = true;
        config.unloadProbeGateEnabled = true;

        assertTrue(UnloadProbeGate.statusLine(config, 0L).contains("blocked"));

        long now = 90_000L;
        config.probePassedAtMs = 30_000L;
        assertTrue(UnloadProbeGate.statusLine(config, now).contains("open"));
    }
}

package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.UnloadOutcomeTracker;
import com.durkz.leancore.runtime.RuntimeProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SavingsReportTest {

    @Test
    void marksObservedOnlyWhenGovernorDisabled() {
        SessionSavingsTracker session = new SessionSavingsTracker();
        long now = System.currentTimeMillis();
        session.noteHeapSample(mb(2000), mb(4000), now);
        session.noteHeapSample(mb(2500), mb(4000), now + 1000L);

        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = false;

        MemorySnapshot current = new MemorySnapshot(mb(1800), mb(4000), 0.45D, 4, 120.0D, MemoryTier.COMFORT);
        String output = join(SavingsReport.format(
                current,
                session,
                GovernorStatus.idle(),
                config,
                RuntimeProfile.FULL,
                new UnloadOutcomeTracker(),
                now + 2000L
        ));

        assertTrue(output.contains("governor OFF"));
        assertTrue(output.contains("OBSERVED ONLY"));
    }

    @Test
    void attributesSavingsWhenGovernorWasActive() {
        SessionSavingsTracker session = new SessionSavingsTracker();
        long now = System.currentTimeMillis();
        session.noteHeapSample(mb(3000), mb(4000), now);
        session.noteGovernorTick(4, 24);

        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = true;
        config.viewRadiusGovernanceEnabled = true;

        GovernorPolicy policy = GovernorPolicy.forTier(GovernorPreset.FRIENDS_NIGHT, MemoryTier.WATCH);
        GovernorStatus governor = new GovernorStatus(
                true,
                GovernorPreset.FRIENDS_NIGHT,
                policy,
                4,
                2,
                12,
                200,
                180,
                5,
                10,
                false,
                120L
        );

        MemorySnapshot current = new MemorySnapshot(mb(2200), mb(4000), 0.55D, 4, 120.0D, MemoryTier.COMFORT);
        String output = join(SavingsReport.format(
                current,
                session,
                governor,
                config,
                RuntimeProfile.FULL,
                new UnloadOutcomeTracker(),
                now + 5000L
        ));

        assertTrue(output.contains("governor ON"));
        assertTrue(output.contains("while governor was active"));
        assertTrue(output.contains("JVM heap only"));
    }

    private static String join(java.util.List<SavingsReport.Line> lines) {
        StringBuilder builder = new StringBuilder();
        for (SavingsReport.Line line : lines) {
            builder.append(line.text()).append('\n');
        }
        return builder.toString();
    }

    private static long mb(int megabytes) {
        return megabytes * 1024L * 1024L;
    }
}

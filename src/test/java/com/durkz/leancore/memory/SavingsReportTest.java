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
                new GcHintScheduler(config),
                0L,
                0L,
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
                new GcHintScheduler(config),
                0L,
                0L,
                now + 5000L
        ));

        assertTrue(output.contains("governor ON"));
        assertTrue(output.contains("while governor was active"));
        assertTrue(output.contains("JVM heap only"));
    }

    @Test
    void reportsGcHintDisabledByDefault() {
        LeanCoreConfig config = new LeanCoreConfig();
        SessionSavingsTracker session = new SessionSavingsTracker();
        long now = System.currentTimeMillis();
        session.noteHeapSample(mb(2000), mb(4000), now);

        MemorySnapshot current = new MemorySnapshot(mb(1800), mb(4000), 0.45D, 1, 0.0D, MemoryTier.COMFORT);
        String output = join(SavingsReport.format(
                current,
                session,
                GovernorStatus.idle(),
                config,
                RuntimeProfile.LITE,
                new UnloadOutcomeTracker(),
                new GcHintScheduler(config),
                0L,
                0L,
                now + 1000L
        ));

        assertTrue(output.contains("GC hint"));
        assertTrue(output.contains("gcHintEnabled=false"));
        assertTrue(output.contains("lite governor waiting"));
    }

    @Test
    void reportsLiteGovernorOn() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteMemoryGovernorEnabled = true;

        SessionSavingsTracker session = new SessionSavingsTracker();
        long now = System.currentTimeMillis();
        session.noteHeapSample(mb(3000), mb(4000), now);
        session.noteGovernorTick(0, 0);

        GovernorPolicy policy = LiteViewScaleResolver.policyFor(config, MemoryTier.COMFORT, 0.5D);
        GovernorStatus governor = new GovernorStatus(
                true,
                GovernorPreset.SOLO_LEAN,
                policy,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                30L
        );

        MemorySnapshot current = new MemorySnapshot(mb(2200), mb(4000), 0.55D, 1, 0.0D, MemoryTier.COMFORT);
        String output = join(SavingsReport.format(
                current,
                session,
                governor,
                config,
                RuntimeProfile.LITE,
                new UnloadOutcomeTracker(),
                new GcHintScheduler(config),
                0L,
                now - 120_000L,
                now
        ));

        assertTrue(output.contains("lite governor ON"));
        assertTrue(output.contains("view 100%"));
        assertTrue(output.contains("liteGov=true"));
    }

    @Test
    void reportsGcHintCountsOnLite() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.gcHintEnabled = true;
        config.soloIdleThresholdSeconds = 60;
        GcHintScheduler scheduler = new GcHintScheduler(config);
        scheduler.maybeHint(0L, 120L, MemoryTier.COMFORT, RuntimeProfile.LITE);

        SessionSavingsTracker session = new SessionSavingsTracker();
        long now = 601_000L;
        session.noteHeapSample(mb(2000), mb(4000), now);

        MemorySnapshot current = new MemorySnapshot(mb(1800), mb(4000), 0.45D, 1, 0.0D, MemoryTier.COMFORT);
        String output = join(SavingsReport.format(
                current,
                session,
                GovernorStatus.idle(),
                config,
                RuntimeProfile.LITE,
                new UnloadOutcomeTracker(),
                scheduler,
                0L,
                0L,
                now
        ));

        assertTrue(output.contains("gcHintEnabled=true"));
        assertTrue(output.contains("hints=1"));
        assertTrue(output.contains("idle>=60s"));
    }

    @Test
    void warnsGcHintOnNonLiteProfile() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.gcHintEnabled = true;

        SessionSavingsTracker session = new SessionSavingsTracker();
        long now = System.currentTimeMillis();
        session.noteHeapSample(mb(2000), mb(4000), now);

        MemorySnapshot current = new MemorySnapshot(mb(1800), mb(4000), 0.45D, 1, 0.0D, MemoryTier.COMFORT);
        String output = join(SavingsReport.format(
                current,
                session,
                GovernorStatus.idle(),
                config,
                RuntimeProfile.STANDARD,
                new UnloadOutcomeTracker(),
                new GcHintScheduler(config),
                0L,
                0L,
                now + 1000L
        ));

        assertTrue(output.contains("hints only on LITE"));
        assertTrue(output.contains("profile STANDARD"));
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

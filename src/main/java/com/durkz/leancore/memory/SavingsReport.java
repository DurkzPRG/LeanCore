package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.UnloadOutcomeTracker;
import com.durkz.leancore.runtime.RuntimeProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SavingsReport {

    public record Line(String text, String color) {
    }

    private SavingsReport() {
    }

    public static List<Line> format(
            MemorySnapshot current,
            SessionSavingsTracker session,
            GovernorStatus governor,
            LeanCoreConfig config,
            RuntimeProfile profile,
            UnloadOutcomeTracker unloadTracker,
            long viewRadiusGraceUntilMs,
            long nowMs
    ) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("=== LeanCore savings ===", "#FFAA00"));

        if (session == null || current == null) {
            lines.add(new Line("no session data yet", "#FF8888"));
            return lines;
        }

        lines.add(new Line(String.format(Locale.ROOT,
                "session %s | profile %s | dedicated=%s",
                formatDuration(nowMs - session.sessionStartedMs()),
                profile,
                config.dedicatedServerMode), "#AAAAAA"));

        lines.add(new Line("--- JVM heap (measured) ---", "#888888"));
        if (session.heapSampleCount() <= 0) {
            lines.add(new Line("no heap samples yet — wait for runtime tick", "#FF8888"));
            return lines;
        }

        lines.add(formatHeapLine("current", current.heapUsedBytes(), current.heapMaxBytes(), current.heapUsedRatio(), "#55FF55", ""));
        if (session.peakUsedBytes() >= 0L) {
            double peakRatio = session.peakMaxBytes() > 0L
                    ? (double) session.peakUsedBytes() / session.peakMaxBytes()
                    : 0.0D;
            lines.add(formatHeapLine("peak",
                    session.peakUsedBytes(),
                    session.peakMaxBytes(),
                    peakRatio,
                    "#AAAAAA",
                    " at " + formatOffset(session.peakAtMs() - session.sessionStartedMs())));
        }
        if (session.bootBaselineUsedBytes() >= 0L) {
            double baseRatio = session.bootBaselineMaxBytes() > 0L
                    ? (double) session.bootBaselineUsedBytes() / session.bootBaselineMaxBytes()
                    : 0.0D;
            lines.add(formatHeapLine("baseline",
                    session.bootBaselineUsedBytes(),
                    session.bootBaselineMaxBytes(),
                    baseRatio,
                    "#AAAAAA",
                    " first runtime sample"));
        }

        if (session.peakUsedBytes() >= 0L) {
            long deltaMb = (session.peakUsedBytes() - current.heapUsedBytes()) / (1024 * 1024);
            double deltaPp = (session.peakUsedBytes() / (double) Math.max(1L, session.peakMaxBytes())
                    - current.heapUsedRatio()) * 100.0D;
            lines.add(new Line(String.format(Locale.ROOT,
                    "delta %s%d MB (%s%.0f pp) peak -> current",
                    deltaMb >= 0 ? "-" : "+",
                    Math.abs(deltaMb),
                    deltaPp >= 0 ? "-" : "+",
                    Math.abs(deltaPp)), "#FFAA00"));
        }

        lines.add(new Line("--- Governor ---", "#888888"));
        boolean governorConfigured = config.governEnabled;
        boolean governorRunning = governor != null && governor.enabled();
        boolean liteProfile = profile == RuntimeProfile.LITE;

        if (liteProfile) {
            lines.add(new Line("profile LITE: governor tick not active on embedded solo", "#FFAA00"));
        } else if (!governorConfigured) {
            lines.add(new Line("governor OFF (governEnabled=false in LeanCore.json)", "#FF8888"));
        } else if (!governorRunning) {
            lines.add(new Line("governor configured but idle this tick", "#FFAA00"));
        } else {
            String policy = governor.policy() != null ? governor.policy().key() : "none";
            String view = governor.policy() != null
                    ? String.format(Locale.ROOT, "%.0f%%", governor.policy().viewScale() * 100.0D)
                    : "n/a";
            lines.add(new Line(String.format(Locale.ROOT,
                    "governor ON | preset %s | policy %s | view %s | tier %s",
                    governor.preset(),
                    policy,
                    view,
                    current.tier()), "#55FF55"));
            if (session.governorEverActive() && session.firstGovernorActiveMs() > 0L) {
                lines.add(new Line("first active " + formatOffset(session.firstGovernorActiveMs() - session.sessionStartedMs()),
                        "#888888"));
            }
        }

        lines.add(new Line(String.format(Locale.ROOT,
                "viewRadiusGovernance=%s | learning=%s | unload=%s",
                config.viewRadiusGovernanceEnabled,
                config.learningEnabled,
                config.unloadEnabled), "#888888"));
        if (config.dedicatedBootstrapApplied) {
            lines.add(new Line("dedicatedBootstrap=applied (one-time preset on first dedicated boot)", "#888888"));
        }
        if (viewRadiusGraceUntilMs > 0L && nowMs < viewRadiusGraceUntilMs) {
            lines.add(new Line(String.format(Locale.ROOT,
                    "view-radius grace: cuts blocked for %s more",
                    formatDuration(viewRadiusGraceUntilMs - nowMs)), "#FFAA00"));
        }

        lines.add(new Line("--- Session actions (cumulative) ---", "#888888"));
        lines.add(new Line(String.format(Locale.ROOT,
                "zones demoted->FROZEN: %d (model estimate ~%d MB, not direct heap proof)",
                session.cumulativeDemotedZones(),
                session.cumulativeReclaimedMbEstimate()), "#AAAAAA"));
        int policyUnloads = unloadTracker != null ? unloadTracker.policyUnloads() : 0;
        int engineUnloads = unloadTracker != null ? unloadTracker.engineUnloads() : 0;
        lines.add(new Line(String.format(Locale.ROOT,
                "chunks unloaded (policy): %d  |  chunks evicted (engine): %d",
                policyUnloads,
                engineUnloads), "#AAAAAA"));
        if (!config.unloadEnabled) {
            lines.add(new Line("unload=OFF — policy unload count may not reduce heap if engine already evicted", "#888888"));
        }

        lines.add(new Line("--- Notes ---", "#888888"));
        lines.add(new Line("JVM heap only — not OS/VPS RSS. GC can shift numbers.", "#888888"));

        if (session.bootStabilizing(nowMs)) {
            lines.add(new Line("boot grace (<60s): baseline still stabilizing", "#FFAA00"));
        }

        if (liteProfile || !governorConfigured || !session.governorEverActive()) {
            lines.add(new Line("heap delta is OBSERVED ONLY — not attributed to LeanCore policy", "#FF8888"));
            if (!liteProfile && !governorConfigured) {
                lines.add(new Line("set governEnabled=true (and viewRadiusGovernanceEnabled) to apply cuts", "#888888"));
            }
        } else if (session.peakUsedBytes() >= 0L) {
            long savedMb = (session.peakUsedBytes() - current.heapUsedBytes()) / (1024 * 1024);
            if (savedMb > 0) {
                lines.add(new Line(String.format(Locale.ROOT,
                        "while governor was active: peak->current %d MB lower (measured JVM heap)",
                        savedMb), "#55FF55"));
            }
        }

        if (governor != null && governor.rolledBack()) {
            lines.add(new Line("rollback active — last policy change reverted", "#FF8888"));
        }

        return lines;
    }

    private static Line formatHeapLine(String label, long used, long max, double ratio, String color, String suffix) {
        return new Line(String.format(Locale.ROOT, "%s: %d / %d MB (%.0f%%)%s",
                label,
                used / (1024 * 1024),
                max / (1024 * 1024),
                ratio * 100.0D,
                suffix), color);
    }

    private static String formatDuration(long ms) {
        if (ms < 0L) {
            ms = 0L;
        }
        long totalSec = ms / 1000L;
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %dm", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %ds", minutes, seconds);
        }
        return seconds + "s";
    }

    private static String formatOffset(long ms) {
        return "boot+" + formatDuration(ms);
    }
}

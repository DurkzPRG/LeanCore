package com.durkz.leancore.config;

import com.durkz.leancore.runtime.RuntimeActivationPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class LeanCoreConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_CLIENT_VIEW_RADIUS_CAP = 64;
    private static final int MAX_UNLOAD_CHUNKS_CAP = 64;

    private transient File configFile;

    public boolean enabled = true;
    @Deprecated
    public boolean localHostPassiveMode = false;
    public String localHostMode = "AUTO";
    public int runtimeInitialDelaySeconds = 30;
    public int soloTickIntervalSeconds = 30;
    public int soloIdleTickIntervalSeconds = 60;
    public int soloHeapSampleIntervalSeconds = 60;
    public int soloDormancyMinIntervalSeconds = 30;
    public double soloDormancyMotionBlocks = 8.0D;
    public boolean soloAdaptiveTickEnabled = true;
    public int soloIdleThresholdSeconds = 300;
    public int regionalPressureIntervalSeconds = 60;
    public int friendsTickIntervalSeconds = 15;
    public boolean governEnabled = false;
    public boolean viewRadiusGovernanceEnabled = false;
    public String preset = "AUTO";
    public boolean dedicatedServerMode = false;
    /**
     * Solo embedded: use STANDARD (governor + learning ticks every friendsTickIntervalSeconds)
     * instead of LITE. Safer than dedicatedServerMode for local dogfood — does not force FULL 5s ticks.
     */
    public boolean embeddedStandardProfile = false;
    public boolean dedicatedBootstrapEnabled = true;
    public boolean dedicatedBootstrapApplied = false;
    public int friendsMaxPlayers = 8;
    public int serverDensePlayerThreshold = 9;

    public int minClientViewRadius = 4;
    public int maxClientViewRadius = 32;
    public int policyChangeMinIntervalSec = 30;
    public int policyApplyMinIntervalSeconds = 15;
    public int runtimeTickIntervalSeconds = 5;
    public int dormancyRefreshIntervalSeconds = 15;
    public int minViewRadiusDelta = 2;
    public boolean unloadEnabled = false;
    /** When unloadEnabled, block sweeps until /leancore probe passes. Set false to override. */
    public boolean unloadProbeGateEnabled = true;
    /** Set when probe S1-S5 pass; persisted in LeanCore.json. */
    public long probePassedAtMs = 0L;
    /** Poll loaded-chunk deltas on the world thread; do not register ChunkUnloadEvent listeners. */
    public boolean chunkUnloadEventTracking = false;
    public int unloadMinIntervalSeconds = 5;
    public int unloadMaxChunksPerSweep = 16;
    public int rollbackWindowSec = 60;
    public double rollbackHeapDelta = 0.03;

    public double watchHeapRatio = 0.70;
    public double tightHeapRatio = 0.82;
    public double criticalHeapRatio = 0.90;

    public int dormantAfterMinutes = 8;
    public int frozenAfterMinutes = 20;
    public int memoryBudgetMb = 0;

    public boolean learningEnabled = false;
    public int persistIntervalSeconds = 300;
    /** Max UUID profiles kept in learning.state; oldest stale entries pruned on flush. 0 = unlimited. */
    public int learningMaxPersistedPlayers = 512;
    /** Drop offline player profiles older than this many days. 0 = TTL prune disabled. */
    public int learningPlayerTtlDays = 90;

    public boolean hudFeatureEnabled = false;
    public String[] hudViewerGroups = {"OP", "Admin"};
    public String[] hudAdminGroups = {"OP", "Admin"};
    public int hudUpdateIntervalSeconds = 3;
    public int heatmapDefaultLimit = 24;
    public int zonePinMaxCount = 16;

    public String criticalWebhookUrl = "";
    public int criticalWebhookCooldownSeconds = 300;

    /** LITE + COMFORT + solo idle only. Off by default — experimental for 1.4.x tuning. */
    public boolean gcHintEnabled = false;
    public int gcHintMinIntervalSeconds = 600;

    // --- LITE solo governor (1.5.0); STANDARD/FULL ignore these ---
    /** Master switch for LITE memory actions (view, unload, demote). */
    public boolean liteMemoryGovernorEnabled = true;
    /** Learning persistence and demand features on LITE profile. */
    public boolean liteLearningEnabled = true;
    /** Adaptive view-radius on embedded solo. */
    public boolean liteViewRadiusEnabled = true;
    /** Chunk saturation (loaded/budget) above this triggers COMFORT cap scale. */
    public double liteViewPressureThreshold = 0.85D;
    /** COMFORT view scale when chunk pressure is high. */
    public double liteViewComfortCapScale = 0.97D;
    public double liteViewWatchScale = 0.94D;
    public double liteViewTightScale = 0.88D;
    public double liteViewCriticalScale = 0.76D;
    public int liteMinClientViewRadius = 8;
    public int liteViewRadiusLoginGraceSeconds = 600;
    public boolean liteUnloadEnabled = true;
    public int liteUnloadIdleSeconds = 180;
    public int liteUnloadMaxChunksPerSweep = 8;

    // v1.6.0 Frente 1: motion model (on by default). Predicted position biases unload away
    // from zones ahead of the player and protects the cone ahead. The live view-radius boost is
    // off by default: rewriting the client view radius every motion tick churns the chunk ring
    // and stutters on the current engine, whose post-load pipeline is slow. Opt-in.
    public boolean motionModelEnabled = true;
    public int motionPredictionHorizonSeconds = 3;
    public boolean motionViewRadiusBoostEnabled = false;
    public double motionViewRadiusMaxBoost = 1.6D;
    public double motionMinSpeedBlocksPerSecond = 2.0D;
    // Fast cadence (seconds) for the live motion sampler + HUD refresh. Must stay under the
    // motion model reset window (5s) so velocity accumulates between samples.
    public int motionSampleIntervalSeconds = 1;

    // v1.6.0 Frente 2: per-zone reuse-distance + survival model (on by default). Biases chunk
    // unload toward zones unlikely to be revisited and scales dormancy thresholds per zone.
    // Persisted across sessions (capped + TTL pruned).
    public boolean zoneReuseModelEnabled = true;
    public double zoneReuseRankWeight = 0.5D;
    public double zoneReuseThresholdScaleMin = 0.5D;
    public double zoneReuseThresholdScaleMax = 2.0D;
    public int zoneReuseMaxPersistedZones = 4096;
    public int zoneReuseTtlDays = 30;

    // Always-on diagnostic logging to the server log (lifecycle, command mirroring, decision
    // reasoning). Enabled by default; set false to silence all [diag] lines.
    public boolean diagnosticLogEnabled = true;

    public static LeanCoreConfig load(Path dataDirectory) {
        File directory = dataDirectory.toFile();
        if (!directory.exists()) {
            directory.mkdirs();
        }

        File file = new File(directory, "LeanCore.json");
        LeanCoreConfig config = new LeanCoreConfig();
        config.configFile = file;

        if (!file.exists()) {
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            LeanCoreConfig loaded = GSON.fromJson(reader, LeanCoreConfig.class);
            if (loaded != null) {
                loaded.configFile = file;
                loaded.applyRuntimeDefaults();
                return loaded;
            }
        } catch (Exception ignored) {
            quarantineCorruptConfig(file);
        }

        config.applyRuntimeDefaults();
        return config;
    }

    private static void quarantineCorruptConfig(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            Path corrupt = file.toPath().resolveSibling(
                    file.getName() + ".corrupt." + System.currentTimeMillis());
            Files.move(file.toPath(), corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void applyRuntimeDefaults() {
        if (policyApplyMinIntervalSeconds <= 0) {
            policyApplyMinIntervalSeconds = 15;
        }
        if (runtimeTickIntervalSeconds <= 0) {
            runtimeTickIntervalSeconds = 5;
        }
        if (runtimeInitialDelaySeconds < 0) {
            runtimeInitialDelaySeconds = 30;
        }
        if (soloTickIntervalSeconds <= 0) {
            soloTickIntervalSeconds = 30;
        }
        if (soloIdleTickIntervalSeconds <= 0) {
            soloIdleTickIntervalSeconds = 60;
        }
        if (soloHeapSampleIntervalSeconds <= 0) {
            soloHeapSampleIntervalSeconds = 60;
        }
        if (soloDormancyMinIntervalSeconds <= 0) {
            soloDormancyMinIntervalSeconds = 30;
        }
        if (soloDormancyMotionBlocks <= 0.0D) {
            soloDormancyMotionBlocks = 8.0D;
        }
        if (soloIdleThresholdSeconds <= 0) {
            soloIdleThresholdSeconds = 300;
        }
        if (regionalPressureIntervalSeconds <= 0) {
            regionalPressureIntervalSeconds = 60;
        }
        if (friendsTickIntervalSeconds <= 0) {
            friendsTickIntervalSeconds = 15;
        }
        if (localHostPassiveMode) {
            localHostMode = RuntimeActivationPolicy.MODE_PASSIVE;
        }
        if (localHostMode == null || localHostMode.isBlank()) {
            localHostMode = RuntimeActivationPolicy.MODE_AUTO;
        }
        if (dormancyRefreshIntervalSeconds <= 0) {
            dormancyRefreshIntervalSeconds = 15;
        }
        if (minViewRadiusDelta <= 0) {
            minViewRadiusDelta = 2;
        }
        if (unloadMinIntervalSeconds <= 0) {
            unloadMinIntervalSeconds = 5;
        }
        if (unloadMaxChunksPerSweep <= 0) {
            unloadMaxChunksPerSweep = 16;
        }
        if (unloadMaxChunksPerSweep > MAX_UNLOAD_CHUNKS_CAP) {
            unloadMaxChunksPerSweep = MAX_UNLOAD_CHUNKS_CAP;
        }
        sanitizeViewRadiusSettings();
        if (hudUpdateIntervalSeconds <= 0) {
            hudUpdateIntervalSeconds = 3;
        }
        if (heatmapDefaultLimit <= 0) {
            heatmapDefaultLimit = 24;
        }
        if (zonePinMaxCount <= 0) {
            zonePinMaxCount = 16;
        }
        if (criticalWebhookCooldownSeconds <= 0) {
            criticalWebhookCooldownSeconds = 300;
        }
        if (gcHintMinIntervalSeconds <= 0) {
            gcHintMinIntervalSeconds = 600;
        }
        sanitizeLiteSettings();
        sanitizeMotionSettings();
        if (learningMaxPersistedPlayers < 0) {
            learningMaxPersistedPlayers = 512;
        }
        if (learningPlayerTtlDays < 0) {
            learningPlayerTtlDays = 90;
        }
        if (hudViewerGroups == null || hudViewerGroups.length == 0) {
            hudViewerGroups = new String[]{"OP", "Admin"};
        }
        if (hudAdminGroups == null || hudAdminGroups.length == 0) {
            hudAdminGroups = new String[]{"OP", "Admin"};
        }
    }

    /** Package-visible for unit tests. */
    void normalizeDefaults() {
        applyRuntimeDefaults();
    }

    private void sanitizeViewRadiusSettings() {
        if (minClientViewRadius <= 0) {
            minClientViewRadius = 4;
        }
        if (maxClientViewRadius <= 0) {
            maxClientViewRadius = 32;
        }
        if (maxClientViewRadius > MAX_CLIENT_VIEW_RADIUS_CAP) {
            maxClientViewRadius = MAX_CLIENT_VIEW_RADIUS_CAP;
        }
        if (minClientViewRadius > maxClientViewRadius) {
            minClientViewRadius = maxClientViewRadius;
        }
    }

    private void sanitizeMotionSettings() {
        if (motionPredictionHorizonSeconds < 1) {
            motionPredictionHorizonSeconds = 1;
        }
        if (motionPredictionHorizonSeconds > 10) {
            motionPredictionHorizonSeconds = 10;
        }
        if (motionViewRadiusMaxBoost < 1.0D) {
            motionViewRadiusMaxBoost = 1.0D;
        }
        if (motionViewRadiusMaxBoost > 2.0D) {
            motionViewRadiusMaxBoost = 2.0D;
        }
        if (motionMinSpeedBlocksPerSecond <= 0.0D) {
            motionMinSpeedBlocksPerSecond = 3.0D;
        }
        if (motionSampleIntervalSeconds < 1) {
            motionSampleIntervalSeconds = 1;
        }
        if (motionSampleIntervalSeconds > 4) {
            motionSampleIntervalSeconds = 4;
        }
        sanitizeZoneReuseSettings();
    }

    private void sanitizeZoneReuseSettings() {
        if (zoneReuseRankWeight < 0.0D) {
            zoneReuseRankWeight = 0.0D;
        }
        if (zoneReuseRankWeight > 2.0D) {
            zoneReuseRankWeight = 2.0D;
        }
        if (zoneReuseThresholdScaleMin < 0.1D) {
            zoneReuseThresholdScaleMin = 0.1D;
        }
        if (zoneReuseThresholdScaleMin > 1.0D) {
            zoneReuseThresholdScaleMin = 1.0D;
        }
        if (zoneReuseThresholdScaleMax < 1.0D) {
            zoneReuseThresholdScaleMax = 1.0D;
        }
        if (zoneReuseThresholdScaleMax > 4.0D) {
            zoneReuseThresholdScaleMax = 4.0D;
        }
        if (zoneReuseThresholdScaleMax < zoneReuseThresholdScaleMin) {
            zoneReuseThresholdScaleMax = zoneReuseThresholdScaleMin;
        }
        if (zoneReuseMaxPersistedZones < 0) {
            zoneReuseMaxPersistedZones = 0;
        }
        if (zoneReuseMaxPersistedZones > 65536) {
            zoneReuseMaxPersistedZones = 65536;
        }
        if (zoneReuseTtlDays < 0) {
            zoneReuseTtlDays = 0;
        }
        if (zoneReuseTtlDays > 365) {
            zoneReuseTtlDays = 365;
        }
    }

    private void sanitizeLiteSettings() {
        liteViewPressureThreshold = clampRatio(liteViewPressureThreshold, 0.85D);
        liteViewComfortCapScale = clampScale(liteViewComfortCapScale, 0.97D);
        liteViewWatchScale = clampScale(liteViewWatchScale, 0.94D);
        liteViewTightScale = clampScale(liteViewTightScale, 0.88D);
        liteViewCriticalScale = clampScale(liteViewCriticalScale, 0.76D);
        if (liteMinClientViewRadius <= 0) {
            liteMinClientViewRadius = 8;
        }
        liteMinClientViewRadius = Math.max(minClientViewRadius, liteMinClientViewRadius);
        liteMinClientViewRadius = Math.min(maxClientViewRadius, liteMinClientViewRadius);
        if (liteViewRadiusLoginGraceSeconds < 0) {
            liteViewRadiusLoginGraceSeconds = 600;
        }
        if (liteUnloadIdleSeconds < 60) {
            liteUnloadIdleSeconds = 180;
        }
        if (liteUnloadMaxChunksPerSweep <= 0) {
            liteUnloadMaxChunksPerSweep = 8;
        }
        if (liteUnloadMaxChunksPerSweep > 64) {
            liteUnloadMaxChunksPerSweep = 64;
        }
    }

    private static double clampRatio(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.50D, Math.min(1.0D, value));
    }

    private static double clampScale(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.50D, Math.min(1.0D, value));
    }

    public void save() {
        if (configFile == null) {
            return;
        }
        Path target = configFile.toPath();
        Path temp = target.resolveSibling(configFile.getName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
            return;
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignoredAgain) {
            }
        }
    }
}

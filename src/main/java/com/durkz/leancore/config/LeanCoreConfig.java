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

public class LeanCoreConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        } catch (IOException ignored) {
        }

        config.applyRuntimeDefaults();
        return config;
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

    public void save() {
        if (configFile == null) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
        }
    }
}

package com.durkz.leancore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class LeanCoreConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient File configFile;

    public boolean enabled = true;
    public boolean governEnabled = true;
    public String preset = "AUTO";
    public boolean dedicatedServerMode = false;
    public int friendsMaxPlayers = 8;
    public int serverDensePlayerThreshold = 9;

    public int minClientViewRadius = 4;
    public int maxClientViewRadius = 32;
    public int policyChangeMinIntervalSec = 30;
    public int policyApplyMinIntervalSeconds = 5;
    public int runtimeTickIntervalSeconds = 2;
    public int dormancyRefreshIntervalSeconds = 5;
    public boolean unloadEnabled = true;
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

    public boolean learningEnabled = true;
    public int persistIntervalSeconds = 300;

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

        try (FileReader reader = new FileReader(file)) {
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
            policyApplyMinIntervalSeconds = 5;
        }
        if (runtimeTickIntervalSeconds <= 0) {
            runtimeTickIntervalSeconds = 2;
        }
        if (dormancyRefreshIntervalSeconds <= 0) {
            dormancyRefreshIntervalSeconds = 5;
        }
        if (unloadMinIntervalSeconds <= 0) {
            unloadMinIntervalSeconds = 5;
        }
        if (unloadMaxChunksPerSweep <= 0) {
            unloadMaxChunksPerSweep = 16;
        }
    }

    public void save() {
        if (configFile == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
        }
    }
}

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
    public boolean dedicatedServerMode = false;
    public int friendsMaxPlayers = 8;
    public int serverDensePlayerThreshold = 9;

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
                return loaded;
            }
        } catch (IOException ignored) {
        }

        return config;
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

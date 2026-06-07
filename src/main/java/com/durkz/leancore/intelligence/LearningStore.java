package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LearningStore {

    private final Path dataDir;
    private final LeanCoreConfig config;
    private final Map<UUID, String> behaviors = new ConcurrentHashMap<>();
    private MemoryTier lastTier = MemoryTier.COMFORT;
    private int flushCount;

    public LearningStore(Path dataDir, LeanCoreConfig config) {
        this.dataDir = dataDir;
        this.config = config;
    }

    public void noteBehaviors(Map<UUID, PlayerBehavior> snapshot) {
        for (Map.Entry<UUID, PlayerBehavior> e : snapshot.entrySet()) {
            behaviors.put(e.getKey(), e.getValue().name());
        }
    }

    public void noteTier(MemoryTier tier) {
        lastTier = tier;
    }

    public void flush() {
        if (!config.learningEnabled) {
            return;
        }
        Properties props = new Properties();
        props.setProperty("savedAt", Instant.now().toString());
        props.setProperty("tier", lastTier.name());
        props.setProperty("playerCount", Integer.toString(behaviors.size()));
        for (Map.Entry<UUID, String> e : behaviors.entrySet()) {
            props.setProperty("player." + e.getKey(), e.getValue());
        }

        try {
            Files.createDirectories(dataDir);
            Path target = dataDir.resolve("learning.state");
            try (OutputStream out = Files.newOutputStream(target)) {
                props.store(out, "LeanCore runtime snapshot");
            }
            flushCount++;
        } catch (IOException ignored) {
        }
    }

    public String statusLine() {
        return String.format(Locale.ROOT, "learning=%s flushes=%d tier=%s",
                config.learningEnabled, flushCount, lastTier);
    }
}

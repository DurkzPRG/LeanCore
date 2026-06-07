package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;

import java.io.IOException;
import java.io.InputStream;
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

    private static final double MIN_POLICY_SCORE = 0.1D;
    private static final double MAX_POLICY_SCORE = 2.0D;
    private static final double REINFORCE_DELTA = 0.1D;
    private static final double PENALIZE_DELTA = 0.25D;
    private static final double DEPRIORITIZE_SCORE = 0.35D;

    private final Path dataDir;
    private final LeanCoreConfig config;
    private final Map<UUID, String> behaviors = new ConcurrentHashMap<>();
    private final Map<String, Double> policyScores = new ConcurrentHashMap<>();
    private final BehaviorWeights behaviorWeights = new BehaviorWeights();
    private final RollingHeapTracker heapWindows = new RollingHeapTracker();

    private MemoryTier lastTier = MemoryTier.COMFORT;
    private int flushCount;
    private int reinforceCount;
    private int penalizeCount;

    public LearningStore(Path dataDir, LeanCoreConfig config) {
        this.dataDir = dataDir;
        this.config = config;
        load();
    }

    public BehaviorWeights behaviorWeights() {
        return behaviorWeights;
    }

    public void noteBehaviors(Map<UUID, PlayerBehavior> snapshot) {
        for (Map.Entry<UUID, PlayerBehavior> e : snapshot.entrySet()) {
            behaviors.put(e.getKey(), e.getValue().name());
        }
    }

    public void noteTier(MemoryTier tier) {
        lastTier = tier;
    }

    public void noteHeap(double heapRatio) {
        if (!config.learningEnabled) {
            return;
        }
        heapWindows.add(heapRatio, System.currentTimeMillis());
    }

    public double policyScore(String policyKey) {
        return policyScores.getOrDefault(policyKey, 1.0D);
    }

    public boolean isPolicyDeprioritized(String policyKey) {
        return policyScore(policyKey) < DEPRIORITIZE_SCORE;
    }

    public void reinforcePolicy(String policyKey) {
        if (!config.learningEnabled || policyKey == null) {
            return;
        }
        policyScores.merge(policyKey, 1.0D, (old, ignored) -> clampScore(old + REINFORCE_DELTA));
        behaviorWeights.relaxAfkSensitivity();
        reinforceCount++;
    }

    public void penalizePolicy(String policyKey) {
        if (!config.learningEnabled || policyKey == null) {
            return;
        }
        policyScores.merge(policyKey, 1.0D, (old, ignored) -> clampScore(old - PENALIZE_DELTA));
        behaviorWeights.reinforceAfkSensitivity();
        penalizeCount++;
    }

    public void flush() {
        if (!config.learningEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        Properties props = new Properties();
        props.setProperty("savedAt", Instant.now().toString());
        props.setProperty("tier", lastTier.name());
        props.setProperty("playerCount", Integer.toString(behaviors.size()));
        props.setProperty("heap.avg60s", formatRatio(heapWindows.avg60s(now)));
        props.setProperty("heap.avg15m", formatRatio(heapWindows.avg15m(now)));
        props.setProperty("heap.avg24h", formatRatio(heapWindows.avg24h(now)));
        props.setProperty("learn.reinforce", Integer.toString(reinforceCount));
        props.setProperty("learn.penalize", Integer.toString(penalizeCount));

        writeWeight(props, "weight.afkIdleSecMul", behaviorWeights.afkIdleSecMul);
        writeWeight(props, "weight.buildBlockMinMul", behaviorWeights.buildBlockMinMul);
        writeWeight(props, "weight.explorerDistMul", behaviorWeights.explorerDistMul);
        writeWeight(props, "weight.explorerZonesMul", behaviorWeights.explorerZonesMul);
        writeWeight(props, "weight.fighterBreaksMul", behaviorWeights.fighterBreaksMul);
        writeWeight(props, "weight.fighterMaxDistMul", behaviorWeights.fighterMaxDistMul);
        writeWeight(props, "weight.socialMaxDistMul", behaviorWeights.socialMaxDistMul);

        for (Map.Entry<String, Double> e : policyScores.entrySet()) {
            props.setProperty("policy." + e.getKey(), Double.toString(e.getValue()));
        }
        for (Map.Entry<UUID, String> e : behaviors.entrySet()) {
            props.setProperty("player." + e.getKey(), e.getValue());
        }

        try {
            Files.createDirectories(dataDir);
            Path target = dataDir.resolve("learning.state");
            try (OutputStream out = Files.newOutputStream(target)) {
                props.store(out, "LeanCore learning snapshot");
            }
            flushCount++;
        } catch (IOException ignored) {
        }
    }

    public String statusLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT,
                "learning=%s flushes=%d tier=%s heap60s=%.0f%% +%d -%d",
                config.learningEnabled,
                flushCount,
                lastTier,
                heapWindows.avg60s(now) * 100.0D,
                reinforceCount,
                penalizeCount);
    }

    public String windowLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT, "windows 60s=%.0f%% 15m=%.0f%% 24h=%.0f%%",
                heapWindows.avg60s(now) * 100.0D,
                heapWindows.avg15m(now) * 100.0D,
                heapWindows.avg24h(now) * 100.0D);
    }

    private void load() {
        Path target = dataDir.resolve("learning.state");
        if (!Files.isRegularFile(target)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(target)) {
            props.load(in);
        } catch (IOException ignored) {
            return;
        }

        behaviorWeights.afkIdleSecMul = readDouble(props, "weight.afkIdleSecMul", 1.0D);
        behaviorWeights.buildBlockMinMul = readDouble(props, "weight.buildBlockMinMul", 1.0D);
        behaviorWeights.explorerDistMul = readDouble(props, "weight.explorerDistMul", 1.0D);
        behaviorWeights.explorerZonesMul = readDouble(props, "weight.explorerZonesMul", 1.0D);
        behaviorWeights.fighterBreaksMul = readDouble(props, "weight.fighterBreaksMul", 1.0D);
        behaviorWeights.fighterMaxDistMul = readDouble(props, "weight.fighterMaxDistMul", 1.0D);
        behaviorWeights.socialMaxDistMul = readDouble(props, "weight.socialMaxDistMul", 1.0D);

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("policy.")) {
                policyScores.put(key.substring("policy.".length()), readDouble(props, key, 1.0D));
            }
        }
        reinforceCount = readInt(props, "learn.reinforce", 0);
        penalizeCount = readInt(props, "learn.penalize", 0);
    }

    private static void writeWeight(Properties props, String key, double value) {
        props.setProperty(key, Double.toString(value));
    }

    private static double readDouble(Properties props, String key, double fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.4f", ratio);
    }

    private static double clampScore(double score) {
        return Math.max(MIN_POLICY_SCORE, Math.min(MAX_POLICY_SCORE, score));
    }
}

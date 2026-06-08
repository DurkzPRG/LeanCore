package com.durkz.leancore.alert;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CriticalWebhookNotifier {

    private final LeanCoreConfig config;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LeanCore-webhook");
        t.setDaemon(true);
        return t;
    });

    private MemoryTier lastTier = MemoryTier.COMFORT;
    private long lastSentMs;

    public CriticalWebhookNotifier(LeanCoreConfig config) {
        this.config = config;
    }

    public void onTier(MemoryTier tier, double heapRatio) {
        if (tier != MemoryTier.CRITICAL) {
            lastTier = tier;
            return;
        }
        if (lastTier == MemoryTier.CRITICAL) {
            return;
        }
        lastTier = MemoryTier.CRITICAL;
        dispatch(heapRatio);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void dispatch(double heapRatio) {
        String url = config.criticalWebhookUrl;
        if (url == null || url.isBlank()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long cooldownMs = Math.max(60, config.criticalWebhookCooldownSeconds) * 1000L;
        if (lastSentMs > 0L && nowMs - lastSentMs < cooldownMs) {
            return;
        }
        lastSentMs = nowMs;

        String body = String.format(Locale.ROOT,
                "{\"source\":\"LeanCore\",\"tier\":\"CRITICAL\",\"heapRatio\":%.3f}",
                heapRatio);
        executor.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url.trim()))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        });
    }
}

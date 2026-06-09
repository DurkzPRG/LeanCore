package com.durkz.leancore.intelligence;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PolicyBlacklistTracker {

    private final Map<String, Long> untilMsByPolicy = new ConcurrentHashMap<>();

    public void blacklist(String policyKey, long untilEpochMs) {
        if (policyKey == null || policyKey.isBlank() || untilEpochMs <= 0L) {
            return;
        }
        untilMsByPolicy.put(policyKey, untilEpochMs);
    }

    public boolean isBlacklisted(String policyKey) {
        if (policyKey == null) {
            return false;
        }
        Long until = untilMsByPolicy.get(policyKey);
        if (until == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= until) {
            untilMsByPolicy.remove(policyKey);
            return false;
        }
        return true;
    }

    public Map<String, Long> snapshotActive(long nowMs) {
        pruneExpired(nowMs);
        return Map.copyOf(untilMsByPolicy);
    }

    public void hydrate(Map<String, Long> entries, long nowMs) {
        untilMsByPolicy.clear();
        if (entries == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : entries.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() > nowMs) {
                untilMsByPolicy.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public int activeCount(long nowMs) {
        pruneExpired(nowMs);
        return untilMsByPolicy.size();
    }

    void pruneExpired(long nowMs) {
        Iterator<Map.Entry<String, Long>> iterator = untilMsByPolicy.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= nowMs) {
                iterator.remove();
            }
        }
    }
}

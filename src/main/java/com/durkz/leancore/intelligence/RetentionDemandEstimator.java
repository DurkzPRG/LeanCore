package com.durkz.leancore.intelligence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RetentionDemandEstimator {

    private static final double SOLO_ACTIVITY_NORM = 350.0D;
    private static final double HIGH_DEMAND_THRESHOLD = 0.75D;

    public Map<UUID, RetentionDemand> estimate(
            Map<UUID, PlayerFeatureState> features,
            Map<UUID, PlayerBehavior> debugLabels,
            long nowMs
    ) {
        if (features.isEmpty()) {
            return Map.of();
        }

        List<ActivityEntry> ranked = new ArrayList<>(features.size());
        for (Map.Entry<UUID, PlayerFeatureState> e : features.entrySet()) {
            ranked.add(new ActivityEntry(e.getKey(), adjustedActivity(e.getValue(), nowMs)));
        }
        ranked.sort((a, b) -> Double.compare(a.activity, b.activity));

        Map<UUID, RetentionDemand> out = new HashMap<>(features.size());
        int n = ranked.size();
        for (int i = 0; i < n; i++) {
            UUID id = ranked.get(i).playerId;
            PlayerFeatureState state = features.get(id);
            double demand;
            if (n == 1) {
                demand = Math.max(0.0D, Math.min(1.0D, ranked.get(i).activity / SOLO_ACTIVITY_NORM));
            } else {
                demand = (double) i / (n - 1);
            }
            double confidence = state.confidence();
            int retentionMb = resolveRetentionMb(demand, confidence);
            PlayerBehavior label = debugLabels.getOrDefault(id, PlayerBehavior.UNKNOWN);
            out.put(id, new RetentionDemand(demand, confidence, retentionMb, label));
        }
        return Collections.unmodifiableMap(out);
    }

    public static boolean isHighDemand(double demand) {
        return demand >= HIGH_DEMAND_THRESHOLD;
    }

    private static double adjustedActivity(PlayerFeatureState state, long nowMs) {
        double activity = state.activityIndex();
        long idleSec = state.idleSec(nowMs);
        if (idleSec >= 300L) {
            activity *= 0.15D;
        } else if (idleSec >= 120L) {
            activity *= 0.45D;
        }
        return activity;
    }

    private static int resolveRetentionMb(double demand, double confidence) {
        double raw = RetentionDemand.MIN_MB + demand * (RetentionDemand.MAX_MB - RetentionDemand.MIN_MB);
        double blended = raw * confidence + RetentionDemand.PRIOR_MB * (1.0D - confidence);
        return (int) Math.round(blended);
    }

    private record ActivityEntry(UUID playerId, double activity) {
    }
}

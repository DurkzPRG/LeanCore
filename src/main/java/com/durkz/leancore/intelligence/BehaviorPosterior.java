package com.durkz.leancore.intelligence;

import java.util.Locale;

/**
 * Activity posterior — primary path is {@link ActivityClassifierModel}; EMA fallback before warm-up.
 */
public final class BehaviorPosterior {

    private BehaviorPosterior() {
    }

    public static PlayerBehavior topLabel(PlayerFeatureState state, ActivityClassifierModel model, long nowMs) {
        if (state == null) {
            return PlayerBehavior.UNKNOWN;
        }
        PlayerBehavior recent = resolveRecent(state, nowMs);
        if (recent != PlayerBehavior.UNKNOWN) {
            return recent;
        }
        if (model != null) {
            return model.topLabel(state, nowMs);
        }
        return topLabelFromActivityEmas(state, nowMs);
    }

    static PlayerBehavior resolveRecent(PlayerFeatureState state, long nowMs) {
        if (state == null || state.idleSec(nowMs) >= 300L) {
            return PlayerBehavior.UNKNOWN;
        }
        PlayerBehavior recent = state.recentDominantBehavior();
        return recent == null ? PlayerBehavior.UNKNOWN : recent;
    }

    public static PlayerBehavior topLabelFromActivityEmas(PlayerFeatureState state, long nowMs) {
        if (state == null) {
            return PlayerBehavior.UNKNOWN;
        }
        long idleSec = state.idleSec(nowMs);
        if (idleSec >= 300L) {
            return PlayerBehavior.AFK;
        }

        double[] raw = new double[PlayerBehavior.values().length];
        raw[PlayerBehavior.MINER.ordinal()] = state.emaMine60();
        raw[PlayerBehavior.LUMBERJACK.ordinal()] = state.emaWood60();
        raw[PlayerBehavior.FARMER.ordinal()] = state.emaFarm60();
        raw[PlayerBehavior.BUILDER.ordinal()] = state.emaBuild60() * 1.2D;
        raw[PlayerBehavior.CRAFTER.ordinal()] = state.emaCraft60();
        raw[PlayerBehavior.FIGHTER.ordinal()] = state.emaCombat60();
        raw[PlayerBehavior.EXPLORER.ordinal()] = state.emaMovement60() * 0.04D + state.emaZones60() * 2.0D;
        raw[PlayerBehavior.SOCIAL.ordinal()] = Math.max(0.0D, 2.0D - state.activityIndex() * 0.02D);

        int best = -1;
        double bestScore = 0.0D;
        for (int i = 0; i < raw.length; i++) {
            PlayerBehavior label = PlayerBehavior.values()[i];
            if (label == PlayerBehavior.UNKNOWN || label == PlayerBehavior.AFK) {
                continue;
            }
            if (raw[i] > bestScore) {
                bestScore = raw[i];
                best = i;
            }
        }
        if (best < 0 || bestScore < 0.5D) {
            return PlayerBehavior.UNKNOWN;
        }
        return PlayerBehavior.values()[best];
    }

    public static String formatTopThree(PlayerFeatureState state, ActivityClassifierModel model, long nowMs) {
        if (state == null) {
            return "posterior=none";
        }
        double[] scores = model != null ? model.posterior(state, nowMs) : emaScores(state);
        int first = -1;
        int second = -1;
        double firstScore = -1.0D;
        double secondScore = -1.0D;
        for (int i = 0; i < scores.length; i++) {
            PlayerBehavior label = PlayerBehavior.values()[i];
            if (label == PlayerBehavior.UNKNOWN || label == PlayerBehavior.AFK) {
                continue;
            }
            if (scores[i] > firstScore) {
                second = first;
                secondScore = firstScore;
                first = i;
                firstScore = scores[i];
            } else if (scores[i] > secondScore) {
                second = i;
                secondScore = scores[i];
            }
        }
        if (first < 0) {
            return "posterior=UNKNOWN";
        }
        PlayerBehavior top = PlayerBehavior.values()[first];
        if (second < 0) {
            return String.format(Locale.ROOT, "posterior=%s %.0f%%", top, firstScore * 100.0D);
        }
        return String.format(Locale.ROOT, "posterior=%s %.0f%% %s %.0f%%",
                top,
                firstScore * 100.0D,
                PlayerBehavior.values()[second],
                secondScore * 100.0D);
    }

    private static double[] emaScores(PlayerFeatureState state) {
        double[] raw = new double[PlayerBehavior.values().length];
        double norm = Math.max(1.0D, state.emaMine60() + state.emaWood60() + state.emaFarm60()
                + state.emaBuild60() + state.emaCraft60() + state.emaCombat60() + 1.0D);
        raw[PlayerBehavior.MINER.ordinal()] = state.emaMine60() / norm;
        raw[PlayerBehavior.LUMBERJACK.ordinal()] = state.emaWood60() / norm;
        raw[PlayerBehavior.FARMER.ordinal()] = state.emaFarm60() / norm;
        raw[PlayerBehavior.BUILDER.ordinal()] = state.emaBuild60() / norm;
        raw[PlayerBehavior.CRAFTER.ordinal()] = state.emaCraft60() / norm;
        raw[PlayerBehavior.FIGHTER.ordinal()] = state.emaCombat60() / norm;
        raw[PlayerBehavior.EXPLORER.ordinal()] = (state.emaMovement60() * 0.02D + state.emaZones60()) / norm;
        return raw;
    }
}

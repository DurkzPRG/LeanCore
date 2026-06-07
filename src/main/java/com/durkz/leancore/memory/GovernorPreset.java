package com.durkz.leancore.memory;

import com.durkz.leancore.session.SessionMode;

public enum GovernorPreset {
    SOLO_LEAN(0.90D, 0.85D),
    FRIENDS_NIGHT(1.0D, 1.0D),
    SERVER_DENSE(0.82D, 0.75D);

    private final double viewScale;
    private final double footprintScale;

    GovernorPreset(double viewScale, double footprintScale) {
        this.viewScale = viewScale;
        this.footprintScale = footprintScale;
    }

    public double viewScale() {
        return viewScale;
    }

    public double footprintScale() {
        return footprintScale;
    }

    public static GovernorPreset resolve(String raw, SessionMode mode) {
        if (raw == null || raw.isBlank() || "AUTO".equalsIgnoreCase(raw)) {
            return switch (mode) {
                case SOLO -> SOLO_LEAN;
                case FRIENDS -> FRIENDS_NIGHT;
                case SERVER -> SERVER_DENSE;
            };
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return FRIENDS_NIGHT;
        }
    }
}

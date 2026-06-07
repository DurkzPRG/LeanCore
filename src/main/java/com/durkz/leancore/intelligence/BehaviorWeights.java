package com.durkz.leancore.intelligence;

public class BehaviorWeights {

    static final long BASE_AFK_IDLE_SEC = 120L;
    static final int BASE_BUILD_BLOCK_MIN = 8;
    static final double BASE_EXPLORER_DIST = 200.0D;
    static final int BASE_EXPLORER_ZONES = 2;
    static final int BASE_FIGHTER_BREAKS = 12;
    static final double BASE_FIGHTER_MAX_DIST = 80.0D;
    static final double BASE_SOCIAL_MAX_DIST = 20.0D;

    private static final double MIN_MUL = 0.5D;
    private static final double MAX_MUL = 2.0D;

    public double afkIdleSecMul = 1.0D;
    public double buildBlockMinMul = 1.0D;
    public double explorerDistMul = 1.0D;
    public double explorerZonesMul = 1.0D;
    public double fighterBreaksMul = 1.0D;
    public double fighterMaxDistMul = 1.0D;
    public double socialMaxDistMul = 1.0D;

    public long afkIdleSec() {
        return Math.round(BASE_AFK_IDLE_SEC * clamp(afkIdleSecMul));
    }

    public int buildBlockMin() {
        return Math.max(1, (int) Math.round(BASE_BUILD_BLOCK_MIN * clamp(buildBlockMinMul)));
    }

    public double explorerDist() {
        return BASE_EXPLORER_DIST * clamp(explorerDistMul);
    }

    public int explorerZones() {
        return Math.max(1, (int) Math.round(BASE_EXPLORER_ZONES * clamp(explorerZonesMul)));
    }

    public int fighterBreaks() {
        return Math.max(1, (int) Math.round(BASE_FIGHTER_BREAKS * clamp(fighterBreaksMul)));
    }

    public double fighterMaxDist() {
        return BASE_FIGHTER_MAX_DIST * clamp(fighterMaxDistMul);
    }

    public double socialMaxDist() {
        return BASE_SOCIAL_MAX_DIST * clamp(socialMaxDistMul);
    }

    public void reinforceAfkSensitivity() {
        afkIdleSecMul = clamp(afkIdleSecMul * 0.95D);
    }

    public void relaxAfkSensitivity() {
        afkIdleSecMul = clamp(afkIdleSecMul * 1.05D);
    }

    private static double clamp(double value) {
        return Math.max(MIN_MUL, Math.min(MAX_MUL, value));
    }
}

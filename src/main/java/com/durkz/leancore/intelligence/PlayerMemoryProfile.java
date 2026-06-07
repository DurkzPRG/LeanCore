package com.durkz.leancore.intelligence;

import java.util.UUID;

public class PlayerMemoryProfile {

    private static final long AFK_IDLE_SEC = 120L;
    private static final int BUILD_BLOCK_MIN = 8;
    private static final double EXPLORER_DIST = 200.0D;
    private static final int EXPLORER_ZONES = 2;
    private static final int FIGHTER_BREAKS = 12;
    private static final double FIGHTER_MAX_DIST = 80.0D;
    private static final double SOCIAL_MAX_DIST = 20.0D;

    private final UUID playerId;

    private int blocksBroken;
    private int blocksPlaced;
    private int zonesDiscovered;
    private double distanceMoved;
    private long lastActivityMs;

    private double lastX;
    private double lastZ;
    private boolean positioned;

    public PlayerMemoryProfile(UUID playerId) {
        this.playerId = playerId;
        this.lastActivityMs = System.currentTimeMillis();
    }

    public UUID playerId() {
        return playerId;
    }

    public void blockBroken() {
        blocksBroken++;
        touch();
    }

    public void blockPlaced() {
        blocksPlaced++;
        touch();
    }

    public void zoneDiscovered() {
        zonesDiscovered++;
        touch();
    }

    public void samplePosition(double x, double z) {
        if (positioned) {
            distanceMoved += Math.hypot(x - lastX, z - lastZ);
        }
        lastX = x;
        lastZ = z;
        positioned = true;
        touch();
    }

    private void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    // Debug label only — retention and view radius use RetentionDemand instead.
    public PlayerBehavior classify(long nowMs) {
        long idleSec = (nowMs - lastActivityMs) / 1000L;
        if (idleSec >= AFK_IDLE_SEC) {
            return PlayerBehavior.AFK;
        }
        if (blocksPlaced + blocksBroken >= BUILD_BLOCK_MIN && blocksPlaced >= blocksBroken) {
            return PlayerBehavior.BUILDER;
        }
        if (zonesDiscovered >= EXPLORER_ZONES && distanceMoved >= EXPLORER_DIST) {
            return PlayerBehavior.EXPLORER;
        }
        if (blocksBroken >= FIGHTER_BREAKS && distanceMoved < FIGHTER_MAX_DIST) {
            return PlayerBehavior.FIGHTER;
        }
        if (distanceMoved < SOCIAL_MAX_DIST && blocksBroken + blocksPlaced < 3) {
            return PlayerBehavior.SOCIAL;
        }
        return PlayerBehavior.UNKNOWN;
    }
}

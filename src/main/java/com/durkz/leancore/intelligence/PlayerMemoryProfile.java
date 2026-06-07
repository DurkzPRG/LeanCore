package com.durkz.leancore.intelligence;

import java.util.UUID;

public class PlayerMemoryProfile {

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

    public PlayerBehavior classify(long nowMs, BehaviorWeights weights) {
        long idleSec = (nowMs - lastActivityMs) / 1000L;
        if (idleSec >= weights.afkIdleSec()) {
            return PlayerBehavior.AFK;
        }
        if (blocksPlaced + blocksBroken >= weights.buildBlockMin() && blocksPlaced >= blocksBroken) {
            return PlayerBehavior.BUILDER;
        }
        if (zonesDiscovered >= weights.explorerZones() && distanceMoved >= weights.explorerDist()) {
            return PlayerBehavior.EXPLORER;
        }
        if (blocksBroken >= weights.fighterBreaks() && distanceMoved < weights.fighterMaxDist()) {
            return PlayerBehavior.FIGHTER;
        }
        if (distanceMoved < weights.socialMaxDist() && blocksBroken + blocksPlaced < 3) {
            return PlayerBehavior.SOCIAL;
        }
        return PlayerBehavior.UNKNOWN;
    }
}

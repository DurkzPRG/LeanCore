package com.durkz.leancore.intelligence;

public enum ActionKind {
    MINE,
    CHOP,
    FARM,
    BUILD,
    CRAFT,
    COMBAT,
    EXPLORE,
    UNKNOWN;

    public PlayerBehavior toBehavior() {
        return switch (this) {
            case MINE -> PlayerBehavior.MINER;
            case CHOP -> PlayerBehavior.LUMBERJACK;
            case FARM -> PlayerBehavior.FARMER;
            case BUILD -> PlayerBehavior.BUILDER;
            case CRAFT -> PlayerBehavior.CRAFTER;
            case COMBAT -> PlayerBehavior.FIGHTER;
            case EXPLORE -> PlayerBehavior.EXPLORER;
            case UNKNOWN -> PlayerBehavior.UNKNOWN;
        };
    }

    public int teacherIndex() {
        return toBehavior().ordinal();
    }
}

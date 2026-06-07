package com.durkz.leancore.session;

import com.durkz.leancore.config.LeanCoreConfig;

public class SessionModeDetector {

    private final LeanCoreConfig config;

    public SessionModeDetector(LeanCoreConfig config) {
        this.config = config;
    }

    public SessionMode detect(int playerCount) {
        if (config.dedicatedServerMode || playerCount >= config.serverDensePlayerThreshold) {
            return SessionMode.SERVER;
        }
        if (playerCount <= 1) {
            return SessionMode.SOLO;
        }
        return SessionMode.FRIENDS;
    }
}

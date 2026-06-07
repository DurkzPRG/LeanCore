package com.durkz.leancore.intelligence;

public class FalseCutTracker {

    private int windowCuts;
    private int sessionCuts;

    public void beginWindow() {
        windowCuts = 0;
    }

    public void noteCut(boolean highDemand) {
        if (!highDemand) {
            return;
        }
        windowCuts++;
        sessionCuts++;
    }

    public int windowCuts() {
        return windowCuts;
    }

    public int sessionCuts() {
        return sessionCuts;
    }
}

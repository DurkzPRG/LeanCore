package com.durkz.leancore.probe;

import com.durkz.leancore.config.LeanCoreConfig;

import java.util.Locale;

public final class UnloadProbeGate {

    private UnloadProbeGate() {
    }

    public static boolean blocksUnload(LeanCoreConfig config) {
        if (config == null || !config.unloadEnabled) {
            return false;
        }
        if (!config.unloadProbeGateEnabled) {
            return false;
        }
        return config.probePassedAtMs <= 0L;
    }

    public static String statusLine(LeanCoreConfig config, long nowMs) {
        if (config == null || !config.unloadEnabled) {
            return "unload gate=n/a (unloadEnabled=false)";
        }
        if (!config.unloadProbeGateEnabled) {
            return "unload gate=override (unloadProbeGateEnabled=false)";
        }
        if (config.probePassedAtMs <= 0L) {
            return "unload gate=blocked (run /leancore probe)";
        }
        long ageMin = Math.max(0L, (nowMs - config.probePassedAtMs) / 60_000L);
        if (ageMin < 120L) {
            return String.format(Locale.ROOT, "unload gate=open probePassed=%dm ago", ageMin);
        }
        long ageHours = ageMin / 60L;
        if (ageHours < 48L) {
            return String.format(Locale.ROOT, "unload gate=open probePassed=%dh ago", ageHours);
        }
        return String.format(Locale.ROOT, "unload gate=open probePassed=%dd ago", ageHours / 24L);
    }
}

package com.durkz.leancore.diagnostics;

import jdk.jfr.Event;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Threshold;

/** Optional profiling event, enabled only with -Ddurkz.leancore.jfr=true. */
@Name("com.durkz.leancore.ZoneRanking")
@Label("LeanCore Zone Ranking")
@Enabled(true)
@Threshold("0 ns")
public final class ZoneRankingJfrEvent extends Event {

    private static final boolean ENABLED = Boolean.getBoolean("durkz.leancore.jfr");

    @Label("Operation")
    public String operation;

    @Label("Scanned Zones")
    public int scannedZones;

    @Label("Ranked Zones")
    public int rankedZones;

    private ZoneRankingJfrEvent(String operation) {
        this.operation = operation;
    }

    public static ZoneRankingJfrEvent begin(String operation) {
        if (!ENABLED) {
            return null;
        }
        ZoneRankingJfrEvent event = new ZoneRankingJfrEvent(operation);
        event.begin();
        return event;
    }

    public static void commit(ZoneRankingJfrEvent event, int scannedZones, int rankedZones) {
        if (event == null) {
            return;
        }
        event.scannedZones = scannedZones;
        event.rankedZones = rankedZones;
        event.end();
        event.commit();
    }
}

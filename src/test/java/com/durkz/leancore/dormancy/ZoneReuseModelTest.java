package com.durkz.leancore.dormancy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneReuseModelTest {

    private static final UUID WORLD = new UUID(1L, 2L);

    private static ZoneKey zone(int x, int z) {
        return new ZoneKey(WORLD, x, z);
    }

    @Test
    void neutralUntilTwoVisits() {
        ZoneReuseModel model = new ZoneReuseModel();
        ZoneKey k = zone(0, 0);
        long t = 1_000_000L;
        model.noteHot(k, t);
        assertEquals(0.5D, model.revisitScore(k, t), 1e-9, "single visit stays neutral");
        assertEquals(1.0D, model.thresholdScale(k, 0.5D, 2.0D), 1e-9, "threshold scale neutral until learned");
        assertEquals(1, model.visitCount(k));
    }

    @Test
    void recentlyRevisitedScoresHigherThanOverdue() {
        ZoneReuseModel model = new ZoneReuseModel();
        ZoneKey k = zone(5, 5);
        long step = 60_000L;
        long lastVisit = 0L;
        for (int i = 0; i < 5; i++) {
            lastVisit = i * step;
            model.noteHot(k, lastVisit);
        }
        double justVisited = model.revisitScore(k, lastVisit);
        double overdue = model.revisitScore(k, lastVisit + 10L * step);
        assertTrue(justVisited > overdue, "score decays as the zone goes overdue");
        assertTrue(justVisited > 0.5D, "a frequently revisited zone scores above neutral right after a visit");
    }

    @Test
    void frequentZoneGetsLongerThresholds() {
        ZoneReuseModel model = new ZoneReuseModel();
        ZoneKey frequent = zone(1, 0);
        ZoneKey rare = zone(9, 0);
        long t = 0L;
        for (int i = 0; i < 25; i++) {
            model.noteHot(frequent, t);
            t += 30_000L;
        }
        long t2 = 0L;
        model.noteHot(rare, t2);
        model.noteHot(rare, t2 + 30_000L);

        double frequentScale = model.thresholdScale(frequent, 0.5D, 2.0D);
        double rareScale = model.thresholdScale(rare, 0.5D, 2.0D);
        assertTrue(frequentScale > rareScale, "frequent zones keep longer dormancy thresholds");
        assertTrue(frequentScale <= 2.0D && rareScale >= 0.5D, "scales stay within bounds");
    }

    @Test
    void pruneDropsStaleAndCaps() {
        ZoneReuseModel model = new ZoneReuseModel();
        long now = 10_000_000L;
        model.noteHot(zone(0, 0), now - 1_000_000L);
        model.noteHot(zone(0, 0), now - 900_000L);
        model.noteHot(zone(1, 1), 1L);
        model.noteHot(zone(1, 1), 2_000L);
        int removed = model.prune(now, 500_000L, 100);
        assertTrue(removed >= 1, "stale zone past TTL is pruned");
        assertEquals(0.5D, model.revisitScore(zone(1, 1), now), 1e-9, "pruned zone reverts to neutral");
    }

    @Test
    void exportImportRoundTrip() {
        ZoneReuseModel model = new ZoneReuseModel();
        ZoneKey k = zone(3, 7);
        long t = 0L;
        for (int i = 0; i < 4; i++) {
            model.noteHot(k, t);
            t += 45_000L;
        }
        List<ZoneReuseModel.Record> records = model.export(2);
        assertEquals(1, records.size());

        ZoneReuseModel restored = new ZoneReuseModel();
        for (ZoneReuseModel.Record r : records) {
            restored.importRecord(r);
        }
        assertEquals(model.revisitScore(k, t), restored.revisitScore(k, t), 1e-6,
                "score survives export/import");
        assertEquals(4, restored.visitCount(k));
    }

    @Test
    void contentScoreBlendsAndPersists() {
        ZoneReuseModel model = new ZoneReuseModel();
        ZoneKey k = zone(5, 5);
        model.noteHot(k, 0L);
        model.noteHot(k, 1_000L);

        model.noteContent(k, 1.0D, 2_000L);
        double first = model.contentScore(k);
        assertTrue(first > 0.99D, "first observation sets the content score directly");

        model.noteContent(k, 0.0D, 3_000L);
        assertTrue(model.contentScore(k) < first, "later observations blend the EMA down");

        ZoneReuseModel restored = new ZoneReuseModel();
        for (ZoneReuseModel.Record r : model.export(2)) {
            restored.importRecord(r);
        }
        assertEquals(model.contentScore(k), restored.contentScore(k), 1e-6,
                "content score survives export/import");
    }
}

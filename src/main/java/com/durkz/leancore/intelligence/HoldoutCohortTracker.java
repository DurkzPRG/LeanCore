package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * Rolling heap averages split by holdout vs treatment cohort (10% hash holdout).
 */
public final class HoldoutCohortTracker {

    private final RollingHeapTracker treatment = new RollingHeapTracker();
    private final RollingHeapTracker holdout = new RollingHeapTracker();
    private int treatmentOnline;
    private int holdoutOnline;

    public void noteOnline(Collection<PlayerRef> online, double heapRatio, long nowMs) {
        int treatmentCount = 0;
        int holdoutCount = 0;

        for (PlayerRef ref : online) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            UUID id = ref.getUuid();
            if (id == null) {
                continue;
            }
            if (HoldoutSet.isHoldout(id)) {
                holdoutCount++;
            } else {
                treatmentCount++;
            }
        }

        noteCohorts(treatmentCount, holdoutCount, heapRatio, nowMs);
    }

    public void noteCohorts(int treatmentCount, int holdoutCount, double heapRatio, long nowMs) {
        treatmentOnline = Math.max(0, treatmentCount);
        holdoutOnline = Math.max(0, holdoutCount);
        if (treatmentOnline > 0) {
            treatment.add(heapRatio, nowMs);
        }
        if (holdoutOnline > 0) {
            holdout.add(heapRatio, nowMs);
        }
    }

    public int treatmentOnline() {
        return treatmentOnline;
    }

    public int holdoutOnline() {
        return holdoutOnline;
    }

    public double treatmentHeap60s(long nowMs) {
        return treatment.avg60s(nowMs);
    }

    public double holdoutHeap60s(long nowMs) {
        return holdout.avg60s(nowMs);
    }

    public String statusLine(long nowMs) {
        if (holdoutOnline <= 0 || treatmentOnline + holdoutOnline < 2) {
            return String.format(Locale.ROOT,
                    "holdout cohort n/a (need 2+ online; treatment=%d holdout=%d)",
                    treatmentOnline,
                    holdoutOnline);
        }
        double treatmentAvg = treatmentHeap60s(nowMs);
        double holdoutAvg = holdoutHeap60s(nowMs);
        double deltaPp = (holdoutAvg - treatmentAvg) * 100.0D;
        return String.format(Locale.ROOT,
                "holdout cohort treatment=%d holdout=%d heap60s treatment=%.0f%% holdout=%.0f%% delta=%+.1fpp",
                treatmentOnline,
                holdoutOnline,
                treatmentAvg * 100.0D,
                holdoutAvg * 100.0D,
                deltaPp);
    }
}

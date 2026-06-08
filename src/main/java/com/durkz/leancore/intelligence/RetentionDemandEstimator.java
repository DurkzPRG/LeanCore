package com.durkz.leancore.intelligence;

/**
 * @deprecated Use {@link DemandModel} via {@link LearningStore#demandModel()}.
 */
@Deprecated
public final class RetentionDemandEstimator {

    private RetentionDemandEstimator() {
    }

    public static boolean isHighDemand(double demand) {
        return HeuristicDemandModel.isHighDemand(demand);
    }
}

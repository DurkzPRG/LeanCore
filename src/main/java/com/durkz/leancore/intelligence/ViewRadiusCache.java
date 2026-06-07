package com.durkz.leancore.intelligence;

import java.util.UUID;

public interface ViewRadiusCache {

    void noteViewRadius(UUID playerId, int serverRadius, int clientRadius);
}

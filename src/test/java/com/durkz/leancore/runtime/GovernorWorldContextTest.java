package com.durkz.leancore.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernorWorldContextTest {

    @AfterEach
    void tearDown() {
        GovernorWorldContext.exit();
    }

    @Test
    void inactiveByDefault() {
        assertFalse(GovernorWorldContext.isActive());
        assertFalse(GovernorWorldContext.matchesWorld(null));
    }

    @Test
    void tracksWorldUuidUntilExit() {
        UUID world = UUID.randomUUID();
        GovernorWorldContext.enter(world);
        assertTrue(GovernorWorldContext.isActive());
        GovernorWorldContext.exit();
        assertFalse(GovernorWorldContext.isActive());
    }
}

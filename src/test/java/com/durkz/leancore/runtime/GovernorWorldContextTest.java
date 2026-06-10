package com.durkz.leancore.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernorWorldContextTest {

    @AfterEach
    void tearDown() {
        GovernorWorldContext.exit();
    }

    @Test
    void marksActiveOnlyInsideEnterExit() {
        assertFalse(GovernorWorldContext.isActive());
        GovernorWorldContext.enter();
        assertTrue(GovernorWorldContext.isActive());
        GovernorWorldContext.exit();
        assertFalse(GovernorWorldContext.isActive());
    }
}

package com.durkz.leancore.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDispatchTest {

    @AfterEach
    void tearDown() {
        GovernorWorldContext.exit();
    }

    @Test
    void nullWorldSkipsTask() {
        AtomicBoolean ran = new AtomicBoolean();
        WorldDispatch.run(null, () -> ran.set(true));
        assertFalse(ran.get());
    }

    @Test
    void governorContextDoesNotInlineWithoutMatchingWorld() {
        GovernorWorldContext.enter(UUID.randomUUID());
        try {
            assertFalse(WorldDispatch.shouldRunInline(null));
        } finally {
            GovernorWorldContext.exit();
        }
    }
}

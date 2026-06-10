package com.durkz.leancore.runtime;

import com.durkz.leancore.LeanCorePlugin;

public final class RuntimeGuard {

    private RuntimeGuard() {
    }

    public static boolean active() {
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        if (plugin == null) {
            return false;
        }
        MemoryRuntime runtime = plugin.runtime();
        return runtime != null && runtime.isRunning();
    }
}

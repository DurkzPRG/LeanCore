package com.durkz.leancore.diagnostics;

import com.durkz.leancore.LeanCorePlugin;

import java.util.List;

public final class DiagnosticLog {

    private static final String PREFIX = "[diag] ";

    private DiagnosticLog() {
    }

    public static void info(String message) {
        if (message == null) {
            return;
        }
        try {
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (plugin == null) {
                return;
            }
            plugin.getLogger().atInfo().log("%s", PREFIX + message);
        } catch (Throwable ignored) {
            // Diagnostics must never break the runtime (e.g. plugin not initialized in tests).
        }
    }

    public static void info(List<String> lines) {
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            info(line);
        }
    }
}

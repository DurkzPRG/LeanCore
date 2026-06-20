package com.durkz.leancore.diagnostics;

import com.durkz.leancore.LeanCorePlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DiagnosticLog {

    private static final String PREFIX = "[diag] ";
    private static final Map<String, String> LAST_BY_KEY = new ConcurrentHashMap<>();

    private DiagnosticLog() {
    }

    public static void info(String message) {
        if (message == null) {
            return;
        }
        try {
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (plugin == null || !plugin.config().diagnosticLogEnabled) {
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

    /**
     * Logs {@code message} only when it differs from the last message logged under {@code key}.
     * Used for change-only decision reasoning so a steady decision is not re-logged every tick.
     */
    public static void infoOnChange(String key, String message) {
        if (key == null || message == null) {
            return;
        }
        String previous = LAST_BY_KEY.put(key, message);
        if (message.equals(previous)) {
            return;
        }
        info(message);
    }
}

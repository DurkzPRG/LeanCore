package com.durkz.leancore.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class HudSessionStore {

    private static final String STATE_FILE = "hud.state";
    private static final String KEY_PREFIX = "hud.";

    private final Path dataDir;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public HudSessionStore(Path dataDir) {
        this.dataDir = dataDir;
        load();
    }

    public boolean isEnabled(UUID uuid) {
        return uuid != null && enabled.contains(uuid);
    }

    public void setEnabled(UUID uuid, boolean on) {
        if (uuid == null) {
            return;
        }
        if (on) {
            enabled.add(uuid);
        } else {
            enabled.remove(uuid);
        }
        save();
    }

    public Set<UUID> enabledPlayers() {
        return Set.copyOf(enabled);
    }

    private void load() {
        Path file = dataDir.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
            return;
        }
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            if (!"true".equalsIgnoreCase(props.getProperty(key))) {
                continue;
            }
            try {
                enabled.add(UUID.fromString(key.substring(KEY_PREFIX.length())));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        if (dataDir != null && !Files.isDirectory(dataDir)) {
            try {
                Files.createDirectories(dataDir);
            } catch (IOException ignored) {
                return;
            }
        }
        Properties props = new Properties();
        for (UUID uuid : enabled) {
            props.setProperty(KEY_PREFIX + uuid, "true");
        }
        Path file = dataDir.resolve(STATE_FILE);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "LeanCore HUD toggles");
        } catch (IOException ignored) {
        }
    }

    public String summary() {
        return enabled.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}

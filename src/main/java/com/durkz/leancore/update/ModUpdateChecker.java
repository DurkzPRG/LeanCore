package com.durkz.leancore.update;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.permissions.LeanCorePermissions;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the LeanCore page for a newer JAR and notifies only operators or explicit admins. */
public final class ModUpdateChecker {

    private static final ModUpdateChecker INSTANCE = new ModUpdateChecker();
    private static final String PAGE_URL = "https://durkzprgmods.pages.dev/mods/leancore";
    private static final String DOWNLOAD_URL = "https://www.curseforge.com/hytale/mods/leancore";
    private static final Pattern JAR_VERSION = Pattern.compile("LeanCore-(\\d+(?:\\.\\d+){1,3})\\.jar");

    private final AtomicReference<String> latestVersion = new AtomicReference<>();
    private final Set<UUID> notifiedThisSession = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    private String currentVersion = "0";

    private ModUpdateChecker() {
    }

    public static ModUpdateChecker getInstance() {
        return INSTANCE;
    }

    public void start(JavaPlugin plugin, LeanCoreConfig config) {
        if (!config.checkForUpdates) {
            plugin.getLogger().atInfo().log("Update check disabled.");
            return;
        }
        stopped = false;
        currentVersion = plugin.getManifest().getVersion().toString();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(PAGE_URL))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "LeanCore/" + currentVersion + " (Hytale plugin update check)")
                .GET()
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> handleResponse(plugin, response))
                .exceptionally(error -> {
                    plugin.getLogger().atWarning().withCause(error).log("Update check request failed.");
                    return null;
                });
    }

    public void shutdown() {
        stopped = true;
        latestVersion.set(null);
        notifiedThisSession.clear();
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            notifiedThisSession.remove(playerId);
        }
    }

    public void notifyPlayer(PlayerRef playerRef) {
        if (stopped || !LeanCorePermissions.canReceiveUpdateNotice(playerRef)) {
            return;
        }
        String latest = latestVersion.get();
        if (latest == null || !isNewer(latest, currentVersion) || !notifiedThisSession.add(playerRef.getUuid())) {
            return;
        }
        playerRef.sendMessage(Message.raw("[LeanCore] Update available: " + latest + " (running " + currentVersion + ")")
                .color("#FFAA00"));
        playerRef.sendMessage(rainbowLink("Click here to download on CurseForge", DOWNLOAD_URL));
    }

    private void handleResponse(JavaPlugin plugin, HttpResponse<String> response) {
        if (stopped) {
            return;
        }
        if (response.statusCode() != 200) {
            plugin.getLogger().atWarning().log("Update check HTTP " + response.statusCode());
            return;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            plugin.getLogger().atWarning().log("Update check got an empty page.");
            return;
        }
        Matcher matcher = JAR_VERSION.matcher(body);
        if (!matcher.find()) {
            plugin.getLogger().atWarning().log("Update check could not find LeanCore-*.jar on the mod page.");
            return;
        }
        String latest = matcher.group(1);
        latestVersion.set(latest);
        if (isNewer(latest, currentVersion)) {
            plugin.getLogger().atInfo().log("Newer LeanCore available: " + latest + " (running " + currentVersion + ")");
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (PlayerRef online : universe.getPlayers()) {
                notifyPlayer(online);
            }
        } else {
            plugin.getLogger().atInfo().log("LeanCore is up to date (" + currentVersion + ").");
        }
    }

    static boolean isNewer(String latest, String current) {
        return compareVersions(current, latest) < 0;
    }

    static int compareVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        int[] parts = new int[3];
        if (version == null || version.isBlank()) {
            return parts;
        }
        int cut = version.indexOf('-');
        String numeric = cut >= 0 ? version.substring(0, cut) : version;
        if (numeric.startsWith("v") || numeric.startsWith("V")) {
            numeric = numeric.substring(1);
        }
        String[] split = numeric.split("\\.");
        for (int i = 0; i < 3 && i < split.length; i++) {
            try {
                parts[i] = Integer.parseInt(split[i]);
            } catch (NumberFormatException ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static Message rainbowLink(String text, String url) {
        Message root = Message.empty().link(url);
        int length = text.length();
        for (int i = 0; i < length; i++) {
            float hue = length == 1 ? 0F : (float) i / (length - 1);
            root.insert(Message.raw(String.valueOf(text.charAt(i))).color(hueToHex(hue)).link(url));
        }
        return root;
    }

    private static String hueToHex(float hue) {
        int sector = (int) (hue * 6);
        float fraction = hue * 6 - sector;
        int rising = Math.round(255 * fraction);
        int falling = 255 - rising;
        int red;
        int green;
        int blue;
        switch (sector % 6) {
            case 0 -> { red = 255; green = rising; blue = 0; }
            case 1 -> { red = falling; green = 255; blue = 0; }
            case 2 -> { red = 0; green = 255; blue = rising; }
            case 3 -> { red = 0; green = falling; blue = 255; }
            case 4 -> { red = rising; green = 0; blue = 255; }
            default -> { red = 255; green = 0; blue = falling; }
        }
        return String.format("#%02X%02X%02X", red, green, blue);
    }
}

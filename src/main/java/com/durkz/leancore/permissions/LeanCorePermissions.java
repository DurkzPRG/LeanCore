package com.durkz.leancore.permissions;

import com.durkz.leancore.config.LeanCoreConfig;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class LeanCorePermissions {

    public static final String HUD_VIEW = "durkz.leancore.hud";
    public static final String HUD_ADMIN = "durkz.leancore.admin";

    private LeanCorePermissions() {
    }

    public static void register() {
        PermissionsModule.registerPermission(HUD_VIEW);
        PermissionsModule.registerPermission(HUD_ADMIN);
    }

    public static boolean canViewHud(UUID uuid, LeanCoreConfig config) {
        if (uuid == null || config == null || !config.hudFeatureEnabled) {
            return false;
        }
        if (hasPermission(uuid, HUD_VIEW) || hasPermission(uuid, HUD_ADMIN)) {
            return true;
        }
        return inAnyGroup(uuid, config.hudViewerGroups) || inAnyGroup(uuid, config.hudAdminGroups);
    }

    public static boolean canAdminHud(UUID uuid, LeanCoreConfig config) {
        if (uuid == null || config == null || !config.hudFeatureEnabled) {
            return false;
        }
        if (hasPermission(uuid, HUD_ADMIN)) {
            return true;
        }
        return inAnyGroup(uuid, config.hudAdminGroups);
    }

    private static boolean hasPermission(UUID uuid, String permission) {
        PermissionsModule module = PermissionsModule.get();
        return module != null && module.hasPermission(uuid, permission);
    }

    private static boolean inAnyGroup(UUID uuid, String[] groups) {
        if (groups == null || groups.length == 0) {
            return false;
        }
        PermissionsModule module = PermissionsModule.get();
        if (module == null) {
            return false;
        }
        Set<String> userGroups = module.getGroupsForUser(uuid);
        if (userGroups == null || userGroups.isEmpty()) {
            return false;
        }
        Set<String> wanted = new HashSet<>(Arrays.asList(groups));
        for (String group : userGroups) {
            if (wanted.contains(group) || wanted.contains(HytalePermissionsProvider.resolveGroupName(group))) {
                return true;
            }
        }
        return false;
    }
}

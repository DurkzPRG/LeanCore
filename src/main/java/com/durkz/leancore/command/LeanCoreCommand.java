package com.durkz.leancore.command;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneHeatmapEntry;
import com.durkz.leancore.dormancy.ZoneKey;
import com.durkz.leancore.dormancy.ZoneState;
import com.durkz.leancore.intelligence.BehaviorPosterior;
import com.durkz.leancore.intelligence.HoldoutSet;
import com.durkz.leancore.permissions.LeanCorePermissions;
import com.durkz.leancore.intelligence.PlayerFeatureState;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.memory.GovernorStatus;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.SavingsReport;
import com.durkz.leancore.probe.ApiProbe;
import com.durkz.leancore.probe.UnloadProbeGate;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.durkz.leancore.runtime.RuntimeProfile;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class LeanCoreCommand extends AbstractAsyncCommand {

    public LeanCoreCommand() {
        super("leancore", "LeanCore diagnostics");
        addSubCommand(new StatusCmd());
        addSubCommand(new MemoryCmd());
        addSubCommand(new SavingsCmd());
        addSubCommand(new ZonesCmd());
        addSubCommand(new ProbeCmd());
        addSubCommand(new LearnCmd());
        addSubCommand(new HudCmd());
        addSubCommand(new HeatmapCmd());
        addSubCommand(new ZoneCmd());
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
        return CompletableFuture.completedFuture(null);
    }

    private static LeanCorePlugin plugin(CommandContext ctx) {
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        if (plugin == null) {
            ctx.sendMessage(Message.raw("LeanCore not loaded").color("#FF5555"));
            return null;
        }
        return plugin;
    }

    private static MemoryRuntime runtime(CommandContext ctx) {
        LeanCorePlugin plugin = plugin(ctx);
        if (plugin == null) {
            return null;
        }
        if (plugin.isPassiveMode()) {
            say(ctx, "PASSIVE mode — runtime disabled. Use localHostMode AUTO.", "#FFAA00");
            return null;
        }
        if (plugin.runtime() == null) {
            ctx.sendMessage(Message.raw("LeanCore runtime unavailable").color("#FF5555"));
            return null;
        }
        return plugin.runtime();
    }

    private static void say(CommandContext ctx, String text, String color) {
        ctx.sendMessage(Message.raw(text).color(color));
    }

    private static final class StatusCmd extends CommandBase {
        StatusCmd() {
            super("status", "Runtime status");
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            LeanCorePlugin plugin = plugin(ctx);
            if (plugin == null) {
                return;
            }
            if (plugin.isPassiveMode()) {
                var sample = plugin.sampleHeapOnce();
                say(ctx, "LeanCore " + plugin.getManifest().getVersion()
                        + " | PASSIVE | " + sample.onlinePlayers() + " online", "#55FF55");
                say(ctx, "tier " + sample.tier() + " | spread " + (int) sample.playerSpreadBlocks() + " blocks", "#AAAAAA");
                say(ctx, "set localHostMode AUTO to enable scaled solo/friends runtime", "#888888");
                return;
            }
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            var sample = rt.lastSample();
            say(ctx, "LeanCore " + plugin.getManifest().getVersion()
                    + " | profile " + rt.activeProfile()
                    + " | " + rt.lastMode()
                    + " | " + sample.onlinePlayers() + " online", "#FFAA00");
            if (rt.activeProfile() == com.durkz.leancore.runtime.RuntimeProfile.LITE) {
                say(ctx, "solo=LITE motion-gated dormancy adaptiveTick="
                        + plugin.config().soloAdaptiveTickEnabled, "#888888");
            }
            say(ctx, "tier " + sample.tier() + " | spread " + (int) sample.playerSpreadBlocks() + " blocks", "#AAAAAA");
            var gov = rt.governorStatus();
            if (gov.enabled() && gov.policy() != null) {
                say(ctx, "preset " + gov.preset() + " | policy " + gov.policy().key()
                        + " | view " + String.format(Locale.ROOT, "%.0f%%", gov.policy().viewScale() * 100.0D), "#AAAAAA");
            }
            say(ctx, rt.learningStore().statusLine(), "#888888");
            say(ctx, rt.learningStore().windowLine(), "#888888");
            say(ctx, rt.learningStore().serverLine(), "#888888");
            LeanCoreConfig config = plugin.config();
            long nowMs = System.currentTimeMillis();
            say(ctx, String.format(Locale.ROOT,
                    "flags govern=%s learning=%s embeddedStd=%s unload=%s",
                    config.governEnabled,
                    config.learningEnabled,
                    config.embeddedStandardProfile,
                    config.unloadEnabled), "#888888");
            if (rt.activeProfile() == RuntimeProfile.LITE) {
                say(ctx, String.format(Locale.ROOT,
                        "lite gov=%s view=%s learning=%s unload=%s",
                        config.liteMemoryGovernorEnabled,
                        config.liteViewRadiusEnabled,
                        config.liteLearningEnabled,
                        config.liteUnloadEnabled), "#888888");
            }
            say(ctx, UnloadProbeGate.statusLine(config, nowMs), "#888888");
        }
    }

    private static final class MemoryCmd extends CommandBase {
        MemoryCmd() {
            super("memory", "Heap snapshot");
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            LeanCorePlugin plugin = plugin(ctx);
            if (plugin == null) {
                return;
            }
            var s = plugin.isPassiveMode() ? plugin.sampleHeapOnce() : null;
            MemoryRuntime rt = plugin.isPassiveMode() ? null : runtime(ctx);
            if (!plugin.isPassiveMode() && rt != null) {
                s = rt.lastSample();
            }
            if (s == null) {
                return;
            }
            say(ctx, String.format(Locale.ROOT, "heap %d/%d MB (%.0f%%) tier=%s",
                    s.heapUsedBytes() / (1024 * 1024),
                    s.heapMaxBytes() / (1024 * 1024),
                    s.heapUsedRatio() * 100.0D,
                    s.tier()), "#FFAA00");
            if (plugin.isPassiveMode()) {
                say(ctx, "on-demand sample (PASSIVE)", "#888888");
                return;
            }
            if (rt != null) {
                say(ctx, "profile " + rt.activeProfile(), "#888888");
            }
            var gov = rt.governorStatus();
            if (!gov.enabled()) {
                say(ctx, "governor disabled", "#888888");
                return;
            }
            say(ctx, String.format(Locale.ROOT,
                    "footprint %d/%d MB | demoted=%d reclaimed~%d MB | unloaded=%d chunks candidates=%d",
                    gov.totalFootprintMb(),
                    gov.budgetMb(),
                    gov.demotedZones(),
                    gov.reclaimedMbEstimate(),
                    gov.unloadedChunks(),
                    gov.unloadCandidateZones()), "#AAAAAA");
            if (gov.rolledBack()) {
                say(ctx, "rollback active (policy reverted)", "#FF8888");
            }
        }
    }

    private static final class SavingsCmd extends CommandBase {
        SavingsCmd() {
            super("savings", "Session JVM heap and governor activity summary");
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = plugin(ctx);
            if (rt == null || plugin == null) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            MemorySnapshot sample = rt.freshSample();
            GovernorStatus governor = rt.governorStatus();
            for (SavingsReport.Line line : SavingsReport.format(
                    sample,
                    rt.sessionSavings(),
                    governor,
                    plugin.config(),
                    rt.activeProfile(),
                    rt.learningStore().unloadOutcomeTracker(),
                    rt.gcHintScheduler(),
                    rt.viewRadiusGraceUntilMs(),
                    rt.liteSessionStartedMs(),
                    nowMs
            )) {
                say(ctx, line.text(), line.color());
            }
        }
    }

    private static final class ZonesCmd extends CommandBase {
        ZonesCmd() {
            super("zones", "Dormancy map");
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            var map = rt.dormancyMap();
            say(ctx, String.format("hot=%d warm=%d dormant=%d frozen=%d",
                    map.countByState(ZoneState.HOT),
                    map.countByState(ZoneState.WARM),
                    map.countByState(ZoneState.DORMANT),
                    map.countByState(ZoneState.FROZEN)), "#FFAA00");
            for (String line : map.topZones(6)) {
                say(ctx, line, "#AAAAAA");
            }
        }
    }

    private static final class LearnCmd extends CommandBase {
        LearnCmd() {
            super("learn", "Learning diagnostics");
            addSubCommand(new LearnPlayerCmd());
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            LeanCorePlugin plugin = plugin(ctx);
            LeanCoreConfig config = plugin != null ? plugin.config() : null;
            boolean liteProfile = rt.activeProfile() == RuntimeProfile.LITE;

            say(ctx, rt.learningStore().statusLine(), "#FFAA00");
            say(ctx, rt.learningStore().mlStatusLine(), "#FFAA00");
            say(ctx, rt.learningStore().unloadOutcomeTracker().statusLine(), "#AAAAAA");
            say(ctx, rt.learningStore().windowLine(), "#888888");
            say(ctx, rt.learningStore().serverLine(), "#888888");

            if (liteProfile) {
                if (config != null) {
                    say(ctx, UnloadProbeGate.statusLine(config, System.currentTimeMillis()), "#888888");
                }
                say(ctx, "LITE learning: demand model + persistence; no bandit or holdout in solo", "#888888");
            } else {
                say(ctx, rt.learningStore().policyBandit().topArmLine(), "#AAAAAA");
                say(ctx, rt.learningStore().holdoutStatusLine(), "#AAAAAA");
                if (config != null) {
                    say(ctx, UnloadProbeGate.statusLine(config, System.currentTimeMillis()), "#888888");
                }
                say(ctx, rt.regionalPressureCache().statusLine(), "#888888");
                say(ctx, "holdout=10% skips view-radius cuts; bandit learns from treatment cohort only", "#888888");
            }
        }
    }

    private static final class LearnPlayerCmd extends AbstractPlayerCommand {
        LearnPlayerCmd() {
            super("player", "Your demand and feature snapshot");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            var demands = rt.classifier().snapshotDemands(nowMs);
            RetentionDemand demand = demands.getOrDefault(
                    playerRef.getUuid(),
                    rt.learningStore().demandFor(playerRef.getUuid())
            );
            PlayerFeatureState features = rt.classifier().features().snapshot().get(playerRef.getUuid());
            say(ctx, String.format(Locale.ROOT,
                    "demand=%.2f confidence=%.2f retention=%d MB viewScale=%.2f label=%s",
                    demand.demand(),
                    demand.confidence(),
                    demand.retentionMb(),
                    demand.viewScale(),
                    demand.debugLabel()), "#FFAA00");
            if (features == null) {
                say(ctx, "no live features yet", "#888888");
                return;
            }
            say(ctx, String.format(Locale.ROOT,
                    "features move60=%.1f break60=%.1f chunks60=%.1f idle=%ds observed=%ds holdout=%s",
                    features.emaMovement60(),
                    features.emaBreaks60(),
                    features.emaChunks60(),
                    features.idleSec(nowMs),
                    features.observedSec(),
                    rt.activeProfile() == RuntimeProfile.LITE
                            ? "n/a"
                            : Boolean.toString(HoldoutSet.isHoldout(playerRef.getUuid()))), "#AAAAAA");
            say(ctx, BehaviorPosterior.formatTopThree(features, rt.learningStore().activityClassifier(), nowMs), "#888888");
            say(ctx, rt.learningStore().activityClassifier().statusLine(features, nowMs), "#888888");
            say(ctx, String.format(Locale.ROOT,
                    "activity mine=%.1f wood=%.1f farm=%.1f build=%.1f craft=%.1f combat=%.1f",
                    features.emaMine60(),
                    features.emaWood60(),
                    features.emaFarm60(),
                    features.emaBuild60(),
                    features.emaCraft60(),
                    features.emaCombat60()), "#AAAAAA");
            LeanCorePlugin lp = plugin(ctx);
            if (lp != null && lp.config().motionModelEnabled) {
                say(ctx, motionLine(features, lp.config()), "#AAAAAA");
            }
        }
    }

    private static String motionLine(PlayerFeatureState features, LeanCoreConfig cfg) {
        long horizonMs = Math.max(0, cfg.motionPredictionHorizonSeconds) * 1000L;
        double[] predicted = features.predictedXZ(horizonMs);
        String predStr = predicted == null
                ? "n/a"
                : String.format(Locale.ROOT, "%.0f,%.0f", predicted[0], predicted[1]);
        return String.format(Locale.ROOT,
                "motion speed=%.1f b/s conf=%.2f horizon=%ds predicted=%s viewBoost=%.2f",
                features.speedBlocksPerSec(),
                features.motionConfidence(),
                cfg.motionPredictionHorizonSeconds,
                predStr,
                features.motionViewScale(cfg.motionMinSpeedBlocksPerSecond, cfg.motionViewRadiusMaxBoost));
    }

    private static final class HudCmd extends CommandBase {
        HudCmd() {
            super("hud", "Toggle memory HUD overlay");
            addSubCommand(new HudOnCmd());
            addSubCommand(new HudOffCmd());
            addSubCommand(new HudStatusCmd());
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            say(ctx, "usage: /leancore hud on | off | status", "#AAAAAA");
        }
    }

    private static final class HudOnCmd extends AbstractPlayerCommand {
        HudOnCmd() {
            super("on", "Show memory HUD");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (rt == null || plugin == null) {
                return;
            }
            LeanCoreConfig config = plugin.config();
            if (!config.hudFeatureEnabled) {
                say(ctx, "HUD disabled in LeanCore.json", "#FF8888");
                return;
            }
            if (!LeanCorePermissions.canViewHud(playerRef.getUuid(), config)) {
                say(ctx, "no permission for LeanCore HUD", "#FF8888");
                return;
            }
            if (!rt.hudService().enable(playerRef, store, ref)) {
                say(ctx, "could not enable HUD", "#FF8888");
                return;
            }
            say(ctx, "LeanCore HUD on", "#FFAA00");
        }
    }

    private static final class HudOffCmd extends AbstractPlayerCommand {
        HudOffCmd() {
            super("off", "Hide memory HUD");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            rt.hudService().disable(playerRef, store, ref);
            say(ctx, "LeanCore HUD off", "#AAAAAA");
        }
    }

    private static final class HudStatusCmd extends AbstractPlayerCommand {
        HudStatusCmd() {
            super("status", "HUD eligibility and toggle state");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (rt == null || plugin == null) {
                return;
            }
            LeanCoreConfig config = plugin.config();
            boolean eligible = LeanCorePermissions.canViewHud(playerRef.getUuid(), config);
            boolean on = rt.hudService().sessions().isEnabled(playerRef.getUuid());
            say(ctx, String.format(Locale.ROOT, "hud eligible=%s toggled=%s feature=%s",
                    eligible, on, config.hudFeatureEnabled), "#FFAA00");
        }
    }

    private static final class HeatmapCmd extends AbstractPlayerCommand {
        private final OptionalArg<Integer> limitArg;

        HeatmapCmd() {
            super("heatmap", "Zone dormancy heatmap");
            this.limitArg = withOptionalArg("limit", "Max rows", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (rt == null || plugin == null) {
                return;
            }
            LeanCoreConfig config = plugin.config();
            if (!LeanCorePermissions.canAdminHud(playerRef.getUuid(), config)) {
                say(ctx, "no permission for heatmap", "#FF8888");
                return;
            }
            int limit = config.heatmapDefaultLimit;
            if (limitArg.provided(ctx)) {
                limit = Math.max(1, limitArg.get(ctx));
            }
            var map = rt.dormancyMap();
            say(ctx, String.format(Locale.ROOT, "heatmap limit=%d hot=%d warm=%d dormant=%d frozen=%d pinned=%d",
                    limit,
                    map.countByState(ZoneState.HOT),
                    map.countByState(ZoneState.WARM),
                    map.countByState(ZoneState.DORMANT),
                    map.countByState(ZoneState.FROZEN),
                    map.pinnedZones().size()), "#FFAA00");
            for (ZoneHeatmapEntry row : map.heatmapEntries(limit)) {
                say(ctx, String.format(Locale.ROOT, "%s %s idle=%dm dist=%d%s",
                        row.key(),
                        row.state(),
                        row.idleMinutes(),
                        row.distanceBlocks(),
                        row.pinned() ? " PIN" : ""), "#AAAAAA");
            }
        }
    }

    private static final class ZoneCmd extends CommandBase {
        ZoneCmd() {
            super("zone", "Zone pin overrides");
            addSubCommand(new ZonePinCmd());
            addSubCommand(new ZoneUnpinCmd());
            addSubCommand(new ZonePinsCmd());
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            say(ctx, "usage: /leancore zone pin | unpin | pins", "#AAAAAA");
        }
    }

    private static final class ZonePinCmd extends AbstractPlayerCommand {
        ZonePinCmd() {
            super("pin", "Pin current zone as HOT");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (rt == null || plugin == null) {
                return;
            }
            if (!LeanCorePermissions.canAdminHud(playerRef.getUuid(), plugin.config())) {
                say(ctx, "no permission for zone pin", "#FF8888");
                return;
            }
            Transform t = playerRef.getTransform();
            if (t == null || t.getPosition() == null) {
                say(ctx, "no position", "#FF8888");
                return;
            }
            ZoneKey key = ZoneKey.fromBlockCoords(playerRef.getWorldUuid(), t.getPosition().x, t.getPosition().z);
            if (!rt.dormancyMap().pinZone(key)) {
                say(ctx, "pin failed (max " + plugin.config().zonePinMaxCount + ")", "#FF8888");
                return;
            }
            say(ctx, "pinned zone " + key, "#FFAA00");
        }
    }

    private static final class ZoneUnpinCmd extends AbstractPlayerCommand {
        ZoneUnpinCmd() {
            super("unpin", "Unpin current zone");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (rt == null || plugin == null) {
                return;
            }
            if (!LeanCorePermissions.canAdminHud(playerRef.getUuid(), plugin.config())) {
                say(ctx, "no permission for zone unpin", "#FF8888");
                return;
            }
            Transform t = playerRef.getTransform();
            if (t == null || t.getPosition() == null) {
                say(ctx, "no position", "#FF8888");
                return;
            }
            ZoneKey key = ZoneKey.fromBlockCoords(playerRef.getWorldUuid(), t.getPosition().x, t.getPosition().z);
            if (!rt.dormancyMap().unpinZone(key)) {
                say(ctx, "zone was not pinned", "#888888");
                return;
            }
            say(ctx, "unpinned zone " + key, "#AAAAAA");
        }
    }

    private static final class ZonePinsCmd extends AbstractPlayerCommand {
        ZonePinsCmd() {
            super("pins", "List pinned zones");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            if (rt == null || plugin == null) {
                return;
            }
            if (!LeanCorePermissions.canAdminHud(playerRef.getUuid(), plugin.config())) {
                say(ctx, "no permission for zone pins", "#FF8888");
                return;
            }
            var pins = rt.dormancyMap().pinnedZones();
            if (pins.isEmpty()) {
                say(ctx, "no pinned zones", "#888888");
                return;
            }
            say(ctx, "pinned=" + pins.size(), "#FFAA00");
            for (ZoneKey key : pins) {
                say(ctx, key.toString(), "#AAAAAA");
            }
        }
    }

    private static final class ProbeCmd extends AbstractPlayerCommand {
        ProbeCmd() {
            super("probe", "API capability check");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            MemoryRuntime rt = runtime(ctx);
            LeanCorePlugin plugin = plugin(ctx);
            if (rt == null || plugin == null) {
                return;
            }
            var lines = ApiProbe.run(
                    store,
                    ref,
                    playerRef,
                    world,
                    rt.dormancyMap(),
                    rt.zoneChunkUnloader(),
                    rt.lastSample().tier());
            boolean passed = ApiProbe.passed(lines);
            for (String line : lines) {
                say(ctx, line, "#AAAAAA");
            }
            if (passed) {
                LeanCoreConfig config = plugin.config();
                config.probePassedAtMs = System.currentTimeMillis();
                config.save();
                say(ctx, "probe PASSED — unload gate open (saved to LeanCore.json)", "#55FF55");
            } else {
                say(ctx, "probe FAILED — fix failing steps before enabling unload", "#FF8888");
            }
            if (plugin.config().motionModelEnabled) {
                PlayerFeatureState features = rt.classifier().features().snapshot().get(playerRef.getUuid());
                if (features != null) {
                    say(ctx, motionLine(features, plugin.config()), "#AAAAAA");
                }
            }
        }
    }
}

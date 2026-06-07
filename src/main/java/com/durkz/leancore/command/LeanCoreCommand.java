package com.durkz.leancore.command;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.dormancy.ZoneState;
import com.durkz.leancore.probe.ApiProbe;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
        addSubCommand(new ZonesCmd());
        addSubCommand(new ProbeCmd());
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext ctx) {
        return CompletableFuture.completedFuture(null);
    }

    private static MemoryRuntime runtime(CommandContext ctx) {
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        if (plugin == null || plugin.runtime() == null) {
            ctx.sendMessage(Message.raw("LeanCore not loaded").color("#FF5555"));
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
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            var sample = rt.lastSample();
            LeanCorePlugin plugin = LeanCorePlugin.getInstance();
            say(ctx, "LeanCore " + plugin.getManifest().getVersion()
                    + " | " + rt.lastMode() + " | " + sample.onlinePlayers() + " online", "#FFAA00");
            say(ctx, "tier " + sample.tier() + " | spread " + (int) sample.playerSpreadBlocks() + " blocks", "#AAAAAA");
            var gov = rt.governorStatus();
            if (gov.enabled() && gov.policy() != null) {
                say(ctx, "preset " + gov.preset() + " | policy " + gov.policy().key()
                        + " | view " + String.format(Locale.ROOT, "%.0f%%", gov.policy().viewScale() * 100.0D), "#AAAAAA");
            }
            say(ctx, rt.learningStore().statusLine(), "#888888");
            say(ctx, rt.learningStore().windowLine(), "#888888");
        }
    }

    private static final class MemoryCmd extends CommandBase {
        MemoryCmd() {
            super("memory", "Heap snapshot");
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            MemoryRuntime rt = runtime(ctx);
            if (rt == null) {
                return;
            }
            var s = rt.lastSample();
            say(ctx, String.format(Locale.ROOT, "heap %d/%d MB (%.0f%%) tier=%s",
                    s.heapUsedBytes() / (1024 * 1024),
                    s.heapMaxBytes() / (1024 * 1024),
                    s.heapUsedRatio() * 100.0D,
                    s.tier()), "#FFAA00");
            var gov = rt.governorStatus();
            if (!gov.enabled()) {
                say(ctx, "governor disabled", "#888888");
                return;
            }
            say(ctx, String.format(Locale.ROOT, "footprint %d/%d MB | demoted=%d reclaimed~%d MB",
                    gov.totalFootprintMb(), gov.budgetMb(), gov.demotedZones(), gov.reclaimedMbEstimate()), "#AAAAAA");
            if (gov.rolledBack()) {
                say(ctx, "rollback active (policy reverted)", "#FF8888");
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

    private static final class ProbeCmd extends AbstractPlayerCommand {
        ProbeCmd() {
            super("probe", "API capability check");
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            for (String line : ApiProbe.run(store, ref, playerRef)) {
                say(ctx, line, "#AAAAAA");
            }
        }
    }
}

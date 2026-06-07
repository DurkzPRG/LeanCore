package com.durkz.leancore.command;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.dormancy.ZoneState;
import com.durkz.leancore.probe.ApiProbe;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

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
            say(ctx, rt.learningStore().statusLine(), "#888888");
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
            say(ctx, "governor idle until v0.2", "#888888");
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

    private static final class ProbeCmd extends CommandBase {
        ProbeCmd() {
            super("probe", "API capability check");
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            for (String line : ApiProbe.run()) {
                say(ctx, line, "#AAAAAA");
            }
        }
    }
}

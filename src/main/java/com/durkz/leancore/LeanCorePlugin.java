package com.durkz.leancore;

import com.durkz.leancore.command.LeanCoreCommand;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.BehaviorClassifier;
import com.durkz.leancore.intelligence.BehaviorSignalSystems;
import com.durkz.leancore.intelligence.ChunkSignalSystems;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.permissions.LeanCorePermissions;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public class LeanCorePlugin extends JavaPlugin {

    private static LeanCorePlugin instance;

    private LeanCoreConfig config;
    private MemoryRuntime runtime;

    public LeanCorePlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static LeanCorePlugin getInstance() {
        return instance;
    }

    public LeanCoreConfig config() {
        return config;
    }

    public MemoryRuntime runtime() {
        return runtime;
    }

    @Override
    protected void setup() {
        super.setup();

        config = LeanCoreConfig.load(getDataDirectory());
        LeanCorePermissions.register();
        LearningStore learning = new LearningStore(getDataDirectory(), config);
        BehaviorClassifier classifier = new BehaviorClassifier(learning);

        getEventRegistry().registerGlobal(PlayerConnectEvent.class, e -> {
            if (e.getPlayerRef() != null) {
                classifier.profileFor(e.getPlayerRef());
            }
        });
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> {
            if (e.getPlayerRef() != null) {
                if (runtime != null && runtime.hudService() != null) {
                    runtime.hudService().onDisconnect(e.getPlayerRef().getUuid());
                }
                classifier.forget(e.getPlayerRef().getUuid());
                classifier.syncToStore(learning);
                learning.flush();
            }
        });
        getEventRegistry().registerGlobal(ShutdownEvent.class, e -> {
            classifier.syncToStore(learning);
            learning.flush();
        });

        BehaviorSignalSystems.register(getEntityStoreRegistry(), classifier);
        ChunkSignalSystems.register(getChunkStoreRegistry(), learning.unloadOutcomeTracker());
        getCommandRegistry().registerCommand(new LeanCoreCommand());

        runtime = MemoryRuntime.create(this, config, classifier, learning);

        getLogger().atInfo().log("LeanCore %s setup.", getManifest().getVersion());
    }

    @Override
    protected void start() {
        super.start();
        if (runtime != null) {
            runtime.start();
        }
    }

    @Override
    protected void shutdown() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
        instance = null;
        super.shutdown();
    }
}

package com.durkz.leancore;

import com.durkz.leancore.command.LeanCoreCommand;
import com.durkz.leancore.config.DedicatedBootstrap;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.BehaviorClassifier;
import com.durkz.leancore.intelligence.BehaviorSignalSystems;
import com.durkz.leancore.intelligence.CombatSignalSystems;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.memory.MemoryPressureSensor;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.ServerContextTracker;
import com.durkz.leancore.permissions.LeanCorePermissions;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.durkz.leancore.runtime.RuntimeActivationPolicy;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.Set;

public class LeanCorePlugin extends JavaPlugin {

    private static LeanCorePlugin instance;

    private LeanCoreConfig config;
    private MemoryRuntime runtime;
    private MemoryPressureSensor passiveHeapSensor;

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

    public boolean isPassiveMode() {
        return RuntimeActivationPolicy.isFullyPassive(config);
    }

    public MemorySnapshot sampleHeapOnce() {
        if (runtime != null) {
            return runtime.lastSample();
        }
        if (passiveHeapSensor == null) {
            passiveHeapSensor = new MemoryPressureSensor(new ServerContextTracker(config));
        }
        return passiveHeapSensor.sample();
    }

    @Override
    protected void setup() {
        super.setup();

        config = LeanCoreConfig.load(getDataDirectory());
        if (DedicatedBootstrap.applyIfNeeded(config)) {
            getLogger().atInfo().log(
                    "Dedicated bootstrap applied — governEnabled, viewRadiusGovernanceEnabled, "
                            + "learningEnabled (unloadEnabled stays false)"
            );
        }
        LeanCorePermissions.register();
        getCommandRegistry().registerCommand(new LeanCoreCommand());

        if (RuntimeActivationPolicy.isFullyPassive(config)) {
            getLogger().atInfo().log(
                    "LeanCore %s local passive — set localHostMode AUTO for scaled runtime.",
                    getManifest().getVersion()
            );
            return;
        }

        LearningStore learning = new LearningStore(getDataDirectory(), config,
                (message, cause) -> getLogger().atWarning().withCause(cause).log("%s", message));
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
                learning.flush(true, Set.of(e.getPlayerRef().getUuid()));
            }
        });
        getEventRegistry().registerGlobal(ShutdownEvent.class, e -> {
            if (runtime != null) {
                runtime.shutdown();
            } else {
                classifier.syncToStore(learning);
                learning.flush(true, classifier.features().snapshot().keySet());
            }
        });

        BehaviorSignalSystems.register(getEntityStoreRegistry(), classifier);
        CombatSignalSystems.register(getEntityStoreRegistry(), classifier);
        runtime = MemoryRuntime.create(this, config, classifier, learning);

        getLogger().atInfo().log(
                "LeanCore %s setup (localHostMode=%s).",
                getManifest().getVersion(),
                config.localHostMode
        );
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
        passiveHeapSensor = null;
        instance = null;
        super.shutdown();
    }
}

package com.durkz.leancore.intelligence;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DiscoverZoneEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

public final class BehaviorSignalSystems {

    private BehaviorSignalSystems() {
    }

    public static void register(ComponentRegistryProxy<EntityStore> registry, BehaviorClassifier classifier) {
        // Break/place/discover are ECS events — global EventRegistry does not carry the entity ref.
        registry.registerSystem(new OnBreak(classifier));
        registry.registerSystem(new OnPlace(classifier));
        registry.registerSystem(new OnDiscover(classifier));
    }

    private static PlayerRef resolvePlayer(Store<EntityStore> store, Ref<EntityStore> ref) {
        UUIDComponent uuid = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuid == null) {
            return null;
        }
        UUID id = uuid.getUuid();
        return id == null ? null : Universe.get().getPlayer(id);
    }

    private static final class OnBreak extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        private final BehaviorClassifier classifier;

        OnBreak(BehaviorClassifier classifier) {
            super(BreakBlockEvent.class);
            this.classifier = classifier;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> buf, BreakBlockEvent event) {
            PlayerRef ref = resolvePlayer(store, chunk.getReferenceTo(index));
            if (ref != null) {
                classifier.onBlockBroken(ref);
            }
        }
    }

    private static final class OnPlace extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        private final BehaviorClassifier classifier;

        OnPlace(BehaviorClassifier classifier) {
            super(PlaceBlockEvent.class);
            this.classifier = classifier;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> buf, PlaceBlockEvent event) {
            PlayerRef ref = resolvePlayer(store, chunk.getReferenceTo(index));
            if (ref != null) {
                classifier.onBlockPlaced(ref);
            }
        }
    }

    private static final class OnDiscover extends EntityEventSystem<EntityStore, DiscoverZoneEvent.Display> {
        private final BehaviorClassifier classifier;

        OnDiscover(BehaviorClassifier classifier) {
            super(DiscoverZoneEvent.Display.class);
            this.classifier = classifier;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> buf, DiscoverZoneEvent.Display event) {
            PlayerRef ref = resolvePlayer(store, chunk.getReferenceTo(index));
            if (ref != null) {
                classifier.onZoneDiscovered(ref);
            }
        }
    }
}

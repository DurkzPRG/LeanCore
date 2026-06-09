package com.durkz.leancore.intelligence;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

public final class CombatSignalSystems {

    private CombatSignalSystems() {
    }

    public static void register(ComponentRegistryProxy<EntityStore> registry, BehaviorClassifier classifier) {
        registry.registerSystem(new OnDamage(classifier));
    }

    private static final class OnDamage extends DamageEventSystem {
        private final BehaviorClassifier classifier;

        OnDamage(BehaviorClassifier classifier) {
            this.classifier = classifier;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(
                int index,
                ArchetypeChunk<EntityStore> chunk,
                Store<EntityStore> store,
                CommandBuffer<EntityStore> buf,
                Damage event
        ) {
            if (event == null || event.getAmount() <= 0.0F) {
                return;
            }
            Damage.Source source = event.getSource();
            if (!(source instanceof Damage.EntitySource entitySource)) {
                return;
            }
            Ref<EntityStore> attackerRef = entitySource.getRef();
            if (attackerRef == null) {
                return;
            }
            UUIDComponent uuid = store.getComponent(attackerRef, UUIDComponent.getComponentType());
            if (uuid == null || uuid.getUuid() == null) {
                return;
            }
            PlayerRef player = Universe.get().getPlayer(uuid.getUuid());
            if (player != null && player.isValid()) {
                classifier.onCombatHit(player);
            }
        }
    }
}

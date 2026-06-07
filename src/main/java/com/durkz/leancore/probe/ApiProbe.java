package com.durkz.leancore.probe;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ApiProbe {

    private ApiProbe() {
    }

    public static List<String> run(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef) {
        List<String> out = new ArrayList<>(6);
        out.add("probe:");
        out.add(s1(store, ref));
        out.add(s2(playerRef));
        out.add(s3(playerRef));
        out.add(s4(playerRef));
        out.add(s5());
        return out;
    }

    private static String s1(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null) {
            return "S1 view-radius: fail (no entity ref)";
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return "S1 view-radius: fail (no Player component)";
        }
        return "S1 view-radius: ok server=" + player.getViewRadius()
                + " client=" + player.getClientViewRadius()
                + " write=setClientViewRadius";
    }

    private static String s2(PlayerRef p) {
        if (p == null || !p.isValid()) {
            return "S2 position: skip (no player)";
        }
        if (p.getTransform() == null || p.getTransform().getPosition() == null) {
            return "S2 position: fail";
        }
        var pos = p.getTransform().getPosition();
        return String.format(Locale.ROOT, "S2 position: ok %.0f %.0f %.0f world=%s",
                pos.x, pos.y, pos.z, p.getWorldUuid());
    }

    private static String s3(PlayerRef p) {
        if (p == null || !p.isValid()) {
            return "S3 chunks: skip (no player)";
        }
        return p.getChunkTracker() == null
                ? "S3 chunks: fail (no ChunkTracker)"
                : "S3 chunks: partial (tracker ok, no enumerate yet)";
    }

    private static String s4(PlayerRef p) {
        if (p == null || !p.isValid()) {
            return "S4 entities: skip (no player)";
        }
        return p.getReference() == null
                ? "S4 entities: fail (no entity ref)"
                : "S4 entities: partial (ref ok, regional count TBD)";
    }

    private static String s5() {
        return "S5 unload: pending (governor not wired)";
    }

}

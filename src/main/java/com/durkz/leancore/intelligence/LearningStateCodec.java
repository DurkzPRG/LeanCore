package com.durkz.leancore.intelligence;

import com.durkz.leancore.dormancy.ZoneReuseModel;
import com.durkz.leancore.memory.MemoryTier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compact gzip binary persistence for learning state (schema v9).
 * v9 adds a per-zone content score; v8 (zones without content) and v7 (no zones) payloads still
 * load. Typical size: a few KB solo, tens of KB with hundreds of players.
 */
final class LearningStateCodec {

    static final int VERSION = 9;
    static final int VERSION_WITH_ZONES = 8;
    static final int VERSION_WITHOUT_ZONES = 7;
    static final String GZ_FILE = "learning.state.gz";
    private static final int MAGIC = 0x454C434C; // "LCLC" little-endian
    private static final int MAX_COLLECTION_ENTRIES = 512;
    private static final int MAX_PLAYER_ENTRIES = 4096;
    private static final int MAX_ZONE_ENTRIES = 65_536;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_FLOAT_ARRAY_LEN = 64;

    private LearningStateCodec() {
    }

    static byte[] encode(Snapshot snapshot) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
             DataOutputStream out = new DataOutputStream(gzip)) {
            writeSnapshot(out, snapshot);
        }
        return buffer.toByteArray();
    }

    static Snapshot decode(byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
             GZIPInputStream gzip = new GZIPInputStream(in);
             DataInputStream data = new DataInputStream(gzip)) {
            return readSnapshot(data);
        }
    }

    static void writeTo(OutputStream target, Snapshot snapshot) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(target);
             DataOutputStream out = new DataOutputStream(gzip)) {
            writeSnapshot(out, snapshot);
        }
    }

    static Snapshot readFrom(InputStream source) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(source);
             DataInputStream data = new DataInputStream(gzip)) {
            return readSnapshot(data);
        }
    }

    private static void writeSnapshot(DataOutputStream out, Snapshot s) throws IOException {
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeByte(0); // flags

        out.writeLong(s.savedAtMs);
        out.writeByte(s.lastTier.ordinal());
        out.writeFloat((float) s.regionalPressure);

        out.writeInt(s.learnCompleted);
        out.writeInt(s.learnDiscarded);
        out.writeInt(s.learnFalseCuts);
        out.writeInt(s.demandUpdates);
        out.writeInt(s.activityUpdates);
        out.writeInt(s.unloadPolicy);
        out.writeInt(s.unloadEngine);

        out.writeFloat((float) s.heapAvg60s);
        out.writeFloat((float) s.heapAvg15m);
        out.writeFloat((float) s.heapAvg24h);

        out.writeFloat((float) s.serverQ50);
        out.writeFloat((float) s.serverQ75);
        out.writeFloat((float) s.serverQ90);
        out.writeFloat((float) s.serverQ97);
        out.writeInt(s.serverHeapSamples);

        writeFloatArray(out, s.demandWeights);
        writeActivityWeights(out, s.activityWeights);
        writeBandit(out, s.banditArms);
        writeBlacklist(out, s.blacklist);
        writePlayers(out, s.players);
        writeZones(out, s.zones);
    }

    private static Snapshot readSnapshot(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("invalid learning state magic");
        }
        int version = in.readShort();
        if (version != VERSION && version != VERSION_WITH_ZONES && version != VERSION_WITHOUT_ZONES) {
            throw new IOException("unsupported learning state version: " + version);
        }
        in.readByte(); // flags

        long savedAtMs = in.readLong();
        MemoryTier lastTier = readMemoryTier(in.readByte());
        double regionalPressure = in.readFloat();

        int learnCompleted = in.readInt();
        int learnDiscarded = in.readInt();
        int learnFalseCuts = in.readInt();
        int demandUpdates = in.readInt();
        int activityUpdates = in.readInt();
        int unloadPolicy = in.readInt();
        int unloadEngine = in.readInt();

        double heapAvg60s = in.readFloat();
        double heapAvg15m = in.readFloat();
        double heapAvg24h = in.readFloat();

        double serverQ50 = in.readFloat();
        double serverQ75 = in.readFloat();
        double serverQ90 = in.readFloat();
        double serverQ97 = in.readFloat();
        int serverHeapSamples = in.readInt();

        double[] demandWeights = readFloatArray(in, FeatureSchema.DEMAND_DIM);
        double[][] activityWeights = readActivityWeights(in);
        Map<String, BanditArm> banditArms = readBandit(in);
        Map<String, Long> blacklist = readBlacklist(in);
        Map<UUID, PlayerRecord> players = readPlayers(in);
        List<ZoneReuseModel.Record> zones = version >= VERSION_WITH_ZONES
                ? readZones(in, version)
                : new ArrayList<>();

        return new Snapshot(
                savedAtMs,
                lastTier,
                regionalPressure,
                learnCompleted,
                learnDiscarded,
                learnFalseCuts,
                demandUpdates,
                activityUpdates,
                unloadPolicy,
                unloadEngine,
                heapAvg60s,
                heapAvg15m,
                heapAvg24h,
                serverQ50,
                serverQ75,
                serverQ90,
                serverQ97,
                serverHeapSamples,
                demandWeights,
                activityWeights,
                banditArms,
                blacklist,
                players,
                zones
        );
    }

    private static void writeFloatArray(DataOutputStream out, double[] values) throws IOException {
        out.writeShort(values.length);
        for (double value : values) {
            out.writeFloat((float) value);
        }
    }

    private static double[] readFloatArray(DataInputStream in, int expected) throws IOException {
        int len = in.readShort() & 0xFFFF;
        if (len > MAX_FLOAT_ARRAY_LEN) {
            throw new IOException("learning state float array too large: " + len);
        }
        double[] values = new double[Math.max(expected, len)];
        for (int i = 0; i < len; i++) {
            values[i] = in.readFloat();
        }
        return values;
    }

    private static void writeActivityWeights(DataOutputStream out, double[][] weights) throws IOException {
        out.writeByte(weights.length);
        out.writeByte(ActivityFeatureEncoder.DIM);
        for (double[] row : weights) {
            for (int i = 0; i < ActivityFeatureEncoder.DIM; i++) {
                out.writeFloat((float) row[i]);
            }
        }
    }

    private static double[][] readActivityWeights(DataInputStream in) throws IOException {
        int classes = in.readByte() & 0xFF;
        int dim = in.readByte() & 0xFF;
        if (classes <= 0 || dim <= 0) {
            return new double[PlayerBehavior.values().length][ActivityFeatureEncoder.DIM];
        }
        double[][] weights = new double[classes][dim];
        for (int c = 0; c < classes; c++) {
            for (int i = 0; i < dim; i++) {
                weights[c][i] = in.readFloat();
            }
        }
        return weights;
    }

    private static void writeBandit(DataOutputStream out, Map<String, BanditArm> arms) throws IOException {
        int count = Math.min(arms.size(), MAX_COLLECTION_ENTRIES);
        out.writeShort(count);
        int written = 0;
        for (Map.Entry<String, BanditArm> e : arms.entrySet()) {
            if (written >= count) {
                break;
            }
            written++;
            byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeShort(key.length);
            out.write(key);
            BanditArm arm = e.getValue();
            out.writeInt(arm.pulls);
            out.writeFloat((float) arm.rewardSum);
            for (int i = 0; i < PolicyBandit.CONTEXT_DIM; i++) {
                out.writeFloat((float) arm.aDiag[i]);
            }
            for (int i = 0; i < PolicyBandit.CONTEXT_DIM; i++) {
                out.writeFloat((float) arm.b[i]);
            }
        }
    }

    private static Map<String, BanditArm> readBandit(DataInputStream in) throws IOException {
        int count = readCollectionCount(in.readShort() & 0xFFFF, "bandit");
        Map<String, BanditArm> arms = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            int keyLen = readKeyLength(in.readShort() & 0xFFFF);
            byte[] keyBytes = in.readNBytes(keyLen);
            String key = new String(keyBytes, StandardCharsets.UTF_8);
            BanditArm arm = new BanditArm();
            arm.pulls = in.readInt();
            arm.rewardSum = in.readFloat();
            for (int j = 0; j < PolicyBandit.CONTEXT_DIM; j++) {
                arm.aDiag[j] = in.readFloat();
            }
            for (int j = 0; j < PolicyBandit.CONTEXT_DIM; j++) {
                arm.b[j] = in.readFloat();
            }
            arms.put(key, arm);
        }
        return arms;
    }

    private static void writeBlacklist(DataOutputStream out, Map<String, Long> entries) throws IOException {
        int count = Math.min(entries.size(), MAX_COLLECTION_ENTRIES);
        out.writeShort(count);
        int written = 0;
        for (Map.Entry<String, Long> e : entries.entrySet()) {
            if (written >= count) {
                break;
            }
            written++;
            byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeShort(key.length);
            out.write(key);
            out.writeLong(e.getValue());
        }
    }

    private static Map<String, Long> readBlacklist(DataInputStream in) throws IOException {
        int count = readCollectionCount(in.readShort() & 0xFFFF, "blacklist");
        Map<String, Long> entries = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            int keyLen = readKeyLength(in.readShort() & 0xFFFF);
            byte[] keyBytes = in.readNBytes(keyLen);
            String key = new String(keyBytes, StandardCharsets.UTF_8);
            entries.put(key, in.readLong());
        }
        return entries;
    }

    private static void writePlayers(DataOutputStream out, Map<UUID, PlayerRecord> players) throws IOException {
        int count = Math.min(players.size(), MAX_PLAYER_ENTRIES);
        out.writeInt(count);
        int written = 0;
        for (Map.Entry<UUID, PlayerRecord> e : players.entrySet()) {
            if (written >= count) {
                break;
            }
            written++;
            UUID id = e.getKey();
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            PlayerRecord p = e.getValue();
            out.writeLong(p.lastSeenMs);
            out.writeFloat((float) p.demand);
            out.writeFloat((float) p.confidence);
            out.writeShort(p.retentionMb);
            out.writeByte(p.debugLabel.ordinal());
            out.writeInt((int) Math.min(Integer.MAX_VALUE, p.observedSec));
            writePlayerEmas(out, p);
        }
    }

    private static void writePlayerEmas(DataOutputStream out, PlayerRecord p) throws IOException {
        out.writeFloat((float) p.movement60);
        out.writeFloat((float) p.breaks60);
        out.writeFloat((float) p.places60);
        out.writeFloat((float) p.zones60);
        out.writeFloat((float) p.movement15m);
        out.writeFloat((float) p.breaks15m);
        out.writeFloat((float) p.places15m);
        out.writeFloat((float) p.zones15m);
        out.writeFloat((float) p.chunks60);
        out.writeFloat((float) p.chunks15m);
        out.writeFloat((float) p.mine60);
        out.writeFloat((float) p.wood60);
        out.writeFloat((float) p.farm60);
        out.writeFloat((float) p.build60);
        out.writeFloat((float) p.craft60);
        out.writeFloat((float) p.combat60);
    }

    private static Map<UUID, PlayerRecord> readPlayers(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_PLAYER_ENTRIES) {
            throw new IOException("invalid learning state player count: " + count);
        }
        Map<UUID, PlayerRecord> players = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = new UUID(in.readLong(), in.readLong());
            long lastSeenMs = in.readLong();
            double demand = in.readFloat();
            double confidence = in.readFloat();
            int retentionMb = in.readShort() & 0xFFFF;
            PlayerBehavior label = readBehavior(in.readByte());
            long observedSec = in.readInt() & 0xFFFFFFFFL;
            PlayerRecord p = readPlayerEmas(in);
            players.put(id, new PlayerRecord(
                    demand, confidence, retentionMb, label,
                    p.movement60, p.breaks60, p.places60, p.zones60,
                    p.movement15m, p.breaks15m, p.places15m, p.zones15m,
                    p.chunks60, p.chunks15m,
                    p.mine60, p.wood60, p.farm60, p.build60, p.craft60, p.combat60,
                    observedSec, lastSeenMs
            ));
        }
        return players;
    }

    private static PlayerRecord readPlayerEmas(DataInputStream in) throws IOException {
        return new PlayerRecord(
                0.0D, 0.0D, 0, PlayerBehavior.UNKNOWN,
                in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                0L, 0L
        );
    }

    private static void writeZones(DataOutputStream out, List<ZoneReuseModel.Record> zones) throws IOException {
        List<ZoneReuseModel.Record> records = zones == null ? List.of() : zones;
        int count = Math.min(records.size(), MAX_ZONE_ENTRIES);
        out.writeInt(count);
        int written = 0;
        for (ZoneReuseModel.Record r : records) {
            if (written >= count) {
                break;
            }
            out.writeLong(r.worldUuid().getMostSignificantBits());
            out.writeLong(r.worldUuid().getLeastSignificantBits());
            out.writeInt(r.regionX());
            out.writeInt(r.regionZ());
            out.writeInt(r.visitCount());
            out.writeLong(r.lastHotAtMs());
            out.writeDouble(r.emaIntervalMs());
            out.writeLong(r.lastSeenMs());
            out.writeFloat((float) r.contentScore());
            written++;
        }
    }

    private static List<ZoneReuseModel.Record> readZones(DataInputStream in, int version) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ZONE_ENTRIES) {
            throw new IOException("invalid learning state zone count: " + count);
        }
        List<ZoneReuseModel.Record> zones = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID world = new UUID(in.readLong(), in.readLong());
            int regionX = in.readInt();
            int regionZ = in.readInt();
            int visitCount = in.readInt();
            long lastHotAtMs = in.readLong();
            double emaIntervalMs = in.readDouble();
            long lastSeenMs = in.readLong();
            double contentScore = version >= VERSION ? in.readFloat() : 0.0D;
            zones.add(new ZoneReuseModel.Record(world, regionX, regionZ,
                    visitCount, lastHotAtMs, emaIntervalMs, lastSeenMs, contentScore));
        }
        return zones;
    }

    private static PlayerBehavior readBehavior(int ordinal) {
        PlayerBehavior[] values = PlayerBehavior.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return PlayerBehavior.UNKNOWN;
        }
        return values[ordinal];
    }

    private static MemoryTier readMemoryTier(int ordinal) throws IOException {
        MemoryTier[] values = MemoryTier.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid learning state memory tier: " + ordinal);
        }
        return values[ordinal];
    }

    private static int readCollectionCount(int count, String label) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_ENTRIES) {
            throw new IOException("invalid learning state " + label + " count: " + count);
        }
        return count;
    }

    private static int readKeyLength(int keyLen) throws IOException {
        if (keyLen < 0 || keyLen > MAX_KEY_BYTES) {
            throw new IOException("invalid learning state key length: " + keyLen);
        }
        return keyLen;
    }

    static final class BanditArm {
        final double[] aDiag = new double[PolicyBandit.CONTEXT_DIM];
        final double[] b = new double[PolicyBandit.CONTEXT_DIM];
        int pulls;
        double rewardSum;

        BanditArm() {
            for (int i = 0; i < PolicyBandit.CONTEXT_DIM; i++) {
                aDiag[i] = 1.0D;
            }
        }
    }

    record PlayerRecord(
            double demand,
            double confidence,
            int retentionMb,
            PlayerBehavior debugLabel,
            double movement60,
            double breaks60,
            double places60,
            double zones60,
            double movement15m,
            double breaks15m,
            double places15m,
            double zones15m,
            double chunks60,
            double chunks15m,
            double mine60,
            double wood60,
            double farm60,
            double build60,
            double craft60,
            double combat60,
            long observedSec,
            long lastSeenMs
    ) {
    }

    record Snapshot(
            long savedAtMs,
            MemoryTier lastTier,
            double regionalPressure,
            int learnCompleted,
            int learnDiscarded,
            int learnFalseCuts,
            int demandUpdates,
            int activityUpdates,
            int unloadPolicy,
            int unloadEngine,
            double heapAvg60s,
            double heapAvg15m,
            double heapAvg24h,
            double serverQ50,
            double serverQ75,
            double serverQ90,
            double serverQ97,
            int serverHeapSamples,
            double[] demandWeights,
            double[][] activityWeights,
            Map<String, BanditArm> banditArms,
            Map<String, Long> blacklist,
            Map<UUID, PlayerRecord> players,
            List<ZoneReuseModel.Record> zones
    ) {
    }
}

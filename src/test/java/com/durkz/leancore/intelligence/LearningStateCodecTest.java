package com.durkz.leancore.intelligence;

import com.durkz.leancore.dormancy.ZoneReuseModel;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningStateCodecTest {

    @Test
    void rejectsInvalidMemoryTierOrdinal() throws Exception {
        byte[] corrupt = gzipWithTierOrdinal(99);
        assertThrows(Exception.class, () -> LearningStateCodec.decode(corrupt));
    }

    @Test
    void rejectsNegativePlayerCount() throws Exception {
        byte[] corrupt = gzipWithPlayerCount(-1);
        assertThrows(Exception.class, () -> LearningStateCodec.decode(corrupt));
    }

    @Test
    void roundTripsZonesV8() throws Exception {
        UUID world = new UUID(7L, 9L);
        ZoneReuseModel.Record record = new ZoneReuseModel.Record(world, 4, -3, 6, 123_000L, 45_000.0D, 200_000L);
        LearningStateCodec.Snapshot snapshot = emptySnapshot(List.of(record));

        LearningStateCodec.Snapshot decoded = LearningStateCodec.decode(LearningStateCodec.encode(snapshot));

        assertEquals(1, decoded.zones().size());
        ZoneReuseModel.Record out = decoded.zones().get(0);
        assertEquals(world, out.worldUuid());
        assertEquals(4, out.regionX());
        assertEquals(-3, out.regionZ());
        assertEquals(6, out.visitCount());
        assertEquals(123_000L, out.lastHotAtMs());
        assertEquals(45_000.0D, out.emaIntervalMs(), 1e-6);
        assertEquals(200_000L, out.lastSeenMs());
    }

    @Test
    void readsLegacyV7PayloadWithEmptyZones() throws Exception {
        byte[] legacy = gzipV7Body();
        LearningStateCodec.Snapshot decoded = LearningStateCodec.decode(legacy);
        assertTrue(decoded.zones().isEmpty(), "v7 payload yields empty zones");
    }

    @Test
    void writeCapsBlacklistToReadLimitSoFileStaysReadable() throws Exception {
        // 600 > MAX_COLLECTION_ENTRIES (512): the writer must cap so the reader can load it back.
        LinkedHashMap<String, Long> big = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) {
            big.put("policy-" + i, (long) i);
        }
        LearningStateCodec.Snapshot snapshot = snapshotWithBlacklist(big);

        LearningStateCodec.Snapshot decoded = LearningStateCodec.decode(LearningStateCodec.encode(snapshot));

        assertTrue(decoded.blacklist().size() <= 512,
                "blacklist must be capped to the read limit, was " + decoded.blacklist().size());
        assertTrue(decoded.blacklist().size() > 0, "some blacklist entries should survive the round trip");
    }

    private static LearningStateCodec.Snapshot emptySnapshot(List<ZoneReuseModel.Record> zones) {
        return new LearningStateCodec.Snapshot(
                1L, MemoryTier.COMFORT, 0.0D,
                0, 0, 0, 0, 0, 0, 0,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 0,
                new double[FeatureSchema.DEMAND_DIM],
                new double[PlayerBehavior.values().length][ActivityFeatureEncoder.DIM],
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                zones
        );
    }

    private static LearningStateCodec.Snapshot snapshotWithBlacklist(LinkedHashMap<String, Long> blacklist) {
        return new LearningStateCodec.Snapshot(
                1L, MemoryTier.COMFORT, 0.0D,
                0, 0, 0, 0, 0, 0, 0,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 0,
                new double[FeatureSchema.DEMAND_DIM],
                new double[PlayerBehavior.values().length][ActivityFeatureEncoder.DIM],
                new LinkedHashMap<>(),
                blacklist,
                new LinkedHashMap<>(),
                List.of()
        );
    }

    private static byte[] gzipV7Body() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeInt(0x454C434C);
            out.writeShort(LearningStateCodec.VERSION_WITHOUT_ZONES);
            out.writeByte(0);
            out.writeLong(0L);
            out.writeByte(MemoryTier.COMFORT.ordinal());
            writeScalars(out);
            writeEmptyCollections(out);
            out.writeInt(0); // players, no zone section in v7
        }
        return buffer.toByteArray();
    }

    private static byte[] gzipWithTierOrdinal(int tierOrdinal) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeInt(0x454C434C);
            out.writeShort(LearningStateCodec.VERSION);
            out.writeByte(0);
            out.writeLong(0L);
            out.writeByte(tierOrdinal);
            writeEmptyTail(out);
        }
        return buffer.toByteArray();
    }

    private static byte[] gzipWithPlayerCount(int playerCount) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeInt(0x454C434C);
            out.writeShort(LearningStateCodec.VERSION);
            out.writeByte(0);
            out.writeLong(0L);
            out.writeByte(0);
            writeScalars(out);
            writeEmptyCollections(out);
            out.writeInt(playerCount);
        }
        return buffer.toByteArray();
    }

    private static void writeEmptyTail(DataOutputStream out) throws Exception {
        writeScalars(out);
        writeEmptyCollections(out);
        out.writeInt(0);
    }

    private static void writeScalars(DataOutputStream out) throws Exception {
        out.writeFloat(0.0F); // regionalPressure
        out.writeInt(0); // learnCompleted
        out.writeInt(0); // learnDiscarded
        out.writeInt(0); // learnFalseCuts
        out.writeInt(0); // demandUpdates
        out.writeInt(0); // activityUpdates
        out.writeInt(0); // unloadPolicy
        out.writeInt(0); // unloadEngine
        out.writeFloat(0.0F); // heapAvg60s
        out.writeFloat(0.0F); // heapAvg15m
        out.writeFloat(0.0F); // heapAvg24h
        out.writeFloat(0.0F); // serverQ50
        out.writeFloat(0.0F); // serverQ75
        out.writeFloat(0.0F); // serverQ90
        out.writeFloat(0.0F); // serverQ97
        out.writeInt(0); // serverHeapSamples
    }

    private static void writeEmptyCollections(DataOutputStream out) throws Exception {
        out.writeShort(FeatureSchema.DEMAND_DIM);
        for (int i = 0; i < FeatureSchema.DEMAND_DIM; i++) {
            out.writeFloat(0.0F);
        }
        out.writeByte(PlayerBehavior.values().length);
        out.writeByte(ActivityFeatureEncoder.DIM);
        for (int c = 0; c < PlayerBehavior.values().length; c++) {
            for (int i = 0; i < ActivityFeatureEncoder.DIM; i++) {
                out.writeFloat(0.0F);
            }
        }
        out.writeShort(0);
        out.writeShort(0);
    }
}

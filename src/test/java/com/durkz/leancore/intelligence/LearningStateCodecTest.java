package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
        out.writeFloat(0.0F);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writeFloat(0.0F);
        out.writeFloat(0.0F);
        out.writeFloat(0.0F);
        out.writeFloat(0.0F);
        out.writeFloat(0.0F);
        out.writeFloat(0.0F);
        out.writeFloat(0.0F);
        out.writeInt(0);
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

package com.durkz.leancore.intelligence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

final class LearningStateCodecTestHelper {

    private LearningStateCodecTestHelper() {
    }

    static byte[] invalidTierPayload() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeInt(0x454C434C);
            out.writeShort(LearningStateCodec.VERSION);
            out.writeByte(0);
            out.writeLong(0L);
            out.writeByte(99);
            writeScalars(out);
            writeEmptyCollections(out);
            out.writeInt(0);
        }
        return buffer.toByteArray();
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

package com.shilapi.xcertplay.media;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Strict bounded Annex B / avcC parsing; no Android dependency. */
public final class H264BridgeFrames {
    public static final int MAX_FRAME = 2 * 1024 * 1024;
    public static final int MAX_CONFIG = 65536;
    private static final byte[] START = {0, 0, 0, 1};
    private H264BridgeFrames() {}
    public static byte[] config(byte[] avc) {
        if (avc.length < 7 || avc.length > MAX_CONFIG || avc[0] != 1 || (avc[4] & 3) != 3)
            throw new IllegalArgumentException("Expected AVC configuration with four-byte NAL lengths");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cursor = 6, spsCount = avc[5] & 31;
        if (spsCount == 0) throw new IllegalArgumentException("Missing SPS");
        for (int group = 0; group < 2; group++) {
            int count = group == 0 ? spsCount : u8(avc, cursor++);
            if (count == 0) throw new IllegalArgumentException("Missing PPS");
            for (int i = 0; i < count; i++) {
                int length = (u8(avc, cursor) << 8) | u8(avc, cursor + 1); cursor += 2;
                if (length < 1 || length > avc.length - cursor || (avc[cursor] & 31) != (group == 0 ? 7 : 8))
                    throw new IllegalArgumentException("Invalid parameter set");
                out.write(START, 0, 4); out.write(avc, cursor, length); cursor += length;
            }
        }
        return out.toByteArray();
    }
    private static int u8(byte[] data, int i) {
        if (i < 0 || i >= data.length) throw new IllegalArgumentException("Truncated AVC config");
        return data[i] & 255;
    }
    public static List<byte[]> nalus(byte[] annex) {
        if (annex.length == 0 || annex.length > MAX_FRAME) throw new IllegalArgumentException("NAL size");
        List<byte[]> result = new ArrayList<>(); int start = 0;
        while (start < annex.length) {
            int prefix = prefix(annex, start);
            if (prefix == 0) throw new IllegalArgumentException("Expected Annex B");
            int end = start + prefix;
            while (end < annex.length && prefix(annex, end) == 0) end++;
            if (end == start + prefix) throw new IllegalArgumentException("Empty NAL");
            byte[] nal = java.util.Arrays.copyOfRange(annex, start + prefix, end);
            if ((nal[0] & 128) != 0 || (nal[0] & 31) == 0) throw new IllegalArgumentException("NAL header");
            result.add(nal); start = end;
        }
        return result;
    }
    private static int prefix(byte[] a, int i) {
        if (i + 2 >= a.length || a[i] != 0 || a[i+1] != 0) return 0;
        if (a[i+2] == 1) return 3;
        return i + 3 < a.length && a[i+2] == 0 && a[i+3] == 1 ? 4 : 0;
    }
    public static boolean keyFrame(byte[] annex) {
        for (byte[] nal : nalus(annex)) if ((nal[0] & 31) == 5) return true;
        return false;
    }
    public static int[] size(byte[] annex) {
        boolean pps = false; int[] size = null;
        for (byte[] nal : nalus(annex)) {
            if ((nal[0] & 31) == 8) pps = true;
            if ((nal[0] & 31) == 7) {
                int[] next = spsSize(nal);
                if (size != null && !java.util.Arrays.equals(size, next)) throw new IllegalArgumentException("Mixed SPS dimensions");
                size = next;
            }
        }
        if (size == null || !pps) throw new IllegalArgumentException("Missing SPS/PPS");
        return size;
    }
    private static int[] spsSize(byte[] nal) {
        ByteArrayOutputStream rbsp = new ByteArrayOutputStream(); int zeros = 0;
        for (int i = 1; i < nal.length; i++) {
            int b = nal[i] & 255;
            if (zeros >= 2 && b == 3) { zeros = 0; continue; }
            rbsp.write(b); zeros = b == 0 ? zeros + 1 : 0;
        }
        Bits b = new Bits(rbsp.toByteArray()); int profile = b.read(8);
        b.read(8); b.read(8); b.ue(); int chroma = 1; boolean separate = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                || profile == 139 || profile == 134 || profile == 135) {
            chroma = b.ue(); if (chroma > 3) throw new IllegalArgumentException("Chroma");
            if (chroma == 3) separate = b.read(1) != 0;
            b.ue(); b.ue(); b.read(1);
            if (b.read(1) != 0) for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                if (b.read(1) == 0) continue;
                int last = 8, next = 8;
                for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                    if (next != 0) next = (last + b.se() + 256) & 255;
                    if (next != 0) last = next;
                }
            }
        }
        b.ue(); int order = b.ue();
        if (order == 0) b.ue();
        else if (order == 1) { b.read(1); b.se(); b.se(); int n = b.ue();
            if (n > 256) throw new IllegalArgumentException("SPS cycle"); for (int i=0;i<n;i++) b.se(); }
        else if (order != 2) throw new IllegalArgumentException("SPS order");
        b.ue(); b.read(1); long width = (b.ue() + 1L) * 16, height = (b.ue() + 1L) * 16;
        int frame = b.read(1); if (frame == 0) b.read(1); height *= 2 - frame; b.read(1);
        if (b.read(1) != 0) {
            int array = separate ? 0 : chroma;
            int cropX = array == 0 || array == 3 ? 1 : 2;
            int cropY = (array == 1 ? 2 : 1) * (2 - frame);
            width -= (b.ue() + (long)b.ue()) * cropX; height -= (b.ue() + (long)b.ue()) * cropY;
        }
        if (width < 16 || height < 16 || width > 4096 || height > 4096) throw new IllegalArgumentException("SPS dimensions");
        return new int[]{(int)width, (int)height};
    }
    private static final class Bits {
        final byte[] bytes; int bit;
        Bits(byte[] bytes) { this.bytes = bytes; }
        int read(int n) { if (n < 0 || n > 30 || bit + n > bytes.length * 8) throw new IllegalArgumentException("Truncated SPS");
            int value = 0; for (int i=0;i<n;i++,bit++) value=(value<<1)|((bytes[bit/8]>>(7-bit%8))&1); return value; }
        int ue() { int zeros=0; while(read(1)==0) if(++zeros>24) throw new IllegalArgumentException("SPS integer");
            return (1<<zeros)-1+read(zeros); }
        int se() { int value=ue(); return (value&1)==0 ? -value/2 : (value+1)/2; }
    }
    /** EOF between records is normal; EOF within a header/body is a failed stream. */
    public static byte[] readRecord(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > limit) throw new IOException("Video record length out of bounds");
        byte[] data = new byte[length]; in.readFully(data); return data;
    }
}

package com.shilapi.xcertplay.media;

import java.util.Arrays;

/** Streaming PCM16 mono/stereo -> 48k stereo. Interpolates upsampling; preserves split frames. */
public final class BridgePcmNormalizer {
    private final int rate, channels;
    private final byte[] partial = new byte[4];
    private final int[] previous = new int[2];
    private int partialBytes, phase;
    private boolean first = true;
    public BridgePcmNormalizer(int rate, int channels) {
        if (rate < 8000 || rate > 48000 || (channels != 1 && channels != 2))
            throw new IllegalArgumentException("Unsupported bridge PCM format");
        this.rate = rate; this.channels = channels;
    }
    public byte[] convert(byte[] input, int offset, int length) {
        if (offset < 0 || length < 0 || offset > input.length - length)
            throw new IllegalArgumentException("PCM range");
        int frames = (partialBytes + length) / (channels * 2);
        byte[] output = new byte[(int) (((long) frames * 48000 + phase) / rate) * 4];
        int cursor = 0;
        for (int i = offset; i < offset + length; i++) {
            partial[partialBytes++] = input[i];
            if (partialBytes != channels * 2) continue;
            partialBytes = 0;
            int left = (short) ((partial[0] & 255) | partial[1] << 8);
            int right = channels == 1 ? left : (short) ((partial[2] & 255) | partial[3] << 8);
            if (first) { previous[0] = left; previous[1] = right; first = false; }
            phase += 48000;
            while (phase >= rate) {
                phase -= rate;
                double fraction = 1.0 - (double) phase / 48000;
                int l = (int) Math.round(previous[0] + (left - previous[0]) * fraction);
                int r = (int) Math.round(previous[1] + (right - previous[1]) * fraction);
                output[cursor++] = (byte) l; output[cursor++] = (byte) (l >> 8);
                output[cursor++] = (byte) r; output[cursor++] = (byte) (r >> 8);
            }
            previous[0] = left; previous[1] = right;
        }
        return cursor == output.length ? output : Arrays.copyOf(output, cursor);
    }
}

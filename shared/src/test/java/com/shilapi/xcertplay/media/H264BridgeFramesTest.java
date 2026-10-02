package com.shilapi.xcertplay.media;

import java.io.*;
import java.util.*;

public final class H264BridgeFramesTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void reject(Runnable action) { try { action.run(); throw new AssertionError("accepted malformed input"); }
        catch (IllegalArgumentException expected) {} }
    @org.junit.Test public void parsesAndBoundsRealVideoRecords() throws Exception {
        byte[] baseline = Base64.getDecoder().decode("AAAAAWdCwBbZAMg9sBEAAAMAAQAAAwACDxYuSAAAAAFoy4PLIAAAAQYF//9t3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzEwOCAzMWUxOWY5IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTAgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MToweDExMSBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEzIGxvb2thaGVhZF90aHJlYWRzPTIgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTI1MCBrZXlpbnRfbWluPTEgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAFliIQFf///D0UAAULfJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnJ11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111114");
        byte[] high = Base64.getDecoder().decode("AAAAAWdkAB+s2UBQBbsBEAAAAwAQAAADACDxgxlgAAAAAWjr48siwAAAAQYF//+q3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzEwOCAzMWUxOWY5IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEzIGxvb2thaGVhZF90aHJlYWRzPTIgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MyBiX3B5cmFtaWQ9MiBiX2FkYXB0PTEgYl9iaWFzPTAgZGlyZWN0PTEgd2VpZ2h0Yj0xIG9wZW5fZ29wPTAgd2VpZ2h0cD0yIGtleWludD0yNTAga2V5aW50X21pbj0xIHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAABZYiEABX//vfJ78Cm69vetb+Tz0j8LLc+wio/blsTtOoAAAMAAAMAAAMAAAMABU2vcQ5cRw4f6DwAAAMAAAfQAAVkAAcIABCwADUAAMkAA4gAD+AATIACGgAPsABigAOsAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAA2Z");
        check(Arrays.equals(H264BridgeFrames.size(baseline), new int[]{800,480}), "baseline SPS dimensions");
        check(Arrays.equals(H264BridgeFrames.size(high), new int[]{1280,720}), "high profile cropped SPS dimensions");
        check(H264BridgeFrames.keyFrame(baseline), "IDR after config/SEI");
        check(!H264BridgeFrames.keyFrame(new byte[]{0,0,1,0x41,1}), "P frame is dependent");
        List<byte[]> units = H264BridgeFrames.nalus(baseline);
        byte[] sps=units.stream().filter(n -> (n[0]&31)==7).findFirst().get();
        byte[] pps=units.stream().filter(n -> (n[0]&31)==8).findFirst().get();
        ByteArrayOutputStream avc = new ByteArrayOutputStream(); DataOutputStream encoded = new DataOutputStream(avc);
        encoded.write(new byte[]{1,sps[1],sps[2],sps[3],(byte)255,(byte)225});
        encoded.writeShort(sps.length); encoded.write(sps); encoded.writeByte(1); encoded.writeShort(pps.length); encoded.write(pps);
        byte[] config = H264BridgeFrames.config(avc.toByteArray());
        check(Arrays.equals(H264BridgeFrames.size(config), new int[]{800,480}), "avcC preserves SPS/PPS");
        for (int i=0;i<avc.size();i++) { final byte[] truncated=Arrays.copyOf(avc.toByteArray(),i); reject(() -> H264BridgeFrames.config(truncated)); }
        reject(() -> H264BridgeFrames.nalus(new byte[]{0,0,0,1}));
        reject(() -> H264BridgeFrames.nalus(new byte[]{1,2,3,4}));
        reject(() -> H264BridgeFrames.size(new byte[]{0,0,1,0x67,1}));
        reject(() -> H264BridgeFrames.size(new byte[]{0,0,1,0x41,1}));
        byte[] lengthHeader={0x7f,(byte)255,(byte)255,(byte)255};
        try { H264BridgeFrames.readRecord(new DataInputStream(new ByteArrayInputStream(lengthHeader)), H264BridgeFrames.MAX_FRAME);
            throw new AssertionError("oversized record allocated"); } catch(IOException expected) {}
        ByteArrayOutputStream record = new ByteArrayOutputStream(); DataOutputStream stream = new DataOutputStream(record);
        stream.writeInt(config.length); stream.write(config);
        check(Arrays.equals(config,H264BridgeFrames.readRecord(new DataInputStream(new ByteArrayInputStream(record.toByteArray())),65536)), "record round trip");
        for (int i=0;i<record.size();i++) {
            try { H264BridgeFrames.readRecord(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(record.toByteArray(),i))),65536);
                throw new AssertionError("partial record accepted"); } catch(IOException expected) {}
        }
        System.out.println("VideoBridgeCheck passed (real baseline/high SPS, avcC, IDR and bounded/partial pipe records)");
    }
}

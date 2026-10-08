package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class ForwardingOnlyTest {
    @Test fun thousandsOfFramesWithoutPreviewNeverAllocateDecoder() {
        val sink=AndroidMediaSink(forwardingOnly=true)
        try {
            sink.onVideoCodec(110,VideoCodec.H264)
            sink.onVideoConfig(110,byteArrayOf(1,2,3))
            repeat(10000) { sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x65)) }
            assertArrayEquals(longArrayOf(10000,50000,0,0),sink.mediaStats())
            sink.setLocalPreviewEnabled(false)
            sink.onScreenStreamActive(110,false)
            assertEquals(0L,sink.mediaStats()[2])
        } finally { sink.close() }
    }
}

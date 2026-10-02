package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class BridgePcmNormalizerTest {
    @Test fun arbitraryByteSplitsAndRatesHaveTheSameOutput() {
        for (rate in listOf(8000, 16000, 24000, 32000, 44100, 48000)) for (channels in 1..2) {
            val pcm = ByteArray(rate * channels * 2) { (it * 71).toByte() }
            val all = BridgePcmNormalizer(rate, channels).convert(pcm, 0, pcm.size)
            val streaming = BridgePcmNormalizer(rate, channels)
            val out = ByteArrayOutputStream()
            var offset = 0
            while (offset < pcm.size) {
                val length = minOf(137, pcm.size - offset)
                out.write(streaming.convert(pcm, offset, length)); offset += length
            }
            assertEquals(48000 * 4, all.size)
            assertArrayEquals(all, out.toByteArray())
        }
    }
    @Test fun nativeStereoIsExactAndMonoIsDuplicated() {
        val stereo = byteArrayOf(-1, -1, 0, -128, 1, 0, -1, 127)
        assertArrayEquals(stereo, BridgePcmNormalizer(48000, 2).convert(stereo, 0, stereo.size))
        val mono = byteArrayOf(-1, -1, 0, -128)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, 0, -128, 0, -128),
            BridgePcmNormalizer(48000, 1).convert(mono, 0, mono.size))
    }
    @Test fun inputRangesAndFormatsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { BridgePcmNormalizer(96000, 2) }
        assertThrows(IllegalArgumentException::class.java) { BridgePcmNormalizer(48000, 3) }
        assertThrows(IllegalArgumentException::class.java) { BridgePcmNormalizer(48000, 2).convert(ByteArray(4), 2, 3) }
    }
}

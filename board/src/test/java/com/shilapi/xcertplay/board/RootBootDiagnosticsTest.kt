package com.shilapi.xcertplay.board

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootBootDiagnosticsTest {
    @Test fun exportedTextMasksRadioAddressesAndCredentialFields() {
        val text=RootBootDiagnostics.redact("elapsedMs=152415 ready\npeer=F0:B0:40:9A:E0:CE\npassphrase=hidden-one\ntoken=hidden-two\nprivate_key=hidden-three\n")
        assertTrue(text.contains("elapsedMs=152415"))
        for(secret in listOf("F0:B0:40:9A:E0:CE","hidden-one","hidden-two","hidden-three"))assertFalse(text.contains(secret))
    }
    @Test fun oversizedFileKeepsBoundedTailAndTruncationMarker() {
        val file=File.createTempFile("boot-diagnostic-test", ".txt")
        try {
            file.writeText("old secret\n"+"old record\n".repeat(1000)+"last stage ready\n")
            val tail=RootBootDiagnostics.readText(file,256)
            assertFalse(tail.contains("old secret"))
            assertTrue(tail.contains("last stage ready"))
            assertTrue(tail.startsWith("<truncated"))
            assertTrue(tail.length<300)
        } finally {file.delete()}
    }
    @Test fun commandCapturesActualFailureAndDrainsOversizedOutput() {
        val result=RootBootDiagnostics.execute(2,64,"/bin/sh","-c","i=0; while [ $"+"i -lt 1000 ]; do echo large-output; i=$"+"((i+1)); done; echo failed >&2; exit 7")
        assertTrue(result.done);assertEquals(7,result.exit)
        assertTrue(result.output.contains("truncated=true"));assertTrue(result.output.length<100)
        val failure=RootBootDiagnostics.execute(2,4096,"/bin/sh","-c","echo actual-error >&2; exit 9")
        assertEquals(9,failure.exit);assertTrue(failure.output.contains("actual-error"))
    }
    @Test fun commandTimeoutIsBounded() {
        val before=System.nanoTime()
        val result=RootBootDiagnostics.execute(1,64,"/bin/sh","-c","exec sleep 10")
        assertFalse(result.done)
        assertTrue((System.nanoTime()-before)/1_000_000<5000)
    }
}

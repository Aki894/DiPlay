package com.shilapi.xcertplay.board

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class BoardControlsTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid input to be rejected") } catch (_: IllegalArgumentException) {}
    }
    @Test fun rejectUnsupportedCanvasAndCommands() {
        rejected { BoardConfig(width=641) }
        rejected { BoardConfig(fps=120) }
        rejected { BoardConfig(phone="not-a-mac") }
        rejected { BoardConfig(hotspotMode="MANUAL") }
        rejected { BoardConfig.parse(BoardConfig().json(true).put("shell","reboot")) }
        val c=BoardConfig(wireless=true,phone="AA:BB:CC:DD:EE:FF",hotspotMode="MANUAL",ssid="test",passphrase="12345678")
        assertEquals(c,BoardConfig.parse(c.json(true)))
        assertFalse(c.json().has("passphrase"))
    }
    @Test fun interruptedConfigurationRestoresLastConfirmedBeforeBinding() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val confirmed=BoardConfig(width=800,webLan=false)
        BoardConfig.save(service,BoardConfig(width=1024,webLan=true))
        service.getSharedPreferences("board",0).edit().putString("previous",confirmed.json(true).toString()).commit()
        BoardConfig.restoreUnconfirmed(service)
        assertEquals(confirmed,BoardConfig.load(service))
        assertNull(service.getSharedPreferences("board",0).getString("previous",null))
    }
    @Test fun interruptedTokenCreationNeverAllowsEmptyAuthentication() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val file=java.io.File(service.noBackupFilesDir,"web-token")
        file.writeText("")
        val token=BoardConfig.token(service)
        assertTrue(Regex("[0-9a-f]{64}").matches(token))
        assertEquals(token,BoardConfig.token(service))
    }
    private fun request(uri: String,method: NanoHTTPD.Method,headers: Map<String,String>): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(javaClass.classLoader,arrayOf(NanoHTTPD.IHTTPSession::class.java)) { _,m,_ ->
            when(m.name) { "getUri" -> uri; "getMethod" -> method; "getHeaders" -> headers; else -> null }
        } as NanoHTTPD.IHTTPSession
    @Test fun webRejectsMissingTokenCrossOriginAndOversizedBodies() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val server=BoardWebServer(service,false)
        val auth="Bearer "+BoardConfig.token(service)
        try {
            assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED,server.serve(request("/api/v1/status",NanoHTTPD.Method.GET,emptyMap())).status)
            assertEquals(NanoHTTPD.Response.Status.FORBIDDEN,server.serve(request("/api/v1/status",NanoHTTPD.Method.GET,
                mapOf("authorization" to auth,"origin" to "http://evil.test","host" to "localhost:8765"))).status)
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST,server.serve(request("/api/v1/config",NanoHTTPD.Method.POST,
                mapOf("authorization" to auth,"content-type" to "application/json","content-length" to "9000"))).status)
            assertEquals(NanoHTTPD.Response.Status.NOT_FOUND,server.serve(request("/api/v1/shell",NanoHTTPD.Method.GET,mapOf("authorization" to auth))).status)
        } finally { server.stop() }
    }
    @Test fun metadataIsBoundedAndSecretsAreRedacted() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val log=BoardLog(service)
        try {
            repeat(600) { log.add("event $it passphrase=very-secret " + "x".repeat(1000)) }
            assertTrue(log.export().length <= 131072)
            assertFalse(log.export().contains("very-secret"))
            assertTrue(log.json().length()<=100)
        } finally { log.close() }
    }
}

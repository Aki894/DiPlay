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
            when(m.name) { "getUri" -> uri; "getMethod" -> method; "getHeaders" -> headers; "getRemoteIpAddress" -> headers["test-ip"] ?: "127.0.0.1"; else -> null }
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
    @Test fun bootArchiveRequiresAuthenticationReadyStatusAndRegularFile() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val server=BoardWebServer(service,false)
        val auth=mapOf("authorization" to "Bearer "+BoardConfig.token(service))
        val archive=java.io.File(service.filesDir,"boot-diagnostics.zip")
        val metadata=java.io.File(service.filesDir,"boot-export-status.json")
        val uri="/api/v1/diagnostics/boot/download"
        try {
            archive.delete();metadata.delete()
            assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED,server.serve(request(uri,NanoHTTPD.Method.GET,emptyMap())).status)
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST,server.serve(request(uri,NanoHTTPD.Method.GET,auth)).status)
            archive.writeBytes(byteArrayOf(80,75,3,4))
            metadata.writeText("{\"state\":\"capturing\"}")
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST,server.serve(request(uri,NanoHTTPD.Method.GET,auth)).status)
            metadata.writeText("{\"state\":\"ready\"}")
            server.serve(request(uri,NanoHTTPD.Method.GET,auth)).also {
                assertEquals(NanoHTTPD.Response.Status.OK,it.status);it.data.close()
            }
            archive.delete()
            java.nio.file.Files.createSymbolicLink(archive.toPath(),metadata.toPath())
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST,server.serve(request(uri,NanoHTTPD.Method.GET,auth)).status)
        } finally {archive.delete();metadata.delete();server.stop()}
    }
    @Test fun lanAccessChangesWithoutRebindingAndStillRequiresToken() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        BoardConfig.save(service,BoardConfig(webLan=false))
        val server=BoardWebServer(service,false)
        try {
            val remote=mapOf("test-ip" to "192.168.49.2")
            assertEquals(NanoHTTPD.Response.Status.FORBIDDEN,server.serve(request("/api/v1/config",NanoHTTPD.Method.GET,remote)).status)
            BoardConfig.save(service,BoardConfig(webLan=true))
            assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED,server.serve(request("/api/v1/config",NanoHTTPD.Method.GET,remote)).status)
            assertEquals(NanoHTTPD.Response.Status.OK,server.serve(request("/api/v1/config",NanoHTTPD.Method.GET,
                remote+mapOf("authorization" to "Bearer "+BoardConfig.token(service)))).status)
        } finally {server.stop()}
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
    @Test fun pairingRequestsRequireAnOpenWindowAndConfirmationVariant() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val window=java.io.File(service.filesDir,"pairing-window")
        val request=java.io.File(service.filesDir,"pairing-request")
        request.delete()
        window.writeText("120100")
        assertFalse(BoardPairingReceiver.queue(service,"AA:BB:CC:DD:EE:FF",0,100)) // PIN input
        assertFalse(BoardPairingReceiver.queue(service,"AA:BB:CC:DD:EE:FF",1,100)) // Passkey input
        assertFalse(BoardPairingReceiver.queue(service,"bad-address",2,100))
        assertFalse(request.exists())
        assertTrue(BoardPairingReceiver.queue(service,"AA:BB:CC:DD:EE:FF",2,100))
        val queued=JSONObject(request.readText())
        assertEquals("AA:BB:CC:DD:EE:FF",queued.getString("address"))
        assertEquals(2,queued.getInt("variant"))
        assertEquals(120100L,queued.getLong("until"))
        request.delete()
        assertFalse(BoardPairingReceiver.queue(service,"AA:BB:CC:DD:EE:FF",3,120100))
        window.writeText("0")
        assertFalse(BoardPairingReceiver.queue(service,"AA:BB:CC:DD:EE:FF",3,100))
        assertFalse(request.exists())
    }
    @Test fun idleHttpConnectionsCannotBlockControlRequests() {
        val service=Robolectric.buildService(BoardService::class.java).get()
        val server=BoardWebServer(service,false)
        val sockets=mutableListOf<java.net.Socket>()
        try {
            server.start(5000,false)
            repeat(3) {
                val socket=java.net.Socket("127.0.0.1",8765).also { sockets.add(it); it.soTimeout=1500 }
                socket.getOutputStream().write("GET /api/v1/status HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                val reader=socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().contains("401"))
                var close=false
                while(true) {
                    val line=reader.readLine() ?: break
                    if(line.isEmpty())break
                    if(line.equals("Connection: close",ignoreCase=true))close=true
                }
                assertTrue("Response must release the bounded HTTP worker",close)
                // Leave the first two client sockets open, as a browser would.
            }
        } finally { sockets.forEach { it.close() };server.stop() }
    }
}



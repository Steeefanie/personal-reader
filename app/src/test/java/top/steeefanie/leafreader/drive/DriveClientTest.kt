package top.steeefanie.leafreader.drive

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.*

private class Script(private val handler: (String, String, Map<String, String>, ByteArray?) -> WireResponse) : DriveTransport {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) = handler(method, url, headers, body)
}
class DriveClientTest {
    private val hash = "a".repeat(64)
    private fun response(status: Int, json: String, headers: Map<String, String> = emptyMap()) = WireResponse(status, headers, ByteArrayInputStream(json.toByteArray()))
    private fun identity() = JSONObject().put("api_version", 1).put("progress_schema_versions", JSONArray().put(1))
        .put("scope", JSONArray(listOf("books:read", "reading-state:read", "reading-state:write")))
        .put("user", JSONObject().put("id", "u1").put("name", "用户").put("role", "admin"))
        .put("device", JSONObject().put("id", "d1").put("name", "设备")).put("books_root_id", "root")
    @Test fun deletionCapabilityAndPermissionMustBothBePresent() = runBlocking {
        val json=identity().put("capabilities",JSONArray().put("book-delete-v1"))
        val client=DriveClient("https://drive.example","token",Script { _,_,_,_ -> response(200,json.toString()) })
        assertFalse(client.me().canDeleteBooks)
        json.getJSONArray("scope").put("books:delete")
        assertTrue(client.me().canDeleteBooks)
        json.remove("capabilities")
        assertFalse(client.me().canDeleteBooks)
    }
    @Test fun deletionPinsOneFileAndRequiresCompleteMatchingReceipt() = runBlocking {
        val entry=RemoteEntry("owner","file/a?b","root","file","书.epub",hash=hash,version="v7")
        val requestId=java.util.UUID.randomUUID().toString()
        val json=JSONObject().put("book_hash",hash).put("file_id",entry.id).put("book_file_deleted",true)
            .put("progress_file_deleted",true).put("deleted_at","2026-11-07T02:03:04Z")
        val client=DriveClient("https://drive.example","token",Script { method,url,headers,body ->
            assertEquals("DELETE",method);assertTrue(url.endsWith("/books/$hash?file_id=file%2Fa%3Fb"))
            assertEquals(requestId,headers["Idempotency-Key"]);assertEquals("\"v7\"",headers["If-Match"]);assertNull(body)
            response(200,json.toString())
        })
        assertEquals(entry.id,client.deleteBook(hash,requestId,entry).fileId)
        json.put("progress_file_deleted",false)
        try {client.deleteBook(hash,requestId,entry);fail("Partial deletion must not remove local copy")}catch(_:IllegalArgumentException){}
        json.put("progress_file_deleted",true).put("file_id","another-book")
        try {client.deleteBook(hash,requestId,entry);fail("Receipt must identify selected file")}catch(_:IllegalArgumentException){}
    }
    @Test fun deletedProgressIsNeverTreatedAsAnEmptyDocument() = runBlocking {
        val client=DriveClient("https://drive.example","token",Script { _,_,_,_ -> response(410,"""{"error":{"code":"book_deleted"}}""") })
        try {client.readState(hash);fail()}catch(e:DriveException){assertEquals("book_deleted",e.code)}
    }
    @Test fun rejectsInsecureOriginsAndEmbeddedSecrets() {
        listOf("http://drive.example", "https://user:secret@drive.example", "https://drive.example/path", "https://drive.example?token=x").forEach {
            assertThrows(IllegalArgumentException::class.java) { DriveClient(it) }
        }
        assertEquals("https://drive.example", DriveClient.normalizeOrigin("https://DRIVE.example:443/"))
        assertEquals("https://drive.example", DriveClient.normalizeOrigin("drive.example"))
    }
    @Test fun bearerIsOnlyInHeader() = runBlocking {
        val client = DriveClient("https://drive.example", "secret-token", Script { method, url, headers, body ->
            assertEquals("GET", method); assertTrue(url.endsWith("/me")); assertFalse(url.contains("secret-token"))
            assertEquals("Bearer secret-token", headers["Authorization"]); assertNull(body)
            response(200, identity().toString())
        })
        assertEquals("u1", client.me().userId)
    }
    @Test fun onlyExplicitProgressNotFoundIsEmpty() = runBlocking {
        val empty = DriveClient("https://drive.example", "token", Script { _, _, _, _ -> response(404, """{"error":{"code":"progress_not_found"}}""") })
        assertNull(empty.readState(hash))
        val inaccessible = DriveClient("https://drive.example", "token", Script { _, _, _, _ -> response(404, """{"error":{"code":"not_found"}}""") })
        try { inaccessible.readState(hash); fail() } catch (e: DriveException) { assertEquals("not_found", e.code) }
    }
    @Test fun malformedJsonIsNotTreatedAsEmptyProgress() = runBlocking {
        val client = DriveClient("https://drive.example", "token", Script { _, _, _, _ -> response(200, "broken", mapOf("ETag" to "\"v1\"")) })
        try { client.readState(hash); fail() } catch (_: org.json.JSONException) { }
    }
    @Test fun createUsesIfNoneMatchAndUpdateUsesExactEtag() = runBlocking {
        var calls = 0
        val data = Exchange(listOf(Progress(hash, "epub", """{"href":"c.xhtml","type":"application/xhtml+xml"}""", 1)), emptyList())
        val client = DriveClient("https://drive.example", "token", Script { method, _, headers, body ->
            assertEquals("PUT", method); assertNotNull(body)
            if (calls++ == 0) { assertEquals("*", headers["If-None-Match"]); assertNull(headers["If-Match"]) }
            else { assertEquals("\"v1\"", headers["If-Match"]); assertNull(headers["If-None-Match"]) }
            response(if (calls == 1) 201 else 200, BookState.canonical(data), mapOf("ETag" to "\"v$calls\""))
        })
        val first = client.writeState(hash, data, null)
        assertEquals("\"v2\"", client.writeState(hash, data, first.etag).etag)
    }
    @Test fun retryAfterIsPreservedWithoutLeakingServerMessage() = runBlocking {
        val client = DriveClient("https://drive.example", "token", Script { _, _, _, _ -> response(429, """{"error":{"code":"rate_limited","message":"secret-token"}}""", mapOf("Retry-After" to "120")) })
        try { client.me(); fail() } catch (e: DriveException) { assertEquals(120000L, e.retryAfterMillis); assertFalse(e.message!!.contains("secret-token")) }
    }
    @Test fun bindingVerificationMustStayOnSameOrigin() = runBlocking {
        val client = DriveClient("https://drive.example", transport = Script { _, _, _, _ ->
            response(200, """{"binding_id":"b1","device_code":"private-code","verification_uri_complete":"https://evil.example/device?code=public","confirmation_code":"123456","expires_in":300,"interval":5}""")
        })
        try { client.createBinding("1.0.0"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun missingPaginationCursorIsRejected() = runBlocking {
        val client = DriveClient("https://drive.example", "token", Script { _, _, _, _ -> response(200, """{"items":[]}""") })
        try { client.index(null); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun approvedBindingUsesTemporaryCodeInBodyAndOnlyReturnsBearerAfterApproval() = runBlocking {
        val binding = BindingRequest("b1", "temporary-private-code", "https://drive.example/device?code=public", "123456", 300, 5)
        val client = DriveClient("https://drive.example", transport = Script { method, url, headers, body ->
            assertEquals("POST", method); assertTrue(url.endsWith("/bindings/poll"))
            assertFalse(url.contains(binding.deviceCode)); assertNull(headers["Authorization"])
            assertEquals(binding.deviceCode, JSONObject(body!!.toString(Charsets.UTF_8)).getString("device_code"))
            response(200, identity().put("status", "approved").put("token_type", "Bearer").put("access_token", "device-secret").toString())
        })
        val result = client.poll(binding)
        assertEquals("approved", result.status); assertEquals("device-secret", result.token); assertEquals("admin", result.identity!!.role)
    }
}

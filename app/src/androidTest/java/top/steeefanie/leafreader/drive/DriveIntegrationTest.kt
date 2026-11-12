package top.steeefanie.leafreader.drive

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.*

private class MemoryCredentials : Credentials {
    val tokens = mutableMapOf<String, String>()
    override fun read(connectionId: String) = tokens[connectionId]
    override fun write(connectionId: String, token: String) { tokens[connectionId] = token }
    override fun clear(connectionId: String) { tokens.remove(connectionId) }
}
private class IsolatedContext(base: Context) : ContextWrapper(base) {
    private val prefix = UUID.randomUUID().toString()
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$prefix-$name", mode)
}

private class MockDrive : DriveTransport {
    data class State(var data: Exchange, var version: Int)
    data class Request(val method: String, val path: String, val token: String)
    val states = mutableMapOf<String, State>()
    val calls = mutableListOf<Request>()
    var onPut: (suspend () -> Unit)? = null
    var forceConflict = false
    var revoked = false
    var unavailable = false
    var directoryFailure = false
    var commitThenTimeout = false
    var progressReadLimit = Int.MAX_VALUE
    var progressReads = 0
    var indexFailure = false
    var uploadCapability = false
    var uploadPermission = false
    var listCapability = false
    var deletionCapability = false
    var deletionPermission = false
    var loseDeletionResponse = false
    var partialDeletionReceipt = false
    var onDelete: (suspend () -> Unit)? = null
    val deleted = mutableSetOf<String>()
    val files = mutableMapOf<String,ByteArray>()
    var onContent: (suspend () -> Unit)? = null
    fun response(status: Int, data: String, headers: Map<String, String> = emptyMap()) = WireResponse(status, headers, ByteArrayInputStream(data.toByteArray()))
    fun identity(user: String) = DriveIdentity(user, user, "user", "device-$user", "设备", "root-$user")
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): WireResponse {
        val uri = URI(url); val path = uri.path.removePrefix("/api/reader/v1")
        val user = headers["Authorization"].orEmpty().removePrefix("Bearer token-")
        calls += Request(method, path, user)
        if (revoked) return response(401, """{"error":{"code":"device_revoked"}}""")
        if (unavailable) return response(503, """{"error":{"code":"temporarily_unavailable"}}""")
        if (path == "/me") return response(200, JSONObject().put("api_version", 1).put("progress_schema_versions", JSONArray().put(1))
            .put("scope", JSONArray(listOf("books:read", "reading-state:read", "reading-state:write") + (if(deletionPermission)listOf("books:delete") else emptyList()) + (if(uploadPermission)listOf("books:write") else emptyList())))
            .put("capabilities",JSONArray((if(deletionCapability)listOf("book-delete-v1") else emptyList<String>()) + (if(uploadCapability)listOf("book-upload-v1") else emptyList()) + (if(listCapability)listOf("book-list-v1") else emptyList())))
            .put("user", JSONObject().put("id", user).put("name", user).put("role", "user"))
            .put("device", JSONObject().put("id", "device-$user").put("name", "设备")).put("books_root_id", "root-$user").toString())
        if(path=="/books" && method=="GET") {
            val entries=JSONArray()
            files.filterKeys {it.startsWith("$user:")}.forEach {(key,bytes)->
                val h=key.substringAfter(':');entries.put(JSONObject().put("id","file-$h").put("parent_id","root-$user").put("kind","file")
                    .put("name","上传测试.epub").put("format","epub").put("content_sha256",h).put("size",bytes.size)
                    .put("content_version","content-v1").put("modified_at","2026-11-08T01:00:00Z"))
            }
            return response(200,JSONObject().put("items",entries).put("next_cursor",JSONObject.NULL).toString())
        }
        if(path=="/books" && method=="POST") {
            if(!uploadCapability || !uploadPermission)return response(403,"""{"error":{"code":"upload_forbidden"}}""")
            val bytes=checkNotNull(body);val h=checkNotNull(headers["X-Content-SHA256"])
            val actual=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            require(h==actual);files["$user:$h"]=bytes;deleted.remove("$user:$h")
            return response(201,JSONObject().put("id","file-$h").put("parent_id","root-$user").put("kind","file")
                .put("name","上传测试.epub").put("format","epub").put("content_sha256",h).put("size",bytes.size)
                .put("content_version","content-v1").put("modified_at","2026-11-08T01:00:00Z").toString())
        }
        if(method=="GET" && path.startsWith("/files/")) {
            val id=path.removePrefix("/files/").substringBefore('/')
            val hash=id.removePrefix("file-");val key="$user:$hash"
            if(key in deleted)return response(410,"""{"error":{"code":"book_deleted"}}""")
            val bytes=files[key] ?: return response(404,"""{"error":{"code":"not_found"}}""")
            if(path.endsWith("/content")) {
                onContent?.let {onContent=null;it()}
                return WireResponse(200,mapOf("ETag" to "\"content-v1\"","Content-Length" to bytes.size.toString()),ByteArrayInputStream(bytes))
            }
            return response(200,JSONObject().put("id",id).put("parent_id","root-$user").put("kind","file")
                .put("name","下载测试.epub").put("format","epub").put("content_sha256",hash).put("size",bytes.size)
                .put("content_version","content-v1").put("modified_at","2026-11-07T01:00:00Z").toString())
        }
        if (method=="DELETE" && path.startsWith("/books/")) {
            if(!deletionCapability)return response(404,"""{"error":{"code":"not_found"}}""")
            if(!deletionPermission)return response(403,"""{"error":{"code":"delete_forbidden"}}""")
            val hash=path.substringAfterLast('/');val key="$user:$hash"
            deleted+=key;states.remove(key);files.remove(key)
            onDelete?.let {onDelete=null;it()}
            if(loseDeletionResponse){loseDeletionResponse=false;throw java.io.IOException("Lost deletion response")}
            return response(200,JSONObject().put("book_hash",hash).put("file_id","file-$hash").put("book_file_deleted",true)
                .put("progress_file_deleted",!partialDeletionReceipt).put("deleted_at","2026-11-07T02:03:04Z").toString())
        }
        if (path == "/progress") {
            val items = JSONArray()
            if (indexFailure) return response(503, """{"error":{"code":"temporary"}}""")
            val offset = uri.query.orEmpty().split('&').firstOrNull { it.startsWith("cursor=") }?.substringAfter('=')?.toInt() ?: 0
            deleted.filter {it.startsWith("$user:")}.forEach { items.put(JSONObject().put("book_hash",it.substringAfter(':')).put("etag","\"deleted-v1\"")) }
            val all = states.filterKeys { it.startsWith("$user:") }.entries.toList()
            all.drop(offset).take(100).forEach { (key, state) -> items.put(JSONObject().put("book_hash", key.substringAfter(':')).put("etag", "\"v${state.version}\"")) }
            val next = if (offset + 100 < all.size) (offset + 100).toString() else JSONObject.NULL
            return response(200, JSONObject().put("items", items).put("next_cursor", next).toString())
        }
        if (path == "/entries") {
            if (uri.query?.contains("cursor=") == true && directoryFailure) return response(503, """{"error":{"code":"temporary"}}""")
            val entries = JSONArray().put(JSONObject().put("id", "new-directory").put("kind", "directory").put("parent_id", "root-$user").put("name", "新目录").put("modified_at", "2026-11-03T08:00:00Z"))
            return response(200, JSONObject().put("parent", JSONObject().put("id", "root-$user").put("name", "books").put("parent_id", JSONObject.NULL))
                .put("entries", entries).put("next_cursor", if (directoryFailure) "next" else JSONObject.NULL).toString())
        }
        if (path.startsWith("/progress/")) {
            val hash = path.substringAfterLast('/'); val key = "$user:$hash"; val current = states[key]
            if(key in deleted)return response(410,"""{"error":{"code":"book_deleted"}}""")
            if (method == "GET" && progressReads++ >= progressReadLimit)
                return response(429, """{"error":{"code":"rate_limited"}}""", mapOf("Retry-After" to "60"))
            if (method == "GET") return if (current == null) response(404, """{"error":{"code":"progress_not_found"}}""")
                else response(200, BookState.canonical(current.data), mapOf("ETag" to "\"v${current.version}\""))
            if (method == "PUT") {
                if (forceConflict) { forceConflict = false; states[key] = State(BookState.empty, (current?.version ?: 0) + 1); return response(412, """{"error":{"code":"version_conflict"}}""") }
                val allowed = if (current == null) headers["If-None-Match"] == "*" else headers["If-Match"] == "\"v${current.version}\""
                if (!allowed) return response(412, """{"error":{"code":"version_conflict"}}""")
                onPut?.let { onPut = null; it() }
                val state = State(BookState.decode(hash, body!!.toString(Charsets.UTF_8)), (current?.version ?: 0) + 1)
                states[key] = state
                if (commitThenTimeout) { commitThenTimeout = false; throw java.io.IOException("simulated lost upload response") }
                return response(if (current == null) 201 else 200, BookState.canonical(state.data), mapOf("ETag" to "\"v${state.version}\""))
            }
        }
        return response(404, """{"error":{"code":"not_found"}}""")
    }
}

class DriveIntegrationTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "a".repeat(64)
    private fun position(page: Int, time: Long) = Progress(hash, "epub", """{"href":"c.xhtml","type":"application/xhtml+xml","locations":{"position":$page}}""", time)
    private fun connection() = DriveConnection("connection", "https://unit.example", "u1", "u1", "user", "device-u1", "设备", "root-u1")
    private fun database() = Room.inMemoryDatabaseBuilder(base, ReaderDatabase::class.java).build()

    private suspend fun cachedBook(repository:Repository,owner:String):Book {
        val book=Book(hash,"删除测试","epub","delete-${UUID.randomUUID()}.epub",1)
        repository.file(book).writeText("self-authored deletion test cache")
        repository.dao.insert(book);repository.db.drive().grant(BookAccess(owner,hash))
        repository.saveProgress(position(2,100),owner)
        return book
    }
    @Test fun manualUploadRespectsLocalOnlyAndCreatesOneBookAndOneJson()=runBlocking {
        service {service,repository,db,mock ->
            mock.listCapability=true;mock.uploadCapability=true;mock.uploadPermission=true
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val books=(1..2).map { i->
                val bytes="Self-authored cloud upload $i ${UUID.randomUUID()}".toByteArray()
                val h=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                val book=Book(h,"上传 $i","epub","$h.epub",1)
                repository.file(book).writeBytes(bytes);repository.dao.insert(book);db.drive().grant(BookAccess(c.ownerKey,h))
                repository.saveProgress(position(2,100).copy(hash=h),c.ownerKey);book
            }
            try {
                service.setLocalOnly(c.ownerKey,books[1].hash,true)
                service.synchronize();assertTrue(mock.calls.none{it.method=="POST" || it.method=="PUT"})
                service.uploadPending()
                assertEquals(setOf("u1:${books[0].hash}"),mock.files.keys)
                assertEquals(setOf("u1:${books[0].hash}"),mock.states.keys)
                assertEquals(1,db.drive().isLocalOnly(c.ownerKey,books[1].hash))
                assertTrue(db.drive().state(c.ownerKey,books[1].hash)!!.dirty)
                service.uploadPending();assertEquals(1,mock.calls.count{it.method=="POST" && it.path=="/books"})
            }finally{books.forEach{repository.file(it).delete();repository.jsonFile(it.hash).delete()}}
        }
    }
    @Test fun deletionNeedsCapabilityAndSeparatePermissionAndNeverRemovesLocalOnFailure()=runBlocking {
        service {service,repository,db,mock ->
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val book=cachedBook(repository,c.ownerKey)
            try {
                repeat(2){i ->
                    mock.deletionCapability=i==1
                    try{service.deleteBook(hash,c.ownerKey);fail()}catch(_:IllegalStateException){}
                    assertTrue(repository.file(book).exists());assertNotNull(repository.book(hash,c.ownerKey))
                    assertTrue(db.drive().state(c.ownerKey,hash)!!.dirty)
                }
                assertTrue(mock.calls.none {it.method=="DELETE"})
                mock.deletionPermission=true;mock.unavailable=true
                try{service.deleteBook(hash,c.ownerKey);fail()}catch(_:DriveException){}
                assertTrue(repository.file(book).exists());assertEquals(0,db.drive().isDeleted(c.ownerKey,hash))
            }finally{repository.file(book).delete()}
        }
    }
    @Test fun deletionRemovesOnlySelectedBookAndKeepsAnotherAccountsSharedCache()=runBlocking {
        service {service,repository,db,mock ->
            mock.deletionCapability=true;mock.deletionPermission=true
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val book=cachedBook(repository,c.ownerKey)
            val other="b".repeat(64)
            db.drive().grant(BookAccess("another-account",hash))
            mock.states["u1:$hash"]=MockDrive.State(Exchange(listOf(position(2,100)),emptyList()),1)
            mock.states["u1:$other"]=MockDrive.State(BookState.empty,1)
            try {
                service.deleteBook(hash,c.ownerKey)
                assertNull(repository.book(hash,c.ownerKey));assertNotNull(repository.book(hash,"another-account"))
                assertTrue(repository.file(book).exists());assertEquals(1,db.drive().isDeleted(c.ownerKey,hash))
                assertNull(mock.states["u1:$hash"]);assertNotNull(mock.states["u1:$other"])
                assertFalse(db.drive().state(c.ownerKey,hash)!!.dirty)
                repository.saveProgress(position(5,200),c.ownerKey)
                assertFalse(db.drive().state(c.ownerKey,hash)!!.dirty)
                service.synchronize();assertTrue(mock.calls.none {it.method=="PUT"})
            }finally{repository.file(book).delete()}
        }
    }
    @Test fun lostDeletionResponseKeepsLocalAndRetryConfirmsAlreadyDeletedBook()=runBlocking {
        service {service,repository,db,mock ->
            mock.deletionCapability=true;mock.deletionPermission=true;mock.loseDeletionResponse=true
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val book=cachedBook(repository,c.ownerKey)
            try {
                try{service.deleteBook(hash,c.ownerKey);fail()}catch(_:java.io.IOException){}
                assertTrue(repository.file(book).exists());assertNotNull(repository.book(hash,c.ownerKey))
                service.synchronize()
                assertEquals(1,db.drive().isDeleted(c.ownerKey,hash));assertFalse(db.drive().state(c.ownerKey,hash)!!.dirty)
                repository.saveBookmark(Bookmark(hash,"offline","epub","离线记录",position(1,1).locator,200),c.ownerKey)
                service.synchronize();assertTrue(mock.calls.none {it.method=="PUT"})
                service.deleteBook(hash,c.ownerKey)
                assertNull(repository.book(hash,c.ownerKey));assertFalse(repository.file(book).exists())
            }finally{repository.file(book).delete()}
        }
    }
    @Test fun partialDeletionReceiptRetainsLocalCopy()=runBlocking {
        service {service,repository,db,mock ->
            mock.deletionCapability=true;mock.deletionPermission=true;mock.partialDeletionReceipt=true
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val book=cachedBook(repository,c.ownerKey)
            try {
                try{service.deleteBook(hash,c.ownerKey);fail()}catch(_:IllegalArgumentException){}
                assertNotNull(repository.book(hash,c.ownerKey));assertTrue(repository.file(book).exists())
                assertEquals(0,db.drive().isDeleted(c.ownerKey,hash))
            }finally{repository.file(book).delete()}
        }
    }
    @Test fun deletionWaitsForInFlightSyncThenPreventsLaterUploads()=runBlocking {
        service {service,repository,db,mock ->
            mock.deletionCapability=true;mock.deletionPermission=true
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val book=cachedBook(repository,c.ownerKey)
            val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
            mock.onPut={entered.complete(Unit);finish.await()}
            try {
                coroutineScope {
                    val upload=async {service.syncPending()};entered.await()
                    val delete=async {service.deleteBook(hash,c.ownerKey)}
                    delay(100);assertTrue(mock.calls.none {it.method=="DELETE"})
                    finish.complete(Unit);upload.await();delete.await()
                }
                assertNull(mock.states["u1:$hash"]);assertEquals(1,db.drive().isDeleted(c.ownerKey,hash))
                repository.saveProgress(position(9,300),c.ownerKey);service.syncPending()
                assertNull(mock.states["u1:$hash"]);assertFalse(db.drive().state(c.ownerKey,hash)!!.dirty)
            }finally{finish.complete(Unit);repository.file(book).delete()}
        }
    }
    @Test fun deletionWaitsForInFlightDownloadAndRemovesItsImportedCopy()=runBlocking {
        service {service,repository,db,mock ->
            mock.deletionCapability=true;mock.deletionPermission=true
            val c=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val bytes="Self-authored download fixture ${UUID.randomUUID()}".toByteArray()
            val h=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            mock.files["u1:$h"]=bytes
            val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
            mock.onContent={entered.complete(Unit);finish.await()}
            try {
                coroutineScope {
                    val download=async {service.download("file-$h",{ "epub" to "下载测试" }){_,_->}}
                    entered.await()
                    val delete=async {service.deleteBook(h,c.ownerKey)}
                    delay(100);assertTrue(mock.calls.none {it.method=="DELETE"})
                    finish.complete(Unit);assertEquals(h,download.await());delete.await()
                }
                assertNull(repository.book(h,c.ownerKey));assertNull(repository.dao.book(h))
                assertFalse(java.io.File(repository.jsonFile(h).parentFile,"$h.epub").exists())
                assertEquals(1,db.drive().isDeleted(c.ownerKey,h))
            }finally {finish.complete(Unit);repository.jsonFile(h).delete()}
        }
    }
    @Test fun accountSwitchAfterRemoteCommitStillCleansOnlyCapturedAccount()=runBlocking {
        service {service,repository,db,mock ->
            mock.deletionCapability=true;mock.deletionPermission=true
            val first=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            val second=service.bind("https://unit.example",BindingPoll("approved",token="token-u2",identity=mock.identity("u2")))
            service.select(first);val book=cachedBook(repository,first.ownerKey)
            db.drive().grant(BookAccess(second.ownerKey,hash))
            mock.onDelete={service.select(second)}
            try {
                service.deleteBook(hash,first.ownerKey)
                assertEquals(second.ownerKey,repository.owner.value)
                assertNull(repository.book(hash,first.ownerKey));assertNotNull(repository.book(hash,second.ownerKey))
                assertEquals(0,db.drive().isDeleted(second.ownerKey,hash));assertTrue(repository.file(book).exists())
            }finally{repository.file(book).delete()}
        }
    }
    @Test fun rateLimitedFullPullResumesAfterRestartWithoutUploadingOrReReadingAcceptedBooks() = runBlocking {
        val name = "full-sync-restart-${UUID.randomUUID()}.db"
        val context = IsolatedContext(base)
        var db = Room.databaseBuilder(base, ReaderDatabase::class.java, name).build()
        var repository = Repository(context, db)
        val credentials = MemoryCredentials(); val mock = MockDrive(); var now = System.currentTimeMillis()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service = DriveService(context, repository, scope, credentials, mock, { now })
        try {
            val c = service.bind("https://unit.example", BindingPoll("approved", token="token-u1", identity=mock.identity("u1")))
            repeat(262) { i ->
                val h=i.toString(16).padStart(64,'0')
                mock.states["u1:$h"]=MockDrive.State(Exchange(listOf(position(5,100).copy(hash=h)),emptyList()),1)
            }
            mock.progressReadLimit=223
            try {service.synchronize();fail("Expected rate limiting")} catch(e:DriveException){assertEquals(429,e.status)}
            assertEquals(223,db.drive().states(c.ownerKey).count{it.etag!=null})
            assertTrue(service.pending()>0)
            service.close();db.close()
            db=Room.databaseBuilder(base,ReaderDatabase::class.java,name).build()
            repository=Repository(context,db)
            service=DriveService(context,repository,scope,credentials,mock,{now})
            val callsAtLimit=mock.calls.size
            service.syncPending()
            assertEquals(callsAtLimit,mock.calls.size) // Retry-After survives service/process reconstruction.
            now+=60001;mock.progressReadLimit=Int.MAX_VALUE;mock.progressReads=0
            service.syncPending()
            val recovered=db.drive().states(c.ownerKey)
            assertEquals(262,recovered.size);assertTrue(recovered.all{it.etag!=null && !it.dirty && it.error.isEmpty()})
            assertEquals(39,mock.progressReads);assertEquals(0,service.pending())
            assertTrue(mock.calls.none{it.method=="PUT"})
        } finally {service.close();scope.cancel();db.close();base.deleteDatabase(name)}
    }
    @Test fun failedIndexRemainsDurableAndDoesNotLeakIntoAnotherAccount() = runBlocking {
        val db=database();val context=IsolatedContext(base);val repository=Repository(context,db)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val credentials=MemoryCredentials();val mock=MockDrive()
        var now=System.currentTimeMillis();val service=DriveService(context,repository,scope,credentials,mock,{now})
        try{
            val first=service.bind("https://unit.example",BindingPoll("approved",token="token-u1",identity=mock.identity("u1")))
            mock.states["u1:$hash"]=MockDrive.State(Exchange(listOf(position(2,100)),emptyList()),1)
            mock.indexFailure=true
            try{service.synchronize();fail()}catch(e:DriveException){assertEquals(503,e.status)}
            assertTrue(db.drive().states(first.ownerKey).isEmpty());assertTrue(service.pending()>0)
            val second=service.bind("https://unit.example",BindingPoll("approved",token="token-u2",identity=mock.identity("u2")))
            mock.indexFailure=false;now+=30001
            val count=mock.calls.size;service.syncPending();assertEquals(count,mock.calls.size)
            assertNull(db.drive().state(second.ownerKey,hash))
            service.select(first);service.syncPending()
            assertEquals(100L,repository.progress(hash,first.ownerKey)!!.updatedAt)
            assertEquals(0,service.pending())
        }finally{service.close();scope.cancel();db.close()}
    }
    @Test fun androidLargeDocumentSurvivesStateListsObservationAndConflictStorage() = runBlocking {
        val db=database();val c=connection()
        try{
            val data=Exchange(emptyList(),(0 until 23000).map {
                Bookmark(hash,"mark-$it","epub","界面联调书签🙂",position(1,1).locator,1760000000000L)
            })
            val compact=BookState.canonical(data)
            assertTrue(compact.toByteArray().size<=ProgressExchange.MAX_BYTES)
            assertEquals(23000,BookState.decode(hash,compact).bookmarks.size)
            db.drive().putState(CloudState(c.ownerKey,hash,compact,compact,"\"v1\"",1,true))
            assertEquals(compact,db.drive().state(c.ownerKey,hash)!!.currentJson)
            assertEquals(compact,db.drive().states(c.ownerKey).single().baseJson)
            assertEquals(compact,db.drive().dirty(c.ownerKey).single().currentJson)
            assertEquals(compact,db.drive().observeStates(c.ownerKey).first().single().currentJson)
            db.drive().putConflict(CloudConflict("large-copy",c.ownerKey,hash,compact,100))
            assertEquals(compact,db.drive().conflicts(c.ownerKey).single().json)
        }finally{db.close()}
    }
    @Test fun bookmarkConflictIsPersistedAndReadableAfterSync() = runBlocking {
        val db=database();val repository=Repository(base,db);val c=connection()
        try{
            val original=Bookmark(hash,"same","epub","原始",position(1,1).locator,50)
            val baseline=Exchange(emptyList(),listOf(original))
            db.drive().putState(CloudState(c.ownerKey,hash,BookState.canonical(baseline),BookState.canonical(baseline),"\"v1\"",1))
            repository.saveBookmark(original.copy(label="本机",updatedAt=100),c.ownerKey)
            val mock=MockDrive();mock.states["u1:$hash"]=MockDrive.State(Exchange(emptyList(),listOf(original.copy(label="远端",updatedAt=100))),2)
            ProgressSync(db).sync(c,DriveClient(c.baseUrl,"token-u1",mock),hash){}
            assertEquals("远端",repository.bookmarks(hash,c.ownerKey).single().label)
            val copy=db.drive().conflicts(c.ownerKey).single()
            assertEquals("本机",BookState.decode(hash,copy.json).bookmarks.single().label)
        }finally{db.close()}
    }
    @Test fun uploadWhileReadingRetainsNewerLocalRevision() = runBlocking {
        val db = database()
        try {
            val repository = Repository(base, db); val c = connection(); val mock = MockDrive()
            repository.saveProgress(position(1, 100), c.ownerKey)
            mock.onPut = { repository.saveProgress(position(2, 200), c.ownerKey) }
            val sync = ProgressSync(db); val client = DriveClient(c.baseUrl, "token-u1", mock)
            sync.sync(c, client, hash) {}
            val pending = db.drive().state(c.ownerKey, hash)!!
            assertTrue(pending.dirty)
            assertEquals(200L, BookState.decode(hash, pending.currentJson).progress.single().updatedAt)
            assertEquals(100L, mock.states["u1:$hash"]!!.data.progress.single().updatedAt)
            sync.sync(c, client, hash) {}
            assertFalse(db.drive().state(c.ownerKey, hash)!!.dirty)
            assertEquals(200L, mock.states["u1:$hash"]!!.data.progress.single().updatedAt)
        } finally { db.close() }
    }
    @Test fun concurrent412RefetchesAndMerges() = runBlocking {
        val db = database()
        try {
            val repository = Repository(base, db); val c = connection(); val mock = MockDrive().apply { forceConflict = true }
            repository.saveProgress(position(2, 100), c.ownerKey)
            ProgressSync(db).sync(c, DriveClient(c.baseUrl, "token-u1", mock), hash) {}
            assertFalse(db.drive().state(c.ownerKey, hash)!!.dirty)
            assertEquals(100L, mock.states["u1:$hash"]!!.data.progress.single().updatedAt)
            assertEquals(2, mock.calls.count { it.method == "PUT" })
        } finally { db.close() }
    }
    @Test fun lostUploadResponseIsConfirmedByReadingInsteadOfBlindOverwrite() = runBlocking {
        val db = database()
        try {
            val repository = Repository(base, db); val c = connection(); val mock = MockDrive().apply { commitThenTimeout = true }
            repository.saveProgress(position(2, 100), c.ownerKey)
            ProgressSync(db).sync(c, DriveClient(c.baseUrl, "token-u1", mock), hash) {}
            assertEquals(1, mock.calls.count { it.method == "PUT" })
            assertFalse(db.drive().state(c.ownerKey, hash)!!.dirty)
            assertEquals(100L, mock.states["u1:$hash"]!!.data.progress.single().updatedAt)
        } finally { db.close() }
    }
    @Test fun remoteIndexCanRestoreProgressWithoutAnyDownloadedBook() = runBlocking {
        service { service, _, db, mock ->
            service.bind("https://unit.example", BindingPoll("approved", token = "token-u1", identity = mock.identity("u1")))
            mock.states["u1:$hash"] = MockDrive.State(Exchange(listOf(position(5, 100)), emptyList()), 1)
            service.synchronize()
            val c = service.connection()!!
            assertNotNull(db.drive().state(c.ownerKey, hash)); assertNull(db.dao().book(hash))
        }
    }
    @Test fun switchingAccountNeverUploadsPreviousUsersQueue() = runBlocking {
        service { service, repository, db, mock ->
            val first = service.bind("https://unit.example", BindingPoll("approved", token = "token-u1", identity = mock.identity("u1")))
            repository.saveProgress(position(1, 100), first.ownerKey)
            repository.saveProgress(position(9, 90), "local")
            val second = service.bind("https://unit.example", BindingPoll("approved", token = "token-u2", identity = mock.identity("u2")))
            service.synchronize()
            assertTrue(db.drive().state(first.ownerKey, hash)!!.dirty)
            assertNull(db.drive().state(second.ownerKey, hash))
            assertTrue(mock.calls.none { it.method == "PUT" })
            assertEquals(90L, db.dao().progress(hash)!!.updatedAt)
        }
    }
    @Test fun revokedCredentialStopsRequestsButRetainsDirtyState() = runBlocking {
        service { service, repository, db, mock ->
            val c = service.bind("https://unit.example", BindingPoll("approved", token = "token-u1", identity = mock.identity("u1")))
            repository.saveProgress(position(1, 100), c.ownerKey); mock.revoked = true
            try { service.synchronize(); fail() } catch (e: DriveException) { assertEquals(401, e.status) }
            assertEquals("revoked", db.drive().connection(c.id)!!.status)
            assertTrue(db.drive().state(c.ownerKey, hash)!!.dirty)
            assertTrue(mock.calls.none { it.method == "PUT" })
        }
    }
    @Test fun networkFailureDoesNotEraseBindingOrPendingData() = runBlocking {
        service { service, repository, db, mock ->
            val c = service.bind("https://unit.example", BindingPoll("approved", token = "token-u1", identity = mock.identity("u1")))
            repository.saveProgress(position(1, 100), c.ownerKey); mock.unavailable = true
            try { service.synchronize(); fail() } catch (e: DriveException) { assertEquals(503, e.status) }
            assertEquals("connected", db.drive().connection(c.id)!!.status)
            assertTrue(db.drive().state(c.ownerKey, hash)!!.dirty)
            mock.unavailable = false; service.synchronize()
            assertFalse(db.drive().state(c.ownerKey, hash)!!.dirty)
        }
    }
    @Test fun failedDirectoryPaginationPreservesPreviouslyCachedList() = runBlocking {
        service { service, _, db, mock ->
            val c = service.bind("https://unit.example", BindingPoll("approved", token = "token-u1", identity = mock.identity("u1")))
            db.drive().putEntries(listOf(RemoteEntry(c.ownerKey, "old", "root-u1", "directory", "原目录")))
            mock.directoryFailure = true
            try { service.refreshDirectory(); fail() } catch (_: DriveException) { }
            assertEquals("old", db.drive().entries(c.ownerKey, "root-u1").single().id)
        }
    }
    @Test fun adoptingLocalDataRequiresExplicitCallAndKeepsOriginals() = runBlocking {
        val db = database()
        try {
            val repository = Repository(base, db)
            db.dao().insert(Book(hash, "本机书", "epub", "$hash.epub", 1)); db.drive().grant(BookAccess("local", hash))
            repository.saveProgress(position(1, 100), "local")
            val owner = connection().ownerKey
            assertNull(db.drive().state(owner, hash))
            withTimeout(5000) { repository.adoptLocal(owner) }
            assertEquals(1, db.drive().hasBook(owner, hash)); assertEquals(1, db.drive().hasBook("local", hash))
            assertTrue(db.drive().state(owner, hash)!!.dirty); assertNotNull(db.dao().progress(hash))
        } finally { db.close() }
    }
    @Test fun credentialIsEncryptedAndRecoverable() {
        val context = IsolatedContext(base); val credentials = SecureCredentials(context)
        try {
            credentials.write("test-connection", "test-private-device-token")
            assertEquals("test-private-device-token", credentials.read("test-connection"))
            val stored = context.getSharedPreferences("drive-credentials", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(stored.contains("test-private-device-token"))
            credentials.clear("test-connection"); assertNull(credentials.read("test-connection"))
        } finally { credentials.clear("test-connection") }
    }
    @Test fun removingFromWrongSpaceCannotDeleteAnotherAccountsCachedFile() = runBlocking {
        val db = database(); val repository = Repository(base, db)
        val book = Book("c".repeat(64), "隔离测试", "epub", "scope-cache-test.dat", 1)
        val file = repository.file(book)
        try {
            file.writeText("shared test cache")
            db.dao().insert(book); db.drive().grant(BookAccess("account-a", book.hash))
            try { repository.remove(book, "account-b"); fail() } catch (_: IllegalArgumentException) { }
            assertTrue(file.exists()); assertNotNull(db.dao().book(book.hash))
            db.drive().grant(BookAccess("account-b", book.hash))
            repository.remove(book, "account-a")
            assertTrue(file.exists()); assertEquals(1, db.drive().hasBook("account-b", book.hash))
            assertEquals(0, db.drive().hasBook("account-a", book.hash))
        } finally { file.delete(); db.close() }
    }
    @Test fun invalidLocalCloudRecordRollsBackWithoutDamagingPreviousState() = runBlocking {
        val db = database()
        try {
            val repository = Repository(base, db); val owner = connection().ownerKey
            repository.saveProgress(position(1, 100), owner)
            val before = db.drive().state(owner, hash)!!
            val bad = Bookmark(hash, "bad-label", "epub", "x".repeat(1025), position(1, 1).locator, 200)
            try { repository.saveBookmark(bad, owner); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(before, db.drive().state(owner, hash))
        } finally { db.close() }
    }

    private suspend fun service(action: suspend (DriveService, Repository, ReaderDatabase, MockDrive) -> Unit) {
        val db = database(); val repository = Repository(IsolatedContext(base), db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val mock = MockDrive()
        val service = DriveService(IsolatedContext(base), repository, scope, MemoryCredentials(), mock)
        try { action(service, repository, db, mock) } finally { service.close(); scope.cancel(); db.close() }
    }
}

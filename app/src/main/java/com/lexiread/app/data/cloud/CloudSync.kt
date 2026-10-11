package com.lexiread.app.data.cloud

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.lexiread.app.LexiReadApp
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.ReadingPosition
import com.lexiread.app.data.model.readingPosition
import com.shelest.shared.SyncPolicy
import com.shelest.shared.AuthProtocol
import com.shelest.shared.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No HTTP logging interceptor: credentials and reading data must never reach logs. */
class CloudSync(private val app: LexiReadApp) {
    companion object {
        const val CLIENT_ID = AuthProtocol.GOOGLE_WEB_CLIENT_ID
        private const val API = "https://api.shelest-book.ru/api/v1/"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val personal = PersonalCloud(app, { account.value?.optString("id").orEmpty() }, { schedule() })
    suspend fun titleChanged(book: Book, title: String) { val who=account.value?.optString("id").orEmpty();personal.capture("title:${hash(book)}", title,who) }
    suspend fun markChanged(mark: com.lexiread.app.data.model.Annotation, deleted: Boolean) {
        val who=account.value?.optString("id").orEmpty()
        val book=app.database.bookDao().getBookById(mark.bookId) ?: return
        personal.markChanged(mark,deleted,hash(book),who)
    }
    private val mutex = Mutex()
    private val refreshLock = Any()
    private var positionJob: Job? = null
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(600, TimeUnit.SECONDS).build()
    private val prefs = app.getSharedPreferences("cloud-metadata", Context.MODE_PRIVATE)
    private val vault = File(app.noBackupFilesDir, "cloud-session.enc")
    @Volatile private var session: JSONObject? = readSession()
    val account = MutableStateFlow(session?.optJSONObject("account"))
    val lastSuccessfulSync = MutableStateFlow(prefs.getLong("last-successful-sync:${account.value?.optString("id")}", 0))
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val autoSync = MutableStateFlow(prefs.getBoolean("auto-sync", SyncPolicy.DEFAULT_AUTO_SYNC))
    val syncDeletions = MutableStateFlow(prefs.getBoolean("sync-deletions", SyncPolicy.DEFAULT_SYNC_DELETIONS))
    private val stateLock = Any()
    private val coordinator = SyncCoordinator().apply {
        updateAccount(account.value?.let { runCatching { SyncProtocol.account(it.toString()) }.getOrNull() })
    }
    var readerOpen: Boolean
        get() = synchronized(stateLock) { coordinator.readerOpen }
        set(value) { synchronized(stateLock) { coordinator.setReading(value) } }
    @Volatile var foreground = false
    private val cursor: Long get() = synchronized(stateLock) { coordinator.cursor }
    private fun current(run: Int) = synchronized(stateLock) { coordinator.isCurrent(run) }
    private fun updateAccount(value: JSONObject): CloudAccount {
        val shared = SyncProtocol.account(value.toString())
        synchronized(stateLock) { coordinator.updateAccount(shared) }
        account.value = JSONObject(shared.toJson().toString())
        lastSuccessfulSync.value = prefs.getLong("last-successful-sync:${shared.id}", 0)
        return shared
    }
    private fun invalidate() = synchronized(stateLock) { coordinator.invalidate() }
    private var delayed: Job? = null
    private val device = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    init {
        scope.launch { while (isActive) { delay(SyncPolicy.intervalMs(autoSync.value)); if (foreground) schedule() } }
    }
    fun setAutoSync(enabled: Boolean) {
        if (autoSync.value && !enabled) invalidate()
        prefs.edit().putBoolean("auto-sync", enabled).apply(); autoSync.value = enabled
        if (enabled) schedule() else positionJob?.cancel()
    }
    fun setSyncDeletions(enabled: Boolean) { prefs.edit().putBoolean("sync-deletions", enabled).apply(); syncDeletions.value = enabled; if (enabled) invalidate() }
    private fun hashes(kind: String, owner: String): Set<String> = prefs.getStringSet("$kind:$owner", emptySet()).orEmpty().toSet()
    private fun saveHashes(kind: String, owner: String, values: Set<String>) { prefs.edit().putStringSet("$kind:$owner", values).commit() }
    suspend fun bookImported(book: Book) {
        val owner = account.value?.optString("id") ?: return
        val sha = hash(book)
        if (account.value?.optString("id") != owner) return
        invalidate()
        synchronized(prefs) {
            saveHashes("hidden", owner, hashes("hidden", owner) - sha)
            saveHashes("deleted", owner, hashes("deleted", owner) - sha)
            saveHashes("pending-deletes", owner, hashes("pending-deletes", owner) - sha)
            saveHashes("restores", owner, hashes("restores", owner) + sha)
        }
        prefs.edit().remove("sent:$owner:$sha").apply()
    }
    suspend fun bookDeleted(book: Book) {
        val owner = account.value?.optString("id")
        if (owner == null) {
            knownCollectionHash(book)?.let { removeCollectionMembership(it, "") }
            return
        }
        val sha = hash(book)
        if (account.value?.optString("id") != owner) error("Аккаунт изменился. Повторите удаление")
        invalidate()
        val kind = SyncRules.deletionKind(syncDeletions.value)
        if (syncDeletions.value) removeCollectionMembership(sha, owner)
        synchronized(prefs) { saveHashes(kind, owner, hashes(kind, owner) + sha); saveHashes("restores", owner, hashes("restores", owner) - sha) }
        scope.launch { mutex.withLock { if (account.value?.optString("id") == owner) runCatching { flushDeletions(owner) }.onFailure { status.value = "Книга удалена локально; удаление из облака повторится при подключении" } } }
    }
    private fun removeCollectionMembership(sha: String, owner: String) {
        val entries = personal.collectionEntries(owner)
        val changes = entries.keys().asSequence().filter { it.startsWith("member:") && it.endsWith(":" + sha) && !entries.getJSONObject(it).isNull("value") }
            .associateWith { JSONObject.NULL as Any }
        personal.captureBatch(changes, owner)
    }
    private fun flushDeletions(owner: String) {
        for (sha in hashes("pending-deletes", owner)) {
            if (account.value?.optString("id") != owner) return
            request("sync/books/$sha", "DELETE", JSONObject().put("accountId", owner).toString().toRequestBody("application/json".toMediaType()), expectedOwner = owner).use { check(it.isSuccessful) { "Не удалось удалить книгу из облака (${it.code})" } }
            if (account.value?.optString("id") != owner) return
            synchronized(prefs) {
                saveHashes("pending-deletes", owner, hashes("pending-deletes", owner) - sha)
                saveHashes("deleted", owner, hashes("deleted", owner) + sha)
            }
        }
    }
    private fun userKey(id: Long) = "place:${account.value?.optString("id")}:$id"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("shelest-cloud", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("shelest-cloud", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun readSession(): JSONObject? = runCatching {
        if (!vault.exists()) return@runCatching null
        val bytes = vault.readBytes(); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }.getOrNull()
    @Synchronized private fun persist() {
        if (session == null) { vault.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val temp = File(app.noBackupFilesDir, "cloud-session.tmp")
        temp.writeBytes(cipher.iv + cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8)))
        check(temp.renameTo(vault)) { "Не удалось сохранить аккаунт" }
    }
    private fun request(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true, retry: Boolean = true, timeoutSeconds: Long = 0,
        expectedOwner: String? = if (authenticated) account.value?.optString("id") else null): Response {
        fun ensureOwner() { if (authenticated && expectedOwner != account.value?.optString("id")) throw CancellationException("Аккаунт изменился") }
        ensureOwner()
        val token = session?.optString("accessToken").orEmpty()
        val request = Request.Builder().url(API + path).method(method, body)
        if (authenticated && token.isNotBlank()) request.header("Authorization", "Bearer $token")
        val transport = if (timeoutSeconds > 0) client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build() else client
        val response = transport.newCall(request.build()).execute()
        try { ensureOwner() } catch (e: CancellationException) { response.close(); throw e }
        if (response.code == 401 && authenticated && retry && session != null) {
            response.close()
            synchronized(refreshLock) {
                ensureOwner()
                if (session?.optString("accessToken") == token) {
                    val payload = JSONObject().put("refreshToken", session!!.getString("refreshToken"))
                    val refreshed = json("auth/refresh", "POST", payload, false, timeoutSeconds)
                    ensureOwner()
                    session!!.put("accessToken", refreshed.getString("accessToken")).put("refreshToken", refreshed.getString("refreshToken")); persist()
                }
            }
            return request(path, method, body, true, false, timeoutSeconds, expectedOwner)
        }
        return response
    }
    private fun json(path: String, method: String = "GET", payload: JSONObject? = null, authenticated: Boolean = true, timeoutSeconds: Long = 0,
        expectedOwner: String? = if (authenticated) account.value?.optString("id") else null, retry: Boolean = true): JSONObject {
        request(path, method, payload?.toString()?.toRequestBody("application/json".toMediaType()), authenticated, retry = retry, timeoutSeconds = timeoutSeconds, expectedOwner = expectedOwner).use {
            val data = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            check(it.isSuccessful) {
                AuthProtocol.errorJson(data.toString(), it.code)
            }
            return data
        }
    }
    /** Read-only opening probe: cancellable, no refresh/file work on the reader's critical path. */
    private suspend fun openingSnapshot(owner: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(API + "sync/books")
            .put(JSONObject(SyncProtocol.request(emptyList(), 0)).toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${session?.optString("accessToken").orEmpty()}").build()
        val call = client.newBuilder().callTimeout(1200, TimeUnit.MILLISECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    check(account.value?.optString("id") == owner) { "Аккаунт изменился" }
                    val data = JSONObject(it.body?.string().orEmpty())
                    check(it.isSuccessful) { AuthProtocol.errorJson(data.toString(), it.code) }
                    data
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
    suspend fun signIn(context: Context) {
        val config = withContext(Dispatchers.IO) { json("auth/config", authenticated = false) }
        val option = GetSignInWithGoogleOption.Builder(CLIENT_ID).setNonce(config.getString("nonce")).build()
        val result = CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val token = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        withContext(Dispatchers.IO) { mutex.withLock {
            val result = json("auth/google", "POST", JSONObject().put("idToken", token).put("nonce", config.getString("nonce"))
                .put("deviceName", android.os.Build.MODEL), false)
            if (result.optBoolean("mfaRequired")) throw MfaRequired(result.getString("challenge"))
            session = result
            updateAccount(session!!.getJSONObject("account")); invalidate(); persist()
        } }
        if (autoSync.value) synchronize()
    }
    suspend fun signInWithPassword(login: String, password: String, register: Boolean = false) {
        withContext(Dispatchers.IO) { mutex.withLock {
            val result = json(AuthProtocol.passwordPath(false, register), "POST",
                JSONObject(AuthProtocol.passwordPayload(login, password, register, android.os.Build.MODEL)), false)
            if (result.optBoolean("mfaRequired")) throw MfaRequired(result.getString("challenge"))
            session = result
            updateAccount(session!!.getJSONObject("account")); invalidate(); persist()
        } }
        if (autoSync.value) synchronize()
    }
    class MfaRequired(val challenge: String) : Exception("Введите код аутентификатора")
    suspend fun finishMfa(challenge: String, code: String) {
        withContext(Dispatchers.IO) { mutex.withLock {
            session = json("auth/mfa", "POST", JSONObject().put("challenge", challenge).put("code", code), false)
            updateAccount(session!!.getJSONObject("account")); invalidate(); persist()
        } }
        if (autoSync.value) synchronize()
    }
    suspend fun setAccountPassword(login: String, password: String, currentPassword: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = json("account/password", "POST", JSONObject().put("login", login).put("password", password).put("currentPassword", currentPassword))
            updateAccount(result.getJSONObject("account"))
            session?.put("account", account.value); persist()
        }
    }
    suspend fun manageAccountSecurity(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = json("account/security", "POST", payload, retry = false)
            result.optJSONObject("account")?.let {
                updateAccount(it)
                session?.put("account", account.value)
                persist()
            }
            result
        }
    }
    suspend fun signOut() = withContext(Dispatchers.IO) { mutex.withLock {
        runCatching { json("auth/logout", "POST", JSONObject()) }
        positionJob?.cancel(); session = null; account.value = null
        lastSuccessfulSync.value = 0
        synchronized(stateLock) { coordinator.updateAccount(null) }; persist(); status.value = ""
    } }
    fun placeChanged(id: Long) {
        prefs.edit().putLong(userKey(id), System.currentTimeMillis()).apply()
        if (!SyncPolicy.canPublishPosition(autoSync.value, account.value != null)) return
        val owner = account.value?.optString("id") ?: return
        positionJob?.cancel()
        positionJob = scope.launch {
            delay(SyncRules.DEBOUNCE_MS)
            if (account.value?.optString("id") != owner || !autoSync.value) return@launch
            try {
                val book = app.bookRepository.getBook(id) ?: return@launch
                val sha = hash(book)
                if (bookMarks(sha, owner).blocked) return@launch
                val payload = JSONObject(SyncProtocol.request(listOf(positionRecord(book, sha)), 0))
                val result = json("sync/books", "PUT", payload, timeoutSeconds = 10, expectedOwner = owner)
                if (account.value?.optString("id") == owner && result.has("account")) updateAccount(result.getJSONObject("account"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.value = e.message ?: "Место сохранено локально; нет связи с облаком" }
        }
    }
    fun anchorCaptured(id: Long, anchor: String) {
        prefs.edit().putString("anchor:$id", anchor.take(240)).apply()
    }
    fun schedule() {
        delayed?.cancel(); delayed = scope.launch {
            delay(SyncRules.DEBOUNCE_MS)
            if (autoSync.value) synchronize()
            else mutex.withLock { account.value?.optString("id")?.let { owner -> runCatching { flushDeletions(owner) } } }
        }
    }
    private fun hash(book: Book): String {
        val file = File(book.filePath)
        val cacheKey = "hash:${book.id}:${file.length()}:${file.lastModified()}"
        prefs.getString(cacheKey, null)?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { prefs.edit().putString(cacheKey, it).apply() }
    }
    fun collectionHash(book: Book): String = hash(book)
    fun knownCollectionHash(book: Book): String? =
        prefs.getString("hash:${book.id}:${File(book.filePath).length()}:${File(book.filePath).lastModified()}", null)
    private fun progressTime(book: Book): Long = synchronized(prefs) {
        val key = userKey(book.id)
        val value = SyncRules.progressTime(if (prefs.contains(key)) prefs.getLong(key, 0) else null,
            book.lastOpenedAt ?: 0L, book.scrollPercent > 0 || (book.readingPage ?: 0) > 0)
        // Migrate once: reopening/restyling updates lastOpenedAt but isn't new reading.
        if (!prefs.contains(key)) prefs.edit().putLong(key, value).apply()
        value
    }
    private fun bookMarks(sha: String, owner: String) = BookMarks(sha in hashes("hidden", owner),
        sha in hashes("pending-deletes", owner), sha in hashes("deleted", owner), sha in hashes("restores", owner))
    private fun positionRecord(book: Book, sha: String, restoring: Boolean = false): SyncBook {
        val exact = book.readingPosition
        val place = exact?.let { ReadingPlace("android", it.segment, it.page, it.character,
            it.layout, it.scrollY, anchor = prefs.getString("anchor:${book.id}", "")) }
        return SyncBook(sha, book.title.take(500), book.author.take(500), book.fileType.name, File(book.filePath).length(),
            (book.scrollPercent / 100f).coerceIn(0f, 1f).toDouble(), exact?.page, progressTime(book), place,
            deviceId = device, restoreDeleted = restoring)
    }
    private suspend fun resolvePosition(book: Book, remote: SyncBook, action: PositionAction): ReadingPosition? = when (action) {
        PositionAction.PDF_PAGE -> ReadingPosition(0, remote.pageIndex!!, null, "pdf", 0.0)
        PositionAction.LOCAL_PLACE -> remote.readingPlace?.let {
            ReadingPosition(it.segment ?: 0, it.page ?: 0, it.character, it.layout.orEmpty(), it.scrollY ?: 0.0)
        }
        PositionAction.TEXT_ANCHOR -> app.bookRepository.resolveCloudAnchor(book, remote.readingPlace?.anchor.orEmpty())
        else -> null
    }

    suspend fun pullPositionBeforeReading(id: Long): Unit = withContext(Dispatchers.IO) {
        if (account.value == null) return@withContext
        val owner = account.value!!.getString("id")
        val generation = synchronized(stateLock) { coordinator.generation }
        fun valid() = account.value?.optString("id") == owner &&
            synchronized(stateLock) { coordinator.generation == generation }
        try {
            val original = app.bookRepository.getBook(id) ?: return@withContext
            progressTime(original)
            if (!autoSync.value || !valid()) return@withContext
            val sha = hash(original)
            val result = SyncProtocol.response(openingSnapshot(owner).toString())
            if (!valid() || bookMarks(sha, owner).blocked) return@withContext
            val remote = result.books.find { it.sha256 == sha } ?: return@withContext
            val book = app.bookRepository.getBook(id) ?: return@withContext
            val action = SyncRules.positionAction(progressTime(book), remote, "android")
            if (action == PositionAction.KEEP_LOCAL) return@withContext
            val exact = resolvePosition(book, remote, action)
            if (!valid()) return@withContext
            if (exact == null) { status.value = "Не удалось однозначно найти место чтения в тексте"; return@withContext }
            app.bookRepository.applyCloudPosition(id, (remote.position * 100).toFloat(), remote.updatedAt, exact)
            if (!valid()) return@withContext
            prefs.edit().putLong("place:$owner:$id", remote.updatedAt)
                .putString("anchor:$id", remote.readingPlace?.anchor.orEmpty()).apply()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (valid()) status.value = e.message ?: "Книга открыта офлайн" }
    }

    suspend fun synchronize() = withContext(Dispatchers.IO) {
        if (account.value == null || readerOpen || !mutex.tryLock()) return@withContext
        busy.value = true
        var run = -1
        var incompatiblePlace = false
        try {
            val who = updateAccount(json("account").getJSONObject("account"))
            run = synchronized(stateLock) { coordinator.begin() }
            if (run < 0) return@withContext
            val owner = who.id
            flushDeletions(owner)
            if (!current(run)) return@withContext
            val books = app.database.bookDao().getBooksForSync().associateBy { hash(it) }.toMutableMap()
            val changed = books.filter { (sha, book) -> SyncRules.needsPublish(progressTime(book),
                prefs.getLong("sent:$owner:$sha", -1).takeIf { it >= 0 }, bookMarks(sha, owner)) }
            val chunks = changed.entries.toList().chunked(SyncRules.BATCH_SIZE).ifEmpty { listOf(emptyList()) }
            for (batch in chunks) {
                if (!current(run)) return@withContext
                val records = batch.mapNotNull { (sha, original) ->
                    app.bookRepository.getBook(original.id)?.let { positionRecord(it, sha, sha in hashes("restores", owner)) }
                }
                val result = SyncProtocol.response(json("sync/books", "PUT",
                    JSONObject(SyncProtocol.request(records, cursor)), expectedOwner = owner).toString())
                if (!current(run)) return@withContext
                check(result.account?.id?.let { it == owner } != false) { "Аккаунт изменился" }
                result.account?.let { updateAccount(JSONObject(it.toJson().toString())) }
                if (!current(run)) return@withContext
                for (sha in result.deletedBooks) {
                    if (!current(run)) return@withContext
                    synchronized(prefs) { saveHashes("deleted", owner, hashes("deleted", owner) + sha) }
                    if (syncDeletions.value) books.remove(sha)?.let { app.bookRepository.deleteBook(it, notifyCloud = false) }
                }
                for (remote in result.books) {
                    if (!current(run)) return@withContext
                    val sha = remote.sha256
                    synchronized(prefs) {
                        saveHashes("deleted", owner, hashes("deleted", owner) - sha)
                        saveHashes("restores", owner, hashes("restores", owner) - sha)
                    }
                    if (bookMarks(sha, owner).blocked) continue
                    var book = books[sha]
                    if (SyncRules.shouldDownload(who, book != null, remote.hasFile, bookMarks(sha, owner))) {
                        check(SyncRules.validFileSize(remote.size, who)) { "Книга превышает допустимый размер файла" }
                        val temp = File(app.cacheDir, "cloud_${UUID.randomUUID()}.${remote.fileType.lowercase()}")
                        try {
                            request("sync/books/$sha/download", expectedOwner = owner).use { response ->
                                check(response.isSuccessful) { "Не удалось скачать книгу" }
                                response.body!!.byteStream().use { input -> temp.outputStream().use { output ->
                                    val buffer = ByteArray(65536); var total = 0L
                                    while (true) {
                                        val n = input.read(buffer); if (n < 0) break
                                        total += n
                                        check(SyncRules.validFileSize(total, who)) { "Книга превышает допустимый размер файла" }
                                        output.write(buffer, 0, n)
                                    }
                                } }
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            temp.inputStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                            }
                            check(digest.digest().joinToString("") { "%02x".format(it) } == sha) { "Повреждённый файл книги" }
                            if (!current(run)) return@withContext
                            book = app.bookRepository.addBook(Uri.fromFile(temp), fromCloud = true)
                            book?.let { imported ->
                                val restored = imported.copy(title = remote.title.ifBlank { imported.title },
                                    author = remote.author.ifBlank { imported.author })
                                app.database.bookDao().updateMetadata(restored.id, restored.title, restored.author)
                                book = restored; books[sha] = restored
                                prefs.edit().putLong("place:$owner:${restored.id}", 0).apply()
                            }
                        } finally { temp.delete() }
                    }
                    if (!current(run)) return@withContext
                    val local = book?.let { app.bookRepository.getBook(it.id) } ?: continue
                    val action = SyncRules.positionAction(progressTime(local), remote, "android")
                    if (action != PositionAction.KEEP_LOCAL) {
                        val exact = resolvePosition(local, remote, action)
                        if (!current(run)) return@withContext
                        if (exact == null) incompatiblePlace = true
                        else {
                            app.bookRepository.applyCloudPosition(local.id, (remote.position * 100).toFloat(), remote.updatedAt, exact)
                            if (!current(run)) return@withContext
                            prefs.edit().putLong("place:$owner:${local.id}", remote.updatedAt)
                                .putString("anchor:${local.id}", remote.readingPlace?.anchor.orEmpty()).apply()
                        }
                    }
                    if (!current(run)) return@withContext
                    if (SyncRules.shouldUpload(who, remote.hasFile, bookMarks(sha, owner))) {
                        val file = File(local.filePath)
                        check(SyncRules.validFileSize(file.length(), who)) { "Книга превышает допустимый размер файла" }
                        request("sync/books/$sha/file", "PUT", file.asRequestBody("application/octet-stream".toMediaType()),
                            expectedOwner = owner).use {
                            if (it.code == 422) prefs.edit().remove("hash:${local.id}:${file.length()}:${file.lastModified()}").apply()
                            check(it.isSuccessful) { "Не удалось сохранить книгу в облаке (${it.code})" }
                        }
                    }
                }
                if (!current(run)) return@withContext
                for (record in records) prefs.edit().putLong("sent:$owner:${record.sha256}", record.updatedAt).apply()
                if (!synchronized(stateLock) { coordinator.acceptCursor(run, result.serverTime) }) return@withContext
            }
            if (!current(run)) return@withContext
            personal.synchronize(owner, { current(run) }, { payload -> json("sync/personal", "PUT",payload,expectedOwner=owner) }, { hash(it) })
            if (!current(run)) return@withContext
            status.value = if (incompatiblePlace) "Не удалось однозначно перенести точное место чтения" else "Синхронизировано"
            if (!incompatiblePlace) {
                val completedAt = System.currentTimeMillis()
                prefs.edit().putLong("last-successful-sync:$owner", completedAt).apply()
                lastSuccessfulSync.value = completedAt
            }
            session?.put("account", account.value); persist()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status.value = e.message ?: "Нет связи. Книги доступны офлайн" }
        finally {
            synchronized(stateLock) { coordinator.finish(run) }
            busy.value = false; mutex.unlock()
        }
    }
}

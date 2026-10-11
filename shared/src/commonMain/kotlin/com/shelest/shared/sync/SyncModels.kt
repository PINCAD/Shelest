package com.shelest.shared.sync

import kotlinx.serialization.json.*

data class CloudAccount(
    val id: String, val email: String = "", val displayName: String = "",
    val isPremium: Boolean = false, val quotaBytes: Long = 0, val storageUsedBytes: Long = 0,
    val maxBookBytes: Long = 100L * 1024 * 1024, val login: String = "", val hasPassword: Boolean = false,
    val twoFactorEnabled: Boolean = false
) {
    val entitlementKey get() = "$id:$isPremium:$quotaBytes:$maxBookBytes"
    fun toJson() = buildJsonObject {
        put("id", id); put("email", email); put("displayName", displayName)
        put("isPremium", isPremium); put("quotaBytes", quotaBytes); put("storageUsedBytes", storageUsedBytes)
        put("maxBookBytes", maxBookBytes); put("login", login); put("hasPassword", hasPassword)
        put("twoFactorEnabled", twoFactorEnabled)
    }
}

/** Page/segment coordinates are local to a layout; anchor is the cross-device identity. */
data class ReadingPlace(
    val platform: String, val segment: Int? = null, val page: Int? = null,
    val character: Int? = null, val layout: String? = null, val scrollY: Double? = null,
    val cfi: String? = null, val anchor: String? = null
) {
    fun toJson() = buildJsonObject {
        put("platform", platform)
        segment?.let { put("segment", it) }; page?.let { put("page", it) }
        put("character", character?.let(::JsonPrimitive) ?: JsonNull)
        layout?.let { put("layout", it) }; scrollY?.let { put("scrollY", it) }
        cfi?.let { put("cfi", it) }; anchor?.let { put("anchor", it) }
    }
}

data class SyncBook(
    val sha256: String, val title: String, val author: String, val fileType: String,
    val size: Long, val position: Double, val pageIndex: Int?, val updatedAt: Long,
    val readingPlace: ReadingPlace? = null, val hasFile: Boolean = false,
    val deviceId: String = "", val restoreDeleted: Boolean = false
) {
    fun toJson(outbound: Boolean = false) = buildJsonObject {
        put("sha256", sha256); put("title", title); put("author", author); put("fileType", fileType)
        put("size", size); put("position", position); put("pageIndex", pageIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("updatedAt", updatedAt); put("readingPlace", readingPlace?.toJson() ?: JsonNull)
        if (outbound) { put("deviceId", deviceId); put("restoreDeleted", restoreDeleted) }
        else put("hasFile", hasFile)
    }
}

data class SyncResponse(val books: List<SyncBook>, val deletedBooks: List<String>, val account: CloudAccount?, val serverTime: Long) {
    fun toJson() = buildJsonObject {
        put("books", JsonArray(books.map { it.toJson() }))
        put("deletedBooks", JsonArray(deletedBooks.map(::JsonPrimitive)))
        account?.let { put("account", it.toJson()) }; put("serverTime", serverTime)
    }
}

/** One wire contract on both clients. No tokens, passwords, file contents or database IDs. */
object SyncProtocol {
    val formats = setOf("EPUB", "PDF", "FB2", "TXT", "HTML", "MD", "DOCX", "RTF", "CBZ")
    private val hash = Regex("[a-f0-9]{64}")
    private val device = Regex("[A-Za-z0-9_-]{8,64}")
    fun objectOf(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    fun account(json: String): CloudAccount = account(objectOf(json))
    fun account(value: JsonObject): CloudAccount {
        val id = value.string("id").ifBlank { error("Некорректный аккаунт сервера") }
        return CloudAccount(id, value.string("email"), value.string("displayName"), value.bool("isPremium"),
            value.long("quotaBytes").coerceAtLeast(0), value.long("storageUsedBytes").coerceAtLeast(0),
            value.long("maxBookBytes", 100L * 1024 * 1024).coerceAtLeast(0), value.string("login"), value.bool("hasPassword"),
            value.bool("twoFactorEnabled"))
    }
    fun place(value: JsonElement?): ReadingPlace? {
        if (value == null || value == JsonNull) return null
        val obj = value.jsonObject
        val platform = obj.string("platform")
        require(platform in setOf("android", "web")) { "Некорректная платформа позиции" }
        fun number(key: String): Int? = obj[key]?.takeUnless { it == JsonNull }?.let {
            val n = (it as? JsonPrimitive)?.intOrNull
            require(n != null && n >= 0) { "Некорректная позиция чтения" }; n
        }
        fun text(key: String, max: Int): String? = obj[key]?.takeUnless { it == JsonNull }?.let {
            val p = it as? JsonPrimitive
            require(p != null && p.isString && p.content.length <= max) { "Некорректный текст позиции" }; p.content
        }
        val y = obj["scrollY"]?.takeUnless { it == JsonNull }?.let {
            val n = (it as? JsonPrimitive)?.doubleOrNull
            require(n != null && n.isFinite() && n >= 0) { "Некорректная прокрутка" }; n
        }
        return ReadingPlace(platform, number("segment"), number("page"), number("character"),
            text("layout", 256), y, text("cfi", 2048), text("anchor", 240))
    }
    fun book(json: String, outbound: Boolean = false): SyncBook = book(objectOf(json), outbound)
    fun book(value: JsonObject, outbound: Boolean = false): SyncBook {
        val sha = value.string("sha256"); require(hash.matches(sha)) { "Некорректный идентификатор книги" }
        val type = value.string("fileType"); require(type in formats) { "Неподдерживаемый формат книги" }
        val title = value.string("title"); require(title.isNotBlank() && title.length <= 500) { "Некорректное название книги" }
        val author = value.string("author"); require(author.length <= 500)
        val size = value.long("size", -1); require(size in 0..(1L shl 40)) { "Некорректный размер книги" }
        val progress = (value["position"] as? JsonPrimitive)?.doubleOrNull
        require(progress != null && progress.isFinite() && progress in 0.0..1.0) { "Некорректный прогресс книги" }
        val page = value["pageIndex"]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.intOrNull ?: error("Некорректная страница") }
        require(page == null || page in 0 until 10_000_000) { "Некорректная страница" }
        val updated = value["updatedAt"]?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.longOrNull ?: error("Некорректное время позиции") } ?: 0L
        require(updated >= 0)
        val deviceId = value.string("deviceId")
        if (outbound) { require(device.matches(deviceId)); require(updated >= SyncRules.MIN_TIMESTAMP) }
        return SyncBook(sha, title, author, type, size, progress, page, updated,
            place(value["readingPlace"]), value.bool("hasFile"), deviceId, value.bool("restoreDeleted"))
    }
    fun response(json: String): SyncResponse {
        val obj = objectOf(json)
        val books = obj["books"]?.jsonArray ?: error("Нет списка книг в ответе сервера")
        val deleted = obj["deletedBooks"]?.jsonArray?.map {
            val sha = it.jsonPrimitive.content; require(hash.matches(sha)); sha
        }.orEmpty()
        val time = obj.long("serverTime", -1); require(time >= 0)
        return SyncResponse(books.map { book(it.jsonObject) }, deleted,
            obj["account"]?.takeUnless { it == JsonNull }?.let { account(it.jsonObject) }, time)
    }
    fun request(books: List<SyncBook>, since: Long): String {
        require(books.size <= SyncRules.BATCH_SIZE && since >= 0)
        // Validate before sending: a bad local record must not poison the whole batch.
        val records = books.map { book(it.toJson(true), true).toJson(true) }
        return buildJsonObject { put("books", JsonArray(records)); put("since", since) }.toString()
    }
}

internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
internal fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.long(key: String, default: Long = 0L) = (get(key) as? JsonPrimitive)?.longOrNull ?: default

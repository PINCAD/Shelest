@file:OptIn(ExperimentalJsExport::class)

import com.shelest.shared.AccountPresentation
import com.shelest.shared.AuthForm
import com.shelest.shared.SyncPolicy
import com.shelest.shared.AuthProtocol
import com.shelest.shared.SettingsRules
import com.shelest.shared.sync.*
import kotlinx.serialization.json.*
import com.shelest.shared.reader.ReaderDocumentStyle
import com.shelest.shared.reader.MobiDocumentDecoder
import com.shelest.shared.reader.buildReaderHtml

/** Stable, typed JS boundary. No Kotlin Long or platform types escape to JavaScript. */
@JsExport
object ShelestCore {
    fun personalWordKey(word: String): String = com.shelest.shared.PersonalSyncRules.wordKey(word)
    fun personalResponse(source: String): String = com.shelest.shared.PersonalSyncRules.response(source)
    val defaultAutoSync: Boolean get() = SyncPolicy.DEFAULT_AUTO_SYNC
    val defaultSyncDeletions: Boolean get() = SyncPolicy.DEFAULT_SYNC_DELETIONS
    fun syncIntervalMs(enabled: Boolean): Double = SyncPolicy.intervalMs(enabled).toDouble()
    fun canPublishPosition(enabled: Boolean, signedIn: Boolean): Boolean = SyncPolicy.canPublishPosition(enabled, signedIn)
    fun blocksBook(hidden: Boolean, pendingDelete: Boolean, deleted: Boolean, restoring: Boolean): Boolean =
        SyncPolicy.blocksBook(hidden, pendingDelete, deleted, restoring)
    fun initials(name: String): String = AccountPresentation.initials(name)
    fun storageFraction(used: Double, quota: Double): Double = AccountPresentation.storageFraction(used, quota)
    fun formatBytes(bytes: Double): String = AccountPresentation.formatBytes(bytes)
    fun validateLogin(login: String, password: String, confirmation: String, register: Boolean): String =
        AuthForm.validate(login, password, confirmation, register)

    fun collectionsJson(source: String): String = com.shelest.shared.CollectionRules.json(source)
    fun mobiDocumentJson(binary: String): String {
        val document = MobiDocumentDecoder.decodeBinary(binary)
        return buildJsonObject {
            put("title", document.title)
            put("author", document.author)
            put("html", document.html)
        }.toString()
    }
    val googleWebClientId: String get() = AuthProtocol.GOOGLE_WEB_CLIENT_ID
    val syncBatchSize: Int get() = SyncRules.BATCH_SIZE
    val syncDebounceMs: Double get() = SyncRules.DEBOUNCE_MS.toDouble()
    fun accountJson(json: String): String = SyncProtocol.account(json).toJson().toString()
    fun syncResponseJson(json: String): String = SyncProtocol.response(json).toJson().toString()
    fun syncRequestJson(books: String, since: Double): String = SyncProtocol.request(
        Json.parseToJsonElement(books).jsonArray.map { SyncProtocol.book(it.jsonObject, true) }, since.toLong())
    fun passwordPath(register: Boolean): String = AuthProtocol.passwordPath(true, register)
    fun passwordPayload(login: String, password: String, register: Boolean): String = AuthProtocol.passwordPayload(login, password, register)
    fun serverError(json: String, status: Int): String = AuthProtocol.errorJson(json, status)
    fun webSettings(json: String): String = SettingsRules.webSettings(json)
    fun accountDisplayName(json: String): String = com.shelest.shared.AccountRules.displayName(
        if (json == "null") null else SyncProtocol.account(json))
    fun progressTime(explicit: Double, legacyOpenedAt: Double, hasRead: Boolean): Double =
        SyncRules.progressTime(explicit.takeIf { it.isFinite() && it >= 0 }?.toLong(),
            legacyOpenedAt.takeIf { it.isFinite() }?.toLong() ?: 0L, hasRead).toDouble()
    fun normalizeAnchor(text: String): String = TextAnchor.normalize(text)
    fun anchorOffset(text: String, anchor: String): Int = TextAnchor.uniqueOffset(text, anchor) ?: -1
    fun rawAnchorOffset(text: String, target: Int): Int = TextAnchor.rawOffset(text, target)
    fun positionAction(localTime: Double, remoteJson: String, consumer: String, reading: Boolean): String =
        SyncRules.positionAction(localTime.toLong(), SyncProtocol.book(remoteJson), consumer, reading).name
    fun needsPublish(localTime: Double, sentTime: Double, hidden: Boolean, pending: Boolean, deleted: Boolean, restoring: Boolean): Boolean =
        SyncRules.needsPublish(localTime.toLong(), sentTime.takeIf { it >= 0 && it.isFinite() }?.toLong(), BookMarks(hidden, pending, deleted, restoring))
    fun deletionKind(everywhere: Boolean): String = SyncRules.deletionKind(everywhere)
    fun shouldDownload(premium: Boolean, local: Boolean, hasFile: Boolean, blocked: Boolean): Boolean =
        SyncRules.shouldDownload(CloudAccount("js", isPremium = premium), local, hasFile, BookMarks(hidden = blocked))
    fun shouldUpload(premium: Boolean, hasFile: Boolean, blocked: Boolean): Boolean =
        SyncRules.shouldUpload(CloudAccount("js", isPremium = premium), hasFile, BookMarks(hidden = blocked))
    fun validFileSize(size: Double, limit: Double): Boolean = size.isFinite() && limit.isFinite() && size >= 0 &&
        size % 1.0 == 0.0 && limit % 1.0 == 0.0 &&
        SyncRules.validFileSize(size.toLong(), CloudAccount("js", maxBookBytes = limit.toLong()))

    /** Browser adapter; the document implementation is the same commonMain code Android uses. */
    fun readerDocument(
        content: String, fontCss: String, fontLoad: String, fontSize: Int,
        lineHeight: String, margin: String, background: String, foreground: String,
        accent: String, selection: String, align: String, animation: String, styleJson: String,
        fontFaceCss: String, savedProgress: Double, hasPrevious: Boolean, hasNext: Boolean,
        positionJson: String, segmentIndex: Int, horizontal: Boolean, bridgeJs: String,
        backgroundMeasurement: Boolean
    ): String = buildReaderHtml(
        content, ReaderDocumentStyle(fontCss, fontLoad, fontSize, lineHeight, margin, background,
            foreground, accent, selection, align, animation, styleJson, fontFaceCss),
        savedProgress, hasPrevious, hasNext, positionJson, segmentIndex, horizontal, bridgeJs, backgroundMeasurement
    )
}

/** Instantiated per client; not a global singleton shared by unrelated accounts. */
@JsExport
class SharedSyncSession {
    private val core = SyncCoordinator()
    val owner: String get() = core.owner
    val generation: Int get() = core.generation
    val cursor: Double get() = core.cursor.toDouble()
    val readerOpen: Boolean get() = core.readerOpen
    fun updateAccount(json: String): Boolean = core.updateAccount(if (json == "null") null else SyncProtocol.account(json))
    fun setReading(value: Boolean) = core.setReading(value)
    fun resetCursor() = core.resetCursor()
    fun invalidate() = core.invalidate()
    fun begin(): Int = core.begin()
    fun isCurrent(run: Int): Boolean = core.isCurrent(run)
    fun acceptCursor(run: Int, time: Double): Boolean = core.acceptCursor(run, time.toLong())
    fun finish(run: Int) = core.finish(run)
}

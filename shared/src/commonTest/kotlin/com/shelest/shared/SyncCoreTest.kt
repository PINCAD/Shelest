package com.shelest.shared

import com.shelest.shared.sync.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SyncCoreTest {
    private val account = CloudAccount("one", login = "reader", maxBookBytes = 4096)
    private val record = SyncBook("a".repeat(64), "Book", "", "EPUB", 24, 0.4, 3,
        SyncRules.MIN_TIMESTAMP + 100, ReadingPlace("android", 1, 3, 40, "layout",
            anchor = "Уникальный отрывок достаточной длины"), deviceId = "fixture-device")

    @Test fun wireRoundTripAndNullCoordinates() {
        val outgoing = SyncProtocol.objectOf(SyncProtocol.request(listOf(record), 0))
        assertEquals(record, SyncProtocol.book(outgoing.getValue("books").jsonArray[0].jsonObject, true))
        val response = SyncResponse(listOf(record.copy(deviceId = "")), emptyList(), account, 100)
        assertEquals(response, SyncProtocol.response(response.toJson().toString()))
        assertNull(SyncProtocol.book(record.copy(pageIndex = null).toJson()).pageIndex)
    }
    @Test fun malformedRecordCannotApplyTombstones() {
        val invalid = record.toJson().toMutableMap().apply { put("position", JsonPrimitive(1.2)) }
        val response = buildJsonObject {
            put("books", JsonArray(listOf(JsonObject(invalid))))
            put("deletedBooks", JsonArray(listOf(JsonPrimitive("a".repeat(64)))))
            put("serverTime", 100)
        }
        assertFails { SyncProtocol.response(response.toString()) }
        assertFails { SyncProtocol.request(listOf(record.copy(deviceId = "invalid")), 0) }
        assertFails { SyncProtocol.request(List(101) { record }, 0) }
        assertFails { SyncProtocol.request(listOf(record.copy(updatedAt = 0)), 0) }
        assertFails { SyncProtocol.book(record.copy(sha256 = "bad").toJson()) }
    }
    @Test fun newerPlaceIsExactNeverPercentageOnly() {
        val time = SyncRules.MIN_TIMESTAMP
        assertEquals(PositionAction.LOCAL_PLACE, SyncRules.positionAction(time, record, "android"))
        assertEquals(PositionAction.TEXT_ANCHOR, SyncRules.positionAction(time, record, "web"))
        assertEquals(PositionAction.KEEP_LOCAL, SyncRules.positionAction(record.updatedAt, record, "web"))
        assertEquals(PositionAction.KEEP_LOCAL, SyncRules.positionAction(time, record, "web", true))
        assertEquals(PositionAction.INCOMPATIBLE, SyncRules.positionAction(time, record.copy(readingPlace = null), "web"))
        assertEquals(PositionAction.PDF_PAGE, SyncRules.positionAction(time, record.copy(fileType = "PDF"), "web"))
        assertEquals(PositionAction.INCOMPATIBLE, SyncRules.positionAction(time, record.copy(fileType = "PDF", pageIndex = null), "android"))
    }
    @Test fun explicitInitialTimestampWinsOverOpeningTime() {
        assertEquals(SyncRules.MIN_TIMESTAMP, SyncRules.progressTime(0, Long.MAX_VALUE, true))
        assertEquals(SyncRules.MIN_TIMESTAMP, SyncRules.progressTime(null, Long.MAX_VALUE, false))
        assertEquals(record.updatedAt, SyncRules.progressTime(null, record.updatedAt, true))
    }
    @Test fun sameUnicodeAnchorOnBothPlatforms() {
        val needle = "Достаточно длинный отрывок."
        assertEquals(4, TextAnchor.uniqueOffset("A \u00a0\ufeff" + needle, needle))
        assertEquals(TextAnchor.normalize(needle), TextAnchor.normalize(needle.replace(" ", "\u202f\n")))
        assertNull(TextAnchor.uniqueOffset(needle + needle, needle))
        assertNull(TextAnchor.uniqueOffset(needle, "коротко"))
    }
    @Test fun initialLayoutAndRestylingAreNotReading() {
        val place = record.readingPlace!!
        assertFalse(SyncRules.isReadingChange(null, place))
        assertFalse(SyncRules.isReadingChange(place, place.copy(page = 10, layout = "other")))
        assertTrue(SyncRules.isReadingChange(place, place.copy(character = 400))) // Same repeated quotation, different place.
        assertTrue(SyncRules.isReadingChange(place, place.copy(anchor = "Совсем другой достаточно длинный отрывок")))
    }
    @Test fun freeAccountNeverTransfersBookFiles() {
        assertFalse(SyncRules.shouldDownload(account, false, true, BookMarks()))
        assertFalse(SyncRules.shouldUpload(account, false, BookMarks()))
        val premium = account.copy(isPremium = true)
        assertTrue(SyncRules.shouldDownload(premium, false, true, BookMarks()))
        assertTrue(SyncRules.shouldUpload(premium, false, BookMarks()))
        assertFalse(SyncRules.shouldDownload(premium, true, true, BookMarks()))
        assertFalse(SyncRules.shouldUpload(premium, false, BookMarks(pendingDelete = true)))
        assertTrue(SyncRules.validFileSize(4096, premium))
        assertFalse(SyncRules.validFileSize(4097, premium))
    }
    @Test fun importUnblocksTombstoneAndForcesPublishing() {
        assertTrue(BookMarks(pendingDelete = true, restoring = true).blocked)
        assertFalse(SyncRules.marksAfterImport().blocked)
        assertFalse(SyncRules.needsPublish(100, 100, BookMarks()))
        assertTrue(SyncRules.needsPublish(100, null, SyncRules.marksAfterImport()))
        assertEquals("hidden", SyncRules.deletionKind(false))
    }
    @Test fun accountSwitchRejectsOldResponseAndCursor() {
        val state = SyncCoordinator()
        assertEquals(-1, state.begin())
        assertTrue(state.updateAccount(account))
        val run = state.begin()
        assertTrue(state.acceptCursor(run, 100))
        state.updateAccount(account.copy(id = "two"))
        assertFalse(state.isCurrent(run))
        assertFalse(state.acceptCursor(run, 900))
        assertEquals(0, state.cursor)
        state.finish(run)
        assertTrue(state.begin() > run)
    }
    @Test fun readerOpeningCancelsLibraryButNoParallelRun() {
        val state = SyncCoordinator().apply { updateAccount(account) }
        val run = state.begin()
        assertEquals(-1, state.begin())
        state.setReading(true)
        assertFalse(state.isCurrent(run))
        assertFalse(state.acceptCursor(run, 100))
        state.setReading(false)
        assertFalse(state.isCurrent(run))
        state.finish(run)
        assertTrue(state.begin() > run)
    }
    @Test fun entitlementChangesResetCursorNotUsageChanges() {
        val state = SyncCoordinator().apply { updateAccount(account) }
        val run = state.begin()
        state.acceptCursor(run, 100)
        assertFalse(state.updateAccount(account.copy(storageUsedBytes = 24)))
        assertTrue(state.isCurrent(run))
        assertTrue(state.updateAccount(account.copy(isPremium = true)))
        assertEquals(0, state.cursor)
        assertFalse(state.isCurrent(run))
    }
    @Test fun invalidationAndCursorMonotonicity() {
        val state = SyncCoordinator().apply { updateAccount(account) }
        val run = state.begin()
        assertTrue(state.acceptCursor(run, 200))
        assertTrue(state.acceptCursor(run, 100))
        assertEquals(200, state.cursor)
        state.invalidate()
        assertFalse(state.acceptCursor(run, 300))
    }
    @Test fun existingFalseSettingsAndExtraFieldsSurviveNormalization() {
        val settings = SyncProtocol.objectOf(SettingsRules.webSettings("""{"autoSync":false,"syncDeletions":false,"dailyGoal":35,"fontSize":25,"custom":"keep"}"""))
        assertFalse(settings.getValue("autoSync").jsonPrimitive.boolean)
        assertFalse(settings.getValue("syncDeletions").jsonPrimitive.boolean)
        assertEquals(25.0, settings.getValue("fontSize").jsonPrimitive.double)
        assertEquals("keep", settings.getValue("custom").jsonPrimitive.content)
        assertEquals(18.0, SettingsRules.fontSize(Double.NaN))
        assertEquals(1.0, SettingsRules.pdfZoom(Double.POSITIVE_INFINITY))
        assertEquals(240, SettingsRules.dailyGoal(999))
        assertTrue(SyncProtocol.objectOf(SettingsRules.webSettings("{}")).getValue("autoSync").jsonPrimitive.boolean)
    }
    @Test fun accountPresentationAndAuthContract() {
        assertEquals("reader", AccountRules.displayName(account))
        assertFalse(AccountRules.showLoginForm(true))
        assertEquals("auth/web/password", AuthProtocol.passwordPath(true, false))
        assertEquals("auth/register", AuthProtocol.passwordPath(false, true))
        assertEquals("reader", SyncProtocol.objectOf(AuthProtocol.passwordPayload(" reader ", "existing", false)).getValue("login").jsonPrimitive.content)
        assertEquals("Неверный логин или пароль", AuthProtocol.errorJson("""{"error":"invalid_credentials"}""", 401))
        assertFails { AuthProtocol.passwordPayload("reader", "short", true) }
    }
}

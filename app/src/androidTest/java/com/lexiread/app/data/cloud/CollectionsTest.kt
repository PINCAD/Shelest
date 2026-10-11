package com.lexiread.app.data.cloud

import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.LexiReadApp
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.FileType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Disposable emulator only. Synthetic local files; no network or real credentials. */
class CollectionsTest {
    @Test fun offlineCreateReorderRemoveAndDeleteNeverChangeBooks() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiReadApp
        assertNull("Run only in the signed-out disposable emulator", app.cloud.account.value)
        val files = listOf(File.createTempFile("collection-a-", ".txt", app.cacheDir), File.createTempFile("collection-b-", ".txt", app.cacheDir))
        files[0].writeText("Synthetic first volume " + UUID.randomUUID()); files[1].writeText("Synthetic second volume " + UUID.randomUUID())
        val books = files.mapIndexed { index, file -> Book(id = -System.nanoTime() - index, title = "Том $index", filePath = file.path, fileType = FileType.TXT, readingPage = 254) }
        var id: String? = null
        try {
            id = app.cloud.addToCollection(null, "  Серия  ", books + books[0])
            val hashes = books.map { app.cloud.collectionHash(it) }
            assertEquals(hashes, app.cloud.personal.collections("").single { it.id == id }.members)
            app.cloud.addToCollection(id, "", books)
            assertEquals(2, app.cloud.personal.collections("").single { it.id == id }.members.size)
            app.cloud.editCollection(id, name = "Новое название", order = hashes.reversed())
            assertEquals(hashes.reversed(), app.cloud.personal.collections("").single { it.id == id }.members)
            app.cloud.editCollection(id, remove = listOf(hashes[1]), order = listOf(hashes[0]))
            assertEquals(listOf(hashes[0]), app.cloud.personal.collections("").single { it.id == id }.members)
            app.cloud.editCollection(id, delete = true)
            assertFalse(app.cloud.personal.collections("").any { it.id == id })
            assertTrue(files.all { it.exists() && it.length() > 0 })
            assertTrue(books.all { it.readingPage == 254 })
        } finally {
            id?.let { group ->
                val prefs = app.getSharedPreferences("personal-sync", 0)
                val entries = JSONObject(prefs.getString("entries:guest", "{}")!!)
                val editor = prefs.edit()
                entries.keys().asSequence().toList().filter { it == "collection:$group" || it.startsWith("member:$group:") }.forEach {
                    entries.remove(it); editor.remove("binding:$it")
                }
                editor.putString("entries:guest", entries.toString()).commit()
            }
            files.forEach { it.delete() }
        }
    }

    @Test fun remoteGroupsReplayOnUpgradeAndStayInTheirAccount() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiReadApp
        val owner = "collection-test-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString(); val sha = "c".repeat(64)
        val personal = PersonalCloud(app, { owner }, {})
        val prefs = app.getSharedPreferences("personal-sync", 0)
        prefs.edit().putLong("cursor:$owner", 999).commit() // Previous-version cursor must not skip groups.
        var first = true
        try {
            personal.synchronize(owner, { true }, { request ->
                assertTrue(request.getBoolean("collections"))
                if (first) { assertEquals(0, request.getLong("since")); first = false }
                JSONObject().put("accountId", owner).put("ack", request.getJSONArray("entries"))
                    .put("entries", JSONArray().put(JSONObject().put("key", "collection:$id").put("value", "Облачная серия").put("changedAt", 100))
                        .put(JSONObject().put("key", "member:$id:$sha").put("value", 2).put("changedAt", 100)))
                    .put("cursor", 2).put("hasMore", false)
            }, { "d".repeat(64) })
            assertEquals(listOf(sha), personal.collections(owner).single { it.id == id }.members)
            assertTrue(personal.collections("other-$owner").isEmpty())
            assertFalse(personal.collections("").any { it.id == id })
            personal.captureBatch(mapOf("collection:$id" to JSONObject.NULL), owner)
            assertFalse(personal.collections(owner).any { it.id == id })
        } finally {
            prefs.edit().remove("entries:$owner").remove("cursor:$owner").remove("cursor-collections:$owner")
                .remove("binding:collection:$id").remove("binding:member:$id:$sha").commit()
        }
    }
}

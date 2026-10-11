package com.lexiread.app.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.lexiread.app.LexiReadApp
import com.lexiread.app.data.model.VocabularyWord
import com.lexiread.app.data.model.Annotation
import com.lexiread.app.data.model.AnnotationType
import com.lexiread.app.data.prefs.PrefKeys
import com.lexiread.app.data.prefs.settingsDataStore
import com.lexiread.app.domain.goal.ReadingGoal
import com.shelest.shared.PersonalSyncRules
import org.json.JSONArray
import org.json.JSONObject

/** Persistent small per-account outbox; Room IDs never leave the device. */
class PersonalCloud(private val app: LexiReadApp, private val owner: () -> String, private val changed: () -> Unit) {
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val prefs = app.getSharedPreferences("personal-sync", Context.MODE_PRIVATE)
    private val lock = Any()
    private fun journal(who: String): JSONObject {
        val entries = JSONObject(prefs.getString("entries:${who.ifEmpty { "guest" }}", "{}")!!)
        var migrated = false
        for (key in entries.keys().asSequence().toList()) {
            val entry = entries.optJSONObject(key) ?: continue
            val word = entry.optJSONObject("value")?.optString("word") ?: continue
            if (!key.startsWith("word:")) continue
            val canonical = PersonalSyncRules.wordKey(word)
            if (canonical == key) continue
            val existing = entries.optJSONObject(canonical)
            if (existing == null || entry.getLong("changedAt") > existing.getLong("changedAt"))
                entries.put(canonical, JSONObject(entry.toString()).put("key",canonical).put("pending",true))
            entries.remove(key); migrated = true
            val binding = prefs.getString("binding:$key",null)
            if (binding != null && !prefs.contains("binding:$canonical")) prefs.edit().putString("binding:$canonical",binding).commit()
        }
        if (migrated) write(who,entries)
        return entries
    }
    private fun write(who: String, entries: JSONObject) {
        check(prefs.edit().putString("entries:${who.ifEmpty { "guest" }}", entries.toString()).commit())
        revision.value = revision.value + 1
    }
    fun collectionEntries(who: String): JSONObject = synchronized(lock) {
        val entries = journal(who)
        if (who.isEmpty()) for (key in entries.keys().asSequence().toList()) {
            if (com.shelest.shared.CollectionRules.isKey(key) && prefs.getString("binding:$key", "guest") != "guest") entries.remove(key)
        }
        entries
    }
    fun collections(who: String) = com.shelest.shared.CollectionRules.read(collectionEntries(who).toString())
    fun captureBatch(values: Map<String, Any>, who: String) {
        if (values.isEmpty()) return
        synchronized(lock) {
            val entries = journal(who)
            val bindings = prefs.edit()
            for ((key, value) in values) {
                entries.put(key, JSONObject().put("key", key).put("value", value)
                    .put("changedAt", maxOf(System.currentTimeMillis(), (entries.optJSONObject(key)?.optLong("changedAt") ?: 0) + 1))
                    .put("pending", true))
                bindings.putString("binding:$key", who.ifEmpty { "guest" })
            }
            check(bindings.commit()); write(who, entries)
        }
        changed()
    }
    private fun value(word: VocabularyWord) = JSONObject().put("word", word.word.take(200)).put("definition", word.definition.take(12000))
        .put("bookTitle", word.sourceBookTitle.orEmpty().take(500)).put("addedAt", word.addedAt).put("learned", word.isLearned)
    fun wordChanged(word: VocabularyWord, deleted: Boolean) = capture(PersonalSyncRules.wordKey(word.word), if (deleted) JSONObject.NULL else value(word))
    private fun markKey(mark: Annotation, sha: String): String = synchronized(lock) {
        val mapping="mark-id:${mark.bookId}:${mark.id}"
        prefs.getString(mapping,null) ?: "mark:$sha:${java.util.UUID.randomUUID()}".also {
            check(prefs.edit().putString(mapping,it).putLong("mark-local:$it:${mark.bookId}",mark.id).commit())
        }
    }
    private fun markValue(mark: Annotation): JSONObject {
        val path=mark.pathData?.let(::JSONArray) ?: JSONArray()
        val points=JSONArray()
        val count=minOf(path.length(),10000)
        for (index in 0 until count) {
            val i=if(count<=1) 0 else (index.toLong()*(path.length()-1)/(count-1)).toInt()
            path.getJSONObject(i).let { points.put(JSONObject().put("x",it.getDouble("x").coerceIn(0.0,1.0)).put("y",it.getDouble("y").coerceIn(0.0,1.0))) }
        }
        return JSONObject().put("type",mark.type.name).put("color",mark.color.toLong() and 0xffffffffL).put("createdAt",mark.createdAt)
            .put("selectedText",mark.selectedText?.take(20000) ?: JSONObject.NULL).put("contextBefore",mark.contextBefore?.take(20000) ?: JSONObject.NULL)
            .put("contextAfter",mark.contextAfter?.take(20000) ?: JSONObject.NULL).put("noteText",mark.noteText?.take(20000) ?: JSONObject.NULL)
            .put("pageIndex",mark.pageIndex ?: JSONObject.NULL).put("normX",mark.normX?.coerceIn(0f,1f) ?: JSONObject.NULL).put("normY",mark.normY?.coerceIn(0f,1f) ?: JSONObject.NULL)
            .put("points",points).put("width",path.optJSONObject(0)?.optDouble("w",2.5) ?: 2.5)
    }
    fun markChanged(mark: Annotation, deleted: Boolean, sha: String, who: String) = capture(markKey(mark,sha),if(deleted) JSONObject.NULL else markValue(mark),who)
    private suspend fun applyMark(remote: JSONObject, books: Map<String,List<com.lexiread.app.data.model.Book>>, valid: () -> Boolean) {
        val key=remote.getString("key"); val sha=key.split(':')[1]
        for (book in books[sha].orEmpty()) {
            if (!valid()) return
            val local=prefs.getLong("mark-local:$key:${book.id}",0).takeIf { it>0 }
            val replacement=if(remote.isNull("value")) null else {
                val v=remote.getJSONObject("value");val points=JSONArray(v.getJSONArray("points").toString())
                if(points.length()>0) points.getJSONObject(0).put("w",v.getDouble("width"))
                fun string(field:String)=if(v.isNull(field)) null else v.getString(field)
                fun coordinate(field:String)=if(v.isNull(field)) null else v.getDouble(field).toFloat()
                Annotation(bookId=book.id,type=AnnotationType.valueOf(v.getString("type")),color=v.getLong("color").toInt(),createdAt=v.getLong("createdAt"),
                    selectedText=string("selectedText"),contextBefore=string("contextBefore"),contextAfter=string("contextAfter"),noteText=string("noteText"),
                    pageIndex=if(v.isNull("pageIndex")) null else v.getInt("pageIndex"),normX=coordinate("normX"),normY=coordinate("normY"),
                    pathData=if(v.getString("type")=="DRAWING") points.toString() else null)
            }
            val id=app.annotationDao.applyCloud(local,replacement,valid) ?: continue
            if(id>0) check(prefs.edit().putLong("mark-local:$key:${book.id}",id).putString("mark-id:${book.id}:$id",key).commit())
            else prefs.edit().remove("mark-local:$key:${book.id}").commit()
        }
    }
    fun capture(key: String, value: Any, who: String = owner()) {
        synchronized(lock) {
            val binding = prefs.getString("binding:$key",null)
            val target = if (who.isEmpty() && binding != null && binding != "guest") binding else who
            val entries = journal(target)
            entries.put(key, JSONObject().put("key", key).put("value", value).put("changedAt", maxOf(System.currentTimeMillis(), (entries.optJSONObject(key)?.optLong("changedAt") ?: 0)+1)).put("pending", true))
            write(target, entries); prefs.edit().putString("binding:$key", target.ifEmpty { "guest" }).commit()
        }
        changed()
    }
    private fun seed(who: String, key: String, value: Any) = synchronized(lock) {
        val binding = prefs.getString("binding:$key", null)
        if (binding != null && binding != who && binding != "guest") return@synchronized
        val entries = journal(who); if (entries.has(key)) return@synchronized
        val guest = journal("").optJSONObject(key)
        val stamp = if (guest != null && guest.opt("value")?.toString() == value.toString()) guest.getLong("changedAt") else 1L
        entries.put(key, JSONObject().put("key",key).put("value",value).put("changedAt",stamp).put("pending",true))
        write(who,entries); prefs.edit().putString("binding:$key",who).commit()
    }
    suspend fun synchronize(who: String, current: () -> Boolean, send: suspend (JSONObject) -> JSONObject, hash: suspend (com.lexiread.app.data.model.Book) -> String) {
        if (!current()) return
        val books=app.database.bookDao().getBooksForSync().groupBy { hash(it) }
        // Replay confirmed records when a book was downloaded after its annotations.
        for (record in synchronized(lock) { journal(who).let { j -> j.keys().asSequence().map { j.getJSONObject(it) }.filter { it.getString("key").startsWith("mark:") && !it.optBoolean("pending") }.toList() } }) {
            val key=record.getString("key");val before=record.toString()
            applyMark(record,books) { current() && synchronized(lock) { journal(who).optJSONObject(key)?.toString()==before } }
        }
        val guest = synchronized(lock) { journal("") }
        for (key in guest.keys()) {
            if (!current()) return
            val entry = guest.getJSONObject(key)
            if (entry.optBoolean("pending") && prefs.getString("binding:$key",null) == "guest") seed(who,key,entry.get("value"))
        }
        for (word in app.vocabularyDao.getOnce()) { if (!current()) return; seed(who,PersonalSyncRules.wordKey(word.word),value(word)) }
        seed(who,"goal",ReadingGoal.minutes(app))
        for ((sha, localBooks) in books) for (book in localBooks) for(mark in app.annotationDao.getOnce(book.id)) {
            if(!current()) return
            seed(who,markKey(mark,sha),markValue(mark))
        }
        var cursor = prefs.getLong("cursor-collections:$who",0)
        var more = true
        fun pending() = synchronized(lock) {
            val entries=journal(who);val batch=mutableListOf<JSONObject>();var bytes=0
            for(key in entries.keys()) {
                val e=entries.getJSONObject(key);if(!e.optBoolean("pending")) continue
                val size=e.toString().toByteArray(Charsets.UTF_8).size
                if(batch.isNotEmpty() && (bytes+size>1500000 || batch.size>=25)) break
                batch.add(e);bytes+=size
            }
            batch
        }
        while (current() && (more || pending().isNotEmpty())) {
            val outgoing = pending()
            val raw = send(JSONObject().put("collections",true).put("since",cursor).put("entries",JSONArray(outgoing.map { JSONObject(it.toString()).apply { remove("pending") } })))
            if (!current()) return
            val result = JSONObject(PersonalSyncRules.response(raw.toString()))
            check(result.getString("accountId") == who) { "Аккаунт изменился" }
            val received = linkedMapOf<String,JSONObject>()
            for (list in listOf(result.optJSONArray("ack") ?: JSONArray(),result.getJSONArray("entries"))) {
                for (i in 0 until list.length()) list.getJSONObject(i).let { received[it.getString("key")] = it }
            }
            for (remote in received.values) {
                if (!current()) return
                val key = remote.getString("key")
                val before = synchronized(lock) { journal(who).optJSONObject(key)?.toString() }
                fun valid() = current() && synchronized(lock) { journal(who).optJSONObject(key)?.toString() == before }
                val previous = before?.let(::JSONObject)
                val sent = outgoing.find { it.getString("key") == key }
                if (previous != null && previous.getLong("changedAt") > remote.getLong("changedAt") && before != sent?.toString()) continue
                when {
                    com.shelest.shared.CollectionRules.isKey(key) -> Unit // The durable journal is the collection store.
                    key.startsWith("mark:") -> applyMark(remote,books,::valid)
                    key.startsWith("word:") -> {
                        val matches = app.vocabularyDao.getOnce().filter { PersonalSyncRules.wordKey(it.word) == key }
                        if (!current()) return
                        if (!valid()) continue
                        prefs.edit().putString("binding:$key",who).commit()
                        val replacement = if (remote.isNull("value")) null else {
                            val v = remote.getJSONObject("value")
                            VocabularyWord(id=matches.firstOrNull()?.id ?: 0,word=v.getString("word"),definition=v.getString("definition"),sourceBookTitle=v.getString("bookTitle"),addedAt=v.getLong("addedAt"),isLearned=v.getBoolean("learned"))
                        }
                        if (!app.vocabularyDao.applyCloudWord(matches,replacement,::valid)) continue
                    }
                    key == "goal" -> app.settingsDataStore.edit { if (valid()) it[PrefKeys.GOAL_MINUTES] = remote.getInt("value") }
                    key.startsWith("title:") -> for (book in app.database.bookDao().getBooksForSync()) {
                        if (!current()) return
                        if (hash(book) == key.removePrefix("title:") && valid()) app.database.bookDao().updateMetadata(book.id,remote.getString("value"),book.author)
                    }
                    else -> error("Неизвестные личные данные")
                }
                if (!current()) return
                synchronized(lock) {
                    val entries = journal(who)
                    if (entries.optJSONObject(key)?.toString() == before) {
                        entries.put(key, JSONObject(remote.toString()).put("pending",false));write(who,entries);prefs.edit().putString("binding:$key",who).commit()
                    }
                }
            }
            if (!current()) return
            synchronized(lock) {
                val entries = journal(who)
                for (sent in outgoing) if (entries.optJSONObject(sent.getString("key"))?.toString() == sent.toString()) entries.put(sent.getString("key"),JSONObject(sent.toString()).put("pending",false))
                write(who,entries);cursor=result.getLong("cursor");prefs.edit().putLong("cursor-collections:$who",cursor).commit();more=result.getBoolean("hasMore")
            }
        }
    }
}

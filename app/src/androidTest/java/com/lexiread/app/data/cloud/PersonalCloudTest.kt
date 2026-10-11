package com.lexiread.app.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.LexiReadApp
import com.lexiread.app.data.model.*
import com.lexiread.app.data.prefs.PrefKeys
import com.lexiread.app.data.prefs.settingsDataStore
import com.lexiread.app.domain.goal.ReadingGoal
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic metadata on a disposable emulator only; never uses the network. */
@RunWith(AndroidJUnit4::class)
class PersonalCloudTest {
    @Test fun dictionaryGoalAndRenameRoundTripPreservesPlace() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiReadApp
        val owner="test-${UUID.randomUUID()}"
        val personal=PersonalCloud(app,{owner},{})
        val oldGoal=ReadingGoal.minutes(app)
        val word=VocabularyWord(word="fixture-${UUID.randomUUID()}",definition="Synthetic definition",sourceBookTitle="Fixture")
        val dao=app.vocabularyDao
        val bookId=app.database.bookDao().insertBook(Book(title="Original",filePath="/test/personal-fixture.txt",fileType=FileType.TXT,readingPage=25,readingChar=200))
        val sha="a".repeat(64)
        val server=linkedMapOf<String,JSONObject>();var revision=0L
        suspend fun exchange(body:JSONObject):JSONObject {
            val incoming=body.getJSONArray("entries");val ack=JSONArray()
            for(i in 0 until incoming.length()) {
                val row=incoming.getJSONObject(i);val key=row.getString("key")
                if(!server.containsKey(key)||row.getLong("changedAt")>server.getValue(key).getLong("changedAt")) server[key]=JSONObject(row.toString()).put("revision",++revision)
                ack.put(JSONObject(server.getValue(key).toString()).apply{remove("revision")})
            }
            val delta=JSONArray(server.values.filter{it.getLong("revision")>body.getLong("since")}.map{JSONObject(it.toString()).apply{remove("revision")}})
            return JSONObject().put("accountId",owner).put("ack",ack).put("entries",delta).put("cursor",revision).put("hasMore",false)
        }
        suspend fun sync()=personal.synchronize(owner,{true},::exchange,{if(it.id==bookId)sha else "b".repeat(64)})
        try {
            dao.insert(word,notifyCloud=false);personal.wordChanged(word,false)
            personal.capture("goal",45);personal.capture("title:$sha","Renamed")
            sync()
            assertEquals(45,ReadingGoal.minutes(app))
            val restored=app.database.bookDao().getBookById(bookId)!!
            assertEquals("Renamed",restored.title);assertEquals(25,restored.readingPage);assertEquals(200,restored.readingChar)
            val key=com.shelest.shared.PersonalSyncRules.wordKey(word.word)
            val row=server.getValue(key);row.getJSONObject("value").put("learned",true);row.put("changedAt",System.currentTimeMillis()+1000).put("revision",++revision)
            sync();assertTrue(dao.getByWord(word.word)!!.isLearned)
            row.put("value",JSONObject.NULL).put("changedAt",System.currentTimeMillis()+2000).put("revision",++revision)
            sync();assertNull(dao.getByWord(word.word))
            sync();assertNull(dao.getByWord(word.word))
            val marks=app.annotationDao
            val mark=Annotation(bookId=bookId,type=AnnotationType.HIGHLIGHT,color=0xff9ecbf0.toInt(),selectedText="Synthetic selected text",contextBefore="Before",contextAfter="After")
            val drawing=Annotation(bookId=bookId,type=AnnotationType.DRAWING,color=0xff3976ba.toInt(),pageIndex=2,pathData="[{\"x\":0.2,\"y\":0.3,\"w\":4},{\"x\":0.4,\"y\":0.5}]")
            val oldCallback=marks.onLocalChanged
            marks.onLocalChanged={m,deleted->personal.markChanged(m,deleted,sha,owner)}
            try {
                marks.insert(mark);marks.insert(drawing);sync()
                val rows=server.values.filter{it.getString("key").startsWith("mark:$sha:")}
                assertEquals(2,rows.size)
                val stroke=rows.single{it.getJSONObject("value").getString("type")=="DRAWING"}
                assertEquals(2,stroke.getJSONObject("value").getInt("pageIndex"))
                assertEquals(4.0,stroke.getJSONObject("value").getDouble("width"),0.0)
                val highlight=rows.single{it.getJSONObject("value").getString("type")=="HIGHLIGHT"}
                highlight.getJSONObject("value").put("color",0xffa6d8a8L)
                highlight.put("changedAt",System.currentTimeMillis()+3000).put("revision",++revision)
                sync();assertEquals(0xffa6d8a8.toInt(),marks.getOnce(bookId).single{it.type==AnnotationType.HIGHLIGHT}.color)
                marks.delete(marks.getOnce(bookId).single{it.type==AnnotationType.DRAWING});sync()
                assertTrue(server.getValue(stroke.getString("key")).isNull("value"))
                sync();assertEquals(1,marks.getOnce(bookId).size)
                assertEquals(25,app.database.bookDao().getBookById(bookId)!!.readingPage)
            } finally {marks.onLocalChanged=oldCallback}
        } finally {
            dao.getByWord(word.word)?.let{dao.delete(it,notifyCloud=false)}
            app.database.bookDao().deleteBook(app.database.bookDao().getBookById(bookId)!!)
            app.settingsDataStore.edit{it[PrefKeys.GOAL_MINUTES]=oldGoal}
        }
    }

    @Test fun highlightRecolorAndNoteDoNotOverwriteEachOther() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,com.lexiread.app.data.db.AppDatabase::class.java).build()
        try {
            val book=db.bookDao().insertBook(Book(title="Fixture",filePath="/test/highlight.txt",fileType=FileType.TXT))
            val dao=db.annotationDao()
            val original=Annotation(bookId=book,type=AnnotationType.HIGHLIGHT,color=0xffffd54f.toInt(),selectedText="Passage",contextBefore="Before",contextAfter="After")
            val id=dao.insert(original)
            assertEquals(id,dao.insert(original.copy(color=0xff4fc3f7.toInt())))
            assertEquals(1,dao.getOnce(book).size)
            dao.setColor(id,0xffaed581.toInt());dao.setNote(id,"A note")
            assertEquals(0xffaed581.toInt(),dao.getById(id)!!.color)
            assertEquals("A note",dao.getById(id)!!.noteText)
        } finally {db.close()}
    }

    @Test fun vocabularyTransactionsRejectStaleRemoteEdits() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,com.lexiread.app.data.db.AppDatabase::class.java).build()
        try {
            val dao=db.vocabularyDao();var edits=0;dao.onLocalChanged={_,_->edits++}
            dao.insert(VocabularyWord(word="Test",definition="Local"))
            val saved=dao.getOnce().single();assertEquals(1,edits)
            assertFalse(dao.applyCloudWord(listOf(saved),saved.copy(definition="Stale")){false})
            assertEquals("Local",dao.getOnce().single().definition)
            assertTrue(dao.applyCloudWord(listOf(saved),saved.copy(isLearned=true)){true})
            assertEquals(1,edits);assertTrue(dao.getOnce().single().isLearned)
            dao.setLearned(saved.id,false);assertEquals(2,edits)
            dao.delete(dao.getOnce().single());assertEquals(3,edits);assertTrue(dao.getOnce().isEmpty())
        } finally {db.close()}
    }
}

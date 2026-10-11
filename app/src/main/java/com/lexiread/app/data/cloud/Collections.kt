package com.lexiread.app.data.cloud

import com.lexiread.app.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hash work only occurs after a user action, never while scrolling the shelf. */
suspend fun CloudSync.addToCollection(id: String?, name: String, books: List<Book>): String = withContext(Dispatchers.IO) {
    val who = account.value?.optString("id").orEmpty()
    val existing = personal.collections(who)
    val target = id ?: java.util.UUID.randomUUID().toString()
    val title = if (id == null) name.trim() else existing.find { it.id == id }?.name ?: error("Коллекция удалена")
    require(title.length in 1..120) { "Название должно содержать от 1 до 120 символов" }
    val entries = personal.collectionEntries(who)
    val prefix = "member:$target:"
    var order = entries.keys().asSequence().filter { it.startsWith(prefix) }
        .mapNotNull { entries.optJSONObject(it)?.opt("value") as? Number }.maxOfOrNull { it.toLong() }?.plus(1) ?: 0
    val updates = linkedMapOf<String, Any>()
    if (id == null) updates["collection:$target"] = title
    val members = existing.find { it.id == target }?.members.orEmpty().toMutableSet()
    for (book in books.distinctBy { it.id }) {
        val sha = collectionHash(book)
        if (members.add(sha)) updates["member:$target:$sha"] = order++
    }
    check(order <= 1_000_000) { "Слишком много книг в коллекции" }
    check(account.value?.optString("id").orEmpty() == who) { "Аккаунт изменился" }
    personal.captureBatch(updates, who)
    target
}

suspend fun CloudSync.editCollection(id: String, name: String? = null, delete: Boolean = false,
    remove: List<String> = emptyList(), order: List<String>? = null) = withContext(Dispatchers.IO) {
    val who = account.value?.optString("id").orEmpty()
    val collection = personal.collections(who).find { it.id == id } ?: error("Коллекция удалена")
    val changes = linkedMapOf<String, Any>()
    if (delete) {
        changes["collection:$id"] = JSONObject.NULL
        collection.members.forEach { changes["member:$id:$it"] = JSONObject.NULL }
    } else {
        name?.let { require(it.trim().length in 1..120); changes["collection:$id"] = it.trim() }
        remove.filter { it in collection.members }.forEach { changes["member:$id:$it"] = JSONObject.NULL }
        order?.let {
            val remaining = collection.members.filter { sha -> sha !in remove }
            require(it.size == remaining.size && it.toSet() == remaining.toSet())
            it.forEachIndexed { index, sha -> changes["member:$id:$sha"] = index }
        }
    }
    personal.captureBatch(changes, who)
}

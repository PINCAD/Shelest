package com.shelest.shared

import kotlinx.serialization.json.*

data class BookCollection(val id: String, val name: String, val members: List<String>)

/** Collections only refer to content identities. No file bytes or device-local IDs. */
object CollectionRules {
    private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val collection = Regex("collection:" + uuid)
    private val member = Regex("member:(" + uuid + "):([0-9a-f]{64})")
    fun isKey(key: String) = collection.matches(key) || member.matches(key)
    fun validate(key: String, value: JsonElement): Boolean = when {
        collection.matches(key) -> value == JsonNull || (value is JsonPrimitive && value.isString &&
            value.content.trim().length in 1..120)
        member.matches(key) -> value == JsonNull || (value is JsonPrimitive && !value.isString &&
            value.longOrNull?.let { it in 0..1_000_000L } == true)
        else -> false
    }
    fun read(source: String): List<BookCollection> {
        val entries = Json.parseToJsonElement(source).jsonObject
        val members = mutableMapOf<String, MutableList<Pair<Long, String>>>()
        for ((key, entry) in entries) {
            val match = member.matchEntire(key) ?: continue
            val value = entry.jsonObject["value"] ?: continue
            if (!validate(key, value) || value == JsonNull) continue
            members.getOrPut(match.groupValues[1]) { mutableListOf() }
                .add(value.jsonPrimitive.long to match.groupValues[2])
        }
        return entries.mapNotNull { (key, entry) ->
            if (!collection.matches(key)) return@mapNotNull null
            val value = entry.jsonObject["value"] ?: return@mapNotNull null
            if (!validate(key, value) || value == JsonNull) return@mapNotNull null
            val id = key.removePrefix("collection:")
            BookCollection(id, value.jsonPrimitive.content.trim(),
                members[id].orEmpty().sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second }).map { it.second })
        }.sortedWith(compareBy<BookCollection> { it.name.lowercase() }.thenBy { it.id })
    }
    fun json(source: String): String = JsonArray(read(source).map {
        buildJsonObject { put("id", it.id); put("name", it.name); put("members", JsonArray(it.members.map(::JsonPrimitive))) }
    }).toString()
}

package com.shelest.shared
import kotlinx.serialization.json.*

/** Stable identity across devices, independent of local database IDs. */
object PersonalSyncRules {
    fun wordKey(word: String): String = "word:" + word.trim().lowercase().take(200)
    fun response(source: String): String {
        val root = Json.parseToJsonElement(source).jsonObject
        fun positive(value: JsonElement?, zero: Boolean = false): Long {
            val p = value as? JsonPrimitive ?: error("Invalid personal timestamp")
            val n = p.longOrNull ?: error("Invalid personal timestamp")
            require(!p.isString && n >= (if (zero) 0L else 1L) && n < 9_007_199_254_740_992L)
            return n
        }
        require(root["accountId"]?.jsonPrimitive?.isString == true)
        positive(root["cursor"], true)
        require(root["hasMore"]?.jsonPrimitive?.booleanOrNull != null && root["hasMore"]?.jsonPrimitive?.isString == false)
        val delta = root["entries"] as? JsonArray ?: error("Invalid personal entries")
        val ack = root["ack"] as? JsonArray ?: JsonArray(emptyList())
        require(delta.size <= 100 && ack.size <= 100)
        for (entries in listOf(delta, ack)) {
        val seen = mutableSetOf<String>()
        for (item in entries) {
            val entry = item.jsonObject; val key = entry.getValue("key").jsonPrimitive.content
            require(key.length <= 256 && seen.add(key)); positive(entry["changedAt"])
            val value = entry.getValue("value")
            when {
                CollectionRules.isKey(key) -> require(CollectionRules.validate(key, value))
                key == "goal" -> { val n = positive(value,true); require(n <= 240) }
                Regex("title:[0-9a-f]{64}").matches(key) -> require(value.jsonPrimitive.isString && value.jsonPrimitive.content.trim().length in 1..500)
                Regex("mark:[0-9a-f]{64}:[0-9a-f-]{36}").matches(key) -> if (value != JsonNull) {
                    val mark = value.jsonObject
                    require(mark.keys == setOf("type","color","createdAt","selectedText","contextBefore","contextAfter","noteText","pageIndex","normX","normY","points","width"))
                    val type = mark.getValue("type").jsonPrimitive.content
                    require(type in setOf("NOTE","HIGHLIGHT","DRAWING"))
                    require(positive(mark["color"],true) <= 0xffffffffL); positive(mark["createdAt"])
                    for (field in listOf("selectedText","contextBefore","contextAfter","noteText")) {
                        val p = mark.getValue(field)
                        require(p == JsonNull || (p.jsonPrimitive.isString && p.jsonPrimitive.content.length <= 20000))
                    }
                    if (mark["pageIndex"] != JsonNull) require(positive(mark["pageIndex"],true) <= 1000000)
                    fun number(p: JsonElement?, low: Double, high: Double) {
                        val v = p!!.jsonPrimitive
                        val n = v.doubleOrNull ?: error("Invalid mark coordinate")
                        require(!v.isString && n.isFinite() && n in low..high)
                    }
                    for (field in listOf("normX","normY")) if (mark[field] != JsonNull) number(mark[field],0.0,1.0)
                    number(mark["width"],0.1,40.0)
                    val points = mark.getValue("points").jsonArray
                    require(points.size <= 10000)
                    for (point in points) { require(point.jsonObject.keys == setOf("x","y")); number(point.jsonObject["x"],0.0,1.0); number(point.jsonObject["y"],0.0,1.0) }
                    if (type == "DRAWING") require(points.isNotEmpty() && mark["pageIndex"] != JsonNull)
                }
                key.startsWith("word:") && key.substring(5).length in 1..200 -> if (value != JsonNull) {
                    val word = value.jsonObject
                    for ((field, limit) in listOf("word" to 200,"definition" to 12000,"bookTitle" to 500)) {
                        val p = word.getValue(field).jsonPrimitive; require(p.isString && p.content.length <= limit)
                    }
                    require(wordKey(word.getValue("word").jsonPrimitive.content) == key)
                    positive(word["addedAt"])
                    require(word["learned"]?.jsonPrimitive?.booleanOrNull != null && word["learned"]?.jsonPrimitive?.isString == false)
                }
                else -> error("Invalid personal key")
            }
        }
        }
        return root.toString()
    }
}

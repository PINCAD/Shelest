package com.shelest.shared

import kotlin.test.*
import kotlinx.serialization.json.*

class CollectionRulesTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun source(vararg values: Pair<String, JsonElement>) = buildJsonObject {
        values.forEach { (key, value) -> put(key, buildJsonObject { put("value", value) }) }
    }.toString()

    @Test fun validatesNamesIdentitiesAndNumericOrder() {
        assertTrue(CollectionRules.validate("collection:$id", JsonPrimitive(" Серия ")))
        assertFalse(CollectionRules.validate("collection:$id", JsonPrimitive(" ")))
        assertFalse(CollectionRules.validate("collection:$id", JsonPrimitive("x".repeat(121))))
        assertFalse(CollectionRules.isKey("collection:../foreign"))
        assertTrue(CollectionRules.validate("member:$id:$a", JsonPrimitive(0)))
        assertTrue(CollectionRules.validate("member:$id:$a", JsonPrimitive(1_000_000)))
        listOf(JsonPrimitive("1"), JsonPrimitive(true), JsonPrimitive(-1), JsonPrimitive(1.5)).forEach {
            assertFalse(CollectionRules.validate("member:$id:$a", it))
        }
        assertTrue(CollectionRules.validate("member:$id:$a", JsonNull))
    }

    @Test fun savedOrderAndStableTieBreakWorkOnBothPlatforms() {
        val group = CollectionRules.read(source("collection:$id" to JsonPrimitive(" Серия "),
            "member:$id:$a" to JsonPrimitive(20), "member:$id:$b" to JsonPrimitive(1))).single()
        assertEquals("Серия", group.name)
        assertEquals(listOf(b, a), group.members)
        assertEquals(listOf(a, b), CollectionRules.read(source("collection:$id" to JsonPrimitive("Серия"),
            "member:$id:$b" to JsonPrimitive(1), "member:$id:$a" to JsonPrimitive(1))).single().members)
    }

    @Test fun deletedParentCannotBeResurrectedByALateMemberAndBookCanBelongToManyGroups() {
        val other = "22345678-1234-1234-1234-123456789abc"
        val rows = source("collection:$id" to JsonNull, "member:$id:$a" to JsonPrimitive(4),
            "collection:$other" to JsonPrimitive("Другая"), "member:$other:$a" to JsonPrimitive(0),
            "member:$other:$b" to JsonNull)
        assertEquals(listOf(BookCollection(other, "Другая", listOf(a))), CollectionRules.read(rows))
    }

    @Test fun protocolRejectsEntireMalformedCollectionResponse() {
        fun reply(value: JsonElement) = buildJsonObject {
            put("accountId", "one"); put("cursor", 1); put("hasMore", false)
            put("entries", buildJsonArray { add(buildJsonObject { put("key", "member:$id:$a"); put("value", value); put("changedAt", 100) }) })
        }.toString()
        assertEquals(reply(JsonPrimitive(2)), PersonalSyncRules.response(reply(JsonPrimitive(2))))
        assertFails { PersonalSyncRules.response(reply(JsonPrimitive("2"))) }
    }
}

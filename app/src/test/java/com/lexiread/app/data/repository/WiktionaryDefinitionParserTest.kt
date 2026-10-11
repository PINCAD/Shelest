package com.lexiread.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WiktionaryDefinitionParserTest {
    @Test fun keepsOnlyRussianMeaningsAndDropsExamples() {
        val extract = """
            = Русский =
            === Морфологические и синтаксические свойства ===
            книга
            === Семантические свойства ===
            ==== Значение ====
            носитель информации в виде листов бумаги ◆ Пример употребления.
            крупное литературное произведение ◆ Другой пример.
            ==== Синонимы ====
            том
            = Болгарский =
            ==== Значение ====
            книга
        """.trimIndent()

        assertEquals(
            "1. носитель информации в виде листов бумаги\n\n2. крупное литературное произведение",
            WiktionaryDefinitionParser.parse(extract, "Русский")
        )
    }

    @Test fun rejectsOtherLanguageEvenWhenItHasMeaning() {
        val extract = "= Болгарский =\n==== Значение ====\nкнига"
        assertNull(WiktionaryDefinitionParser.parse(extract, "Русский"))
    }

    @Test fun limitsMeanings() {
        val extract = "= Русский =\n==== Значение ====\n" +
            (1..6).joinToString("\n") { "значение $it" }
        assertEquals(4, WiktionaryDefinitionParser.parse(extract, "Русский")?.split("\n\n")?.size)
    }
}

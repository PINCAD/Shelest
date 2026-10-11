package com.lexiread.app.ui

import com.shelest.ui.*
import org.junit.Assert.*
import org.junit.Test

class CommonUiRulesTest {
    private fun book(id: Int, opened: Boolean = true) = ShelfBook(id.toString(), "Книга $id", opened = opened)
    @Test fun smallShelfKeepsCompleteAllBooksSection() {
        val groups = shelfGroups(listOf(book(1),book(2)), "", "RECENT")
        assertEquals(2, groups.size); assertEquals("Все книги", groups.last().title)
        assertEquals(listOf("1","2"),groups.last().books.map { it.id })
    }
    @Test fun largeShelfRetainsAllBooksAndRecentShortcut() {
        val books = (1..8).map { book(it) }
        val groups = shelfGroups(books,"","RECENT")
        assertEquals(books.take(3),groups.first().books)
        assertEquals(books,groups.last().books)
        assertEquals(8,groups.flatMap { it.books }.map { it.id }.distinct().size)
    }
    @Test fun mixedShelfPreservesUnopenedBooksAndRemainingOrder() {
        val books = listOf(book(1,false),book(2),book(3,false),book(4),book(5),book(6))
        val groups = shelfGroups(books,"","RECENT")
        assertEquals(listOf("2","4","5"),groups.first().books.map { it.id })
        assertEquals(books,groups.last().books)
    }
    @Test fun syncAgeUsesElapsedTimeAndHandlesMissingOrFutureTimestamp() {
        assertEquals("Ещё не синхронизировано", syncAgeLabel(0, 100000))
        assertEquals("Синхронизация: только что", syncAgeLabel(100000, 90000))
        assertEquals("Синхронизация: 2 мин назад", syncAgeLabel(100000, 220000))
        assertEquals("Синхронизация: 2 ч назад", syncAgeLabel(100000, 7300000))
    }
    @Test fun searchAndExplicitSortingNeverGroup() {
        assertEquals(1,shelfGroups((1..8).map { book(it) },"Книга","RECENT").size)
        assertEquals("all",shelfGroups((1..8).map { book(it) },"","TITLE").single().id)
    }
    @Test fun unopenedBooksDoNotBecomeRecent() {
        assertEquals("all",shelfGroups(listOf(book(1,false)),"","RECENT").single().id)
    }
    @Test fun fractionalStepperDoesNotDriftAndClamps() {
        val number=UiNumber("zoom","Масштаб",1.37,1.0,4.0,.1)
        assertEquals(1.5,stepped(number,1),.000001)
        assertEquals(1.0,stepped(number.copy(value=1.0),-1),.000001)
        assertEquals(4.0,stepped(number.copy(value=4.0),1),.000001)
    }
    @Test fun catalogRetainsSavedAndroidIds() {
        assertEquals(listOf("SYSTEM","LIGHT","DARK"),InterfaceChoices.themes.map { it.id })
        assertEquals(listOf("DEFAULT","BLUE","GREEN","VIOLET","AMBER","TEAL"),InterfaceChoices.accents(false).map { it.id })
        assertEquals(InterfaceChoices.accents(false).map { it.id },InterfaceChoices.accents(true).map { it.id })
    }
}

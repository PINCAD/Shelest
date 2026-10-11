package com.lexiread.app.domain.discover

import com.lexiread.app.data.api.*
import com.lexiread.app.data.model.*
import com.lexiread.app.data.repository.BookDiscoveryRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryLatencyTest {
    @Test fun slowCatalogueDoesNotBlockPartialResultsAndReopeningUsesCache() = runBlocking {
        var calls = 0
        val open = object : OpenLibraryApiService {
            override suspend fun search(url: String): OpenLibrarySearchResponse {
                calls++
                return OpenLibrarySearchResponse(docs = listOf(
                    OpenLibraryDoc(key="/works/test1", title="Новая история", authorName=listOf("Тестовый Автор"), coverId=1),
                    OpenLibraryDoc(key="/works/test2", title="Другая история", authorName=listOf("Тестовый Автор"), coverId=2)
                ))
            }
        }
        val wiki = object : WikidataApiService {
            override suspend fun searchEntities(url: String): WikidataSearchResponse { delay(30_000); return WikidataSearchResponse() }
            override suspend fun sparql(url: String): SparqlResponse { delay(30_000); return SparqlResponse() }
        }
        val google = object : GoogleBooksApiService {
            override suspend fun search(url: String): GoogleBooksResponse { delay(30_000); return GoogleBooksResponse() }
        }
        val repo = BookDiscoveryRepository(open, wiki, google)
        val liked = listOf(LikedItem("test", "Исходная история", "Тестовый Автор", onShelf=false))
        val began = System.nanoTime()
        var partialAt = 0L
        val result = withTimeout(10_000) { repo.discover(liked, emptyList()) {
            if (partialAt == 0L) partialAt = System.nanoTime()
        } }
        assertTrue(result.suggestions.isNotEmpty())
        assertTrue("Fast answers are shown before the slow source timeout", partialAt > began && partialAt-began < 4_000_000_000L)
        val count = calls
        assertEquals(result, repo.discover(liked, emptyList()))
        assertEquals("Cached opening does not repeat catalogue queries", count, calls)
    }
}

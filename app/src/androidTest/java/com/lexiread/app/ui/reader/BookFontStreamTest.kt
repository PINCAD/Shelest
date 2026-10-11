package com.lexiread.app.ui.reader

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Only packaged assets: no books, accounts or user preferences are accessed. */
class BookFontStreamTest {
    @Test fun fontStreamsAreFreshAndKeepTheirMimeType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = BookAssetServer(context)
        repeat(3) {
            val response = server.intercept("${BookAssetServer.ORIGIN}/font/PTSerif-Regular.ttf")!!
            assertEquals(200, response.statusCode)
            assertEquals("font/ttf", response.mimeType)
            response.data.use { actual ->
                context.assets.open("fonts/PTSerif-Regular.ttf").use { expected ->
                    val buffer = ByteArray(4096)
                    var length = 0
                    while (true) {
                        val count = expected.read(buffer)
                        if (count < 0) break
                        for (index in 0 until count) assertEquals(buffer[index].toInt() and 255, actual.read())
                        length += count
                    }
                    assertTrue(length > 1000)
                    assertEquals(-1, actual.read())
                }
            }
        }
        val missing = server.intercept("${BookAssetServer.ORIGIN}/font/missing.ttf")!!
        assertEquals(404, missing.statusCode)
        missing.data.close()
        val traversal = server.intercept("${BookAssetServer.ORIGIN}/font/../secret.ttf")!!
        assertEquals(404, traversal.statusCode)
        traversal.data.close()
    }
}

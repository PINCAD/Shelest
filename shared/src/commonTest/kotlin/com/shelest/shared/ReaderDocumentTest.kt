package com.shelest.shared

import com.shelest.shared.reader.ReaderDocumentStyle
import com.shelest.shared.reader.buildReaderHtml
import kotlin.test.*

class ReaderDocumentTest {
    private val style = ReaderDocumentStyle("Georgia, serif", "Georgia", 18, "1.85", "24",
        "#ffffff", "#292521", "#A8493D", "rgba(168,73,61,.21)", "justify", "slide", "{}")

    @Test fun pagedDocumentKeepsViewportSizedFlowAndIndependentAnimationFrame() {
        val html = buildReaderHtml("<p>Книга</p>", style)
        assertContains(html, "column-count:1; column-width:auto")
        assertContains(html, "#mask::after")
        assertContains(html, "new ResizeObserver(scheduleViewportLayout)")
        assertContains(html, "<div id=\"page-frame\"><div id=\"mask\"><div id=\"container\"><p>Книга</p>")
        assertContains(html, "window.captureReadingPosition")
        assertContains(html, "window.applyAnnotations")
        assertContains(html, "window.applyStyle")
    }

    @Test fun scrollingDocumentRetainsItsOwnPositionAndAutoscrollFunctions() {
        val html = buildReaderHtml("<p>Книга</p>", style, isHorizontal = false)
        assertContains(html, "window.setAutoScroll")
        assertContains(html, "window.scrollY")
        assertFalse(html.contains("column-count:1; column-width:auto"))
    }

    @Test fun positionJsonIsDataAndCannotCloseTheEngineScript() {
        val html = buildReaderHtml("<p>Книга</p>", style,
            position = "{\"anchor\":\"</script><script>alert(1)</script>\"}")
        assertFalse(html.contains("\"anchor\":\"</script>"))
        assertContains(html, "\\u003c/script>")
        assertEquals(1, Regex("<script>").findAll(html).count())
    }

    @Test fun platformBridgeAndSegmentAreExplicitInputsNotPlatformDependencies() {
        val html = buildReaderHtml("<p>Книга</p>", style, hasPrevious = true, hasNext = false,
            segmentIndex = 17, bridgeJs = "window.Shelest = testBridge;")
        assertContains(html, "var positionSegment = 17")
        assertContains(html, "var canOpenPrevious = true")
        assertContains(html, "var canOpenNext = false")
        assertTrue(html.indexOf("window.Shelest = testBridge") < html.indexOf("var initialReadingPosition"))
    }

    @Test fun onlyUnknownImageDimensionsDelayInitialPagination() {
        assertContains(buildReaderHtml("<img src=\"a\" width=\"100\" height=\"100\">", style), "var waitForImages = false")
        assertContains(buildReaderHtml("<img src=\"a\">", style), "var waitForImages = true")
    }
}

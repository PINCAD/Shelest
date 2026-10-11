package com.lexiread.app.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.MainActivity
import com.lexiread.app.domain.parser.PdfParser
import com.lexiread.app.domain.pdf.PdfTextLayer
import com.lexiread.app.ui.settings.ReadingMode
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfImprovementsTest {
    @Test fun twoFingerPanWorksWhilePenIsActiveAndDoesNotCreateAStroke() {
        val file = document(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drawings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent {
                PdfReader(filePath = file.path, pageCount = 1, savedPage = 0, annotations = emptyList(),
                    mode = PdfAnnotationMode.DRAW, activeColor = 0xff315cb8.toInt(), penWidthDp = 3f,
                    readingMode = ReadingMode.LIGHT, zoom = 1f, onZoomChange = {}, verticalPaging = false,
                    jumpTarget = null, onJumpConsumed = {}, onToggle = {}, onPageChange = { _, _ -> },
                    onNotePlaced = { _, _, _ -> }, onDrawingComplete = { _, path, _ -> drawings.add(path) },
                    onDrawingErased = {}, onDrawingPartsErased = {}, onAnnotationClick = {})
            } }
            instrumentation.waitForIdleSync(); Thread.sleep(1200)
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val x = screen.width * .45f; val y = screen.height * .5f
            screen.recycle()
            val properties = arrayOf(
                android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
                android.view.MotionEvent.PointerProperties().apply { id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
            )
            fun coords(y1: Float, y2: Float = y1, spread: Float = 110f) = arrayOf(
                android.view.MotionEvent.PointerCoords().also { it.x = x - spread / 2; it.y = y1; it.pressure = 1f; it.size = 1f },
                android.view.MotionEvent.PointerCoords().also { it.x = x + spread / 2; it.y = y2; it.pressure = 1f; it.size = 1f }
            )
            val down = android.os.SystemClock.uptimeMillis()
            fun send(action: Int, count: Int, y1: Float, y2: Float = y1, spread: Float = 110f) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                    count, properties, coords(y1, y2, spread), 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
            }
            send(android.view.MotionEvent.ACTION_DOWN, 1, y)
            send(android.view.MotionEvent.ACTION_MOVE, 1, y - 70)
            send(android.view.MotionEvent.ACTION_UP, 1, y - 70)
            assertEquals("A one-finger stroke still draws with the pen active", 1, drawings.size)

            val beforePan = instrumentation.uiAutomation.takeScreenshot()
            send(android.view.MotionEvent.ACTION_DOWN, 1, y)
            send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, y, y + 110)
            repeat(8) { step ->
                Thread.sleep(25)
                send(android.view.MotionEvent.ACTION_MOVE, 2, y - (step + 1) * 8f, y + 110 - (step + 1) * 8f, 110f + (step + 1) * 14f)
            }
            send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, y - 64, y + 46, 222f)
            send(android.view.MotionEvent.ACTION_UP, 1, y - 64)
            instrumentation.waitForIdleSync(); Thread.sleep(200)
            val afterPan = instrumentation.uiAutomation.takeScreenshot()
            assertEquals("Two-finger navigation must not append a pen stroke", 1, drawings.size)
            var changed = 0
            for (py in 0 until minOf(beforePan.height, afterPan.height) step 10) {
                for (px in 0 until minOf(beforePan.width, afterPan.width) step 10) {
                    if (beforePan.getPixel(px, py) != afterPan.getPixel(px, py)) changed++
                }
            }
            assertTrue("The page moves under two fingers while the pen remains selected", changed > 100)
            beforePan.recycle(); afterPan.recycle()
        } finally { scenario.close(); file.delete() }
    }

    @Test fun longPressPdfWordOffersDefinitionOnThePage() {
        val file = document(1)
        val layer = PdfTextLayer.open(file)!!
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val received = java.util.concurrent.atomic.AtomicReference<String>()
        val speech = java.util.concurrent.atomic.AtomicReference<String>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        fun waitNode(label: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
                Thread.sleep(100)
            }
            error("Missing PDF selection control: $label")
        }
        try {
            scenario.onActivity { activity -> activity.setContent {
                PdfReader(filePath = file.path, pageCount = 1, annotations = emptyList(), mode = PdfAnnotationMode.NONE,
                    activeColor = 0, penWidthDp = 2f, readingMode = ReadingMode.LIGHT, zoom = 1f,
                    onZoomChange = {}, verticalPaging = false, jumpTarget = null, onJumpConsumed = {}, onToggle = {},
                    onPageChange = { _, _ -> }, onNotePlaced = { _, _, _ -> }, onDrawingComplete = { _, _, _ -> },
                    onDrawingErased = {}, onDrawingPartsErased = {}, onAnnotationClick = {}, textLayer = layer,
                    onWordSelected = { received.set(it) }, onSpeakRequested = { speech.set(it) })
            } }
            val bounds = android.graphics.Rect().also { waitNode("Страница 1").getBoundsInScreen(it) }
            val word = layer.pageWords(0).first { it.text == "extracted" }
            Thread.sleep(400)
            val x = bounds.left + bounds.width() * (word.left + word.right) / 2
            // Accessibility reports only the visible part of a tall image on a
            // landscape tablet. The known LETTER fixture still has its full aspect
            // ratio, and the page opens at its top; don't use the clipped height.
            val imageHeight = bounds.width() * PDPage().mediaBox.height / PDPage().mediaBox.width
            val y = bounds.top + imageHeight * (word.top + word.bottom) / 2
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN); Thread.sleep(700); touch(android.view.MotionEvent.ACTION_UP)
            var define = waitNode("Определить")
            while (!define.isClickable && define.parent != null) define = define.parent
            assertTrue(define.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals("extracted", received.get())
            var read = waitNode("Читать отсюда")
            while (!read.isClickable && read.parent != null) read = read.parent
            assertTrue(read.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            val context = org.json.JSONObject(speech.get())
            assertTrue(context.getString("text").startsWith("extracted words"))
            assertTrue(context.getString("before").contains("Page 1:"))
        } finally { scenario.close(); layer.close(); file.delete() }
    }
    private fun document(pages: Int = 3): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("pdf-improvement-test", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(pages) { index ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.setNonStrokingColor(70 + (index % 3) * 50, 100, 190)
                    stream.addRect(40f, 80f, 200f, 600f); stream.fill()
                    if (index != 2) {
                        stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 20f)
                        stream.newLineAtOffset(45f, 740f)
                        stream.showText("Page ${index + 1}: extracted words for testing.")
                        stream.endText()
                    }
                }
            }
            document.save(file)
        }
        return file
    }

    @Test fun textExtractionAndBoundedRendererReuse() {
        val file = document()
        try {
            PdfTextLayer.open(file)!!.use { layer ->
                assertEquals(3, layer.pageCount)
                assertTrue(layer.pageText(0).contains("Page 1: extracted words"))
                assertTrue(layer.pageText(1).contains("Page 2: extracted words"))
                assertEquals("", layer.pageText(2)) // Scan/image-only page is not fake OCR.
                val words = layer.pageWords(0)
                val extracted = words.first { it.text == "extracted" }
                assertTrue(extracted.left >= 0 && extracted.right <= 1 && extracted.top >= 0 && extracted.bottom <= 1)
                val hit = com.lexiread.app.domain.pdf.pdfWordAt(words, (extracted.left + extracted.right) / 2,
                    (extracted.top + extracted.bottom) / 2)
                assertNotNull(hit)
                assertEquals("extracted", com.lexiread.app.domain.pdf.pdfSelectionText(words, hit!!, hit))
                assertTrue(layer.pageWords(2).isEmpty())
            }
            PdfParser().use { parser ->
                val first: Bitmap = parser.renderPage(file, 0, 500)
                assertSame(first, parser.renderPage(file, 0, 500))
                assertNotSame(first, parser.renderPage(file, 1, 500))
                assertSame(first, parser.renderPage(file, 0, 500))
            }
        } finally { file.delete() }
    }

    @Test fun oneFingerMovesMagnifiedPageWithoutTurningIt() {
        val file = document()
        val current = java.util.concurrent.atomic.AtomicInteger(-1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent {
                PdfReader(filePath = file.path, pageCount = 3, savedProgress = 0f, savedPage = 0,
                    annotations = emptyList(), mode = PdfAnnotationMode.NONE,
                    activeColor = 0, penWidthDp = 2f, readingMode = ReadingMode.LIGHT,
                    zoom = 2f, onZoomChange = {}, verticalPaging = false,
                    jumpTarget = null, onJumpConsumed = {}, onToggle = {},
                    onPageChange = { page, _ -> current.set(page) },
                    onNotePlaced = { _, _, _ -> }, onDrawingComplete = { _, _, _ -> },
                    onDrawingErased = {}, onDrawingPartsErased = {}, onAnnotationClick = {})
            } }
            instrumentation.waitForIdleSync()
            Thread.sleep(1800)
            val before = instrumentation.uiAutomation.takeScreenshot()
            val x = before.width / 2f
            val y = before.height / 2f
            val down = android.os.SystemClock.uptimeMillis()
            fun send(action: Int, at: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, at, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            send(android.view.MotionEvent.ACTION_DOWN, x)
            repeat(20) { Thread.sleep(20); send(android.view.MotionEvent.ACTION_MOVE, x + (it + 1) * 6) }
            send(android.view.MotionEvent.ACTION_UP, x + 120)
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            assertEquals("A drag inside the magnified page must pan, not turn", 0, current.get())
            val after = instrumentation.uiAutomation.takeScreenshot()
            var changed = 0
            for (y in 0 until minOf(before.height, after.height) step 8) {
                for (x in 0 until minOf(before.width, after.width) step 8) {
                    if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                }
            }
            assertTrue("The page actually moved horizontally", changed > 100)
            before.recycle(); after.recycle()
        } finally { scenario.close(); file.delete() }
    }

    @Test fun directJumpToPage254DoesNotRequire253Swipes() {
        val file = document(260)
        val current = java.util.concurrent.atomic.AtomicInteger(-1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val jump = androidx.compose.runtime.mutableStateOf<Int?>(null)
        try {
            scenario.onActivity { activity -> activity.setContent {
                PdfReader(filePath = file.path, pageCount = 260, savedPage = 0,
                    annotations = emptyList(), mode = PdfAnnotationMode.NONE,
                    activeColor = 0, penWidthDp = 2f, readingMode = ReadingMode.LIGHT,
                    zoom = 1f, onZoomChange = {}, verticalPaging = false,
                    jumpTarget = jump.value, onJumpConsumed = { jump.value = null }, onToggle = {},
                    onPageChange = { page, _ -> current.set(page) },
                    onNotePlaced = { _, _, _ -> }, onDrawingComplete = { _, _, _ -> },
                    onDrawingErased = {}, onDrawingPartsErased = {}, onAnnotationClick = {})
            } }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { jump.value = 253 }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (current.get() != 253 && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertEquals(253, current.get())
        } finally { scenario.close(); file.delete() }
    }
}

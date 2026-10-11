package com.lexiread.app.ui.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiread.app.MainActivity
import com.lexiread.app.data.model.ReadingPosition
import com.lexiread.app.ui.settings.PageTurnAnimation
import com.lexiread.app.ui.settings.ReaderFontFamily
import com.lexiread.app.ui.settings.ReaderSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual reader JavaScript in Android WebView, including a fresh DOM on reopen. */
@RunWith(AndroidJUnit4::class)
class ReadingPositionTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var webView: WebView
    private lateinit var server: BookAssetServer
    private var sink = PositionSink()
    private val style = ReaderSettings(fontFamily = ReaderFontFamily.MONO, pageAnimation = PageTurnAnimation.NONE)
    private val book = buildString {
        append("<h1>Тест восстановления</h1>")
        repeat(45) { paragraph ->
            append("\n<p>Абзац $paragraph. ")
            repeat(35) { append("Длинный абзац переходит через границы страниц, чтобы проверить точное место чтения. ") }
            append("</p>\n")
            if (paragraph == 1) append("<div id='empty-page' style='break-before:column;break-after:column;height:100%;background:#ddd'></div>")
        }
    }

    @Before fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { server = BookAssetServer(it) }
    }

    @After fun tearDown() {
        scenario.onActivity { if (::webView.isInitialized) webView.destroy() }
        server.release()
        scenario.close()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(settings: ReaderSettings = style, saved: ReadingPosition? = null, percent: Float = 0f, body: String = book, zeroSized: Boolean = false) {
        sink = PositionSink()
        val document = buildPagedHtml(body, settings, percent, savedPosition = saved, segmentIndex = 3)
            .replace("var exactScroll = initialReadingPosition && initialReadingPosition.layout === positionLayout();",
                "window.restoreLayoutAtStart = positionLayout(); var exactScroll = initialReadingPosition && initialReadingPosition.layout === positionLayout();")
        scenario.onActivity { activity ->
            if (::webView.isInitialized) webView.destroy()
            webView = WebView(activity).apply {
                this.settings.javaScriptEnabled = true
                addJavascriptInterface(sink, "Shelest")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        server.intercept(request?.url.toString())
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("if(window.startPaging)startPaging();", null)
                    }
                }
            }
            activity.setContentView(FrameLayout(activity).apply {
                addView(webView, FrameLayout.LayoutParams(if (zeroSized) 0 else -1, if (zeroSized) 0 else -1))
            })
            if (zeroSized) {
                val loadingView = webView
                loadingView.postDelayed({ loadingView.layoutParams = FrameLayout.LayoutParams(-1, -1) }, 350)
            }
            webView.loadUrl(server.publish(document.toByteArray(), null))
        }
        assertTrue("Reader did not finish restoring its position", sink.ready.await(20, TimeUnit.SECONDS))
        val readyBy = android.os.SystemClock.uptimeMillis() + 20_000
        while (evaluate("Boolean((typeof paged !== 'undefined' && paged) || (typeof placed !== 'undefined' && placed))") != "true") {
            assertTrue("DOM did not finish initial placement", android.os.SystemClock.uptimeMillis() < readyBy)
            Thread.sleep(50)
        }
    }

    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("")
        scenario.onActivity {
            webView.evaluateJavascript(script) { value -> result.set(value); latch.countDown() }
        }
        assertTrue("JavaScript did not answer", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun fractionalColumnsStayAlignedOnDistantPages() {
        load(body = buildString { repeat(350) { append("<p>" + "Проверка точного перелистывания без соседней страницы. ".repeat(40) + "</p>") } })
        evaluate("document.getElementById('page-frame').style.width='360.333px';measurePages();goToPage(500,false);")
        val raw = evaluate("""JSON.stringify((function(){
            var mask=document.getElementById('mask'),c=document.getElementById('container'),m=parseFloat(getComputedStyle(c).paddingLeft),errors=[];
            c.querySelectorAll('p').forEach(function(p){Array.from(p.getClientRects()).forEach(function(r){
                if(r.right>1 && r.left<mask.getBoundingClientRect().width-1 && Math.abs(r.left-m)>2)errors.push(r.left);
            });});return {page:currentPage,pages:totalPages,width:pageWidth,viewport:mask.getBoundingClientRect().width,offset:mask.scrollLeft,margin:m,errors:errors};})())""")
        val result = org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
        assertTrue("Large fixture has enough pages", result.getInt("pages") > 500)
        assertEquals(500, result.getInt("page"))
        assertEquals("Columns must not drift on real Android WebView: $result", 0, result.getJSONArray("errors").length())
    }

    @Test fun speechSelectionUsesActualRangeNotFirstMatchingWord() {
        load(body = "<p>Первый шелест деревьев.</p><p>Второй шелест страниц слышен рядом.</p>")
        val raw = evaluate("""JSON.stringify((function(){
            var node=container.querySelectorAll('p')[1].firstChild;
            var at=node.nodeValue.indexOf('шелест'),r=document.createRange();
            r.setStart(node,at);r.setEnd(node,at+6);
            var s=window.getSelection();s.removeAllRanges();s.addRange(r);
            return getSpeechContext(true);})())""")
        val result = org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
        assertTrue(result.getString("text").startsWith("шелест страниц"))
        assertTrue(result.getString("before").contains("Второй"))
        val index = com.lexiread.app.domain.tts.SpeechTextIndex(listOf("Первый шелест деревьев.", "Второй шелест страниц слышен рядом."))
        val place = index.locate(result.getString("text"), result.getString("before"))!!
        assertEquals(1, place.sentence)
        assertEquals(7, place.offset)
    }

    @Test fun pageSurvivesImmediateExitAndRepeatedReopening() {
        load()
        val total = evaluate("totalPages").toInt()
        assertTrue(total > 12)
        val emptyPage = evaluate("Math.floor((document.getElementById('empty-page').getBoundingClientRect().left+mask.scrollLeft+2)/pageWidth)").toInt()
        for (page in listOf(1, 2, 3, 7, 11, emptyPage, total - 1).distinct()) {
            evaluate("jumpToPage($page);true")
            val saved = sink.position.get()!!.copy(character = null)
            assertEquals("Page must be saved before delayed character lookup", page, saved.page)
            assertEquals(3, saved.segment)
            // An intentionally incorrect percentage must never win over the exact page.
            repeat(2) {
                load(saved = saved, percent = 87f)
                assertEquals("Reopen drifted from page $page", page, evaluate("currentPage").toInt())
            }
        }
    }

    @Test fun changedFontRestoresExactCharacterInsteadOfPercentage() {
        load()
        evaluate("jumpToPage(9);reportPlace();true")
        val saved = sink.position.get()!!
        assertNotNull(saved.character)
        load(settings = style.copy(fontSize = 25f), saved = saved, percent = 99f)
        val expected = evaluate("Math.min(totalPages-1,pageOfChar(${saved.character}))").toInt()
        assertEquals(expected, evaluate("currentPage").toInt())
    }

    @Test fun cloudAnchorBelongsToTheVisiblePageAcrossParagraphWhitespace() {
        // A deliberately blank column has no text anchor. Its screen page is tested
        // separately; this test exercises only pages containing paragraph text.
        load(body = book.replace(Regex("<div id='empty-page'[^>]*></div>"), ""))
        for (page in listOf(3, 7, 11, 18)) {
            evaluate("jumpToPage($page);true")
            assertEquals("Cloud character must point at the page actually being read: " +
                evaluate("JSON.stringify({anchor:currentChar(),starts:charIndexOf().blocks.slice(0,8).map(function(b){return [b.start,b.len,pageOfChar(b.start)]})})"), page,
                evaluate("pageOfChar(currentChar())").toInt())
        }
    }

    @Test fun blankPageRestoresScreenPageEvenWhenAnchorPointsAtNextText() {
        load()
        val blank = evaluate("Math.floor((document.getElementById('empty-page').getBoundingClientRect().left+mask.scrollLeft+2)/pageWidth)").toInt()
        evaluate("jumpToPage($blank);reportPlace();true")
        val saved = sink.position.get()!!
        assertEquals(blank, saved.page)
        load(saved = saved, percent = 99f)
        assertEquals(blank, evaluate("currentPage").toInt())
    }

    @Test fun exitDuringAnimationCapturesDestinationPageAndExactCharacter() {
        load(settings = style.copy(pageAnimation = PageTurnAnimation.SLIDE))
        val raw = evaluate("jumpToPage(5);JSON.stringify(captureReadingPosition())")
        val json = org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
        assertEquals(5, json.getInt("page"))
        val saved = ReadingPosition(json.getInt("segment"), json.getInt("page"),
            json.getInt("character"), json.getString("layout"), json.getDouble("scrollY"))
        load(settings = style.copy(pageAnimation = PageTurnAnimation.SLIDE), saved = saved, percent = 87f)
        assertEquals(5, evaluate("currentPage").toInt())
    }

    @Test fun verticalReaderRestoresExactScrollOffset() {
        val vertical = style.copy(horizontalScrolling = false)
        load(settings = vertical)
        evaluate("window.scrollTo(0,1234);updateScrollPage();true")
        val saved = sink.position.get()!!.copy(character = null)
        assertEquals(1234.0, saved.scrollY, 1.0)
        load(settings = vertical, saved = saved, percent = 87f)
        val reopenedLayout = org.json.JSONTokener(evaluate("positionLayout()")).nextValue() as String
        assertEquals("Layout changed while reopening the same scroll reader", saved.layout, reopenedLayout)
        val diagnosis = evaluate("JSON.stringify({initialLayout:window.restoreLayoutAtStart,finalLayout:positionLayout(),saved:initialReadingPosition})")
        assertEquals("Restore diagnostic: $diagnosis", saved.scrollY, evaluate("window.scrollY").toDouble(), 1.0)
    }

    @Test fun zeroSizedReaderWaitsBeforeRestoringExactPosition() {
        load()
        evaluate("goToPage(7,false);true")
        val page = sink.position.get()!!.copy(character = null)
        load(saved = page, percent = 87f, zeroSized = true)
        assertEquals(7, evaluate("currentPage").toInt())
        val vertical = style.copy(horizontalScrolling = false)
        load(settings = vertical)
        evaluate("window.scrollTo(0,1234);updateScrollPage();true")
        val scroll = sink.position.get()!!.copy(character = null)
        load(settings = vertical, saved = scroll, percent = 87f, zeroSized = true)
        assertEquals(1234.0, evaluate("window.scrollY").toDouble(), 1.0)
        assertFalse(evaluate("window.restoreLayoutAtStart").contains("false,0,0,"))
    }

    class PositionSink {
        val position = AtomicReference<ReadingPosition?>()
        val ready = CountDownLatch(1)
        @JavascriptInterface fun onPositionChange(segment: Int, page: Int, character: Int, layout: String, y: Double, anchor: String) {
            position.set(ReadingPosition(segment, page, character.takeIf { it >= 0 }, layout, y))
        }
        @JavascriptInterface fun onPageChange(page: Int, total: Int, percent: Double, segment: Int) {
            if (percent >= 0) ready.countDown()
        }
        @JavascriptInterface fun onTiming(label: String, ms: Double) = Unit
    }
}

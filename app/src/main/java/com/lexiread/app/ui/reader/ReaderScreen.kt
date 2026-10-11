package com.lexiread.app.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexiread.app.data.model.Annotation
import com.lexiread.app.data.model.AnnotationType
import com.lexiread.app.data.model.Book
import com.lexiread.app.domain.audio.AmbientSoundPlayer
import com.lexiread.app.domain.parser.PdfParser
import com.lexiread.app.domain.parser.BookSegment
import com.lexiread.app.domain.parser.DocumentCache
import com.lexiread.app.domain.parser.TocEntry
import com.lexiread.app.domain.parser.plainTextOf
import com.lexiread.app.domain.pdf.PdfOutlineItem
import com.lexiread.app.domain.pdf.PdfSearchHit
import com.lexiread.app.domain.pdf.PdfTextLayer
import com.lexiread.app.domain.tts.BookTextToSpeech
import com.lexiread.app.ui.definition.DefinitionBottomSheet
import com.lexiread.app.ui.settings.ReadingMode
import com.lexiread.app.ui.settings.ReaderFontFamily
import com.lexiread.app.ui.settings.ReaderSettings
import com.lexiread.app.ui.settings.SettingsViewModel
import com.lexiread.app.ui.theme.*
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class SearchResultItem(
    val pageIndex: Int,
    val snippet: String,
    /** Which occurrence of the query this is, counting from 0 — the reader jumps to
     *  exactly this one and highlights it, rather than to an estimated page. */
    val occurrence: Int,
    val segmentIndex: Int = -1,
    val localPercent: Float = 0f,
    val bookPercent: Int = 0
)

enum class PdfAnnotationMode { NONE, DRAW, NOTE, ERASE, ERASE_PART }

internal fun selectPdfAnnotationTool(current: PdfAnnotationMode, selected: PdfAnnotationMode): PdfAnnotationMode = when {
    selected == PdfAnnotationMode.ERASE && current == PdfAnnotationMode.ERASE_PART -> PdfAnnotationMode.NONE
    selected == PdfAnnotationMode.ERASE -> PdfAnnotationMode.ERASE_PART
    selected == current -> PdfAnnotationMode.NONE
    else -> selected
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    viewModel: ReaderViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    /**
     * Open with the voice already reading — the home-screen widget's second button.
     *
     * Acted on once, when the screen is first composed. The reader may well close the player
     * a minute later, and an argument that stays true would reopen it on the next rotation.
     */
    startListening: Boolean = false
) {
    val content      by viewModel.content.collectAsStateWithLifecycle()
    val defState     by viewModel.definition.collectAsStateWithLifecycle()
    val settings     by settingsViewModel.readerSettings.collectAsStateWithLifecycle()
    val settingsLoaded by settingsViewModel.readerSettingsLoaded.collectAsStateWithLifecycle()
    val pageState    by viewModel.pageState.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isBookmarked.collectAsStateWithLifecycle()
    val snackMessage by viewModel.snackMessage.collectAsStateWithLifecycle()
    val ambientState by viewModel.ambientState.collectAsStateWithLifecycle()
    val ttsState     by viewModel.ttsState.collectAsStateWithLifecycle()
    val annotations  by viewModel.annotations.collectAsStateWithLifecycle()
    val drawingHistory by viewModel.drawingHistory.collectAsStateWithLifecycle()
    val bookmarks    by viewModel.bookmarks.collectAsStateWithLifecycle()
    val savedWords   by viewModel.savedWords.collectAsStateWithLifecycle()

    var showChrome           by remember { mutableStateOf(true) }
    var showSearch           by remember { mutableStateOf(false) }
    var showToc              by remember { mutableStateOf(false) }
    var showNotes            by remember { mutableStateOf(false) }
    var showAmbientSheet     by remember { mutableStateOf(false) }
    var showReaderSettings   by remember { mutableStateOf(false) }
    var autoScrollSpeed      by remember { mutableStateOf(0) }
    var autoScrollSelectedSpeed by remember { mutableStateOf(1) }
    var autoScrollExpanded by remember { mutableStateOf(false) }
    var sleepTimerMinutes    by remember { mutableStateOf(0) }
    var pendingNoteSelection by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var pendingHighlightSelection by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var editingAnnotation    by remember { mutableStateOf<com.lexiread.app.data.model.Annotation?>(null) }
    var pdfMode              by remember { mutableStateOf(PdfAnnotationMode.NONE) }
    var pdfToolsExpanded     by remember { mutableStateOf(false) }
    var pdfOptionsExpanded   by remember { mutableStateOf(false) }
    var pdfActiveColor       by remember { mutableStateOf(Color(0xFF315CB8).toArgb()) }
    var pdfPenWidthDp        by remember { mutableFloatStateOf(2.5f) }
    var confirmClearPdfPage  by remember { mutableStateOf(false) }
    var pdfJumpTarget        by remember { mutableStateOf<Int?>(null) }
    var showPageJump by remember { mutableStateOf(false) }
    var pendingPdfNote       by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var pendingHtmlJs        by remember { mutableStateOf<String?>(null) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    var leavingReader by remember { mutableStateOf(false) }

    fun captureReadingPosition(after: () -> Unit = {}) {
        val reader = webViewRef
        if (reader == null || content !is ContentState.Html) { after(); return }
        reader.evaluateJavascript(
            "JSON.stringify(window.captureReadingPosition ? captureReadingPosition() : null)"
        ) { raw ->
            runCatching {
                val json = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching
                val p = org.json.JSONObject(json)
                viewModel.onReadingPositionChanged(com.lexiread.app.data.model.ReadingPosition(
                    p.getInt("segment"), p.getInt("page"), p.getInt("character").takeIf { it >= 0 },
                    p.getString("layout"), p.getDouble("scrollY"), p.optString("anchor")
                ))
            }
            after()
        }
    }

    val leaveReader: () -> Unit = {
        if (!leavingReader) {
            leavingReader = true
            captureReadingPosition {
                viewModel.saveProgress()
                onBack()
            }
        }
    }

    // A search result or TOC entry may live in another cached segment. Wait until that small
    // document has replaced the old one before asking its JavaScript to reveal the target.
    LaunchedEffect(content, pendingHtmlJs) {
        val call = pendingHtmlJs ?: return@LaunchedEffect
        if (content !is ContentState.Html) return@LaunchedEffect
        delay(80)
        webViewRef?.evaluateJavascript(
            "if(window.runWhenReaderReady){runWhenReaderReady(function(){ $call; });}" +
                "else{$call;}",
            null
        )
        pendingHtmlJs = null
    }

    val context = LocalContext.current
    val ambientPlayer = remember(context) {
        AmbientSoundPlayer(context, onFocusLost = { viewModel.disableAmbient() })
    }

    // What happens when the voice runs out of text.
    //
    // The end of a chapter is the end of the reading; the end of a PDF page is not, because a
    // page is all a PDF ever hands over at once. Kept in a box rather than captured directly:
    // the player is built once for the screen, and a lambda closed over the content at that
    // moment would still be looking at the first page of the first book opened.
    val onSpeechFinished = remember { arrayOf<() -> Unit>({ viewModel.setTtsPlaying(false) }) }

    val ttsPlayer = remember(context) {
        BookTextToSpeech(
            context = context,
            onSentenceStarted = { idx, text -> viewModel.updateTtsSentence(idx, text) },
            onCompleted = { onSpeechFinished[0]() },
            onErrorMessage = { message -> viewModel.setTtsPlaying(false); uiScope.launch { snackbarHostState.showSnackbar(message) } }
        )
    }

    // Arrived from the widget's "Слушать". Setting this before the book has finished loading is
    // deliberate and safe: the effect that hands the chapter to the voice is keyed on the
    // content, so it starts speaking the moment there is something to speak.
    LaunchedEffect(Unit) {
        if (startListening) viewModel.openTts()
    }

    // ── Read-aloud outside the reader ────────────────────────────────────────────
    //
    // Everything below exists so that listening survives the screen going off. A voice with
    // nothing but a thread behind it is, as far as the system is concerned, an app the user
    // has finished with: the process drops down the list and the chapter stops in the middle
    // with no explanation. TtsPlaybackService is the declaration that something is playing,
    // and it brings the lock-screen controls with it.

    val bookTitle = when (val c = content) {
        is ContentState.Html -> c.book.title
        is ContentState.Pdf  -> c.book.title
        else                 -> ""
    }

    // The buttons on the lock screen go through the same state the buttons in the bar do,
    // rather than straight to the engine. Anything else and the notification could be playing
    // while the bar showed a pause button.
    val ttsCommands = remember(ttsPlayer) {
        object : com.lexiread.app.domain.tts.TtsCommands {
            override fun play()     { viewModel.setTtsPlaying(true) }
            override fun pause()    { viewModel.setTtsPlaying(false) }
            override fun next()     { ttsPlayer.nextSentence() }
            override fun previous() { ttsPlayer.previousSentence() }
            override fun close()    { viewModel.closeTts() }
        }
    }

    // Android 13 put notifications behind a runtime permission, and a media notification is
    // still a notification. Refused, the reading works and only the controls are missing —
    // so this is asked for when the player opens and never insisted on.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Granted or not, the voice reads either way. */ }

    DisposableEffect(ttsState.isActive) {
        if (ttsState.isActive) {
            com.lexiread.app.domain.tts.TtsPlaybackLink.commands = ttsCommands
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            com.lexiread.app.domain.tts.TtsPlaybackService.start(context)
        } else {
            com.lexiread.app.domain.tts.TtsPlaybackLink.clear()
            com.lexiread.app.domain.tts.TtsPlaybackService.stop(context)
        }
        onDispose {
            // Leaving the reader takes the voice with it — the player is released a few lines
            // down — so the notification must not outlive the screen that owns it.
            com.lexiread.app.domain.tts.TtsPlaybackLink.clear()
            com.lexiread.app.domain.tts.TtsPlaybackService.stop(context)
        }
    }

    LaunchedEffect(bookTitle, ttsState.currentSentenceText, ttsState.isPlaying, ttsState.isActive) {
        if (ttsState.isActive) {
            com.lexiread.app.domain.tts.TtsPlaybackLink.update(
                bookTitle = bookTitle,
                sentence  = ttsState.currentSentenceText,
                isPlaying = ttsState.isPlaying
            )
        }
    }

    DisposableEffect(ambientState.isEnabled, ambientState.soundId, ambientState.customPath) {
        if (ambientState.isEnabled && !ambientState.soundId.isNullOrBlank()) {
            ambientPlayer.play(ambientState.soundId, ambientState.customPath, ambientState.volume)
        } else {
            ambientPlayer.pause()
        }
        onDispose {
            ambientPlayer.stop()
        }
    }

    LaunchedEffect(ambientState.volume) {
        ambientPlayer.setVolume(ambientState.volume)
    }

    // Pause ambient sound while the app is backgrounded, resume it on return —
    // otherwise it would keep playing indefinitely with no way to stop it.
    // Volume is intentionally excluded from the key list: dragging the volume
    // slider must not retrigger ON_START (which would restart playback from 0).
    val lifecycleOwner = LocalLifecycleOwner.current
    val captureLatestPosition by rememberUpdatedState({ captureReadingPosition() })
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) captureLatestPosition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val latestAmbientState by rememberUpdatedState(ambientState)
    DisposableEffect(lifecycleOwner, ambientState.isEnabled, ambientState.soundId, ambientState.customPath) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> ambientPlayer.pause()
                Lifecycle.Event.ON_START -> {
                    val state = latestAmbientState
                    if (state.isEnabled && !state.soundId.isNullOrBlank()) {
                        ambientPlayer.play(state.soundId, state.customPath, state.volume)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Splitting a chapter runs Jsoup over the whole thing and then a BreakIterator over the
    // result — tens of milliseconds on a long chapter, which is a dropped frame if it
    // happens where it used to, on the main thread the moment the play button is pressed.
    //
    // Preparing and starting live in one effect on purpose. They used to be two, and two
    // coroutines launched in the same frame have no order between them: opening the bar sets
    // active and playing together, so the play half could reach a player that had not been
    // given the chapter yet, find no sentences, and report the chapter finished — leaving the
    // button showing "paused" over a voice the other half then started anyway.
    val preparedText = remember { arrayOfNulls<String>(1) }

    /**
     * Which sentence the page currently in front of the reader begins with, or -1 when the
     * page cannot be matched to one — a page of pictures, a chapter still loading.
     */
    var speechRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val handledSpeechRequest = remember { arrayOf<Long?>(null) }
    suspend fun visibleSpeechContext(): String? {
        val wv = webViewRef ?: return null
        val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
            wv.evaluateJavascript("(window.getSpeechContext ? JSON.stringify(getSpeechContext(false)) : null)") { value ->
                if (cont.isActive) cont.resumeWith(Result.success(value))
            }
        }
        // evaluateJavascript hands back a JSON value, so the string arrives quoted and with
        // its escapes still in it.
        val text = runCatching { org.json.JSONTokener(raw ?: "").nextValue() as? String }
            .getOrNull() ?: return null
        return text
    }

    fun speakContext(raw: String?): Boolean {
        val place = runCatching { org.json.JSONObject(raw ?: "") }.getOrNull()
        val started = place != null && ttsPlayer.playFromContext(place.optString("text"), place.optString("before"))
        if (!started) {
            viewModel.setTtsPlaying(false)
            uiScope.launch { snackbarHostState.showSnackbar("Не удалось найти начало озвучки. Выделите слово в тексте и выберите «Читать отсюда».") }
        }
        return started
    }

    /** Starts the voice at [index], or from the top of the chapter if there is no such place. */
    fun startSpeakingAt(index: Int) {
        val target = index.coerceAtLeast(0)
        viewModel.updateTtsSentence(target, ttsPlayer.sentences.getOrNull(target)?.text.orEmpty())
        ttsPlayer.play(target)
        viewModel.setTtsPlaying(true)
    }

    LaunchedEffect(ttsState.isActive, content, ttsState.isPlaying, speechRequest) {
        if (!ttsState.isActive) {
            // Closing the bar clears isActive and isPlaying in the same state update, so a
            // guard on isPlaying alone would never see the pause. Left to that, dismissing
            // the player kept the voice reading to an empty screen.
            ttsPlayer.stop()
            return@LaunchedEffect
        }
        val html = (content as? ContentState.Html)?.html ?: return@LaunchedEffect
        val isNewText = preparedText[0] != html
        var startAt = ttsState.currentSentenceIndex
        if (isNewText) {
            ttsPlayer.prepareText(html)
            preparedText[0] = html
            // Where to begin. Not sentence zero: the reader is on page forty, and a voice
            // that answers the play button by starting the chapter again is a voice you have
            // to hunt through. The page in front of them is the obvious place, and the two
            // buttons in the bar are there for when it is not.
            //
            // A new chapter also invalidates the old number outright — it counted into a
            // different list of sentences. Reopening the bar on the text it was already
            // reading is the opposite case: there the number still means what it did, and
            // continuing from it is the whole point of the button.
        }
        if (ttsState.isPlaying) {
            if (speechRequest?.first != handledSpeechRequest[0] || isNewText) {
                val request = if (speechRequest?.first != handledSpeechRequest[0]) speechRequest?.second else visibleSpeechContext()
                handledSpeechRequest[0] = speechRequest?.first
                speakContext(request)
            } else ttsPlayer.play(startAt)
        } else {
            ttsPlayer.pause()
        }
    }

    // ── The voice on a PDF ───────────────────────────────────────────────────────
    //
    // A PDF has no chapters to hand over — the reader is looking at one page, drawn as a
    // picture, and one page is what the voice gets. Turning the page hands it the next one, and
    // when it reaches the bottom of a page it turns the page itself; without that last part a
    // PDF would have to be nudged along by hand every two minutes, which is not listening to a
    // book. Kept apart from the effect above rather than folded into it because it has to react
    // to the page changing, and an HTML chapter that re-ran on every page turn would answer each
    // one by starting the current sentence over.
    SideEffect {
        onSpeechFinished[0] = {
            when (val current = content) {
                is ContentState.Pdf -> {
                    val next = pageState.currentPage + 1
                    if (next < current.pageCount) pdfJumpTarget = next
                    else viewModel.setTtsPlaying(false)
                }
                is ContentState.Html -> {
                    if (current.isSegmented && current.segmentIndex < current.segments.lastIndex) {
                        viewModel.moveHtmlSegment(1)
                    } else viewModel.setTtsPlaying(false)
                }
                else -> viewModel.setTtsPlaying(false)
            }
        }
    }

    LaunchedEffect(ttsState.isActive, content, ttsState.isPlaying, pageState.currentPage, speechRequest) {
        if (!ttsState.isActive) return@LaunchedEffect
        val pdf = content as? ContentState.Pdf ?: return@LaunchedEffect
        val layer = pdf.text
        if (layer == null) {
            // Scanned, encrypted, or simply unreadable. Said when the reader actually asks for
            // the voice, and only then: a play button that does nothing and never explains
            // itself is the worst of the three ways this can go.
            if (ttsState.isPlaying) {
                viewModel.setTtsPlaying(false)
                viewModel.showSnack("В этом PDF нет текста для озвучки")
            }
            return@LaunchedEffect
        }
        val page = pageState.currentPage
        val key  = "pdf:${pdf.filePath}#$page"
        if (preparedText[0] != key) {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                layer.pageText(page)
            }
            // Escaped on the way in: the player parses what it is given as markup, and a page
            // that happens to contain a "<" would otherwise lose everything up to the next ">".
            ttsPlayer.prepareText(android.text.TextUtils.htmlEncode(text))
            preparedText[0] = key
            // Sentence zero, always: there is no way to ask a picture of a page which line is
            // in front of the eye, and the top of the page is where a reader would start.
            viewModel.updateTtsSentence(0, ttsPlayer.sentences.firstOrNull()?.text.orEmpty())
            // A page of nothing but a picture inside a book that has text elsewhere: no reason
            // to stop, and the completion handler moves on to the next page on its own.
            if (ttsPlayer.sentences.isEmpty()) {
                if (ttsState.isPlaying) onSpeechFinished[0]()
                return@LaunchedEffect
            }
            if (ttsState.isPlaying) {
                if (speechRequest?.first != handledSpeechRequest[0]) {
                    handledSpeechRequest[0] = speechRequest?.first
                    speakContext(speechRequest?.second)
                } else ttsPlayer.play(0)
            }
            return@LaunchedEffect
        }
        if (ttsState.isPlaying && speechRequest?.first != handledSpeechRequest[0]) {
            handledSpeechRequest[0] = speechRequest?.first
            speakContext(speechRequest?.second)
        } else if (ttsState.isPlaying) ttsPlayer.play(ttsState.currentSentenceIndex.coerceAtLeast(0))
        else ttsPlayer.pause()
    }

    // The line being spoken, marked in the text itself.
    //
    // Only the sentence is sent across, not its number: the page finds it by its words, so
    // the two sides never have to agree on how the book was cut up — and they cannot, since
    // the split is made from the plain text and the page is markup.
    //
    // Following is deliberately tied to isPlaying as well as to the reader's own switch. A
    // paused voice that still drags the page around is the app moving the book while the
    // reader is looking at it, which is what the skip buttons are for.
    LaunchedEffect(ttsState.isActive, ttsState.currentSentenceText, ttsState.followText,
                   ttsState.isPlaying, webViewRef, content) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (content !is ContentState.Html) return@LaunchedEffect
        if (!ttsState.isActive) {
            wv.evaluateJavascript("window.clearSpoken && clearSpoken()", null)
            return@LaunchedEffect
        }
        val line = ttsState.currentSentenceText
        if (line.isBlank()) return@LaunchedEffect
        val follow = ttsState.followText && ttsState.isPlaying
        wv.evaluateJavascript(
            "window.markSpoken && markSpoken(${org.json.JSONObject.quote(line)}, $follow)",
            null
        )
    }

    LaunchedEffect(ttsState.speechRate) {
        ttsPlayer.setSpeechRate(ttsState.speechRate)
    }

    LaunchedEffect(settings.preferNetworkVoice) {
        ttsPlayer.setPreferNetworkVoice(settings.preferNetworkVoice)
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsPlayer.release()
        }
    }

    // Reading time is only counted while this screen is actually in the foreground —
    // a reader left open on a locked phone overnight must not inflate the daily stat.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startReadingSession()
                Lifecycle.Event.ON_STOP  -> viewModel.stopReadingSession()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.startReadingSession()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopReadingSession()
        }
    }

    // ── Sleep timer ──────────────────────────────────────────────────────
    // Counts down in real time and, when it expires, silences everything that would
    // otherwise keep running while the user is asleep: narration, ambient sound and
    // auto-scroll. The remaining minutes are shown live in the ambient sheet.
    var sleepRemainingSec by remember { mutableStateOf(0) }
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes <= 0) {
            sleepRemainingSec = 0
            return@LaunchedEffect
        }
        sleepRemainingSec = sleepTimerMinutes * 60
        while (sleepRemainingSec > 0) {
            kotlinx.coroutines.delay(1_000)
            sleepRemainingSec--
        }
        viewModel.setTtsPlaying(false)
        viewModel.disableAmbient()
        autoScrollSpeed = 0
        sleepTimerMinutes = 0
    }

    // ── Auto-scroll ──────────────────────────────────────────────────────
    // Hands-free reading at one of five paces, but "forward" means two different things:
    // a column layout can only flip a whole page at a time, while a single long column
    // can creep continuously — which is what auto-scroll is supposed to feel like, and
    // what a screenful-every-N-seconds jump never did.
    //
    // In page mode the flip is therefore a countdown, and this Animatable both *is* that
    // countdown and drives the bar that shows it: without one, the page just jumps out
    // from under you with no way to tell whether the pace matches your reading.
    val flipProgress = remember { Animatable(0f) }
    LaunchedEffect(autoScrollSpeed, webViewRef, settings.horizontalScrolling, content) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (autoScrollSpeed <= 0) {
            wv.evaluateJavascript("setAutoScroll(0)", null)
            flipProgress.snapTo(0f)
            return@LaunchedEffect
        }
        if (settings.horizontalScrolling) {
            val intervalMs = AutoScrollPace.intervalMillis(autoScrollSpeed)
            while (true) {
                flipProgress.snapTo(0f)
                flipProgress.animateTo(1f, tween(intervalMs, easing = LinearEasing))
                wv.evaluateJavascript("autoAdvance()", null)
            }
        } else {
            // Pixels per second, run by the page's own animation frames — a Kotlin timer
            // can't produce a smooth crawl, and at these speeds whole-pixel steps would
            // round down to standing still (the page carries the fractions over).
            val pxPerSec = AutoScrollPace.pixelsPerSecond(autoScrollSpeed)
            try {
                wv.evaluateJavascript("setAutoScroll($pxPerSec)", null)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                wv.evaluateJavascript("setAutoScroll(0)", null)
            }
        }
    }

    // A bare "×3" on a button means nothing the first time you see it, so each tap
    // states what just happened and then gets out of the way.

    // One background for every format. PDF used to be forced white here, because its pages
    // were bitmaps with white paper baked in and a sepia or black frame around them read as a
    // mistake. The page itself is now recoloured to the palette (see pdfPaletteFilter), so the
    // frame no longer has anything to clash with.
    val readBg = Color(settings.readingMode.bgArgb)
    val pageCounterAllowed = rememberPageCounterAllowed() && !ttsState.isPlaying

    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearSnack()
        }
    }

    val view = LocalView.current
    ReaderDisplayEffects(settings.brightness, settings.keepScreenOn, autoScrollSpeed > 0)
    val colorScheme = MaterialTheme.colorScheme
    val isDarkAppTheme = colorScheme.background == DarkSurface0

    // Anything that puts its own surface over the whole screen: while one of these is up,
    // the system bars sit on app chrome, not on the page.
    val overlayOpen = showReaderSettings || showAmbientSheet || showNotes ||
                      defState !is DefinitionState.Hidden
    androidx.activity.compose.BackHandler(
        enabled = !overlayOpen && !showSearch && !showToc && editingAnnotation == null &&
            pendingNoteSelection == null && pendingHighlightSelection == null &&
            pendingPdfNote == null && !confirmClearPdfPage
    ) {
        when {
            autoScrollExpanded -> autoScrollExpanded = false
            pdfToolsExpanded && pdfOptionsExpanded -> pdfOptionsExpanded = false
            pdfToolsExpanded -> { pdfToolsExpanded = false; pdfMode = PdfAnnotationMode.NONE }
            ttsState.isActive -> viewModel.closeTts()
            else -> leaveReader()
        }
    }

    // The system bars are left fully transparent so that whatever the app paints
    // underneath them shows through: the white chrome bar when the menu is up, the page
    // colour when it isn't, a bottom sheet's own surface when one is open. Painting them
    // the page colour (as this did before) drew an opaque sepia band *over* the top bar
    // and left a sepia strip under every bottom sheet.
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val previousStatus = window.statusBarColor
        val previousNav    = window.navigationBarColor
        if (!view.isInEditMode) {
            window.statusBarColor     = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            // Without this, Android ≥10 re-adds its own translucent scrim behind the
            // gesture bar, which is exactly the strip we are trying to get rid of.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
        onDispose {
            viewModel.saveProgress()
            if (!view.isInEditMode) {
                window.statusBarColor     = previousStatus
                window.navigationBarColor = previousNav
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars     = !isDarkAppTheme
                    isAppearanceLightNavigationBars = !isDarkAppTheme
                }
            }
        }
    }

    // Transparent bars still need to be told which way to tint their icons, and the
    // answer differs per bar: the top one may be over the chrome bar while the bottom
    // one is still over the page.
    // PDF is no longer excepted here: its pages now take the palette like any other book, so
    // a dark theme really does put dark paper under the status bar icons.
    val pageIsDark = settings.readingMode.isDarkPage
    LaunchedEffect(showChrome, overlayOpen, pageIsDark, isDarkAppTheme) {
        if (view.isInEditMode) return@LaunchedEffect
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars =
                if (showChrome || overlayOpen) !isDarkAppTheme else !pageIsDark
            isAppearanceLightNavigationBars =
                if (overlayOpen) !isDarkAppTheme else !pageIsDark
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(readBg)) {

        // The counter is below the real reader, transparent and exactly the same size. It lays
        // out one unopened segment at a time, which is the only honest way to get a page count
        // for this particular font and screen without making the first visible page wait.
        (content as? ContentState.Html)?.takeIf { settingsLoaded && it.isSegmented && pageState.isTotalEstimated }
            ?.let { html ->
                ExactPageCounter(
                    content = html,
                    settings = settings,
                    enabled = pageCounterAllowed,
                    measuredSegments = pageState.measuredSegmentIndices,
                    onCount = viewModel::recordPrecalculatedSegmentPages
                )
            }

        // Main content
        when (val c = content) {
            is ContentState.Loading -> LoadingPage(readBg)
            is ContentState.Error   -> ErrorPage(c.message, onBack)
            is ContentState.Html    -> if (!settingsLoaded) LoadingPage(readBg) else PagedHtmlReader(
                html            = c.html,
                settings        = settings,
                sourcePath      = c.book.filePath,
                savedProgress   = c.savedProgress,
                savedPosition   = c.savedPosition,
                segmentIndex    = c.segmentIndex,
                onPositionChange = viewModel::onReadingPositionChanged,
                hasPrevious     = c.isSegmented && c.segmentIndex > 0,
                hasNext         = c.isSegmented && c.segmentIndex < c.segments.lastIndex,
                annotations     = annotations,
                onToggleChrome  = { showChrome = !showChrome },
                onWordSelected  = viewModel::lookupWord,
                onSpeakRequested = { request -> speechRequest = System.nanoTime() to request; showChrome = true; viewModel.openTts() },
                onHighlight     = { text, before, after ->
                    pendingHighlightSelection = Triple(text, before, after)
                },
                onNoteRequested = { text, before, after ->
                    pendingNoteSelection = Triple(text, before, after)
                },
                onAnnotationClick = { id ->
                    editingAnnotation = annotations.find { it.id == id }
                },
                onPageChange    = { page, total, localPercent, viewportWidth, viewportHeight ->
                    val bookmarkPage = if (c.isSegmented) {
                        c.segmentIndex * SEGMENT_BOOKMARK_STRIDE + page
                    } else page
                    viewModel.onHtmlPageChanged(
                        currentPage  = page,
                        segmentPages = total,
                        percent      = c.globalPercent(localPercent),
                        bookmarkPage = bookmarkPage,
                        layoutKey    = pageLayoutKey(settings, viewportWidth, viewportHeight)
                    )
                },
                onBoundary      = viewModel::moveHtmlSegment,
                onWebViewReady  = { webViewRef = it }
            )
            is ContentState.Pdf     -> PdfReader(
                filePath = c.filePath,
                pageCount = c.pageCount,
                savedProgress = c.savedProgress,
                savedPage = c.book.readingPage,
                annotations = annotations,
                mode = pdfMode,
                activeColor = pdfActiveColor,
                penWidthDp = pdfPenWidthDp,
                readingMode = settings.readingMode,
                zoom = settings.pdfZoom,
                onZoomChange = settingsViewModel::setPdfZoom,
                verticalPaging = settings.pdfVerticalPaging,
                jumpTarget = pdfJumpTarget,
                onJumpConsumed = { pdfJumpTarget = null },
                onToggle = { showChrome = !showChrome },
                // A PDF's pages are the file's own, not the screen's, so there is no character
                // measure to hand over and the page count is the honest answer here.
                onPageChange = { page, count -> viewModel.onPageChanged(page, count) },
                onNotePlaced = { pageIndex, x, y -> pendingPdfNote = Triple(pageIndex, x, y) },
                onDrawingComplete = { pageIndex, pathData, color ->
                    viewModel.addDrawing(pageIndex, pathData, color)
                },
                onDrawingErased = viewModel::eraseDrawing,
                onDrawingPartsErased = viewModel::eraseDrawingParts,
                onAnnotationClick = { id ->
                    editingAnnotation = annotations.find { it.id == id }
                }, textLayer = c.text, onWordSelected = viewModel::lookupWord,
                onSpeakRequested = { request -> speechRequest = System.nanoTime() to request; showChrome = true; viewModel.openTts() },
                onSelectionVisible = { showChrome = false }
            )
        }

        // Thin accent progress bar at top. Off unless asked for — a permanent rule across
        // the top of a page is the kind of thing you either want or find impossible to
        // stop looking at. Status-bar padded: the system bars are transparent now, so
        // without it the bar hides behind the clock.
        if (settings.showProgressBar && pageState.totalPages > 1 && pageState.progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(2.5.dp)
                    .align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = pageState.progress)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        // Where the next automatic page turn currently stands. Lives outside the chrome
        // so it survives with the toolbars hidden — this is the one thing you need while
        // reading hands-free, to see whether the pace is keeping up with you. Painted in
        // the draw phase, so a bar that animates for a minute costs no recompositions.
        if (autoScrollSpeed > 0 && settings.horizontalScrolling && content is ContentState.Html) {
            val flipColor = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .drawBehind {
                        drawRect(color = flipColor.copy(alpha = 0.18f))
                        drawRect(
                            color = flipColor,
                            size  = Size(size.width * flipProgress.value, size.height)
                        )
                    }
            )
        }

        // Top chrome (back + title + search + TOC + bookmark)
        AnimatedVisibility(
            visible = showChrome,
            enter   = slideInVertically { -it } + fadeIn(tween(180)),
            exit    = slideOutVertically { -it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            when (val c = content) {
                is ContentState.Html -> ReaderTopBar(
                    book         = c.book,
                    isBookmarked = isBookmarked,
                    onBack       = leaveReader,
                    onBookmark   = viewModel::toggleBookmark,
                    onSearch     = { showSearch = true },
                    onToc        = { showToc = true }
                )
                is ContentState.Pdf  -> ReaderTopBar(
                    book         = c.book,
                    isBookmarked = isBookmarked,
                    onBack       = leaveReader,
                    onBookmark   = viewModel::toggleBookmark,
                    onSearch     = { showSearch = true },
                    onToc        = { showToc = true }
                )
                else -> Unit
            }
        }

        // Everything that sits at the foot of the screen shares one column. The player and
        // the toolbar used to be two overlays claiming the same bottom-center slot, so the
        // toolbar had to be switched off whenever the player was up — which is why turning
        // on the voice took the whole navigation panel away with it. Stacked, both fit, and
        // the single navigationBarsPadding here keeps the stack clear of the system bar
        // instead of each piece having to remember it on its own.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = ttsState.isActive,
                enter   = slideInVertically { it } + fadeIn(tween(180)),
                exit    = slideOutVertically { it } + fadeOut(tween(180))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TtsPlayerBar(
                        isPlaying            = ttsState.isPlaying,
                        currentSentenceText  = ttsState.currentSentenceText,
                        speechRate           = ttsState.speechRate,
                        followText           = ttsState.followText,
                        onFollowToggle       = { viewModel.setTtsFollowText(!ttsState.followText) },
                        onPlayPauseToggle    = { viewModel.setTtsPlaying(!ttsState.isPlaying) },
                        onPrevious           = { ttsPlayer.previousSentence() },
                        onNext               = { ttsPlayer.nextSentence() },
                        onSpeedChange        = { viewModel.setTtsSpeed(it) },
                        onStartFromPage      = {
                            uiScope.launch {
                                if (speakContext(visibleSpeechContext())) viewModel.setTtsPlaying(true)
                            }
                        },
                        onStartFromBeginning = { startSpeakingAt(0) },
                        onClose              = { viewModel.closeTts() }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Bottom chrome — text/sound/playback tools, moved down here from the top bar,
            // plus the page-number indicator.
            AnimatedVisibility(
                visible = showChrome,
                enter   = slideInVertically { it } + fadeIn(tween(180)),
                exit    = slideOutVertically { it } + fadeOut(tween(180))
            ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(visible = content is ContentState.Html && autoScrollExpanded) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AutoScrollPanel(autoScrollSelectedSpeed, autoScrollSpeed > 0, settings.horizontalScrolling,
                            progress = { flipProgress.value },
                            onSpeedChange = { next ->
                                autoScrollSelectedSpeed = next
                                if (autoScrollSpeed > 0) autoScrollSpeed = next
                            },
                            onToggle = { autoScrollSpeed = if (autoScrollSpeed > 0) 0 else autoScrollSelectedSpeed },
                            onCollapse = { autoScrollExpanded = false })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (content is ContentState.Pdf && pdfToolsExpanded) {
                    val pageDrawings = annotations.filter {
                        it.type == AnnotationType.DRAWING && it.pageIndex == pageState.currentPage
                    }
                    PdfToolsPanel(
                        mode = pdfMode,
                        onModeChange = { pdfMode = it },
                        optionsExpanded = pdfOptionsExpanded,
                        onOptionsToggle = { pdfOptionsExpanded = !pdfOptionsExpanded },
                        activeColor = pdfActiveColor,
                        onColorChange = { pdfActiveColor = it },
                        penWidthDp = pdfPenWidthDp,
                        onPenWidthChange = { pdfPenWidthDp = it },
                        zoom = settings.pdfZoom,
                        onZoomChange = settingsViewModel::setPdfZoom,
                        drawingCount = pageDrawings.size,
                        canUndo = drawingHistory.canUndo,
                        canRedo = drawingHistory.canRedo,
                        onUndo = viewModel::undoDrawing,
                        onRedo = viewModel::redoDrawing,
                        onClearPage = { confirmClearPdfPage = true }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                val markCount = annotations.size + bookmarks.size

                when (content) {
                    is ContentState.Html -> ReaderBottomBar(
                        isAmbientActive = ambientState.isEnabled,
                        isTtsActive     = ttsState.isActive,
                        onAmbientClick  = { showAmbientSheet = true },
                        onSettingsClick = { showReaderSettings = true },
                        onTtsClick      = {
                            if (ttsState.isActive) viewModel.closeTts() else viewModel.openTts()
                        },
                        onNotesClick    = { showNotes = true },
                        markCount       = markCount,
                        sleepMinutesLeft = if (sleepRemainingSec > 0) (sleepRemainingSec + 59) / 60 else 0,
                        autoScrollSpeed = autoScrollSpeed,
                        autoScrollExpanded = autoScrollExpanded,
                        pdfMode = null,
                        onHideChrome = { showChrome = false },
                        onAutoScrollClick = {
                            autoScrollExpanded = !autoScrollExpanded
                        }
                    )
                    is ContentState.Pdf -> ReaderBottomBar(
                        isAmbientActive = ambientState.isEnabled,
                        isTtsActive     = ttsState.isActive,
                        onAmbientClick  = { showAmbientSheet = true },
                        // Was dead for PDF, on the grounds that none of the settings applied.
                        // The page palette does now, so the button opens the sheet with the
                        // typesetting half left out.
                        onSettingsClick = { showReaderSettings = true },
                        onTtsClick      = {
                            if (ttsState.isActive) viewModel.closeTts() else viewModel.openTts()
                        },
                        onNotesClick    = { showNotes = true },
                        markCount       = markCount,
                        sleepMinutesLeft = if (sleepRemainingSec > 0) (sleepRemainingSec + 59) / 60 else 0,
                        pdfMode = pdfMode,
                        pdfToolsExpanded = pdfToolsExpanded,
                        onHideChrome = {
                            showChrome = false
                            autoScrollExpanded = false
                            pdfToolsExpanded = false
                            pdfOptionsExpanded = false
                            pdfMode = PdfAnnotationMode.NONE
                        },
                        onPdfToolsClick = {
                            pdfToolsExpanded = !pdfToolsExpanded
                            if (pdfToolsExpanded) {
                                pdfOptionsExpanded = false
                                if (pdfMode == PdfAnnotationMode.NONE) pdfMode = PdfAnnotationMode.DRAW
                            } else {
                                // Closing the tools must also leave the active input mode.
                                // Otherwise the page can remain in drawing/erase mode with
                                // no visible control to get back to normal reading.
                                pdfMode = PdfAnnotationMode.NONE
                            }
                        }
                    )
                    else -> Unit
                }

                if (settings.showPageNumbers && pageState.displayTotalPages > 0) {
                    Spacer(Modifier.height(4.dp))
                    val pageLabel = buildString {
                            append("стр. ")
                            append(pageState.displayCurrentPage + 1)
                            if (!pageState.isTotalEstimated) {
                                append(" из ")
                                append(pageState.displayTotalPages)
                            }
                        }
                    AnimatedContent(
                        targetState = pageLabel,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "page counter"
                    ) { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(settings.readingMode.fgArgb).copy(alpha = 0.82f),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .clickable { showPageJump = true }.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            }
        }

        // Snackbar
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (confirmClearPdfPage) {
        AlertDialog(
            onDismissRequest = { confirmClearPdfPage = false },
            title = { Text("Удалить рисунки со страницы?") },
            text = { Text("Все рукописные пометки на текущей странице будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    val pageDrawings = annotations.filter {
                        it.type == AnnotationType.DRAWING && it.pageIndex == pageState.currentPage
                    }
                    viewModel.deleteAnnotations(pageDrawings)
                    confirmClearPdfPage = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmClearPdfPage = false }) { Text("Отмена") } }
        )
    }

    // Definition bottom sheet
    when (val d = defState) {
        is DefinitionState.Loading,
        is DefinitionState.Success,
        is DefinitionState.Error -> DefinitionBottomSheet(
            state        = d,
            isSaved      = (d as? DefinitionState.Success)
                ?.let { it.definition.title.lowercase() in savedWords } ?: false,
            onToggleSave = { word, definition -> viewModel.saveWordToVocabulary(word, definition) },
            onDismiss    = viewModel::dismissDefinition
        )
        else -> Unit
    }

    // Search dialog
    if (showSearch && content is ContentState.Html) {
        val htmlContent = content as ContentState.Html
        SearchInBookDialog(
            html       = htmlContent.html,
            plainTextPath = htmlContent.plainTextPath,
            segments   = htmlContent.segments,
            totalPages = pageState.totalPages,
            onDismiss  = { showSearch = false },
            onSelectResult = { q, item ->
                showSearch = false
                // The page finds the word itself and marks it — jumping to a page number
                // computed from character offsets landed near the hit but left the reader
                // to spot it, and the estimate drifted with font size and margins.
                val call = "findAndHighlight(${org.json.JSONObject.quote(q)}, ${item.occurrence})"
                if (htmlContent.isSegmented && item.segmentIndex != htmlContent.segmentIndex) {
                    pendingHtmlJs = call
                    viewModel.openHtmlSegment(item.segmentIndex, item.localPercent)
                } else webViewRef?.evaluateJavascript(call, null)
            }
        )
    }

    // Table of contents sheet
    if (showToc && content is ContentState.Html) {
        val htmlContent = content as ContentState.Html
        TocBottomSheet(
            html       = htmlContent.html,
            toc        = htmlContent.toc,
            onDismiss  = { showToc = false },
            onSelectChapter = { entry ->
                showToc = false
                // An anchor is the book's own answer and lands exactly; a title is a guess made
                // from the headings, and the page has to go looking for it. Both are quoted
                // through JSONObject, because a chapter called "Ch. 3: What's Left" would
                // otherwise close the string early and throw a syntax error instead of moving.
                val call = if (entry.anchor.isNotEmpty()) {
                    "jumpToAnchor(${org.json.JSONObject.quote(entry.anchor)})"
                } else {
                    "jumpToHeading(${org.json.JSONObject.quote(entry.title)})"
                }
                if (htmlContent.isSegmented && entry.segmentIndex >= 0 &&
                    entry.segmentIndex != htmlContent.segmentIndex
                ) {
                    pendingHtmlJs = call
                    viewModel.openHtmlSegment(entry.segmentIndex, 0f)
                } else webViewRef?.evaluateJavascript(call, null)
            }
        )
    }

    // A PDF is drawn from pictures, but its text is read separately (PdfTextLayer), so both of
    // these offer the real thing when the file has one — full-text search, and the bookmarks the
    // publisher wrote. A scanned book has neither, and they fall back to page numbers.
    if (showSearch && content is ContentState.Pdf) {
        val pdf = content as ContentState.Pdf
        PdfSearchDialog(
            text      = pdf.text,
            pageCount = pdf.pageCount,
            onDismiss = { showSearch = false },
            onSelectPage = { pageIdx ->
                showSearch = false
                pdfJumpTarget = pageIdx
            }
        )
    }

    if (showToc && content is ContentState.Pdf) {
        val pdf = content as ContentState.Pdf
        PdfTocSheet(
            text      = pdf.text,
            pageCount = pdf.pageCount,
            onDismiss = { showToc = false },
            onSelectPage = { pageIdx ->
                showToc = false
                pdfJumpTarget = pageIdx
            }
        )
    }

    // Marks panel — bookmarks, highlights, notes and drawings of this book in one list
    if (showNotes) {
        val currentBook = (content as? ContentState.Html)?.book ?: (content as? ContentState.Pdf)?.book
        BookNotesSheet(
            bookTitle   = currentBook?.title ?: "",
            isPdf       = content is ContentState.Pdf,
            bookmarks   = bookmarks,
            annotations = annotations,
            bookmarkLabel = { encoded ->
                val html = content as? ContentState.Html
                if (html?.isSegmented == true) {
                    val segment = encoded / SEGMENT_BOOKMARK_STRIDE
                    val page = encoded % SEGMENT_BOOKMARK_STRIDE
                    "Фрагмент ${segment + 1} · страница ${page + 1}"
                } else "Страница ${encoded + 1}"
            },
            onJumpToPage = { page ->
                showNotes = false
                if (content is ContentState.Pdf) {
                    pdfJumpTarget = page
                } else {
                    val html = content as? ContentState.Html
                    if (html?.isSegmented == true) {
                        val segment = page / SEGMENT_BOOKMARK_STRIDE
                        val localPage = page % SEGMENT_BOOKMARK_STRIDE
                        val call = "jumpToPage($localPage)"
                        if (segment != html.segmentIndex) {
                            pendingHtmlJs = call
                            viewModel.openHtmlSegment(segment, 0f)
                        } else webViewRef?.evaluateJavascript(call, null)
                    } else webViewRef?.evaluateJavascript("jumpToPage($page)", null)
                }
            },
            onOpenAnnotation  = { ann ->
                showNotes = false
                editingAnnotation = ann
            },
            onDeleteBookmark   = viewModel::deleteBookmark,
            onDeleteAnnotation = viewModel::deleteAnnotation,
            onShareAll = {
                val text = viewModel.buildNotesExport()
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Пометки: ${currentBook?.title ?: ""}")
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(send, "Поделиться пометками"))
            },
            onDismiss = { showNotes = false }
        )
    }

    pendingHighlightSelection?.let { (text, before, after) ->
        AnnotationHighlightDialog(
            selectedText = text,
            onColorSelected = { color ->
                viewModel.addHighlight(text, before, after, color)
                pendingHighlightSelection = null
            },
            onDismiss = { pendingHighlightSelection = null }
        )
    }

    // New note dialog — triggered from the text-selection menu ("📝 Заметка")
    pendingNoteSelection?.let { (text, before, after) ->
        AnnotationNoteDialog(
            selectedText = text,
            onConfirm = { noteText, color ->
                viewModel.addTextNote(text, before, after, noteText, color)
                pendingNoteSelection = null
            },
            onDismiss = { pendingNoteSelection = null }
        )
    }

    // New note-pin dialog — triggered by a tap on a PDF page while in note mode
    if (showPageJump) {
        val exact = !pageState.isTotalEstimated
        PdfGoToPageDialog(
            pageCount = if (exact) pageState.displayTotalPages else pageState.totalPages,
            currentPage = if (exact) pageState.displayCurrentPage else pageState.currentPage,
            note = if (exact) null else "Общее число страниц рассчитывается. Пока доступен переход внутри текущей части книги.",
            onDismiss = { showPageJump = false },
            onSelectPage = { page ->
                showPageJump = false
                if (content is ContentState.Pdf) pdfJumpTarget = page
                else {
                    val html = content as? ContentState.Html
                    val target = if (exact) viewModel.resolveGlobalPage(page) else html?.let { it.segmentIndex to page }
                    target?.let { (segment, local) ->
                        val call = "jumpToPage($local)"
                        if (html?.isSegmented == true && segment != html.segmentIndex) {
                            pendingHtmlJs = call; viewModel.openHtmlSegment(segment, 0f)
                        } else webViewRef?.evaluateJavascript(call, null)
                    }
                }
            }
        )
    }
    pendingPdfNote?.let { (pageIndex, x, y) ->
        AnnotationNoteDialog(
            selectedText = "Заметка на странице ${pageIndex + 1}",
            quoted = false,
            onConfirm = { noteText, color ->
                viewModel.addPdfNote(pageIndex, x, y, noteText, color)
                pendingPdfNote = null
            },
            onDismiss = { pendingPdfNote = null }
        )
    }

    // Existing annotation — tapped highlight/note marker in the text
    editingAnnotation?.let { ann ->
        AnnotationEditDialog(
            annotation   = ann,
            onSaveNote   = { text -> viewModel.updateAnnotationNote(ann, text) },
            onColorChange = { color -> viewModel.updateAnnotationColor(ann, color) },
            onDelete     = {
                viewModel.deleteAnnotation(ann)
                editingAnnotation = null
            },
            onDismiss    = { editingAnnotation = null }
        )
    }

    // Reader appearance sheet (font, page color, spacing — lives only here, not in Settings)
    if (showReaderSettings) {
        ReaderSettingsSheet(
            settings                   = settings,
            onFontSizeChange           = settingsViewModel::setFontSize,
            onFontFamilyChange         = settingsViewModel::setFontFamily,
            onLineHeightChange         = settingsViewModel::setLineHeight,
            onMarginChange             = settingsViewModel::setMargin,
            onReadingModeChange        = settingsViewModel::setReadingMode,
            onPageAnimationChange      = settingsViewModel::setPageAnimation,
            onJustifyTextChange        = settingsViewModel::setJustifyText,
            onHorizontalScrollingChange = settingsViewModel::setHorizontalScrolling,
            onShowPageNumbersChange    = settingsViewModel::setShowPageNumbers,
            onShowProgressBarChange    = settingsViewModel::setShowProgressBar,
            onPreferNetworkVoiceChange = settingsViewModel::setPreferNetworkVoice,
            textLayoutEnabled          = content !is ContentState.Pdf,
            onPdfZoomChange            = settingsViewModel::setPdfZoom,
            onPdfVerticalPagingChange  = settingsViewModel::setPdfVerticalPaging,
            onBrightnessChange         = settingsViewModel::setReaderBrightness,
            onKeepScreenOnChange       = settingsViewModel::setKeepReaderScreen,
            onDismiss                  = { showReaderSettings = false }
        )
    }

    // Ambient sound sheet
    if (showAmbientSheet) {
        AmbientSoundBottomSheet(
            ambientState = ambientState,
            onToggleEnabled = viewModel::toggleAmbient,
            onSelectSound = { soundId, customPath -> viewModel.setAmbientSound(soundId, customPath) },
            onVolumeChange = viewModel::setAmbientVolume,
            onCustomAudioSelected = viewModel::saveCustomAudio,
            sleepTimerMinutes = sleepTimerMinutes,
            sleepRemainingSec = sleepRemainingSec,
            onSleepTimerChange = { sleepTimerMinutes = it },
            onDismiss = { showAmbientSheet = false }
        )
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    book: Book,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onSearch: () -> Unit,
    onToc: () -> Unit
) {
    com.shelest.ui.ReaderHeader(book.title, book.author, listOf(
        com.shelest.ui.ReaderAction("search", "Поиск по книге"),
        com.shelest.ui.ReaderAction("toc", "Оглавление"),
        com.shelest.ui.ReaderAction("bookmark", if (isBookmarked) "Удалить закладку" else "Добавить закладку", isBookmarked)
    )) { action ->
        when(action) { "back" -> onBack(); "search" -> onSearch(); "toc" -> onToc(); "bookmark" -> onBookmark() }
    }
}

// ── Bottom Bar (text / sound / playback tools) ──────────────────────────────────

@Composable
internal fun ReaderBottomBar(
    isAmbientActive: Boolean,
    isTtsActive: Boolean,
    onAmbientClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTtsClick: () -> Unit,
    onNotesClick: () -> Unit,
    markCount: Int = 0,
    sleepMinutesLeft: Int = 0,
    autoScrollSpeed: Int? = null,
    autoScrollExpanded: Boolean = false,
    onAutoScrollClick: (() -> Unit)? = null,
    pdfMode: PdfAnnotationMode? = null,
    pdfToolsExpanded: Boolean = false,
    onHideChrome: () -> Unit,
    onPdfToolsClick: (() -> Unit)? = null
) {
    val actions = buildList {
        add(com.shelest.ui.ReaderAction("notes", "Мои пометки: $markCount", markCount > 0,
            badge = if (markCount > 0) markCount.toString() else ""))
        if (autoScrollSpeed != null && onAutoScrollClick != null)
            add(com.shelest.ui.ReaderAction("autoScroll", if (autoScrollExpanded) "Свернуть автопрокрутку" else "Показать автопрокрутку",
                autoScrollExpanded || autoScrollSpeed > 0, badge = if (autoScrollSpeed > 0) "×$autoScrollSpeed" else ""))
        if (onPdfToolsClick == null) add(com.shelest.ui.ReaderAction("tts", if (isTtsActive) "Закрыть озвучку" else "Озвучка текста", isTtsActive))
        add(com.shelest.ui.ReaderAction("ambient", if (sleepMinutesLeft > 0) "Таймер сна: $sleepMinutesLeft минут" else "Фоновые звуки",
            isAmbientActive || sleepMinutesLeft > 0, badge = if (sleepMinutesLeft > 0) sleepMinutesLeft.toString() else ""))
        if (onPdfToolsClick != null) add(com.shelest.ui.ReaderAction("pdfTools",
            if (pdfToolsExpanded) "Свернуть инструменты PDF" else "Показать инструменты PDF",
            pdfToolsExpanded || pdfMode != PdfAnnotationMode.NONE))
        add(com.shelest.ui.ReaderAction("settings", "Настройки чтения", badge = "Aa"))
        add(com.shelest.ui.ReaderAction("hideChrome", "Убрать панель"))
    }
    com.shelest.ui.ReaderTools(actions) { action ->
        when(action) {
            "notes" -> onNotesClick(); "autoScroll" -> onAutoScrollClick?.invoke()
            "tts" -> onTtsClick(); "ambient" -> onAmbientClick()
            "pdfTools" -> onPdfToolsClick?.invoke(); "settings" -> onSettingsClick()
            "hideChrome" -> onHideChrome()
        }
    }
}

/** Transient one-line message that floats above the bottom bar and fades out. */
@Composable
private fun HintPill(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

/**
 * Icon with a small count bubble. The number is what tells the user there is
 * anything to open at all — an unmarked icon looks the same whether the book has
 * forty highlights or none.
 */
@Composable
private fun BadgedIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int
) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 7.dp, y = (-6).dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text  = if (count > 99) "99+" else count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize   = 9.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

// ── Search & TOC Modals ───────────────────────────────────────────────────────

// ── Reading a finished document without parsing it ───────────────────────────────────────
//
// Both of these used to be jsoup: parse the whole book into a tree, then ask the tree. That is
// the right tool for a document whose shape matters, and the wrong one here — the shape does not
// matter, the tree costs several times the book's own size, and both of these ran on the main
// thread. The document being read is one this app wrote a few hundred lines further down, so its
// markup is known and a scan through it answers the same question.

private val HEADING_TAG = Regex(
    "<h([1-3])\\b([^>]*)>(.*?)</h\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private val ID_ATTRIBUTE = Regex("id\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

/**
 * The headings of [html] as a table of contents — the fallback for a book that states none of its
 * own, and a guess by nature: a book that titles its chapters in a styled paragraph has none of
 * them, and one that uses a heading for every epigraph has forty entries that are not chapters.
 */
private fun headingsOf(html: String): List<TocEntry> {
    val found = HEADING_TAG.findAll(html)
        .mapNotNull { match ->
            val title = plainTextOf(match.groupValues[3])
            if (title.isEmpty()) null
            else TocEntry(
                title  = title,
                // A heading that carries an id can be jumped to exactly; one that does not
                // leaves the page to go looking for its words.
                anchor = ID_ATTRIBUTE.find(match.groupValues[2])?.groupValues?.get(1).orEmpty(),
                level  = match.groupValues[1].toIntOrNull()?.coerceIn(1, 3) ?: 1
            )
        }
        .distinctBy { it.title }
        .take(1000)
        .toList()

    // A book whose chapters are all <h2> has no nesting to show, whatever the tag says. Levels
    // are relative to the shallowest heading in the book, so the list starts flush left.
    val base = found.minOfOrNull { it.level } ?: 1
    return if (base <= 1) found else found.map { it.copy(level = it.level - base + 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInBookDialog(
    html: String,
    plainTextPath: String? = null,
    segments: List<BookSegment> = emptyList(),
    totalPages: Int,
    onDismiss: () -> Unit,
    onSelectResult: (query: String, result: SearchResultItem) -> Unit
) {
    var query by remember { mutableStateOf("") }

    // The book as plain text, worked out once and away from the main thread.
    //
    // This used to build a document tree of the entire book on every keystroke, in the
    // composition — which on a long book is several seconds of a dead keyboard per letter typed,
    // and the tree itself is many times the book's own size in memory.
    val plainText by produceState<String?>(null, html, plainTextPath) {
        value = if (plainTextPath != null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { File(plainTextPath).readText() }.getOrNull()
            }
        } else withContext(kotlinx.coroutines.Dispatchers.Default) { plainTextOf(html) }
    }

    var results by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    LaunchedEffect(query, plainText, totalPages) {
        val text = plainText
        if (query.length < 2 || text == null) {
            results = emptyList()
            return@LaunchedEffect
        }
        // Nobody has finished typing a word after one letter. The pause is cancelled and
        // restarted by the next keystroke, so a search is run once, over the whole word.
        delay(220)
        results = withContext(kotlinx.coroutines.Dispatchers.Default) {
            val matches = mutableListOf<SearchResultItem>()
            var index = 0
            var occurrence = 0
            val totalLen = text.length.coerceAtLeast(1)
            while (index < text.length) {
                val found = text.indexOf(query, index, ignoreCase = true)
                if (found == -1) break
                val start = (found - 30).coerceAtLeast(0)
                val end   = (found + query.length + 40).coerceAtMost(text.length)
                val snippet = "…" + text.substring(start, end) + "…"
                // Only a label: the actual jump is done by the page itself, which knows
                // where the word really landed after the current font and margins.
                val pageIdx = ((found.toFloat() / totalLen) * totalPages).toInt()
                    .coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                val segmentIndex = segments.indexOfFirst { found in it.startChar until it.endChar }
                val segment = segments.getOrNull(segmentIndex)
                val localOccurrence = if (segment != null) {
                    var count = 0
                    var at = segment.startChar.coerceIn(0, text.length)
                    while (at < found) {
                        val hit = text.indexOf(query, at, ignoreCase = true)
                        if (hit < 0 || hit >= found) break
                        count++
                        at = hit + query.length.coerceAtLeast(1)
                    }
                    count
                } else occurrence
                val localPercent = segment?.let {
                    ((found - it.startChar).toFloat() / it.chars.coerceAtLeast(1) * 100f)
                        .coerceIn(0f, 100f)
                } ?: 0f
                matches.add(
                    SearchResultItem(
                        pageIndex    = pageIdx,
                        snippet      = snippet,
                        occurrence   = localOccurrence,
                        segmentIndex = segmentIndex,
                        localPercent = localPercent,
                        bookPercent  = (found.toFloat() / totalLen * 100f).toInt().coerceIn(0, 100)
                    )
                )
                index = found + query.length
                occurrence++
                if (matches.size >= 50) break
            }
            matches
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Plain background, no tonal elevation: Material tints an elevated `surface` with
        // the accent, and this app's accent is a warm red-brown, so the search panel came
        // out the colour of a sepia page.
        shape          = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Поиск по книге",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it },
                    placeholder   = { Text("Слово или фраза", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon   = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(19.dp)) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    textStyle     = MaterialTheme.typography.bodyMedium,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor    = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor   = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.height(4.dp))
                // Says whether the search found nothing or was never run — an empty gap
                // below the field answers neither question.
                if (query.length >= 2) {
                    Text(
                        text     = if (results.isEmpty()) "Ничего не найдено"
                                   else "Совпадений: ${results.size}",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(results) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectResult(query, item) }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text  = if (segments.isNotEmpty()) "${item.bookPercent}% книги"
                                        else "Стр. ${item.pageIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text     = item.snippet,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocBottomSheet(
    html: String,
    toc: List<TocEntry>,
    onDismiss: () -> Unit,
    onSelectChapter: (TocEntry) -> Unit
) {
    // What the book says about itself, and only failing that what its markup looks like. The
    // headings were all there ever was, and they are a poor substitute: a book that titles its
    // chapters in a styled paragraph has none of them, and one that uses a heading for the
    // epigraph on every page has forty entries that are not chapters.
    //
    // Read out of the finished document without building one: a tree of a long book, made in the
    // composition, is the sheet taking several seconds to open and the app frozen for all of them.
    val chapters by produceState(toc, html, toc) {
        if (toc.isNotEmpty()) {
            value = toc
            return@produceState
        }
        value = withContext(kotlinx.coroutines.Dispatchers.Default) { headingsOf(html) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        // Top inset only: the sheet must clear the status bar, but it should paint all
        // the way down past the gesture bar — the default (both edges) leaves a strip of
        // page colour below the sheet. The content keeps its own navigationBarsPadding.
        windowInsets     = WindowInsets.statusBars
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text  = "Оглавление",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            if (chapters.isEmpty()) {
                Text("Главы не найдены", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(chapters) { entry ->
                        Text(
                            text     = entry.title,
                            style    = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(entry) }
                                // Indented by the depth the book declared, so a part and the
                                // chapters inside it read as what they are.
                                .padding(
                                    start = ((entry.level - 1) * 14).dp,
                                    top = 12.dp,
                                    bottom = 12.dp
                                ),
                            color    = if (entry.level > 1) MaterialTheme.colorScheme.onSurfaceVariant
                                       else MaterialTheme.colorScheme.onSurface
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ── PDF navigation ────────────────────────────────────────────────────────────
//
// A PDF is drawn as pictures, so for a long time these two could only offer page numbers. With a
// text layer read out of the file they offer what the book actually says — and they fall back to
// page numbers for a scanned book, where there is genuinely nothing else to offer.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfSearchDialog(
    text: PdfTextLayer?,
    pageCount: Int,
    onDismiss: () -> Unit,
    onSelectPage: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PdfSearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var hasText by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(text) {
        hasText = text != null &&
            withContext(kotlinx.coroutines.Dispatchers.IO) { text.hasText() }
    }

    // Debounced, and off the main thread: the first search pulls every page of the file apart,
    // which is seconds of work on a long book — and running it per keystroke would do that
    // several times over while someone is still typing the word.
    LaunchedEffect(query, text) {
        if (query.trim().length < 2 || text == null) {
            hits = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        hits = withContext(kotlinx.coroutines.Dispatchers.IO) { text.search(query.trim()) }
        searching = false
    }

    if (hasText == false) {
        // Nothing to search: the pages are photographs. Saying so is the honest answer, and the
        // page number is still a way to get somewhere.
        PdfGoToPageDialog(
            pageCount = pageCount,
            note      = "В этом PDF нет текстового слоя. Страницы сохранены картинками, " +
                        "поэтому искать в них нечего.",
            onDismiss = onDismiss,
            onSelectPage = onSelectPage
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape          = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Поиск по книге",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it },
                    placeholder   = { Text("Слово или фраза", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon   = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(19.dp)) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    textStyle     = MaterialTheme.typography.bodyMedium,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor    = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor   = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.height(4.dp))
                if (query.trim().length >= 2) {
                    Text(
                        text = when {
                            searching      -> "Идёт поиск…"
                            hits.isEmpty() -> "Ничего не найдено"
                            else           -> "Совпадений: ${hits.size}"
                        },
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(hits) { hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectPage(hit.pageIndex) }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text  = "Стр. ${hit.pageIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text     = hit.snippet,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

/** The PDF's own bookmarks when it has any, and a list of pages when it has none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfTocSheet(
    text: PdfTextLayer?,
    pageCount: Int,
    onDismiss: () -> Unit,
    onSelectPage: (Int) -> Unit
) {
    var outline by remember { mutableStateOf<List<PdfOutlineItem>?>(null) }
    LaunchedEffect(text) {
        outline = if (text == null) emptyList()
                  else withContext(kotlinx.coroutines.Dispatchers.IO) { text.outline() }
    }

    val entries = outline
    if (entries.isNullOrEmpty()) {
        PdfPageListSheet(pageCount = pageCount, onDismiss = onDismiss, onSelectPage = onSelectPage)
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        windowInsets     = WindowInsets.statusBars
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text  = "Оглавление",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(entries) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPage(item.pageIndex) }
                            .padding(
                                start = ((item.level - 1) * 14).dp,
                                top = 12.dp,
                                bottom = 12.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text     = item.title,
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = if (item.level > 1) MaterialTheme.colorScheme.onSurfaceVariant
                                       else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text  = "${item.pageIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfGoToPageDialog(
    pageCount: Int,
    currentPage: Int = 0,
    /** Why a page number is all this dialog is offering, when there is a reason worth saying. */
    note: String? = null,
    onDismiss: () -> Unit,
    onSelectPage: (Int) -> Unit
) {
    var input by remember { mutableStateOf((currentPage + 1).toString()) }
    val target = input.toIntOrNull()
    val valid = target != null && target in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        shape          = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Перейти на страницу",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "Всего страниц: $pageCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (pageCount > 1) {
                    Slider(value = (target ?: 1).coerceIn(1, pageCount).toFloat(),
                        onValueChange = { input = it.roundToInt().toString() },
                        valueRange = 1f..pageCount.toFloat())
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(6) },
                    placeholder = { Text("Номер страницы", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape      = RoundedCornerShape(12.dp),
                    textStyle  = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor    = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor   = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { target?.let { onSelectPage(it - 1) } },
                enabled = valid
            ) { Text("Перейти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPageListSheet(
    pageCount: Int,
    onDismiss: () -> Unit,
    onSelectPage: (Int) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        // Top inset only: the sheet must clear the status bar, but it should paint all
        // the way down past the gesture bar — the default (both edges) leaves a strip of
        // page colour below the sheet. The content keeps its own navigationBarsPadding.
        windowInsets     = WindowInsets.statusBars
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text  = "Страницы",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(pageCount) { idx ->
                    Text(
                        text     = "Страница ${idx + 1}",
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPage(idx) }
                            .padding(vertical = 12.dp),
                        color    = MaterialTheme.colorScheme.onSurface
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

// ── Paged HTML Reader (CSS columns) ──────────────────────────────────────────

/**
 * Measures the remaining pieces in a second WebView placed behind the reader.
 *
 * CSS columns have no formula for their page count: images, line wrapping and font fallback all
 * participate.  Keeping this view the same size as the visible one gives an exact result, while
 * doing one cached piece at a time keeps both memory use and first-page latency bounded.
 */
@Composable
private fun ExactPageCounter(
    content: ContentState.Html,
    settings: ReaderSettings,
    enabled: Boolean,
    measuredSegments: Set<Int>,
    onCount: (segmentIndex: Int, pages: Int, layoutKey: String) -> Unit
) {
    val segments = content.segments
    val segmentIndex = segments.indices.firstOrNull { it !in measuredSegments } ?: return
    if (!enabled) return

    val html by produceState<String?>(
        initialValue = null,
        content.book.filePath,
        segmentIndex,
        settings
    ) {
        // Give the visible reader its first frame before background Chromium work begins — once
        // per book, not once per segment. Repeating this wait was seconds of avoidable delay in
        // a long novel.
        if (segmentIndex == 0) delay(500)
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            DocumentCache.segmentHtml(File(content.book.filePath), segmentIndex)
        }
    }
    val reported = remember(segmentIndex, html) { mutableStateOf(false) }
    var latestPages by remember(segmentIndex, html) { mutableIntStateOf(0) }
    var latestLayoutKey by remember(segmentIndex, html) { mutableStateOf<String?>(null) }
    LaunchedEffect(segmentIndex, latestPages, latestLayoutKey) {
        if (reported.value || latestPages <= 0 || latestLayoutKey == null) return@LaunchedEffect
        // Text-only pieces settle almost immediately. Illustrated pieces get longer because an
        // image can still change a line break after the first layout event.
        delay(if (html?.contains("<img", ignoreCase = true) == true) 600 else 120)
        if (!reported.value) {
            reported.value = true
            onCount(segmentIndex, latestPages, latestLayoutKey!!)
        }
    }
    html?.let { segmentHtml ->
        PagedHtmlReader(
            html = segmentHtml,
            settings = settings,
            sourcePath = content.book.filePath,
            modifier = Modifier.graphicsLayer(alpha = 0.001f),
            backgroundMeasure = true,
            annotations = emptyList(),
            onToggleChrome = {},
            onWordSelected = {},
            onBoundary = {},
            onPageChange = { _, pages, _, width, height ->
                // Initial layout and font loading can both report. One result per segment is
                // enough; a later settings change recreates this composable with a new key.
                if (!reported.value && pages > 0) {
                    latestPages = pages
                    latestLayoutKey = pageLayoutKey(settings, width, height)
                }
            },
            onWebViewReady = {}
        )
    }
}

/** Background pagination never competes with the reader while the app is inactive or the battery is low. */
@Composable
private fun rememberPageCounterAllowed(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event == Lifecycle.Event.ON_RESUME ||
                (event != Lifecycle.Event.ON_PAUSE && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val battery = remember(context) {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return resumed && (charging || level * 100 / scale >= 15)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PagedHtmlReader(
    html: String,
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    /** The hidden page counter needs layout, but never a curl snapshot or gesture preparation. */
    backgroundMeasure: Boolean = false,
    /** The book's own file, which its pictures are served out of. See [BookAssetServer]. */
    sourcePath: String? = null,
    savedProgress: Float = 0f,
    savedPosition: com.lexiread.app.data.model.ReadingPosition? = null,
    segmentIndex: Int = 0,
    onPositionChange: (com.lexiread.app.data.model.ReadingPosition) -> Unit = {},
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    annotations: List<com.lexiread.app.data.model.Annotation> = emptyList(),
    onToggleChrome: () -> Unit,
    onWordSelected: (String) -> Unit,
    onSpeakRequested: (String) -> Unit = {},
    onHighlight: (String, String, String) -> Unit = { _, _, _ -> },
    onNoteRequested: (String, String, String) -> Unit = { _, _, _ -> },
    onAnnotationClick: (Long) -> Unit = {},
    onBoundary: (Int) -> Unit = {},
    /** Page showing, pages in all, and how far into the book that is as a percentage of its
     *  characters — see the JS bridge's `onPageChange` for why the last one is the real answer. */
    onPageChange: (Int, Int, Float, Int, Int) -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    // Live reading position, deliberately not snapshot state: it changes on every page
    // turn and nothing on screen is derived from it, so observing it would recompose the
    // reader constantly. It exists for the one moment it's needed — a rebuild, where it
    // is the difference between resuming and being thrown back to where the book opened.
    val progressRef = remember(html, segmentIndex) { floatArrayOf(savedProgress) }
    val positionRef = remember(html, segmentIndex) { arrayOf(savedPosition) }
    val liveProgress = rememberUpdatedState(progressRef)
    val livePosition = rememberUpdatedState(positionRef)
    val liveSegment = rememberUpdatedState(segmentIndex)
    val positionChangeCallback = rememberUpdatedState(onPositionChange)

    // One per reader, not per document: it holds what the WebView is currently allowed to fetch,
    // and a fresh one would leave the page it is showing unable to answer its own requests.
    val context = LocalContext.current
    val assetServer = remember(context) { BookAssetServer(context) }
    // It holds the book's archive open between requests; the book being closed is when that
    // stops being something to keep and starts being a handle on a file nobody is reading.
    DisposableEffect(assetServer) { onDispose { assetServer.release() } }

    // Only the document's structure is a reason to rebuild: the text itself, and the
    // paging mode, which decides which script the page runs. Font, size, spacing,
    // margins and colour are pushed into the live page instead.
    //
    // Built away from the main thread, and handed on as bytes. A book is a string the size of the
    // book, and wrapping it in the reader's stylesheet copies it once more, and encoding it for
    // the WebView copies it a third time. Done here, in the composition, that is a whole second
    // of a frozen app on a long book — and it was being done twice over, since the server encoded
    // the string again on the way past.
    val documentBytes by produceState<Pair<ByteArray, String>?>(
        null, html, settings.horizontalScrolling, savedProgress, hasPrevious, hasNext, segmentIndex
    ) {
        val style = settings
        val from  = if (progressRef[0] > 0f) progressRef[0] else savedProgress
        val fromPosition = positionRef[0]
        value = withContext(kotlinx.coroutines.Dispatchers.Default) {
            val began = android.os.SystemClock.uptimeMillis()
            val bytes = buildPagedHtml(
                html, style, from, hasPrevious = hasPrevious, hasNext = hasNext,
                savedPosition = fromPosition, segmentIndex = segmentIndex
            ).toByteArray(Charsets.UTF_8)
            android.util.Log.d(
                "ShelestTiming",
                "build: ${android.os.SystemClock.uptimeMillis() - began} ms, " +
                    "${html.length} chars in, ${bytes.size} bytes out"
            )
            bytes to readerStyleJson(style)
        }
    }

    // Shown for the moment the real one is being assembled: an empty page in the colour of the
    // book, so that a long book opens on the paper rather than on a white flash. It has no script
    // in it on purpose — nothing must report a page number for a document that is not the book,
    // because that number is written down as the reader's place in it.
    val placeholder = remember(settings.readingMode) {
        ("<!doctype html><html><body style=\"margin:0;background:" +
            readerBgCss(settings.readingMode) + "\"></body></html>").toByteArray(Charsets.UTF_8)
    }
    val document = documentBytes?.first ?: placeholder

    val styleJson = remember(settings) { readerStyleJson(settings) }
    val documentStyleJson = documentBytes?.second ?: styleJson

    val lastLoadedHtml = remember { mutableStateOf<ByteArray?>(null) }
    val lastAppliedStyle = remember { mutableStateOf<String?>(null) }
    val pageReady = remember { mutableStateOf(false) }
    val lastAppliedAnnotations = remember { mutableStateOf<List<com.lexiread.app.data.model.Annotation>?>(null) }
    val annotationClickCallback = onAnnotationClick
    val pageChangeCallback = rememberUpdatedState(onPageChange)
    val toggleChromeCallback = rememberUpdatedState(onToggleChrome)
    val boundaryCallback = rememberUpdatedState(onBoundary)

    // ── The native page turn ──────────────────────────────────────────────────────
    // The WebView keeps the gesture; the fold itself is drawn over it from a snapshot.
    // See PageCurl.kt for why none of this can live inside the document.
    val scope = rememberCoroutineScope()
    val curl = remember(scope) { CurlController(scope) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    // Where the WebView sits inside the full-screen fold canvas — the two insets.
    val contentOffset = remember { mutableStateOf(Offset.Zero) }
    val settingsState = rememberUpdatedState(settings)
    // Single-element holders rather than state: the JS bridge writes them from its own
    // thread and nothing on screen is derived from them, so making them observable would
    // recompose the reader for no reason.
    //
    // Two pictures, because a turn folds a different sheet each way: going forward it is
    // the page you are on, going back it is the one before it. The page being uncovered
    // needs no picture — that one is the WebView, which is where it already lives.
    val picPrev = remember { arrayOfNulls<Bitmap>(1) }
    val picCur = remember { arrayOfNulls<Bitmap>(1) }
    val prevPageOf = remember { intArrayOf(-1) }    // page picPrev holds, -1 if none
    val curPageOf = remember { intArrayOf(-1) }
    val tapPending = remember { booleanArrayOf(false) }
    val currentPageRef = remember { intArrayOf(0) }
    val prepJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    // JS reports CSS pixels; this canvas draws in device pixels. On a 3× screen that is
    // the difference between a fold that crosses the page and one that crosses a third
    // of it, grabbed near the top. Derived from the two widths rather than assumed from
    // the display density, so it stays right under zoom and odd viewport settings.
    val cssScale = remember { floatArrayOf(1f) }

    /**
     * Photographs the page showing and the one before it.
     *
     * The first is simply the screen. The second is not: turning back folds the previous
     * page, and a picture can only be taken of what is on screen — which the previous
     * page, by definition, is not. So the document is jumped there and straight back
     * behind a still of the screen, a stretch of frames in which every pixel is identical
     * to the one it replaced. This runs a fifth of a second after a page has settled and
     * nobody is touching anything, so by the time a finger goes down there is nothing
     * left to do.
     */
    // A local function rather than a remembered lambda: this has to suspend, and a lambda
    // literal is not converted to a suspend function type by the inference that runs
    // through remember's type parameter. Everything it closes over is remembered, so the
    // instance being rebuilt each recomposition costs nothing and captures nothing stale.
    suspend fun ensureSheets(page: Int): Boolean {
        val wv = webViewRef.value ?: return false
        val argb = settingsState.value.readingMode.bgArgb.toInt()
        // Whichever of the two survived the last turn is kept. After a turn that landed,
        // one of them always has.
        val cur = if (curPageOf[0] == page && picCur[0] != null) {
            picCur[0]
        } else {
            captureSheet(wv, argb, picCur[0])?.also {
                picCur[0] = it
                curPageOf[0] = page
            }
        }
        if (cur == null) { curPageOf[0] = -1; return false }
        if (page <= 0) { prevPageOf[0] = -1; return false }
        if (prevPageOf[0] == page - 1 && picPrev[0] != null) return true
        // From here the document gets moved, so this must finish and put it back even if
        // the reader touches the screen halfway through.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            curl.showCover(cur.asImageBitmap())
            withFrameNanos { }
            withFrameNanos { }
            // The WebView paints on its own schedule, and a picture taken before it has
            // caught up is a picture of the page we were already on — a fold showing the
            // wrong text, which is worse than no fold. Nothing is visible behind the
            // cover, so the frames are free.
            wv.evaluateJavascript("curlReveal(${page - 1})", null)
            repeat(4) { withFrameNanos { } }
            val prev = captureSheet(wv, argb, picPrev[0])
            picPrev[0] = prev
            prevPageOf[0] = if (prev != null) page - 1 else -1
            wv.evaluateJavascript("curlReveal($page)", null)
            repeat(4) { withFrameNanos { } }
            curl.hideCover()
            prev != null
        }
    }

    /**
     * @param delayMs how long to sit still first. The usual 120 ms is there so a reader
     *   flipping quickly replaces this before it costs anything; zero is for the cases
     *   that already know a picture is wanted and cannot afford to wait for it.
     */
    fun prepareSheets(page: Int, delayMs: Long) {
        prepJob[0]?.cancel()
        prepJob[0] = scope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            // Only the fold needs pictures. Every other mode is a transform the browser
            // composites on its own, and taking two screenshots and walking the document
            // to the previous page after each of those turns is pure cost — which is what
            // made plain sliding start to stutter.
            if (settingsState.value.pageAnimation.js != "curl") return@launch
            if (curl.live) return@launch
            ensureSheets(page)
        }
    }

    // A page's picture stops being the page the moment anything about it changes.
    val invalidateSheets = remember {
        {
            prevPageOf[0] = -1
            curPageOf[0] = -1
        }
    }

    // Invalidating is only half of it. Throwing the pictures away and waiting for the next
    // idle gesture to replace them leaves the book with nothing to fold in between — so a
    // reader who highlights a sentence and immediately turns back gets a slide, and the fold
    // returns on its own a moment later with no explanation. Re-taken here instead.
    //
    // The delay is not a guess at how long a repaint takes: applyAnnotations goes out through
    // evaluateJavascript, which is a message to another thread, so the wrapping span does not
    // exist yet when this line runs. A picture taken now would be a picture of the page
    // without the highlight — which is exactly the fold that showed the text unmarked.
    LaunchedEffect(styleJson, annotations, document) {
        invalidateSheets()
        prepareSheets(currentPageRef[0], 250L)
    }

    // Switching into the fold mid-book: without this the first turn after the switch has
    // no pictures and quietly slides instead.
    LaunchedEffect(settings.pageAnimation) {
        invalidateSheets()
        if (settings.pageAnimation.js == "curl") prepareSheets(currentPageRef[0], 120L)
    }

    val settleCurl = remember {
        { commit: Boolean ->
            val landing = if (commit) curl.to else curl.from
            val wasForward = curl.forward
            val turnedFrom = curl.from
            val turnedTo = curl.to
            webViewRef.value?.evaluateJavascript("curlLand($landing)", null)
            // A turn that was pulled back moved nothing, so both pictures still describe
            // the pages they did. A turn that landed moved one step, and the sheet that
            // just folded is a perfectly good picture of the page now next door — so the
            // two buffers rotate rather than both being thrown away. Throwing them away
            // is what made a reader who turns straight back get a slide: the fold had
            // nothing to fold until the next round of preparation had run.
            if (commit) {
                val spare = picCur[0]
                picCur[0] = picPrev[0]
                picPrev[0] = spare
                if (wasForward) {
                    prevPageOf[0] = turnedFrom     // the sheet just folded
                    curPageOf[0] = -1              // the page landed on, not yet taken
                } else {
                    curPageOf[0] = turnedTo
                    prevPageOf[0] = -1
                }
            }
            scope.launch {
                // The page underneath needs a frame to paint what it landed on. Taking
                // the overlay away any sooner would show the wrong page for that frame —
                // and both end positions are covered, so waiting costs nothing visible.
                withFrameNanos { }
                withFrameNanos { }
                curl.hide()
            }
            Unit
        }
    }

    // Going forward the page under the fold has to move to the destination before the
    // fold uncovers any of it — but not before this overlay is actually on screen, or the
    // next page shows for a frame and that is the flash. Two frames, not one: the first
    // is the one this overlay gets composed and drawn in.
    //
    // Going back there is nothing to move: the page being uncovered is the one already
    // showing, which is why that direction cannot flash at all.
    LaunchedEffect(curl.session) {
        if (!curl.live) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (curl.forward) {
            webViewRef.value?.evaluateJavascript("curlReveal(${curl.to})", null)
            withFrameNanos { }
        }
        if (tapPending[0]) {
            tapPending[0] = false
            curl.finish(true, settleCurl)
        }
    }

    // Two boxes, not one. The text is kept out from under the status bar and the
    // navigation bar; the fold is not, because the paper it is folding runs to the edge
    // of the screen. Inset along with the text, it left those two strips flat and
    // uncreased while everything between them turned — which is what "the page looks
    // trimmed" was.
    Box(modifier = modifier.fillMaxSize()) {

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .widthIn(max = 640.dp)
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // System insets bound the reading viewport; transient chrome overlays it
            // without changing pagination or reserving empty rows in the book.
            .onGloballyPositioned { contentOffset.value = it.positionInParent() }
    ) {

    AndroidView(
        factory = { ctx ->
            val webView = object : WebView(ctx) {
                override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? {
                    val customCallback = object : android.view.ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
                            callback?.onCreateActionMode(mode, menu)
                            menu.add(0, 9004, 0, "Читать отсюда").setShowAsAction(MenuItem.SHOW_AS_ACTION_WITH_TEXT or MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(0, 9001, 1, "Определить").setShowAsAction(MenuItem.SHOW_AS_ACTION_WITH_TEXT or MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            menu.add(0, 9002, 2, "Выделить").setShowAsAction(MenuItem.SHOW_AS_ACTION_WITH_TEXT or MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            menu.add(0, 9003, 3, "Заметка").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                            return true
                        }
                        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
                            return callback?.onPrepareActionMode(mode, menu) ?: false
                        }
                        override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == 9004) {
                                evaluateJavascript("JSON.stringify(getSpeechContext(true))") { raw ->
                                    val place = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                                    if (place != null && place != "null") onSpeakRequested(place)
                                    mode.finish()
                                }
                                return true
                            }
                            if (item.itemId == 9001) {
                                evaluateJavascript("getSelectedText()") { raw ->
                                    if (!raw.isNullOrBlank()) {
                                        var text = raw
                                        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
                                            try {
                                                text = org.json.JSONTokener(text).nextValue().toString()
                                            } catch (_: Exception) {
                                                text = text.removeSurrounding("\"")
                                            }
                                        }
                                        text = text.replace("\\n", " ")
                                            .replace("\\r", "")
                                            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { m ->
                                                m.groupValues[1].toInt(16).toChar().toString()
                                            }
                                            .trim()
                                        if (text.isNotEmpty() && text != "null") {
                                            onWordSelected(text)
                                        }
                                    }
                                    mode.finish()
                                }
                                return true
                            }
                            if (item.itemId == 9002 || item.itemId == 9003) {
                                evaluateJavascript("getSelectionContext()") { raw ->
                                    val ctx = try {
                                        if (!raw.isNullOrBlank() && raw != "null") org.json.JSONObject(raw) else null
                                    } catch (_: Exception) { null }
                                    val selectedText = ctx?.optString("selectedText").orEmpty().trim()
                                    if (selectedText.isNotEmpty()) {
                                        val before = ctx?.optString("contextBefore").orEmpty()
                                        val after  = ctx?.optString("contextAfter").orEmpty()
                                        if (item.itemId == 9002) onHighlight(selectedText, before, after)
                                        else onNoteRequested(selectedText, before, after)
                                    }
                                    mode.finish()
                                }
                                return true
                            }
                            return callback?.onActionItemClicked(mode, item) ?: false
                        }
                        override fun onDestroyActionMode(mode: android.view.ActionMode) {
                            callback?.onDestroyActionMode(mode)
                        }
                        override fun onGetContentRect(
                            mode: android.view.ActionMode?,
                            view: android.view.View?,
                            outRect: android.graphics.Rect?
                        ) {
                            if (callback is android.view.ActionMode.Callback2) {
                                callback.onGetContentRect(mode, view, outRect)
                            } else {
                                super.onGetContentRect(mode, view, outRect)
                            }
                        }
                    }
                    return super.startActionMode(customCallback, android.view.ActionMode.TYPE_FLOATING)
                }

                override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
                    return this.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
                }
            }
            webView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val ws = this.settings
                ws.javaScriptEnabled  = true
                ws.domStorageEnabled  = true
                ws.builtInZoomControls = false
                ws.displayZoomControls = false
                ws.textZoom           = 100
                // The book and its typefaces both arrive through BookAssetServer now, so the
                // document has no reason to reach into the filesystem — and a book is a file
                // from the internet, so it should not be able to.
                ws.allowFileAccess    = false
                ws.allowContentAccess = false
                ws.cacheMode          = android.webkit.WebSettings.LOAD_NO_CACHE
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val bridgeWebView = this
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                addJavascriptInterface(object {
                    /**
                     * [percent] is how far into the book the page is, counted in characters — the
                     * page number beside it is only what to draw at the bottom of the screen and
                     * which sheet to photograph for the fold, and means nothing once the type
                     * size changes.
                     *
                     * A negative [percent] is not a position at all but the absence of one: the
                     * page has turned and has not yet been asked where in the book it landed,
                     * because asking costs a search of the whole document and a turn is the one
                     * moment that must be free. The page number answers until the real one
                     * arrives, and what was last known is kept rather than overwritten with a
                     * zero that would send a reader back to the beginning of the book.
                     */
                    @JavascriptInterface
                    fun onPageChange(current: Int, total: Int, percent: Double, segment: Int) {
                        mainHandler.post {
                            if (segment != liveSegment.value) return@post
                            if (percent >= 0) liveProgress.value[0] = percent.toFloat().coerceIn(0f, 100f)
                            currentPageRef[0] = current
                            pageChangeCallback.value(
                                current, total, liveProgress.value[0], bridgeWebView.width, bridgeWebView.height
                            )
                            // Whatever moved the page — a turn, a search hit, a restyle —
                            // the pictures are now of the wrong pages. Take new ones once
                            // the reader has stopped moving about.
                            if (!backgroundMeasure) prepareSheets(current, 120L)
                        }
                    }
                    @JavascriptInterface
                    fun onPositionChange(segment: Int, page: Int, character: Int, layout: String, scrollY: Double, anchor: String) {
                        if (backgroundMeasure) return
                        mainHandler.post {
                            if (segment != liveSegment.value) return@post
                            val position = com.lexiread.app.data.model.ReadingPosition(
                                segment, page, character.takeIf { it >= 0 }, layout, scrollY, anchor
                            )
                            livePosition.value[0] = position
                            positionChangeCallback.value(position)
                        }
                    }
                    /**
                     * How long a piece of the opening took, in milliseconds, written to the log.
                     *
                     * Here because a slow book is slow for one of several unrelated reasons —
                     * laying the flow out, walking it for the index, finding the saved place —
                     * and guessing which of them it is has already cost one rewrite. Nothing
                     * reads these but a person with logcat open.
                     */
                    @JavascriptInterface
                    fun onTiming(what: String, ms: Double) {
                        android.util.Log.d("ShelestTiming", "page/$what: ${ms.toLong()} ms")
                    }
                    @JavascriptInterface
                    fun onToggleChrome() {
                        mainHandler.post { toggleChromeCallback.value() }
                    }
                    @JavascriptInterface
                    fun onBoundary(direction: Int) {
                        mainHandler.post { boundaryCallback.value(direction) }
                    }
                    @JavascriptInterface
                    fun onAnnotationClick(id: String) {
                        val longId = id.toLongOrNull() ?: return
                        mainHandler.post { annotationClickCallback(longId) }
                    }

                    /**
                     * Finger down, nothing turned yet.
                     *
                     * This used to cancel whatever preparation was running, on the
                     * reasoning that a finger on the screen wants the main thread. That
                     * was exactly backwards, and it is what made a quick second turn
                     * backwards slide instead of fold: a turn backwards leaves the picture
                     * of the page before it untaken, preparation starts to take it, and
                     * the very touch that is about to need it killed it during its wait.
                     *
                     * So the opposite. If the backward picture is missing, this is the
                     * best possible moment to take it — the finger is down and has not
                     * moved, so the frames it costs cannot be seen. If it is already
                     * there, which is the usual case, this does nothing at all.
                     */
                    @JavascriptInterface
                    fun onCurlArm(page: Int) {
                        if (prevPageOf[0] == page - 1 && picPrev[0] != null) return
                        mainHandler.post { prepareSheets(page, 0L) }
                    }

                    /** The finger came and went without turning anything. */
                    @JavascriptInterface
                    fun onCurlIdle(page: Int) {
                        mainHandler.post { prepareSheets(page, 120L) }
                    }

                    /**
                     * Which sheet folds depends on the direction. Forward it is the page
                     * being left; back it is the page being returned to, which is why
                     * that one has to have been photographed in advance.
                     */
                    @JavascriptInterface
                    fun onCurlBegin(from: Int, to: Int, grabY: Double, pageW: Double, tap: Boolean) {
                        mainHandler.post {
                            val wv = webViewRef.value ?: return@post
                            val start = {
                                // Forward the sheet is this page; back it is the previous
                                // page coming down over this one.
                                val fold = if (to > from) {
                                    if (curPageOf[0] == from) picCur[0] else captureSheet(
                                        wv, settingsState.value.readingMode.bgArgb.toInt(), picCur[0]
                                    )?.also { picCur[0] = it; curPageOf[0] = from }
                                } else {
                                    if (prevPageOf[0] == to) picPrev[0] else null
                                }
                                if (fold == null) {
                                    // Pictures not ready — a jump straight into the
                                    // middle of the book, say. Turn the page the plain
                                    // way rather than leave the reader holding a dead
                                    // gesture or snapping it across without warning.
                                    wv.evaluateJavascript("curlFallback($to)", null)
                                } else {
                                    cssScale[0] =
                                        if (pageW > 1.0 && wv.width > 0) wv.width / pageW.toFloat() else 1f
                                    tapPending[0] = tap
                                    curl.begin(
                                        fold.asImageBitmap(),
                                        wv.width.toFloat(),
                                        grabY.toFloat() * cssScale[0],
                                        from, to
                                    )
                                }
                            }
                            // Straight through in the normal case. Waiting on a coroutine
                            // here would cost a frame before the paper moved, which is
                            // exactly the delay this is supposed to have got rid of.
                            val pending = prepJob[0]
                            val ready = to > from || (prevPageOf[0] == to && picPrev[0] != null)
                            if (ready && (pending == null || !pending.isActive)) {
                                start()
                            } else {
                                scope.launch {
                                    // Cancel first, then wait: preparation that has not
                                    // started yet drops out at once, and preparation that
                                    // has is past the point where it can be dropped, so
                                    // waiting for it is waiting for the document to be put
                                    // back where it belongs.
                                    pending?.cancel()
                                    pending?.join()
                                    // Still nothing to fold with. Take the picture now
                                    // rather than give up: a turn that starts a tenth of a
                                    // second late is a turn the reader waited for, while a
                                    // turn that slides instead is the wrong animation, and
                                    // that is what they actually notice. This is the case
                                    // of two quick turns backwards — the second needs the
                                    // page before the one the first just landed on, and
                                    // nothing has had a quiet moment in which to take it.
                                    if (to < from && prevPageOf[0] != to) ensureSheets(from)
                                    start()
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onCurlMove(x: Double, y: Double) {
                        val s = cssScale[0]
                        mainHandler.post { curl.move(x.toFloat() * s, y.toFloat() * s) }
                    }

                    @JavascriptInterface
                    fun onCurlEnd() {
                        mainHandler.post { curl.release(settleCurl) }
                    }

                    /** The page changed under us — a search hit, a jump, a mode switch. */
                    @JavascriptInterface
                    fun onCurlAbort() {
                        mainHandler.post {
                            tapPending[0] = false
                            invalidateSheets()
                            curl.hide()
                        }
                    }
                }, "Shelest")

                webViewClient = object : WebViewClient() {
                    /** Nothing in a book may navigate: the document is the whole book already. */
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ) = request?.url?.toString()?.let { !assetServer.handles(it) } ?: true

                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? =
                        assetServer.intercept(request?.url?.toString().orEmpty())

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // A nudge, not the trigger. The document decides for itself when it can
                        // be measured — the reading font has to have arrived, and for a book
                        // whose pictures do not state their size, the pictures too. Calling
                        // initPaging outright from here measured the book against a fallback
                        // serif and then measured it all over again.
                        view?.evaluateJavascript("if (window.startPaging) startPaging();", null)
                        pageReady.value = true
                    }
                }

                loadUrl(assetServer.publish(document, sourcePath))
                lastLoadedHtml.value = document
                lastAppliedStyle.value = documentStyleJson
                webViewRef.value = this
                onWebViewReady(this)
            }
        },
        update = { webView ->
            if (lastLoadedHtml.value != document) {
                pageReady.value = false
                lastAppliedAnnotations.value = null
                webView.loadUrl(assetServer.publish(document, sourcePath))
                lastLoadedHtml.value = document
                // The fresh document already carries these values in its :root block.
                lastAppliedStyle.value = documentStyleJson
            } else if (pageReady.value) {
                if (lastAppliedStyle.value != styleJson) {
                    webView.evaluateJavascript("applyStyle($styleJson)", null)
                    lastAppliedStyle.value = styleJson
                }
                if (lastAppliedAnnotations.value != annotations) {
                    webView.evaluateJavascript("applyAnnotations(${annotationsToJs(annotations)})", null)
                    lastAppliedAnnotations.value = annotations
                }
            }
            webViewRef.value = webView
            onWebViewReady(webView)
        },
        modifier = Modifier.fillMaxSize()
    )

    }

        if (curl.live) {
            PageCurlOverlay(
                curl = curl,
                pageColor = Color(settings.readingMode.bgArgb),
                contentOffset = contentOffset.value,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A picture of the page as it is on screen right now.
 *
 * The WebView is transparent so the reading background can sit behind it, which is fine
 * on screen and wrong in a snapshot: the folding sheet would be see-through and the page
 * being uncovered would read straight through the text lying on top of it. So the paper
 * is laid down first and the page drawn onto it.
 *
 * One bitmap is kept and redrawn rather than allocated per turn — at full screen it is
 * some ten megabytes, and churning that every page turn would hand the garbage collector
 * a reason to pause exactly where a pause is most visible.
 */
private fun captureSheet(webView: WebView, pageArgb: Int, reuse: Bitmap?): Bitmap? {
    val w = webView.width
    val h = webView.height
    if (w <= 0 || h <= 0) return null
    val bmp = if (reuse != null && !reuse.isRecycled && reuse.width == w && reuse.height == h) {
        reuse
    } else {
        try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
    }
    bmp.eraseColor(pageArgb)
    webView.draw(android.graphics.Canvas(bmp))
    return bmp
}

private fun annotationsToJs(annotations: List<com.lexiread.app.data.model.Annotation>): String {
    val arr = org.json.JSONArray()
    annotations.forEach { a ->
        if (a.type == com.lexiread.app.data.model.AnnotationType.DRAWING) return@forEach
        val obj = org.json.JSONObject()
        obj.put("id", a.id)
        obj.put("type", a.type.name)
        obj.put("color", a.color)
        obj.put("selectedText", a.selectedText ?: "")
        obj.put("contextBefore", a.contextBefore ?: "")
        obj.put("contextAfter", a.contextAfter ?: "")
        arr.put(obj)
    }
    return arr.toString()
}

/** CSS font stack for a reading face, with a sane fallback while its file loads. */
private fun readerFontCss(family: ReaderFontFamily): String = when (family) {
    ReaderFontFamily.MONO -> "monospace"
    ReaderFontFamily.SANS, ReaderFontFamily.GOLOS, ReaderFontFamily.INTER ->
        "'${family.cssName}', -apple-system, 'Helvetica Neue', sans-serif"
    else -> "'${family.cssName}', Georgia, 'Times New Roman', serif"
}

/**
 * The tunable half of the page's styling, in the shape applyStyle() expects. Kept next
 * to buildPagedHtml so the initial `:root` block and every later update can't drift
 * apart — both read from here.
 */
private fun readerStyleJson(settings: ReaderSettings): String {
    val mode = settings.readingMode
    return org.json.JSONObject().apply {
        put("font",   readerFontCss(settings.fontFamily))
        put("fontLoad", if (settings.fontFamily == ReaderFontFamily.MONO) "monospace"
                        else "'${settings.fontFamily.cssName}'")
        put("fs",     "${settings.fontSize.toInt()}px")
        put("lh",     settings.lineHeightMultiplier.toString())
        put("mx",     "${settings.marginDp}px")
        put("bg",     readerBgCss(mode))
        put("fg",     readerFgCss(mode))
        put("accent", readerAccentCss(mode))
        put("sel",    readerSelectionCss(mode))
        put("align",  if (settings.justifyText) "justify" else "left")
        put("anim",   settings.pageAnimation.js)
    }.toString()
}

/** Everything that changes line breaks belongs in the cached page-count identity. */
private fun pageLayoutKey(settings: ReaderSettings, viewportWidth: Int, viewportHeight: Int): String = listOf(
    if (settings.horizontalScrolling) "2" else "1", viewportWidth, viewportHeight,
    settings.fontSize, settings.fontFamily.name, settings.lineHeightMultiplier,
    settings.marginDp, settings.justifyText, settings.horizontalScrolling
).joinToString("|")

/** `0xFF1A1A1A` → `#1A1A1A`; the alpha byte is dropped, CSS here is always opaque. */
private fun Long.toCssHex(): String = "#%06X".format(this and 0xFFFFFF)

private fun readerBgCss(mode: ReadingMode) = mode.bgArgb.toCssHex()
private fun readerFgCss(mode: ReadingMode) = mode.fgArgb.toCssHex()

// The accent has to sit on the page, not on the app's surfaces: the darker brand red
// disappears into a black page, the lighter one washes out on paper.
private fun readerAccentCss(mode: ReadingMode) = if (mode.isDarkPage) "#CB7C6E" else "#A8493D"

private fun readerSelectionCss(mode: ReadingMode) =
    if (mode.isDarkPage) "rgba(203,124,110,0.25)" else "rgba(168,73,61,0.21)"

/**
 * The document the reader is given: the book, wrapped in the reading stylesheet and the scripts
 * that page it.
 *
 * For a cached long book [content] is one bounded segment, never a growing document assembled in
 * instalments. Appending every segment made the browser repeat layout and renumber the page after
 * each append; replacing the window at a boundary keeps both the DOM and each layout pass small.
 * Search reads the separate plain-text cache, while the voice and contents follow the segment
 * switch, so none of them forces the whole HTML document back into memory.
 */
internal fun buildPagedHtml(
    content: String,
    settings: ReaderSettings,
    savedProgress: Float = 0f,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    savedPosition: com.lexiread.app.data.model.ReadingPosition? = null,
    segmentIndex: Int = 0
): String {
    val positionJson = savedPosition?.let {
        org.json.JSONObject().put("page", it.page).put("character", it.character ?: -1)
            .put("layout", it.layout).put("scrollY", it.scrollY).toString()
    } ?: "null"
    // The family the page will actually be set in, named the way document.fonts.load wants it.
    // Kept in step with "fontLoad" in readerStyleJson, which asks for the same face on a restyle.
    val fontLoad = if (settings.fontFamily == ReaderFontFamily.MONO) "monospace"
                   else "'${settings.fontFamily.cssName}'"

    // Every bundled face is declared upfront, not just the selected one: a @font-face
    // block costs nothing until something actually uses the family, and having them all
    // present is what lets a font switch be a one-property change instead of a reload.
    val fontFaceCss = ReaderFontFamily.entries.mapNotNull { f ->
        val asset = f.asset ?: return@mapNotNull null
        // Served by BookAssetServer, not read off the filesystem: the document has a real
        // origin now, and a real origin may not open a file:// URL.
        val base = "/font/"
        if (f.boldAsset != null) {
            "@font-face{font-family:'${f.cssName}';src:url('$base$asset');font-weight:400;}\n" +
            "@font-face{font-family:'${f.cssName}';src:url('$base${f.boldAsset}');font-weight:700;}"
        } else {
            // A variable font: one file covers the whole axis, so claim the whole range
            // rather than letting the browser fake bold from the 400 instance.
            "@font-face{font-family:'${f.cssName}';src:url('$base$asset');font-weight:100 900;}"
        }
    }.joinToString("\n")

    val fontFamilyCss = readerFontCss(settings.fontFamily)

    return com.shelest.shared.reader.buildReaderHtml(
        content = content,
        style = com.shelest.shared.reader.ReaderDocumentStyle(
            fontCss = fontFamilyCss, fontLoad = fontLoad, fontSize = settings.fontSize.toInt(),
            lineHeight = settings.lineHeightMultiplier.toString(), margin = settings.marginDp.toString(),
            background = readerBgCss(settings.readingMode), foreground = readerFgCss(settings.readingMode),
            accent = readerAccentCss(settings.readingMode), selection = readerSelectionCss(settings.readingMode),
            align = if (settings.justifyText) "justify" else "left", animation = settings.pageAnimation.js,
            json = readerStyleJson(settings), fontFaceCss = fontFaceCss
        ),
        savedProgress = savedProgress.toDouble(), hasPrevious = hasPrevious, hasNext = hasNext,
        position = positionJson, segmentIndex = segmentIndex, isHorizontal = settings.horizontalScrolling
    )
}

// ── PDF Reader ────────────────────────────────────────────────────────────────

/** As far as a PDF page may be magnified. Past this the render cap makes it blurry anyway. */
private const val MAX_PDF_ZOOM = 4f

/**
 * Where a double tap lands. Modest on purpose: magnifying crops the page at the sides, so this
 * is set where a typical page has given up its margins and not yet any of its text.
 */
private const val DOUBLE_TAP_PDF_ZOOM = 1.5f

/**
 * Repaints a rendered page in the reading palette: black ink takes the theme's text colour,
 * white paper its background, and every grey between slides along the line joining the two.
 *
 * A PDF page arrives as a picture with its paper baked in, so there is nothing to restyle the
 * way the HTML reader restyles a chapter. What there is, is a fixed relationship — dark marks
 * on a light field — and that survives being mapped onto a different pair of colours. A white
 * page glaring out of a black frame at night was the last thing the themes did not reach.
 *
 * The cost is colour: a photograph comes out in two tones like everything else, because the
 * map runs off luminance alone. So the white palette is left strictly alone rather than sent
 * through an identity matrix — a document is shown exactly as printed unless the reader has
 * actually asked for a different paper.
 */
private fun pdfPaletteFilter(mode: ReadingMode): ColorFilter? {
    if (mode == ReadingMode.LIGHT) return null
    fun channel(argb: Long, shift: Int) = ((argb shr shift) and 0xFF).toFloat()
    // out = fg + luma(in) / 255 * (bg - fg), written as one row per channel. The last column
    // of a colour matrix is an addend on the 0..255 scale, which is why fg goes there raw.
    fun row(bg: Float, fg: Float): FloatArray {
        val k = (bg - fg) / 255f
        return floatArrayOf(0.299f * k, 0.587f * k, 0.114f * k, 0f, fg)
    }
    val values = row(channel(mode.bgArgb, 16), channel(mode.fgArgb, 16)) +
                 row(channel(mode.bgArgb, 8),  channel(mode.fgArgb, 8))  +
                 row(channel(mode.bgArgb, 0),  channel(mode.fgArgb, 0))  +
                 floatArrayOf(0f, 0f, 0f, 1f, 0f)
    return ColorFilter.colorMatrix(ColorMatrix(values))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PdfReader(
    filePath: String,
    pageCount: Int,
    savedProgress: Float = 0f,
    savedPage: Int? = null,
    annotations: List<Annotation>,
    mode: PdfAnnotationMode,
    activeColor: Int,
    penWidthDp: Float,
    readingMode: ReadingMode,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    verticalPaging: Boolean,
    jumpTarget: Int?,
    onJumpConsumed: () -> Unit,
    onToggle: () -> Unit,
    onPageChange: (Int, Int) -> Unit,
    onNotePlaced: (Int, Float, Float) -> Unit,
    onDrawingComplete: (Int, String, Int) -> Unit,
    onDrawingErased: (Annotation) -> Unit,
    onDrawingPartsErased: (List<Pair<Annotation, List<String>>>) -> Unit,
    onAnnotationClick: (Long) -> Unit,
    textLayer: PdfTextLayer? = null,
    onWordSelected: (String) -> Unit = {},
    onSpeakRequested: (String) -> Unit = {},
    onSelectionVisible: () -> Unit = {}
) {
    val pdfParser = remember(filePath) { PdfParser() }
    var selectedText by remember(filePath) { mutableStateOf("") }
    var selectedSpeechContext by remember(filePath) { mutableStateOf("") }
    var selectionDragging by remember(filePath) { mutableStateOf(false) }
    var selectionReset by remember(filePath) { mutableStateOf(0) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(mode) { selectedText = ""; selectionDragging = false; selectionReset++ }
    DisposableEffect(pdfParser) { onDispose { pdfParser.close() } }
    // Restored synchronously as the pager's starting page (rather than scrolled to after the
    // fact) so the very first page-change report already carries the right page — otherwise a
    // fresh pager briefly reports page 0 and clobbers the saved reading position.
    val initialPage = remember(pageCount) {
        if (savedPage != null) savedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        else if (savedProgress > 0f && pageCount > 1) {
            ((savedProgress / 100f) * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
        } else 0
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })

    // One magnification for the whole document, not one per page — and, since it comes from
    // the settings, for the next document too. Set it once for a book of small print and
    // every page opens that way. Where you are *within* the page is per page, because that
    // genuinely differs. Held here rather than inside the page item because the pager has to
    // know: a magnified page owns the horizontal drag, and the pager must not answer the same
    // swipe by turning to the next page.
    //
    // The local copy follows the fingers; the setting is written once they lift. A pinch
    // produces a new value every frame and DataStore is a file — saving each one would put
    // sixty writes a second behind a gesture.
    var scale by remember(filePath) { mutableStateOf(zoom) }
    LaunchedEffect(zoom) { if (abs(zoom - scale) > 0.001f) scale = zoom }
    val pageOffsets = remember(filePath) { mutableStateMapOf<Int, Offset>() }

    // The gesture handler is remembered across recompositions — it must not be restarted when
    // the saved zoom changes, or a pinch would be cancelled halfway by its own first write.
    // So it reads these instead of closing over the parameters directly.
    val savedZoom by rememberUpdatedState(zoom)
    val commitZoom by rememberUpdatedState(onZoomChange)

    val paletteFilter = remember(readingMode) { pdfPaletteFilter(readingMode) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { cur ->
            selectedText = ""; selectionDragging = false; selectionReset++
            onPageChange(cur, pageCount)
        }
    }

    LaunchedEffect(jumpTarget) {
        jumpTarget?.let { target ->
            pagerState.animateScrollToPage(target.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            onJumpConsumed()
        }
    }

    // Same insets as the HTML reader: the system bars are transparent, so without this
    // a page bitmap would slide under the clock and the gesture bar.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // One page per swipe, like turning a physical page, instead of a continuous scroll.
        // Written once and handed to whichever pager the reader asked for: the two differ only
        // in which axis turns the page, and duplicating a hundred lines of gesture handling to
        // say so is how the two copies start drifting apart.
        val pageContent: @Composable (Int) -> Unit = { idx ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // The page is drawn larger than the viewport once magnified; without this
                    // it would spill over the neighbouring pages in the pager.
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                val viewportW = constraints.maxWidth.toFloat()
                val viewportH = constraints.maxHeight.toFloat()
                // The page's height at fit-to-width, measured rather than assumed: PDF pages
                // are not all A4, and a landscape scan is shorter than the screen.
                var contentH by remember(idx) { mutableStateOf(0f) }

                // How far the page may be pushed before its edge would come inside the
                // viewport. Panning past that leaves a strip of background and a page you
                // then have to hunt back for, so it is simply not allowed.
                fun clamp(o: Offset, s: Float): Offset {
                    val maxX = ((viewportW * s - viewportW) / 2f).coerceAtLeast(0f)
                    val maxY = ((contentH * s - viewportH) / 2f).coerceAtLeast(0f)
                    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                }

                // Clamped on the way out rather than trusted: the magnification is shared by
                // the whole document, so a position saved for this page at one zoom can be out
                // of bounds by the time the reader comes back to it at another.
                val offset = clamp(pageOffsets[idx] ?: Offset.Zero, scale)

                // A page taller than the screen opens at its top, not its middle — the layout
                // centres it, so the offset has to undo that. Only once, and only while the
                // reader has not moved it themselves.
                LaunchedEffect(contentH, viewportH) {
                    if (contentH > 0f && pageOffsets[idx] == null) {
                        val top = ((contentH - viewportH) / 2f).coerceAtLeast(0f)
                        if (top > 0f) pageOffsets[idx] = Offset(0f, top)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Written by hand rather than with detectTransformGestures, which
                        // swallows every drag past touch slop — that would leave the pager
                        // unable to turn pages at all. The division here: a pinch is the
                        // page's, a drag inside a magnified page pans it, and a new swipe
                        // at its edge is the pager's. Which of the
                        // two a drag is gets decided from the accumulated direction rather
                        // than per event, so the wobble in a swipe cannot read as a pan.
                        .pointerInput(idx, mode) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var accum = Offset.Zero
                                var ours: Boolean? = null
                                do {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val pressed = event.changes.count { it.pressed }
                                    if (selectionDragging) continue
                                    val liveScale = scale
                                    val liveOffset = clamp(pageOffsets[idx] ?: Offset.Zero, liveScale)
                                    if (pressed >= 2) {
                                        ours = true
                                        val zoom = event.calculateZoom()
                                        val pan  = event.calculatePan()
                                        val next = (liveScale * zoom).coerceIn(1f, MAX_PDF_ZOOM)
                                        // The offset grows with the page, so what was under the
                                        // fingers stays under them instead of sliding away.
                                        val grown = liveOffset * (next / liveScale)
                                        scale = next
                                        pageOffsets[idx] = clamp(grown + pan, next)
                                        event.changes.forEach { it.consume() }
                                    } else if (pressed == 1 && mode == PdfAnnotationMode.NONE) {
                                        val pan = event.calculatePan()
                                        accum += pan
                                        if (ours == null && accum.getDistance() > slop) {
                                            // One finger pans a magnified page. At the edge,
                                            // the next gesture belongs to the pager.
                                            val horizontal = abs(accum.x) > abs(accum.y)
                                            ours = when {
                                                horizontal -> {
                                                    val maxX = ((viewportW * liveScale - viewportW) / 2f).coerceAtLeast(0f)
                                                    liveScale > 1.01f && if (accum.x > 0f) liveOffset.x < maxX - 0.5f
                                                        else liveOffset.x > -maxX + 0.5f
                                                }
                                                // Sideways paging: the vertical drag is the
                                                // page's whenever it is taller than the screen,
                                                // since nothing else wants that axis.
                                                !verticalPaging ->
                                                    contentH * liveScale - viewportH > 1f
                                                // Downward paging: now the pager wants it too,
                                                // so the page keeps the drag only while it has
                                                // room left in the direction of travel and
                                                // hands it over at the edge — the same rule the
                                                // sideways case gets for free from the crop.
                                                else -> {
                                                    val maxY = ((contentH * liveScale - viewportH) / 2f)
                                                        .coerceAtLeast(0f)
                                                    if (accum.y > 0f) liveOffset.y < maxY - 0.5f
                                                    else              liveOffset.y > -maxY + 0.5f
                                                }
                                            }
                                        }
                                        if (ours == true) {
                                            pageOffsets[idx] = clamp(
                                                liveOffset + pan, liveScale
                                            )
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                // Fingers up: the magnification the reader settled on is the
                                // one worth keeping.
                                if (abs(scale - savedZoom) > 0.001f) commitZoom(scale)
                            }
                        }
                        .then(
                            // A plain tap toggles the chrome, same as in the HTML/EPUB reader —
                            // only outside draw/note mode, where a tap has to reach the page
                            // instead. Double tap is the shortcut for people who would rather
                            // not pinch: in to a fixed magnification, again to fit the width.
                            if (mode == PdfAnnotationMode.NONE)
                                Modifier.pointerInput(idx) {
                                    detectTapGestures(
                                        onTap = { if (selectedText.isNotBlank()) { selectedText = ""; selectionReset++ } else onToggle() },
                                        onDoubleTap = {
                                            val prev = scale
                                            val next = if (prev > 1.01f) 1f else DOUBLE_TAP_PDF_ZOOM
                                            val grown = clamp(pageOffsets[idx] ?: Offset.Zero, prev) * (next / prev)
                                            scale = next
                                            pageOffsets[idx] = clamp(grown, next)
                                            commitZoom(next)
                                        }
                                    )
                                }
                            else Modifier
                        ),
                    // The sheet sits in the middle of the screen. Without this it hung from
                    // the top left: a page shorter than the display — which is most of them,
                    // a page being squarer than a phone — clung to the top edge with all the
                    // empty space gathered underneath, which is what made it look both small
                    // and off-centre.
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // A page is usually taller than the screen. Without `unbounded`
                            // the layout would squeeze it to the viewport height and the text
                            // would come out a third of its proper size.
                            .wrapContentHeight(unbounded = true)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { contentH = it.height.toFloat() }
                        ) {
                            PdfPageItem(
                                file               = File(filePath),
                                pageIndex          = idx,
                                parser             = pdfParser,
                                // Re-rendered sharper as it grows, in whole steps so a pinch
                                // does not order a new bitmap on every frame. Scaling the
                                // bitmap alone would just magnify its pixels.
                                //
                                // Only the page being read. The magnification is shared by the
                                // document, so without this the two neighbours the pager keeps
                                // alive would each hold a large bitmap as well, for three times
                                // the memory and nothing on screen to show for it.
                                renderScale        = if (idx == pagerState.currentPage)
                                                         ceil(scale).toInt().coerceIn(1, 3)
                                                     else 1,
                                paletteFilter      = paletteFilter,
                                annotations        = annotations,
                                mode               = mode,
                                activeColor        = activeColor,
                                penWidthDp         = penWidthDp,
                                onNotePlaced       = onNotePlaced,
                                onDrawingComplete  = onDrawingComplete,
                                onDrawingErased    = onDrawingErased,
                                onDrawingPartsErased = onDrawingPartsErased,
                                onAnnotationClick  = onAnnotationClick,
                                textLayer = textLayer, selectionReset = selectionReset,
                                onTextSelected = { selectedText = it; if (it.isNotBlank()) onSelectionVisible() },
                                onSelectionDragging = { selectionDragging = it },
                                onSpeechContext = { text, before -> selectedSpeechContext = org.json.JSONObject().put("text", text).put("before", before).toString() }
                            )
                        }
                    }
                }
            }
        }

        // Swiping is disabled entirely while a drawing/note tool is active, so the gesture goes
        // to the canvas inside PdfPageItem. Otherwise it is left on even when magnified: the
        // page claims a drag only while it still has somewhere to slide along the pager's axis,
        // and lets go at the edge so the swipe turns the page. Blanket-disabling it meant a
        // reader who set a zoom in the settings could no longer leave the first page.
        val pagingEnabled = mode == PdfAnnotationMode.NONE && !selectionDragging && selectedText.isBlank()
        if (verticalPaging) {
            VerticalPager(
                state             = pagerState,
                modifier          = Modifier.fillMaxSize(),
                userScrollEnabled = pagingEnabled
            ) { idx -> pageContent(idx) }
        } else {
            HorizontalPager(
                state             = pagerState,
                modifier          = Modifier.fillMaxSize(),
                userScrollEnabled = pagingEnabled
            ) { idx -> pageContent(idx) }
        }
        if (selectedText.isNotBlank() && !selectionDragging) Surface(
            Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(18.dp),
            tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(selectedText)) }, Modifier.weight(1f)) { Text("Копировать") }
                TextButton({ onWordSelected(selectedText.trim().trim { !it.isLetterOrDigit() }) }, Modifier.weight(1f),
                    enabled = selectedText.trim().none { it.isWhitespace() }) { Text("Определить") }
                TextButton({ onSpeakRequested(selectedSpeechContext); selectedText = ""; selectionReset++ }, Modifier.weight(1f)) { Text("Читать отсюда") }
                IconButton({ selectedText = ""; selectionReset++ }) { Icon(Icons.Default.Close, "Снять выделение") }
            }
        }
    }
}

@Composable
internal fun PdfToolsPanel(
    mode: PdfAnnotationMode,
    onModeChange: (PdfAnnotationMode) -> Unit,
    optionsExpanded: Boolean,
    onOptionsToggle: () -> Unit,
    activeColor: Int,
    onColorChange: (Int) -> Unit,
    penWidthDp: Float,
    onPenWidthChange: (Float) -> Unit,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    drawingCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearPage: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 560.dp).fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(3.dp, RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        // Options grow above the tool row, never moving the pen/eraser targets.
        @Composable fun ToolRow() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple(PdfAnnotationMode.DRAW, "Перо", Icons.Filled.Brush),
                Triple(PdfAnnotationMode.NOTE, "Заметка", Icons.Filled.PushPin),
                Triple(PdfAnnotationMode.ERASE, "Ластик", Icons.Filled.Delete)
            ).forEach { (value, label, icon) ->
                val selected = mode == value ||
                    (value == PdfAnnotationMode.ERASE && mode == PdfAnnotationMode.ERASE_PART)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onModeChange(selectPdfAnnotationTool(mode, value)) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 3.dp),
                color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Undo, contentDescription = "Отменить последний рисунок",
                    modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Redo, contentDescription = "Вернуть рисунок",
                    modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onOptionsToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (optionsExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (optionsExpanded) "Свернуть настройки пера" else "Показать настройки пера",
                    modifier = Modifier.size(23.dp)
                )
            }
        }
        }
        AnimatedVisibility(
            visible = optionsExpanded,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(tween(160)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .height((LocalConfiguration.current.screenHeightDp.dp * .4f).coerceAtMost(224.dp))
                    .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (mode == PdfAnnotationMode.DRAW) {
                    PdfColorSwatchRow(selected = activeColor, onSelect = onColorChange)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Толщина", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(65.dp))
                        Slider(
                            value = penWidthDp,
                            onValueChange = onPenWidthChange,
                            valueRange = 1f..6f,
                            steps = 9,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(penWidthDp * 10).roundToInt() / 10f}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(27.dp))
                    }
                }
                if (mode == PdfAnnotationMode.ERASE || mode == PdfAnnotationMode.ERASE_PART) {
                    Text("Как стирать", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PdfAnnotationMode.ERASE to "Целый штрих",
                            PdfAnnotationMode.ERASE_PART to "Часть линии"
                        ).forEach { (eraseMode, label) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (mode == eraseMode) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onModeChange(eraseMode) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium,
                                    color = if (mode == eraseMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Масштаб", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { onZoomChange((zoom - 0.25f).coerceAtLeast(1f)) },
                        enabled = zoom > 1f, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Remove, contentDescription = "Уменьшить страницу",
                            modifier = Modifier.size(19.dp))
                    }
                    Text("${(zoom * 10).roundToInt() / 10f}×",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { onZoomChange((zoom + 0.25f).coerceAtMost(MAX_PDF_ZOOM)) },
                        enabled = zoom < MAX_PDF_ZOOM, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Увеличить страницу",
                            modifier = Modifier.size(19.dp))
                    }
                }
                run {
                    TextButton(onClick = onClearPage, enabled = drawingCount > 0, modifier = Modifier.align(Alignment.End)) {
                        Text("Удалить рисунки со страницы")
                    }
                }
            }
        }
        ToolRow()
    }
}

private val PDF_INK_COLORS = listOf(
    Color(0xFF315CB8), Color(0xFF242424), Color(0xFFC15343),
    Color(0xFF2F8660), Color(0xFF9063AF), Color(0xFFCF972B)
)

// Colors stay visible while the pen is active; tool changes never cycle through a button.
@Composable
private fun PdfColorSwatchRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PDF_INK_COLORS.forEach { c ->
            val argb = c.toArgb()
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(argb) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (selected == argb) 2.dp else 1.dp,
                            color = if (selected == argb) MaterialTheme.colorScheme.onSurface
                                else Color.Black.copy(alpha = 0.1f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected == argb) {
                        Icon(Icons.Filled.Check, contentDescription = "Выбранный цвет",
                            tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun parseNormalizedPath(json: String, width: Float, height: Float): List<Offset> =
    try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Offset((o.getDouble("x") * width).toFloat(), (o.getDouble("y") * height).toFloat())
        }
    } catch (_: Exception) { emptyList() }

/**
 * Shortest distance from [target] to the polyline through [points] — measured against the
 * segments, not just the sampled vertices, so a tap on a long straight stretch of a stroke
 * still registers as a hit.
 */
private fun distanceToStroke(points: List<Offset>, target: Offset): Float {
    if (points.isEmpty()) return Float.MAX_VALUE
    if (points.size == 1) return (points[0] - target).getDistance()
    var best = Float.MAX_VALUE
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        val closest = if (lenSq == 0f) a else {
            val t = (((target.x - a.x) * abx + (target.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
            Offset(a.x + t * abx, a.y + t * aby)
        }
        val d = (closest - target).getDistance()
        if (d < best) best = d
    }
    return best
}

private fun storedStrokeWidthPx(json: String, density: Float): Float = try {
    val first = org.json.JSONArray(json).optJSONObject(0)
    if (first?.has("w") == true) (first.optDouble("w", 2.5) * density).toFloat().coerceIn(1f, 40f)
    else 6f // Existing drawings predate adjustable width.
} catch (_: Exception) { 6f }

/** Returns null when untouched; otherwise the surviving pieces of the original stroke. */
private fun eraseStrokeParts(
    pathData: String,
    pageWidth: Float,
    pageHeight: Float,
    density: Float,
    eraserPath: List<Offset>,
    eraserRadius: Float
): List<String>? {
    val points = parseNormalizedPath(pathData, pageWidth, pageHeight)
    if (points.isEmpty() || eraserPath.isEmpty()) return null
    val widthPx = storedStrokeWidthPx(pathData, density)
    val step = (eraserRadius / 3f).coerceIn(1f, 4f)
    val fragments = ArrayList<List<Offset>>()
    var current = ArrayList<Offset>()
    var touched = false

    fun classify(point: Offset) {
        if (distanceToStroke(eraserPath, point) <= eraserRadius + widthPx / 2f) {
            touched = true
            if (current.isNotEmpty()) {
                fragments.add(current)
                current = ArrayList()
            }
        } else {
            current.add(point)
        }
    }

    classify(points.first())
    for (index in 1 until points.size) {
        val from = points[index - 1]
        val to = points[index]
        val count = kotlin.math.ceil((to - from).getDistance() / step).toInt().coerceAtLeast(1)
        for (sample in 1..count) {
            classify(from + (to - from) * (sample.toFloat() / count))
        }
    }
    if (!touched) return null
    if (current.isNotEmpty()) fragments.add(current)

    return fragments.map { fragment ->
        org.json.JSONArray().apply {
            fragment.forEachIndexed { index, point ->
                val vertex = org.json.JSONObject()
                    .put("x", (point.x / pageWidth).coerceIn(0f, 1f).toDouble())
                    .put("y", (point.y / pageHeight).coerceIn(0f, 1f).toDouble())
                if (index == 0) vertex.put("w", (widthPx / density).toDouble())
                put(vertex)
            }
        }.toString()
    }
}

private fun DrawScope.drawStrokePath(points: List<Offset>, color: Color, widthPx: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color = color, radius = widthPx / 2f, center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.lastIndex) {
            val middle = Offset(
                (points[i].x + points[i + 1].x) / 2f,
                (points[i].y + points[i + 1].y) / 2f
            )
            quadraticBezierTo(points[i].x, points[i].y, middle.x, middle.y)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, color = color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun PdfPageItem(
    file: File,
    pageIndex: Int,
    parser: PdfParser,
    renderScale: Int = 1,
    paletteFilter: ColorFilter? = null,
    annotations: List<Annotation>,
    mode: PdfAnnotationMode,
    activeColor: Int,
    penWidthDp: Float,
    onNotePlaced: (Int, Float, Float) -> Unit,
    onDrawingComplete: (Int, String, Int) -> Unit,
    onDrawingErased: (Annotation) -> Unit,
    onDrawingPartsErased: (List<Pair<Annotation, List<String>>>) -> Unit,
    onAnnotationClick: (Long) -> Unit,
    textLayer: PdfTextLayer? = null,
    selectionReset: Int = 0,
    onTextSelected: (String) -> Unit = {},
    onSelectionDragging: (Boolean) -> Unit = {},
    onSpeechContext: (String, String) -> Unit = { _, _ -> }
) {
    var bitmap by remember(pageIndex, file.path) { mutableStateOf<Bitmap?>(null) }
    val pageAnnotations = remember(annotations, pageIndex) { annotations.filter { it.pageIndex == pageIndex } }
    val notePins = remember(pageAnnotations) {
        pageAnnotations.filter { it.type == AnnotationType.NOTE && it.normX != null && it.normY != null }
    }
    val drawings = remember(pageAnnotations) {
        pageAnnotations.filter { it.type == AnnotationType.DRAWING && it.pathData != null }
    }
    val currentStroke = remember(pageIndex) { mutableStateListOf<Offset>() }
    val eraserTrail = remember(pageIndex) { mutableStateListOf<Offset>() }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().roundToInt() }
        // Off the main thread. Decoding a page is tens of milliseconds of native work plus a
        // multi-megabyte allocation, and LaunchedEffect runs on the main dispatcher — so this
        // used to drop frames on every page turn, and a corrupt page took the reader down with
        // an uncaught exception instead of just failing to appear.
        LaunchedEffect(widthPx, renderScale, pageIndex) {
            if (widthPx <= 0) return@LaunchedEffect
            val rendered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { parser.renderPage(file, pageIndex, widthPx * renderScale) }.getOrNull()
            }
            if (rendered != null) bitmap = rendered
        }

        bitmap?.let { bmp ->
            val boxWidthPx = with(density) { maxWidth.toPx() }
            val boxHeightPx = boxWidthPx * bmp.height / bmp.width
            val boxHeightDp = with(density) { boxHeightPx.toDp() }

            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap             = bmp.asImageBitmap(),
                    contentDescription = "Страница ${pageIndex + 1}",
                    modifier           = Modifier.fillMaxWidth(),
                    colorFilter        = paletteFilter
                )
                if (mode == PdfAnnotationMode.NONE && textLayer != null) PdfTextSelection(textLayer, pageIndex,
                    Modifier.fillMaxWidth().height(boxHeightDp), MaterialTheme.colorScheme.primary, selectionReset,
                    onTextSelected, onSelectionDragging, onSpeechContext)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(boxHeightDp)
                        .then(
                            if (mode == PdfAnnotationMode.DRAW) {
                                Modifier.pointerInput(pageIndex, activeColor, penWidthDp) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        currentStroke.clear()
                                        currentStroke.add(down.position)
                                        down.consume()
                                        var pressed = true
                                        while (pressed) {
                                            val event = awaitPointerEvent()
                                            if (currentStroke.isEmpty() || event.changes.any { it.isConsumed } || event.changes.count { it.pressed } > 1) {
                                                currentStroke.clear()
                                                event.changes.forEach { if (it.pressed) it.consume() }
                                                pressed = event.changes.any { it.pressed }
                                                continue
                                            }
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null) break
                                            if ((change.position - currentStroke.last()).getDistance() >= 0.5f) {
                                                currentStroke.add(change.position)
                                            }
                                            change.consume()
                                            pressed = change.pressed
                                        }
                                        if (size.width > 0 && size.height > 0 && currentStroke.isNotEmpty()) {
                                            val w = size.width.toFloat()
                                            val h = size.height.toFloat()
                                            val json = org.json.JSONArray()
                                            currentStroke.forEachIndexed { index, point ->
                                                val data = org.json.JSONObject()
                                                    .put("x", (point.x / w).toDouble())
                                                    .put("y", (point.y / h).toDouble())
                                                if (index == 0) data.put("w", penWidthDp.toDouble())
                                                json.put(data)
                                            }
                                            onDrawingComplete(pageIndex, json.toString(), activeColor)
                                        }
                                        currentStroke.clear()
                                    }
                                }
                            } else Modifier
                        )
                        .then(
                            if (mode == PdfAnnotationMode.ERASE_PART) {
                                Modifier.pointerInput(pageIndex, drawings) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        eraserTrail.clear()
                                        eraserTrail.add(down.position)
                                        down.consume()
                                        var pressed = true
                                        while (pressed) {
                                            val event = awaitPointerEvent()
                                            if (eraserTrail.isEmpty() || event.changes.any { it.isConsumed } || event.changes.count { it.pressed } > 1) {
                                                eraserTrail.clear()
                                                event.changes.forEach { if (it.pressed) it.consume() }
                                                pressed = event.changes.any { it.pressed }
                                                continue
                                            }
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null) break
                                            if ((change.position - eraserTrail.last()).getDistance() >= 0.5f) {
                                                eraserTrail.add(change.position)
                                            }
                                            change.consume()
                                            pressed = change.pressed
                                        }
                                        if (size.width > 0 && size.height > 0 && eraserTrail.isNotEmpty()) {
                                            val radius = 9.dp.toPx()
                                            val changes = drawings.mapNotNull { annotation ->
                                                val parts = eraseStrokeParts(
                                                    annotation.pathData!!,
                                                    size.width.toFloat(), size.height.toFloat(),
                                                    density.density, eraserTrail, radius
                                                )
                                                if (parts == null) null else annotation to parts
                                            }
                                            if (changes.isNotEmpty()) onDrawingPartsErased(changes)
                                        }
                                        eraserTrail.clear()
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    drawings.forEach { ann ->
                        val points = parseNormalizedPath(ann.pathData!!, size.width, size.height)
                        drawStrokePath(points, Color(ann.color), storedStrokeWidthPx(ann.pathData, density.density))
                    }
                    if (currentStroke.isNotEmpty()) {
                        drawStrokePath(currentStroke, Color(activeColor), penWidthDp * density.density)
                    }
                    if (eraserTrail.isNotEmpty()) {
                        drawStrokePath(eraserTrail, Color.Gray.copy(alpha = 0.25f), 18.dp.toPx())
                    }
                }

                if (mode == PdfAnnotationMode.NOTE) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(boxHeightDp)
                            .pointerInput(pageIndex) {
                                detectTapGestures { offset ->
                                    onNotePlaced(pageIndex, offset.x / size.width, offset.y / size.height)
                                }
                            }
                    )
                }

                // Eraser — a tap deletes whichever stroke it lands closest to, provided the
                // tap is actually near that stroke (a miss deletes nothing rather than the
                // nearest stroke somewhere else on the page).
                if (mode == PdfAnnotationMode.ERASE) {
                    val touchSlopPx = with(density) { 22.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(boxHeightDp)
                            .pointerInput(pageIndex, drawings) {
                                detectTapGestures { offset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val hit = drawings
                                        .map { it to distanceToStroke(parseNormalizedPath(it.pathData!!, w, h), offset) }
                                        .filter { it.second <= touchSlopPx }
                                        .minByOrNull { it.second }
                                        ?.first
                                    if (hit != null) onDrawingErased(hit)
                                }
                            }
                    )
                }

                notePins.forEach { ann ->
                    val xDp = with(density) { (ann.normX!! * boxWidthPx).toDp() }
                    val yDp = with(density) { (ann.normY!! * boxHeightPx).toDp() }
                    Icon(
                        imageVector        = Icons.Filled.PushPin,
                        contentDescription = "Заметка",
                        tint               = Color(ann.color),
                        modifier = Modifier
                            .offset(x = xDp - 12.dp, y = yDp - 24.dp)
                            .size(24.dp)
                            .clickable { onAnnotationClick(ann.id) }
                    )
                }
            }
        } ?: Box(
            modifier = Modifier.fillMaxWidth().height(420.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
    }
}

// ── Loading / Error ───────────────────────────────────────────────────────────

@Composable
private fun LoadingPage(bg: Color) {
    Box(modifier = Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(14.dp))
            Text("Загрузка…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPage(message: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onBack) { Text("Назад") }
        }
    }
}

// ── Glass Icon Button ─────────────────────────────────────────────────────────

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    isActive: Boolean = false,
    label: String? = null,
    haptic: Boolean = false,
    content: @Composable () -> Unit
) {
    // The clip has to come before clickable, not just be handed to background(). A ripple is
    // drawn by the clickable's own indication, which knows nothing about the shape the
    // background happens to paint — so it filled the whole 44dp box and every press flashed a
    // hard square behind a rounded button. Clipping first bounds the ripple to the same
    // rounded rectangle the highlight uses.
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(if (pressed) 70 else 120),
        label = "reader button press"
    )
    val background by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                      else Color.Transparent,
        animationSpec = tween(160),
        label = "reader button state"
    )
    val feedback = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .then(if (label != null) Modifier.clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            } else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button
            ) {
                if (haptic) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .background(background, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

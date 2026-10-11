package com.lexiread.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.lexiread.app.domain.pdf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lives in page coordinates, inside the same scale/translation as the bitmap. */
@Composable
internal fun PdfTextSelection(layer: PdfTextLayer, page: Int, modifier: Modifier,
    color: Color, reset: Int, onSelection: (String) -> Unit, onDragging: (Boolean) -> Unit,
    onSpeechContext: (String, String) -> Unit = { _, _ -> }) {
    val words by produceState<List<PdfWord>>(emptyList(), layer, page) {
        value = withContext(Dispatchers.IO) { layer.pageWords(page) }
    }
    var first by remember(page, reset) { mutableStateOf<Int?>(null) }
    var last by remember(page, reset) { mutableStateOf<Int?>(null) }
    val selectionCallback by rememberUpdatedState(onSelection)
    val draggingCallback by rememberUpdatedState(onDragging)
    val speechCallback by rememberUpdatedState(onSpeechContext)
    fun publish(start: Int, end: Int) {
        selectionCallback(pdfSelectionText(words, start, end))
        val at = minOf(start, end)
        speechCallback(words.drop(at).joinToString(" ") { it.text }.take(240),
            words.take(at).joinToString(" ") { it.text }.takeLast(120))
    }
    DisposableEffect(page) { onDispose { draggingCallback(false) } }
    Canvas(modifier.pointerInput(words, reset) {
        detectDragGesturesAfterLongPress(onDragStart = { point ->
            first = pdfWordAt(words, point.x / size.width, point.y / size.height)
            last = first
            if (first != null) { draggingCallback(true); publish(first!!, last!!) }
        }, onDrag = { change, _ ->
            if (first != null) {
                pdfWordAt(words, change.position.x / size.width, change.position.y / size.height)?.let { end ->
                    last = end; publish(first!!, end)
                }
                change.consume()
            }
        }, onDragEnd = { draggingCallback(false) }, onDragCancel = { draggingCallback(false) })
    }) {
        val start = first; val end = last
        if (start != null && end != null) for (index in minOf(start, end)..maxOf(start, end)) {
            val word = words.getOrNull(index) ?: continue
            drawRect(color.copy(alpha = .3f), Offset(word.left * size.width, word.top * size.height),
                Size((word.right - word.left) * size.width, (word.bottom - word.top) * size.height))
        }
    }
}

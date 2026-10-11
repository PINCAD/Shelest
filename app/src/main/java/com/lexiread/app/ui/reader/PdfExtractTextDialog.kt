package com.lexiread.app.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lexiread.app.domain.pdf.PdfTextLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local extraction only: book contents never leave the device. Scans require separate OCR. */
@Composable
internal fun PdfExtractTextDialog(layer: PdfTextLayer?, page: Int, title: String, loading: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember(page, layer) { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(page, layer) {
        if (layer != null) text = withContext(Dispatchers.IO) { layer.pageText(page) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && layer != null) scope.launch {
            exporting = true
            try {
                val chars = withContext(Dispatchers.IO) {
                    var count = 0L
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось сохранить файл")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        for (i in 0 until layer.pageCount) {
                            ensureActive()
                            val words = layer.pageText(i)
                            if (words.isNotBlank()) {
                                if (count > 0) writer.append("\n\n")
                                writer.append(words)
                                count += words.length
                            }
                        }
                    }
                    count
                }
                status = if (chars > 0) "Текст сохранён в TXT" else "В PDF нет текстового слоя. Для сканов нужно распознавание текста (OCR)."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Не удалось извлечь текст" }
            finally { exporting = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        title = { Text("Текст · страница ${page + 1}") },
        text = {
            Column {
                when {
                    layer == null && !loading -> Text("Не удалось открыть текстовый слой PDF. Файл может быть защищён или повреждён.")
                    text == null -> { CircularProgressIndicator(); Text("Подготавливаем текст…") }
                    text!!.isBlank() -> Text("На этой странице нет текстового слоя. Сканы требуют распознавания (OCR).")
                    else -> SelectionContainer {
                        Text(text!!, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                    }
                }
                status?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
                if (exporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                TextButton(enabled = !text.isNullOrBlank() && !exporting, onClick = {
                    clipboard.setText(AnnotatedString(text.orEmpty())); status = "Текст страницы скопирован"
                }) { Text("Скопировать страницу") }
            }
        },
        confirmButton = {
            TextButton(enabled = layer != null && !exporting, onClick = {
                export.launch(title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100) + ".txt")
            }) { Text("Весь PDF в TXT") }
        },
        dismissButton = { TextButton(enabled = !exporting, onClick = onDismiss) { Text("Закрыть") } }
    )
}

package com.lexiread.app.ui.definition

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.lexiread.app.ui.reader.DefinitionState
import com.lexiread.app.ui.theme.*

/** Recognized Russian part-of-speech abbreviations produced by DefinitionRepository.posAbbrev. */
private val KNOWN_POS = setOf(
    "сущ.", "гл.", "прил.", "нареч.", "мест.", "предл.", "союз", "межд.", "числ.", "част."
)

private data class ExtractBlock(val label: String?, val lines: List<String>)

/**
 * Splits the flat, source-dependent `extract` string into labeled blocks:
 * dictionaryapi.dev separates part-of-speech groups with a blank line and a
 * label line ("\n\n"-joined), Yandex.Dictionary puts one "label word, word" line
 * per part of speech, and Wikipedia/Wiktionary extracts are plain prose with
 * neither — each shape is detected and rendered accordingly.
 */
private fun parseExtractBlocks(extract: String): List<ExtractBlock> {
    val blocks = mutableListOf<ExtractBlock>()
    extract.split("\n\n").forEach { rawBlock ->
        val lines = rawBlock.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return@forEach

        when {
            lines.size > 1 && lines.first() in KNOWN_POS ->
                blocks += ExtractBlock(lines.first(), lines.drop(1))

            lines.all { line -> KNOWN_POS.any { line.startsWith("$it ") } } ->
                lines.forEach { line ->
                    val label = KNOWN_POS.first { line.startsWith("$it ") }
                    blocks += ExtractBlock(label, listOf(line.removePrefix(label).trim()))
                }

            else -> blocks += ExtractBlock(null, listOf(lines.joinToString(" ")))
        }
    }
    return blocks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefinitionBottomSheet(
    state: DefinitionState,
    isSaved: Boolean = false,
    onToggleSave: ((String, String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        // Top inset only, so the sheet paints past the gesture bar instead of leaving a
        // strip of page colour under it; the content below keeps navigationBarsPadding.
        windowInsets     = WindowInsets.statusBars,
        dragHandle = {
            // Minimal pill handle
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        when (state) {
            is DefinitionState.Loading -> DefinitionLoadingContent()
            is DefinitionState.Success -> DefinitionSuccessContent(
                def          = state.definition,
                isSaved      = isSaved,
                onToggleSave = onToggleSave
            )
            is DefinitionState.Error   -> DefinitionErrorContent(state.word, state.message, onDismiss)
            else -> Unit
        }
    }
}

// ── Shimmer Loading ───────────────────────────────────────────────────────────

@Composable
private fun DefinitionLoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .navigationBarsPadding()
    ) {
        // Fake source badge
        ShimmerBlock(width = 90.dp, height = 22.dp, radius = 20.dp)
        Spacer(Modifier.height(16.dp))
        // Fake title
        ShimmerBlock(width = 220.dp, height = 34.dp, radius = 8.dp)
        Spacer(Modifier.height(6.dp))
        ShimmerBlock(width = 140.dp, height = 16.dp, radius = 6.dp)
        Spacer(Modifier.height(24.dp))
        // Divider
        ShimmerBlock(modifier = Modifier.fillMaxWidth(), height = 1.dp, radius = 1.dp)
        Spacer(Modifier.height(20.dp))
        // Fake text lines
        repeat(5) { i ->
            ShimmerBlock(
                modifier = Modifier.fillMaxWidth(if (i == 4) 0.65f else 1f),
                height   = 14.dp,
                radius   = 6.dp
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        ShimmerBlock(modifier = Modifier.fillMaxWidth(), height = 44.dp, radius = 12.dp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ShimmerBlock(
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    height: Dp,
    radius: Dp = 6.dp
) {
    val anim by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue  = 0.15f,
        targetValue   = 0.40f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "shimmer-alpha"
    )
    Box(
        modifier = modifier
            .then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = anim))
    )
}

// ── Success Content ───────────────────────────────────────────────────────────

@Composable
private fun DefinitionSuccessContent(
    def: com.lexiread.app.data.model.WordDefinition,
    isSaved: Boolean,
    onToggleSave: ((String, String) -> Unit)?
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {

        // ── Header ─────────────────────────────────────────────────────────
        // No illustration. The thumbnail only ever came from the Wikipedia/Wiktionary
        // fallback — the dictionary sources that answer most lookups have no image at
        // all — so the card either reserved 120dp of empty grey or dropped a small
        // upscaled crop above the word. A word deserves better than a picture of
        // something vaguely related, and the definition starts immediately now.
        //
        // No close button either. Forced to share a row with a 36dp circle the word sat
        // off-centre against it, and the circle itself was clipped by the sheet edge; the
        // drag handle right above already says "pull me down", and a tap outside closes
        // the card. The word now simply owns the top of the sheet.
        Text(
            text     = def.title,
            style    = MaterialTheme.typography.headlineLarge,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 16.dp)
        )

        HorizontalDivider(
            modifier  = Modifier.padding(horizontal = 24.dp),
            thickness = 0.5.dp,
            color     = MaterialTheme.colorScheme.outlineVariant
        )

        // ── Definition text ────────────────────────────────────────────────
        Spacer(Modifier.height(20.dp))
        val blocks = remember(def.extract) { parseExtractBlocks(def.extract) }
        Column(
            modifier            = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            blocks.forEach { block ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (block.label != null) {
                        Text(
                            text  = block.label.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight    = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    block.lines.forEachIndexed { index, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (block.lines.size > 1) {
                                Text(
                                    text  = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text  = line,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // ── Save to personal vocabulary ────────────────────────────────────
        // Looking a word up once rarely teaches it; keeping it is what turns a
        // lookup into vocabulary, so the action sits right under the definition.
        if (onToggleSave != null) {
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSaved) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onToggleSave(def.title, def.extract) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text  = if (isSaved) "В словарике" else "Запомнить слово",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // ── Open source link ──────────────────────────────────────────────
        if (!def.wikiUrl.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(def.wikiUrl)))
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.OpenInBrowser,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Подробнее",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun DefinitionErrorContent(word: String, message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = Icons.Outlined.SearchOff,
                contentDescription = null,
                modifier           = Modifier.size(32.dp),
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text  = "«$word»",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text  = "Определение не найдено",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(28.dp))

        // Try in browser row — secondary-importance, accent outline
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .clickable {
                    context.startActivity(Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://ru.wikipedia.org/wiki/${Uri.encode(word)}")
                    ))
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Поискать в Википедии",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onDismiss) {
            Text("Закрыть", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

package com.lexiread.app.ui.vocabulary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexiread.app.data.model.VocabularyWord
import com.lexiread.app.ui.settings.OptionChip

/**
 * The words the reader chose to keep, out of everything they ever looked up.
 *
 * A definition popup is forgotten the moment it closes; this screen is the only
 * place a lookup turns into something you can come back to and test yourself on,
 * which is why the definition stays hidden until tapped.
 */
@Composable
fun VocabularyScreen(
    viewModel: VocabularyViewModel,
    onBack: () -> Unit
) {
    val words        by viewModel.words.collectAsStateWithLifecycle()
    val filter       by viewModel.filter.collectAsStateWithLifecycle()
    val learnedCount by viewModel.learnedCount.collectAsStateWithLifecycle()
    val totalCount   by viewModel.totalCount.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text  = "Словарик",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text  = if (totalCount == 0) "Слова из ваших книг"
                                    else "Выучено $learnedCount из $totalCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (totalCount > 0) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VocabularyFilter.entries.forEach { f ->
                        OptionChip(
                            label    = f.label,
                            selected = filter == f,
                            onSelect = { viewModel.setFilter(f) }
                        )
                    }
                }

                // A thin progress line turns a pile of saved words into something with
                // a finish line — the single reason to keep marking words as learned.
                if (learnedCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(learnedCount.toFloat() / totalCount.coerceAtLeast(1))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (words.isEmpty()) {
                EmptyVocabulary(hasAnyWords = totalCount > 0)
            } else {
                LazyColumn(
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(words, key = { it.id }) { word ->
                        WordCard(
                            word            = word,
                            onToggleLearned = { viewModel.toggleLearned(word) },
                            onDelete        = { viewModel.delete(word) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordCard(
    word: VocabularyWord,
    onToggleLearned: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label       = "chevron"
    )

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                // Same interaction as a book card: tap opens, long press offers delete.
                .combinedClickable(
                    onClick     = { expanded = !expanded },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Learned toggle — a filled check reads as "done" at a glance while
                // scrolling, and leading it keeps a scannable column of states.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (word.isLearned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable(onClick = onToggleLearned),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = if (word.isLearned) "Убрать отметку" else "Выучено",
                        tint = if (word.isLearned) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = word.word,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (word.isLearned) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (word.isLearned) TextDecoration.LineThrough else null
                    )
                    word.sourceBookTitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text     = it,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Показать определение",
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation)
                )
                Spacer(Modifier.width(6.dp))
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text     = word.definition,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 42.dp, end = 8.dp, top = 8.dp)
                )
            }
        }

        com.lexiread.app.ui.components.DeleteMenu(
            expanded         = menuOpen,
            onDismissRequest = { menuOpen = false },
            onDelete         = onDelete,
            modifier         = Modifier.matchParentSize()
        )
    }
}

@Composable
private fun EmptyVocabulary(hasAnyWords: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Outlined.Translate,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text  = if (hasAnyWords) "Здесь пусто" else "Словарик пуст",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text  = if (hasAnyWords) "Попробуйте другой фильтр"
                        else "Выделите слово в книге и нажмите «Запомнить слово»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

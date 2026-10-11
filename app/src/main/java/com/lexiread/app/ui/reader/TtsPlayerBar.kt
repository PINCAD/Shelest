package com.lexiread.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TtsPlayerBar(
    isPlaying: Boolean,
    currentSentenceText: String,
    speechRate: Float,
    followText: Boolean,
    onFollowToggle: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onStartFromPage: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // Same surface as ReaderBottomBar right below it: a plain shadow over the theme
            // surface colour. It used to be a Surface with tonalElevation, which tints the
            // background with the primary colour on top of everything else — so the player
            // came out a different shade from every other panel in the reader, which is the
            // "old look" it never shed.
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Top Row: Label, Speed selector, Close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Озвучка текста",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Speed chips
                listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                    val isSelected = speechRate == rate
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onSpeedChange(rate) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${rate}x",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            // Whatever the theme says reads on top of primary. Hardcoded
                            // white vanished on the light palettes, whose accent is pale.
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(2.dp))

                // A 24dp button with a 16dp glyph is roughly half the smallest target a
                // finger can be expected to hit, which is why the player felt like it could
                // not be dismissed. This is a real one.
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Закрыть озвучку",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Current reading text snippet
        if (currentSentenceText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = currentSentenceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        // Where to begin. The voice picks the visible page on its own when the player opens,
        // but a reader who has since turned pages, or who wants the chapter from the top,
        // needs to be able to say so — before this there was no way to move it anywhere but
        // one sentence at a time.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StartPointChip(
                text = "С этой страницы",
                onClick = onStartFromPage,
                modifier = Modifier.weight(1f)
            )
            StartPointChip(
                text = "С начала главы",
                onClick = onStartFromBeginning,
                modifier = Modifier.weight(1f)
            )
            // Whether the page keeps itself under the voice. The spoken line is marked either
            // way — this is only the page turning on its own, which is unwelcome for anyone
            // reading along slightly ahead of the narration.
            StartPointChip(
                text = "Следить",
                onClick = onFollowToggle,
                active = followText,
                modifier = Modifier.weight(0.75f)
            )
        }

        Spacer(Modifier.height(6.dp))

        // Controls: Prev, Play/Pause, Next
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Предыдущее предложение",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onPlayPauseToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Следующее предложение",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StartPointChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Chips that start something are never lit; a chip that carries a setting shows it. */
    active: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

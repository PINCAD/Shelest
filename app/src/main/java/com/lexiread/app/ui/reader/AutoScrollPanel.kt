package com.lexiread.app.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** One pace table for the timer, its visible explanation and regression tests. */
internal object AutoScrollPace {
    fun intervalMillis(speed: Int): Int = listOf(70_000, 50_000, 35_000, 22_000, 14_000)[speed.coerceIn(1, 5) - 1]
    fun pixelsPerSecond(speed: Int): Int = listOf(9, 15, 24, 36, 52)[speed.coerceIn(1, 5) - 1]
}

@Composable
internal fun AutoScrollPanel(
    speed: Int,
    running: Boolean,
    horizontal: Boolean,
    progress: () -> Float,
    onSpeedChange: (Int) -> Unit,
    onToggle: () -> Unit,
    onCollapse: () -> Unit
) {
    val remaining by remember(speed, running) { derivedStateOf {
        ceil(AutoScrollPace.intervalMillis(speed) / 1000f * (1f - progress().coerceIn(0f, 1f))).toInt()
    } }
    Surface(Modifier.padding(horizontal = 12.dp).widthIn(max = 440.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Автопрокрутка", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "Свернуть автопрокрутку") }
            }
            Text(if (horizontal) "Перелистывание каждые ${AutoScrollPace.intervalMillis(speed) / 1000} с"
                else "Плавная прокрутка · скорость $speed из 5",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(speed.toFloat(), { onSpeedChange(it.toInt().coerceIn(1, 5)) },
                valueRange = 1f..5f, steps = 3)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Медленнее", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Быстрее", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (horizontal && running) Text("Следующая страница через $remaining с",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            FilledTonalButton(onToggle, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Пауза" else "Начать прокрутку")
            }
        }
    }
}

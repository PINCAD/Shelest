package com.lexiread.app.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private data class TourStep(val title: String, val description: String)

private val steps = listOf(
    TourStep("Что почитать дальше", "Здесь найдутся рекомендации по вашим книгам и интересам."),
    TourStep("Личный словарь", "Сохранённые во время чтения слова всегда под рукой."),
    TourStep("Добавить книги", "Выберите несколько файлов или загрузите все книги из папки."),
    TourStep("Настройки", "Здесь можно выбрать тему, акцентный цвет и цель чтения.")
)

/** One-time spotlight over the real controls, not a separate onboarding screen. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun LibraryTour(targets: List<Rect>, onFinish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val bounds = targets[step]
    val left by animateFloatAsState(bounds.left, tween(360), label = "tour-left")
    val top by animateFloatAsState(bounds.top, tween(360), label = "tour-top")
    val right by animateFloatAsState(bounds.right, tween(360), label = "tour-right")
    val bottom by animateFloatAsState(bounds.bottom, tween(360), label = "tour-bottom")
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val spotlight = Rect(left - gap, top - gap, right + gap, bottom + gap)
    val accent = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.67f)

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(Unit) { detectTapGestures { } }) {
            val l = spotlight.left.coerceIn(0f, size.width)
            val r = spotlight.right.coerceIn(0f, size.width)
            val t = spotlight.top.coerceIn(0f, size.height)
            val b = spotlight.bottom.coerceIn(0f, size.height)
            drawRect(scrim)
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                cornerRadius = CornerRadius(18.dp.toPx()),
                blendMode = BlendMode.Clear
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Icon(
            Icons.Filled.ArrowUpward, contentDescription = null,
            tint = accent,
            modifier = Modifier.offset {
                IntOffset(
                    (spotlight.center.x - with(density) { 16.dp.toPx() }).roundToInt(),
                    (spotlight.bottom + with(density) { 12.dp.toPx() }).roundToInt()
                )
            }.size(32.dp)
        )

        Card(
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth().padding(horizontal = 20.dp)
                .offset { IntOffset(0, (spotlight.bottom + with(density) { 54.dp.toPx() }).roundToInt()) },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("ЗНАКОМСТВО · ${step + 1}/${steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent)
                Spacer(Modifier.height(10.dp))
                AnimatedContent(step, transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                }, label = "tour-step") { current ->
                    Column {
                        Text(steps[current].title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(5.dp))
                        Text(steps[current].description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onFinish) { Text("Пропустить") }
                    Button(onClick = { if (step == steps.lastIndex) onFinish() else step++ }) {
                        Text(if (step == steps.lastIndex) "Понятно" else "Далее")
                    }
                }
            }
        }
    }
}

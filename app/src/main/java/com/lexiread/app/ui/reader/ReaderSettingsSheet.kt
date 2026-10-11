package com.lexiread.app.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lexiread.app.ui.settings.*
import com.shelest.ui.UiChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onFontSizeChange: (Float) -> Unit,
    onFontFamilyChange: (ReaderFontFamily) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onMarginChange: (Int) -> Unit,
    onReadingModeChange: (ReadingMode) -> Unit,
    onPageAnimationChange: (PageTurnAnimation) -> Unit,
    onJustifyTextChange: (Boolean) -> Unit,
    onHorizontalScrollingChange: (Boolean) -> Unit,
    onShowPageNumbersChange: (Boolean) -> Unit,
    onShowProgressBarChange: (Boolean) -> Unit,
    onPreferNetworkVoiceChange: (Boolean) -> Unit,
    textLayoutEnabled: Boolean = true,
    onPdfZoomChange: (Float) -> Unit = {},
    onPdfVerticalPagingChange: (Boolean) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onKeepScreenOnChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val height = (LocalConfiguration.current.screenHeightDp.dp * .85f).coerceAtMost(760.dp)
    val scroll = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp, scrimColor = Color.Black.copy(alpha = .22f),
        windowInsets = WindowInsets.statusBars
    ) {
        Column(Modifier.fillMaxWidth().height(height)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Оформление книги", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onDismiss) { Icon(Icons.Default.Close, "Закрыть настройки чтения") }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (textLayoutEnabled) ReaderSettingsGroup("Текст") {
                    ReaderSheetChoice("Шрифт", settings.fontFamily.name,
                        ReaderFontFamily.entries.map { UiChoice(it.name, it.label, fontAsset = it.asset.orEmpty()) }) {
                        ReaderFontFamily.entries.find { font -> font.name == it }?.let(onFontFamilyChange)
                    }
                    ReaderSheetNumber("Размер", settings.fontSize.toInt().toString(),
                        settings.fontSize > 12f, settings.fontSize < 32f,
                        { onFontSizeChange((settings.fontSize - 1).coerceAtLeast(12f)) },
                        { onFontSizeChange((settings.fontSize + 1).coerceAtMost(32f)) })
                    ReaderSheetNumber("Интервал", "%.1f".format(java.util.Locale.ROOT, settings.lineHeightMultiplier),
                        settings.lineHeightMultiplier > 1.2f, settings.lineHeightMultiplier < 2.5f,
                        { onLineHeightChange((settings.lineHeightMultiplier - .1f).coerceAtLeast(1.2f)) },
                        { onLineHeightChange((settings.lineHeightMultiplier + .1f).coerceAtMost(2.5f)) })
                    ReaderSheetNumber("Поля", settings.marginDp.toString(),
                        settings.marginDp > 8, settings.marginDp < 48,
                        { onMarginChange((settings.marginDp - 4).coerceAtLeast(8)) },
                        { onMarginChange((settings.marginDp + 4).coerceAtMost(48)) })
                    ReaderSheetToggle("По ширине", settings.justifyText, onJustifyTextChange)
                }
                ReaderSettingsGroup("Страница") {
                    ReaderSheetChoice("Цвет страницы", settings.readingMode.name, ReadingMode.entries.map {
                        UiChoice(it.name, it.label, "#%06X".format(it.bgArgb and 0xFFFFFF),
                            "#%06X".format(it.fgArgb and 0xFFFFFF))
                    }) { id -> ReadingMode.entries.find { it.name == id }?.let(onReadingModeChange) }
                    if (!textLayoutEnabled) ReaderSheetNumber("Масштаб",
                        "%.1f×".format(java.util.Locale.ROOT, settings.pdfZoom),
                        settings.pdfZoom > 1f, settings.pdfZoom < 4f,
                        { onPdfZoomChange((settings.pdfZoom - .1f).coerceAtLeast(1f)) },
                        { onPdfZoomChange((settings.pdfZoom + .1f).coerceAtMost(4f)) })
                    ReaderSheetToggle("Номера страниц", settings.showPageNumbers, onShowPageNumbersChange)
                    ReaderSheetToggle("Полоска прогресса", settings.showProgressBar, onShowProgressBarChange)
                }
                ReaderSettingsGroup("Листание") {
                    if (textLayoutEnabled) {
                        ReaderSheetToggle("Листание по горизонтали", settings.horizontalScrolling, onHorizontalScrollingChange)
                        ReaderSheetChoice("Перелистывание", settings.pageAnimation.name,
                            PageTurnAnimation.entries.map { UiChoice(it.name, it.label) },
                            enabled = settings.horizontalScrolling) {
                            PageTurnAnimation.entries.find { animation -> animation.name == it }?.let(onPageAnimationChange)
                        }
                    } else ReaderSheetToggle("Вертикальное листание", settings.pdfVerticalPaging, onPdfVerticalPagingChange)
                }
                ReaderSettingsGroup("Экран") {
                    ReaderSheetToggle("Яркость как в системе", settings.brightness < 0f) {
                        onBrightnessChange(if (it) -1f else .5f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(if (settings.brightness < 0f) .5f else settings.brightness,
                            onBrightnessChange, valueRange = .05f..1f, enabled = settings.brightness >= 0f,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Яркость экрана" })
                        Text(if (settings.brightness < 0f) "Авто" else "${(settings.brightness * 100).toInt()}%",
                            Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    ReaderSheetToggle("Не гасить экран", settings.keepScreenOn, onKeepScreenOnChange)
                }
                if (textLayoutEnabled) ReaderSettingsGroup("Озвучка") {
                    ReaderSheetToggle("Живой голос (интернет)", settings.preferNetworkVoice, onPreferNetworkVoiceChange)
                    Text("Интернет-голос передаёт текст службе синтеза речи.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ReaderSheetToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ReaderSheetNumber(label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
    onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onDecrease, enabled = canDecrease) { Icon(Icons.Default.Remove, "Уменьшить: $label", Modifier.size(20.dp)) }
        Text(value, Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onIncrease, enabled = canIncrease) { Icon(Icons.Default.Add, "Увеличить: $label", Modifier.size(20.dp)) }
    }
}

@Composable
private fun ReaderSheetChoice(title: String, value: String, choices: List<UiChoice>,
    enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val active = choices.find { it.id == value }
    val assets = LocalContext.current.assets
    Box {
        Surface(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(12.dp),
            color = Color.Transparent, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Выбрать: $title"
            }) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (active != null && active.color.isNotBlank()) Surface(
                    color = Color(android.graphics.Color.parseColor(active.color)),
                    border = BorderStroke(.8.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(7.dp)
                ) { Text("Aa", Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color(android.graphics.Color.parseColor(active.foreground)), style = MaterialTheme.typography.labelMedium) }
                Text(active?.label.orEmpty(), Modifier.padding(start = 8.dp).widthIn(max = 140.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .4f))
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 4.dp).size(18.dp))
            }
        }
        MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(18.dp))) {
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 220.dp, max = 300.dp)) {
                choices.forEach { choice ->
                    val font = remember(choice.fontAsset) { if (choice.fontAsset.isBlank()) null else
                        runCatching { FontFamily(Font("fonts/${choice.fontAsset}", assets)) }.getOrNull() }
                    val selected = value == choice.id
                    DropdownMenuItem(
                        text = { Text(choice.label, fontFamily = font, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = if (choice.color.isBlank()) null else ({
                            Surface(color = Color(android.graphics.Color.parseColor(choice.color)), shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(.8.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Text("Aa", Modifier.padding(6.dp), color = Color(android.graphics.Color.parseColor(choice.foreground)))
                            }
                        }),
                        trailingIcon = { if (selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.semantics { contentDescription = "$title: ${choice.label}"; this.selected = selected },
                        onClick = { expanded = false; onChange(choice.id) })
                }
            }
        }
    }
}

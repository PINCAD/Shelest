package com.lexiread.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared building blocks for both the app-level Settings screen and the
 * in-reader appearance sheet, so the two surfaces stay visually consistent
 * without duplicating composable code.
 */

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text     = title.uppercase(),
        style    = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape     = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
internal fun SwitchRow(
    icon: ImageVector, title: String, checked: Boolean, onToggle: (Boolean) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Weighted so a long label wraps instead of running under the switch.
        Row(
            modifier              = Modifier.weight(1f).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onToggle,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor  = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/**
 * A page palette, shown as a miniature page rather than a colour dot.
 *
 * The dot said what colour the paper was and nothing about what text on it looks like —
 * which is the actual question — and the label printed *on* the swatch meant white text
 * on a white chip in one mode and black on black in another. Now the swatch is a tiny
 * page with three ruled lines in that palette's own ink, and the label sits underneath
 * in the app's colours, legible in every mode.
 */
@Composable
internal fun RowScope.ReadingModeChip(
    mode: ReadingMode, current: ReadingMode, onSelect: (ReadingMode) -> Unit
) {
    val selected = mode == current
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "chip-border"
    )
    val pageBg = Color(mode.bgArgb)
    val ink    = Color(mode.fgArgb)

    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect(mode) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(pageBg)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = border,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Three ruled lines, the last one short — the universal shorthand for a
            // paragraph, at a size where actual text would be a smudge.
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (i == 2) 0.55f else 1f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(ink.copy(alpha = 0.55f))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            mode.label,
            modifier = Modifier.heightIn(min = 28.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Generic pill-style selector row, used for font family chips (Serif/Sans/Mono). */
@Composable
internal fun RowScope.OptionChip(
    label: String, selected: Boolean, onSelect: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "option-bg"
    )
    val fgColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "option-fg"
    )
    Surface(
        onClick  = onSelect,
        modifier = Modifier.weight(1f),
        shape    = RoundedCornerShape(10.dp),
        color    = bgColor
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = fgColor)
        }
    }
}

package com.shelest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal actual fun PanelColumn(kind: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(AccountUiTokens.CORNER.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(AccountUiTokens.PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(AccountUiTokens.GAP.dp)) { content() }
    }
}
@Composable internal actual fun PanelActions(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}
@Composable internal actual fun PanelProfile(initials: String, name: String, identifier: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(AccountUiTokens.AVATAR.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(initials, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(identifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable internal actual fun PanelText(text: String, kind: TextKind) {
    Text(text, style = if (kind == TextKind.TITLE) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
        color = when(kind) {
            TextKind.ERROR -> MaterialTheme.colorScheme.error
            TextKind.MUTED, TextKind.STATUS -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }, maxLines = if (kind == TextKind.STATUS) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
        modifier = if (kind == TextKind.ERROR || kind == TextKind.STATUS) Modifier.semantics {
            liveRegion = if (kind == TextKind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite
        } else Modifier)
}
@Composable internal actual fun PanelBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
@Composable internal actual fun PanelMeter(fraction: Float) {
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
        .semantics { contentDescription = "Занято в облаке" })
}
@Composable internal actual fun PanelButton(text: String, kind: ButtonKind, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = AccountUiTokens.BUTTON_HEIGHT.dp)
    val shape = RoundedCornerShape(16.dp)
    val icon = when(text) {
        "Обновить" -> Icons.Outlined.Sync
        "Выйти" -> Icons.Outlined.Logout
        "Статистика и цель дня" -> Icons.Outlined.BarChart
        "Сбросить акцентный цвет" -> Icons.Outlined.RestartAlt
        "Войти по логину" -> Icons.Outlined.PersonOutline
        else -> Icons.Outlined.AccountCircle
    }
    val content: @Composable RowScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (text == "Обновить") Surface(shape=shape,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        modifier=modifier.clip(shape).clickable(interactionSource=remember { MutableInteractionSource() },indication=null,enabled=enabled,role=Role.Button,onClick=onClick)) {
        Row(Modifier.padding(horizontal=24.dp,vertical=12.dp),content=content)
    }
    else if (kind == ButtonKind.PRIMARY) FilledTonalButton(onClick, modifier, enabled, shape = shape, content = content)
    else OutlinedButton(onClick, modifier, enabled, shape = shape, content = content)
}
@Composable internal actual fun PanelBusy() { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Обновление аккаунта" }) }
@Composable internal actual fun PanelToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icon = when(title) {
            "Автосинхронизация" -> Icons.Outlined.Sync
            "Удаление на всех устройствах" -> Icons.Outlined.DeleteOutline
            "Статистика чтения" -> Icons.Outlined.BarChart
            else -> null
        }
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

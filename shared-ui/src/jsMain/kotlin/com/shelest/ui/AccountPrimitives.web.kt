package com.shelest.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

@Composable internal actual fun PanelColumn(kind: String, content: @Composable () -> Unit) {
    Section(attrs = {
        classes("shelest-common-account", "cloud-summary")
        attr("data-common-ui", kind)
        attr("style", "display:flex;flex-direction:column;gap:${AccountUiTokens.GAP}px;padding:${AccountUiTokens.PADDING}px;border-radius:${AccountUiTokens.CORNER}px")
    }) { content() }
}
@Composable internal actual fun PanelActions(content: @Composable () -> Unit) {
    Div(attrs = { classes("cloud-actions"); attr("style","display:flex;flex-direction:column;gap:10px") }) { content() }
}
@Composable internal actual fun PanelProfile(initials: String, name: String, identifier: String) {
    Div(attrs = { classes("cloud-profile") }) {
        Span(attrs = { classes("cloud-avatar"); attr("aria-hidden","true");
            attr("style","width:${AccountUiTokens.AVATAR}px;height:${AccountUiTokens.AVATAR}px;flex-shrink:0") }) { Text(initials) }
        Div(attrs = { classes("cloud-identity"); attr("style","min-width:0;overflow-wrap:anywhere") }) {
            H2 { Text(name) }; P { Text(identifier) }
        }
    }
}
@Composable internal actual fun PanelText(text: String, kind: TextKind) {
    P(attrs = {
        classes(when(kind) { TextKind.ERROR -> "auth-error"; TextKind.STATUS -> "cloud-status"; else -> "cloud-description" })
        if (kind == TextKind.ERROR) attr("role","alert")
        if (kind == TextKind.STATUS) attr("role","status")
        attr("style","margin:0;overflow-wrap:anywhere")
        if (kind == TextKind.TITLE) attr("style","margin:0;font-size:17px;font-weight:600;color:var(--text)")
    }) { Text(text) }
}
@Composable internal actual fun PanelBadge(text: String) { Span(attrs = { classes("cloud-plan"); attr("style","align-self:flex-start") }) { Text(text) } }
@Composable internal actual fun PanelMeter(fraction: Float) {
    Div(attrs = { classes("cloud-meter"); attr("role","progressbar"); attr("aria-label","Занято в облаке");
        attr("aria-valuenow", (fraction * 100).toInt().toString()); attr("aria-valuemin","0"); attr("aria-valuemax","100") }) {
        Span(attrs = { attr("style","width:${fraction * 100}%") })
    }
}
@Composable internal actual fun PanelButton(text: String, kind: ButtonKind, enabled: Boolean, onClick: () -> Unit) {
    Button(attrs = { classes(if (kind == ButtonKind.PRIMARY) "cloud-primary" else "cloud-secondary")
        if(text=="Обновить") classes("sync-no-press")
        attr("type","button"); attr("style","min-height:${AccountUiTokens.BUTTON_HEIGHT}px;border-radius:16px;width:100%")
        if (!enabled) attr("disabled", "")
        onClick { onClick() }
    }) { Text(text) }
}
@Composable internal actual fun PanelBusy() {
    Div(attrs = { attr("role","status"); attr("aria-label","Обновление аккаунта"); classes("cloud-status") }) { Text("Подождите…") }
}
@Composable internal actual fun PanelToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Button(attrs = {
        classes("common-sync-toggle")
        attr("type","button"); attr("role","switch"); attr("aria-checked",checked.toString()); attr("aria-label",title)
        onClick { onChange(!checked) }
    }) {
        Span(attrs = { classes("setting-copy") }) {
            B { Text(title) }; Small { Text(description) }
        }
        Span(attrs = { classes("switch", if (checked) "on" else "off"); attr("aria-hidden","true") }) { Span() }
    }
}

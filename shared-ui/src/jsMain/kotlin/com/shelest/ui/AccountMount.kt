@file:OptIn(ExperimentalJsExport::class)

package com.shelest.ui

import androidx.compose.runtime.*
import com.shelest.shared.sync.SyncProtocol
import kotlinx.serialization.json.*
import org.jetbrains.compose.web.renderComposable

private fun state(json: String): AccountPanelState {
    val obj = SyncProtocol.objectOf(json)
    fun flag(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false
    fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val account = obj["account"]?.takeUnless { it == JsonNull }?.let { SyncProtocol.account(it.jsonObject) }
    return AccountPanelState(account, flag("busy"), text("status"), text("error"), flag("manage"),
        flag("sync"), flag("logout"), flag("google"), flag("password"))
}

@JsExport
class AccountPanelMount(elementId: String, initialState: String, onAction: (String) -> Unit) {
    private var current by mutableStateOf(state(initialState))
    private val composition = renderComposable(rootElementId = elementId) { AccountPanel(current, onAction) }
    fun update(json: String) { current = state(json) }
    fun dispose() { composition.dispose() }
}

private fun syncSettings(json: String): com.shelest.shared.SyncPreferences {
    val obj = SyncProtocol.objectOf(json)
    return com.shelest.shared.SyncPreferences((obj["autoSync"] as? JsonPrimitive)?.booleanOrNull ?: true,
        (obj["syncDeletions"] as? JsonPrimitive)?.booleanOrNull ?: true)
}
@JsExport
class SyncSettingsMount(elementId: String, initialState: String, onChange: (String, Boolean) -> Unit) {
    private var current by mutableStateOf(syncSettings(initialState))
    private val composition = renderComposable(rootElementId = elementId) {
        SyncSettingsPanel(current, {onChange("autoSync",it)}, {onChange("syncDeletions",it)})
    }
    fun update(json: String) { current = syncSettings(json) }
    fun dispose() { composition.dispose() }
}

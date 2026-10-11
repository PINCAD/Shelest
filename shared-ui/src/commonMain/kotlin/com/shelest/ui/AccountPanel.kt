package com.shelest.ui

import androidx.compose.runtime.Composable
import com.shelest.shared.AccountPresentation
import com.shelest.shared.AccountRules
import com.shelest.shared.sync.CloudAccount

/** No credentials or platform objects cross the view boundary. */
data class AccountPanelState(
    val account: CloudAccount? = null,
    val busy: Boolean = false,
    val status: String = "",
    val error: String = "",
    val manage: Boolean = false,
    val sync: Boolean = false,
    val logout: Boolean = false,
    val google: Boolean = false,
    val password: Boolean = false
)

object AccountUiTokens {
    const val CORNER = 24
    const val PADDING = 20
    const val GAP = 16
    const val AVATAR = 56
    const val BUTTON_HEIGHT = 48
}
enum class TextKind { TITLE, BODY, MUTED, ERROR, STATUS }
enum class ButtonKind { PRIMARY, SECONDARY }

/** Shared layout, copy, states and actions; only semantic primitives are platform adapters. */
@Composable
fun AccountPanel(state: AccountPanelState, onAction: (String) -> Unit) {
    val account = state.account
    val name = AccountRules.displayName(account)
    PanelColumn("account") {
        PanelProfile(if (account == null) "Ш" else AccountPresentation.initials(name),
            name, account?.email?.ifBlank { account.login }?.ifBlank { "Аккаунт Шелест" }
                ?: "Книги и место чтения на ваших устройствах")
        if (account != null) {
            PanelBadge(if (account.isPremium) "Шелест Premium" else "Бесплатный аккаунт")
            if (account.isPremium) {
                PanelText("Облачное хранилище", TextKind.MUTED)
                PanelText("${AccountPresentation.formatBytes(account.storageUsedBytes.toDouble())} из ${AccountPresentation.formatBytes(account.quotaBytes.toDouble())}", TextKind.BODY)
                PanelMeter(AccountPresentation.storageFraction(account.storageUsedBytes.toDouble(), account.quotaBytes.toDouble()).toFloat())
            } else PanelText("Место чтения синхронизируется бесплатно. Хранение файлов книг доступно с Premium.", TextKind.MUTED)
        }
        if (state.manage) PanelButton(if (account == null) "Войти в аккаунт" else "Управление аккаунтом",
            ButtonKind.PRIMARY, !state.busy) { onAction("manage") }
        if (account == null) {
            if (state.google) PanelButton(if (state.busy) "Входим…" else "Продолжить с Google",
                ButtonKind.PRIMARY, !state.busy) { onAction("google") }
            if (state.password) PanelButton("Войти по логину", ButtonKind.SECONDARY, !state.busy) { onAction("password") }
        } else if (state.sync || state.logout) {
            PanelActions {
                if (state.sync) PanelButton("Обновить",
                    ButtonKind.PRIMARY, !state.busy) { onAction("sync") }
                if (state.logout) PanelButton("Выйти", ButtonKind.SECONDARY, !state.busy) { onAction("logout") }
            }
        }
        if (state.busy && account == null) PanelBusy()
        if (state.error.isNotBlank()) PanelText(state.error, TextKind.ERROR)
        else if (account != null || state.status.isNotBlank()) PanelText(state.status.ifBlank { "Ещё не синхронизировано" }, TextKind.STATUS)
    }
}

fun syncAgeLabel(lastSync: Long, now: Long): String {
    if (lastSync <= 0) return "Ещё не синхронизировано"
    val minutes = ((now - lastSync).coerceAtLeast(0) / 60000)
    return when {
        minutes < 1 -> "Синхронизация: только что"
        minutes < 60 -> "Синхронизация: $minutes мин назад"
        minutes < 1440 -> "Синхронизация: ${minutes / 60} ч назад"
        else -> "Синхронизация: ${minutes / 1440} дн назад"
    }
}

@Composable internal expect fun PanelColumn(kind: String, content: @Composable () -> Unit)
@Composable internal expect fun PanelActions(content: @Composable () -> Unit)
@Composable internal expect fun PanelProfile(initials: String, name: String, identifier: String)
@Composable internal expect fun PanelText(text: String, kind: TextKind)
@Composable internal expect fun PanelBadge(text: String)
@Composable internal expect fun PanelMeter(fraction: Float)
@Composable internal expect fun PanelButton(text: String, kind: ButtonKind, enabled: Boolean, onClick: () -> Unit)
@Composable internal expect fun PanelBusy()
@Composable internal expect fun PanelToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit)

@Composable
fun SyncSettingsPanel(settings: com.shelest.shared.SyncPreferences,
    onAutoSyncChange: (Boolean) -> Unit, onSyncDeletionsChange: (Boolean) -> Unit) {
    PanelColumn("sync-settings") {
        PanelText("Синхронизация", TextKind.TITLE)
        PanelToggle("Автосинхронизация", "Обновлять книги и место чтения", settings.autoSync, onAutoSyncChange)
        PanelToggle("Удаление на всех устройствах", "Если выключено, только на этом устройстве", settings.syncDeletions, onSyncDeletionsChange)
    }
}

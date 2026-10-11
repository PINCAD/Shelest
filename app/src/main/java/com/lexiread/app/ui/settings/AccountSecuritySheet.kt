package com.lexiread.app.ui.settings

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shelest.shared.sync.CloudAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Secrets stay in memory only; screenshots and the recent-app thumbnail are blocked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSecuritySheet(account: CloudAccount, onRequest: suspend (JSONObject) -> JSONObject,
    onReauthenticate: () -> Unit, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf("") }
    var login by remember { mutableStateOf(account.login) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val wasSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun submit(next: String) {
        if (next == "credentials" && password != confirmation) { error = "Пароли не совпадают"; return }
        if (next == "credentials" && (!Regex("[a-z0-9][a-z0-9_.-]{2,31}").matches(login.trim().lowercase()) ||
            login.trim().lowercase().startsWith("g_"))) { error = "Логин: 3–32 символа, латиница, цифры, точка, дефис или подчёркивание"; return }
        if (next == "credentials" && (password.isNotEmpty() || !account.hasPassword) && password.length < 8) {
            error = "Пароль должен содержать не меньше 8 символов"; return
        }
        busy = true; error = ""; message = ""
        scope.launch {
            try {
                val result = onRequest(JSONObject().put("action", next).put("login", login.trim().lowercase())
                    .put("password", password).put("currentPassword", currentPassword).put("code", code.trim()))
                password = ""; confirmation = ""; currentPassword = ""; code = ""
                if (result.has("secret")) { secret = result.getString("secret"); action = "confirm" }
                else if (result.has("recoveryCodes")) {
                    val codes = result.getJSONArray("recoveryCodes")
                    recovery = (0 until codes.length()).map(codes::getString)
                    secret = ""; action = ""
                } else { action = ""; message = "Настройки безопасности сохранены" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось сохранить настройки" }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Безопасность аккаунта", style = MaterialTheme.typography.titleLarge)
            if (recovery.isNotEmpty()) {
                Text("Двухфакторная защита включена", style = MaterialTheme.typography.titleMedium)
                Text("Сохраните резервные коды в безопасном месте. Каждый код одноразовый; после закрытия этого окна они больше не показываются.")
                SelectionContainer { Text(recovery.joinToString("\n")) }
                Button(onDismiss, Modifier.fillMaxWidth()) { Text("Коды сохранены") }
            } else if (action.isBlank()) {
                OutlinedButton({ action = "credentials" }, Modifier.fillMaxWidth()) { Text("Логин и пароль") }
                OutlinedButton({ action = if (account.twoFactorEnabled) "disable" else "start" }, Modifier.fillMaxWidth()) {
                    Text(if (account.twoFactorEnabled) "Отключить двухфакторную защиту" else "Настроить двухфакторную защиту")
                }
                Text(if (account.twoFactorEnabled) "Двухфакторная защита включена" else "Двухфакторная защита выключена",
                    style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message)
                TextButton(onDismiss, Modifier.fillMaxWidth()) { Text("Закрыть") }
            } else {
                Text(when(action) { "credentials" -> "Логин и пароль"; "confirm" -> "Подтвердите подключение";
                    "disable" -> "Отключение защиты"; else -> "Подключение аутентификатора" }, style = MaterialTheme.typography.titleMedium)
                if (action == "credentials") {
                    OutlinedTextField(login, { login = it.take(32) }, enabled = !busy, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it.take(128) }, enabled = !busy, label = { Text("Новый пароль") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirmation, { confirmation = it.take(128) }, enabled = !busy, label = { Text("Повторите новый пароль") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (account.hasPassword) Text("Оставьте новый пароль пустым, чтобы изменить только логин.", style = MaterialTheme.typography.bodySmall)
                }
                if (action == "confirm") {
                    Text("В аутентификаторе выберите добавление ключа вручную: Шелест, ключ ниже, код на основе времени (TOTP).")
                    SelectionContainer { Text(secret) }
                } else if (account.hasPassword) {
                    OutlinedTextField(currentPassword, { currentPassword = it.take(128) }, enabled = !busy,
                        label = { Text("Текущий пароль") }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Для изменения настроек подтвердите Google-вход заново. Подтверждение действует 5 минут.")
                    TextButton(onReauthenticate, enabled = !busy) { Text("Подтвердить через Google") }
                }
                if (account.twoFactorEnabled || action == "confirm") OutlinedTextField(code, { code = it.take(32) },
                    enabled = !busy, label = { Text(if (action == "confirm") "Код аутентификатора" else "Код или резервный код") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Изменение безопасности завершит другие сеансы аккаунта.", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Button({ submit(action) }, Modifier.fillMaxWidth(), enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(when(action) { "start" -> "Получить ключ"; "confirm" -> "Подтвердить код"; "disable" -> "Отключить защиту"; else -> "Сохранить" })
                }
                TextButton({ password = ""; confirmation = ""; currentPassword = ""; code = ""; secret = ""; action = ""; error = "" },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
            }
        }
    }
}

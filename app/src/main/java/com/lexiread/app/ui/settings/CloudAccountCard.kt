package com.lexiread.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.lexiread.app.LexiReadApp
import com.shelest.shared.AuthForm
import com.shelest.shared.sync.SyncProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountCard() {
    val context = LocalContext.current
    val cloud = (context.applicationContext as LexiReadApp).cloud
    val account by cloud.account.collectAsState()
    val syncing by cloud.busy.collectAsState()
    val status by cloud.status.collectAsState()
    val lastSync by cloud.lastSuccessfulSync.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60000); now = System.currentTimeMillis() } }
    val autoSync by cloud.autoSync.collectAsState()
    val syncDeletions by cloud.syncDeletions.collectAsState()
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var passwordDialog by remember { mutableStateOf(false) }
    var securityDialog by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var mfaChallenge by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val who = account
    val sharedAccount = who?.let { runCatching { SyncProtocol.account(it.toString()) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.shelest.ui.AccountPanel(com.shelest.ui.AccountPanelState(
            account = sharedAccount, busy = signingIn || syncing,
            status = if (sharedAccount == null) "" else com.shelest.ui.syncAgeLabel(lastSync, maxOf(now, lastSync)),
            error = error.ifBlank { status.takeUnless { it == "Синхронизировано" }.orEmpty() },
            sync = sharedAccount != null, logout = sharedAccount != null,
            google = sharedAccount == null, password = sharedAccount == null
        )) { action ->
            when (action) {
                "google" -> {
                    signingIn = true; error = ""
                    scope.launch {
                        try { cloud.signIn(context) }
                        catch (e: CancellationException) { throw e }
                        catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) { }
                        catch (e: com.lexiread.app.data.cloud.CloudSync.MfaRequired) { mfaChallenge = e.challenge; error = "" }
                        catch (e: Exception) { error = e.message ?: "Не удалось войти" }
                        finally { signingIn = false }
                    }
                }
                "password" -> { register = false; error = ""; passwordDialog = true }
                "sync" -> scope.launch { cloud.synchronize() }
                "logout" -> scope.launch { cloud.signOut() }
            }
        }
        com.shelest.ui.SyncSettingsPanel(com.shelest.shared.SyncPreferences(autoSync, syncDeletions),
            cloud::setAutoSync, cloud::setSyncDeletions)
        if (sharedAccount != null) OutlinedButton({ securityDialog = true }, Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Security, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Безопасность аккаунта")
        }
    }
    if (securityDialog && sharedAccount != null) AccountSecuritySheet(sharedAccount,
        onRequest = cloud::manageAccountSecurity, onDismiss = { securityDialog = false }, onReauthenticate = {
            securityDialog = false; signingIn = true; error = ""
            scope.launch {
                try { cloud.signIn(context); securityDialog = true }
                catch (e: CancellationException) { throw e }
                catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) { }
                catch (e: com.lexiread.app.data.cloud.CloudSync.MfaRequired) { mfaChallenge = e.challenge }
                catch (e: Exception) { error = e.message ?: "Не удалось подтвердить вход" }
                finally { signingIn = false }
            }
        })
    if (mfaChallenge.isNotBlank()) AlertDialog(
        onDismissRequest = { if (!signingIn) { mfaChallenge = ""; mfaCode = "" } },
        title = { Text("Подтвердите вход") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(mfaCode, { mfaCode = it.take(32) }, label = { Text("Код или резервный код") }, singleLine = true)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !signingIn && mfaCode.isNotBlank(), onClick = {
            signingIn = true; error = ""
            scope.launch { try { cloud.finishMfa(mfaChallenge, mfaCode); mfaChallenge = ""; mfaCode = ""; password = "" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Не удалось войти" }
                finally { signingIn = false } }
        }) { Text("Войти") } },
        dismissButton = { TextButton(enabled = !signingIn, onClick = { mfaChallenge = ""; mfaCode = "" }) { Text("Отмена") } }
    )
    if (passwordDialog && account == null) ModalBottomSheet(
        onDismissRequest = { if (!signingIn) { passwordDialog = false; password = ""; confirmation = "" } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (register) "Создать аккаунт" else "С возвращением", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Продолжайте чтение на любом устройстве", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!register, { if (!signingIn) { register = false; error = "" } }, label = { Text("Вход") })
                FilterChip(register, { if (!signingIn) { register = true; error = "" } }, label = { Text("Регистрация") })
            }
            OutlinedTextField(login, { login = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("Логин", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(16.dp), singleLine = true, enabled = !signingIn,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false), leadingIcon = { Icon(Icons.Outlined.PersonOutline, null) })
            OutlinedTextField(password, { password = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("Пароль", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(16.dp), singleLine = true, enabled = !signingIn,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "Скрыть пароль" else "Показать пароль") } })
            if (register) OutlinedTextField(confirmation, { confirmation = it.take(128) }, Modifier.fillMaxWidth(),
                label = { Text("Повторите пароль", style = MaterialTheme.typography.bodyMedium) }, textStyle = MaterialTheme.typography.bodyMedium,
                supportingText = { Text("Не меньше 8 символов") }, shape = RoundedCornerShape(16.dp),
                singleLine = true, enabled = !signingIn, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp), enabled = !signingIn, onClick = {
                error = AuthForm.validate(login, password, confirmation, register)
                if (error.isNotBlank()) return@Button
                signingIn = true
                scope.launch {
                    try { cloud.signInWithPassword(login.trim(), password, register); passwordDialog = false; password = ""; confirmation = "" }
                    catch (e: CancellationException) { throw e }
                    catch (e: com.lexiread.app.data.cloud.CloudSync.MfaRequired) { mfaChallenge = e.challenge; passwordDialog = false; error = "" }
                    catch (e: Exception) { error = e.message ?: "Не удалось войти" }
                    finally { signingIn = false }
                }
            }) {
                if (signingIn) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
                Text(if (signingIn) "Подождите…" else if (register) "Создать аккаунт" else "Войти")
            }
        }
    }
}

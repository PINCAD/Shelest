package com.shelest.shared

import com.shelest.shared.sync.*
import kotlinx.serialization.json.*

object AuthProtocol {
    const val GOOGLE_WEB_CLIENT_ID = "710173618689-0uaan7qturrea27viom0ht84pdlkcja4.apps.googleusercontent.com"
    fun passwordPath(web: Boolean, register: Boolean): String =
        "auth/" + (if (web) "web/" else "") + if (register) "register" else "password"
    fun passwordPayload(login: String, password: String, register: Boolean, deviceName: String? = null): String {
        val invalid = AuthForm.validate(login, password, password, register)
        require(invalid.isEmpty()) { invalid }
        return buildJsonObject {
            put("login", login.trim()); put("password", password)
            deviceName?.let { put("deviceName", it.take(200)) }
        }.toString()
    }
    fun errorMessage(error: String, status: Int, messages: List<String> = emptyList()): String = when (error) {
        "invalid_credentials" -> "Неверный логин или пароль"
        "invalid_login" -> "Логин: от 3 до 32 символов, латиница, цифры, точка, дефис или подчёркивание"
        "login_unavailable" -> "Этот логин уже занят"
        "rate_limited" -> "Слишком много попыток. Попробуйте через 5 минут"
        "invalid_mfa_code" -> "Неверный или уже использованный код"
        "mfa_challenge_expired" -> "Подтверждение истекло. Начните вход заново"
        "reauthentication_required" -> "Подтвердите текущий пароль и код; для Google войдите заново"
        "password_required" -> "Укажите новый пароль"
        "mfa_already_enabled" -> "Двухфакторная защита уже включена"
        "use_account_security" -> "Измените пароль в настройках безопасности аккаунта"
        "weak_password" -> messages.filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Выберите более надёжный пароль" }
        else -> if (status == 401) "Войдите в аккаунт повторно" else "Ошибка сервера: ${error.ifBlank { status.toString() }}"
    }
    fun errorJson(json: String, status: Int): String {
        val obj = runCatching { SyncProtocol.objectOf(json) }.getOrDefault(buildJsonObject {})
        val messages = (obj["messages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        return errorMessage(obj.string("error"), status, messages)
    }
}

/** Device-specific presentation stays in UI; account semantics come only from the server. */
object AccountRules {
    fun displayName(account: CloudAccount?) = account?.displayName?.ifBlank { account.login }?.ifBlank { "Аккаунт Шелест" } ?: "Аккаунт Шелест"
    fun canTransferBooks(account: CloudAccount?) = account?.isPremium == true
    fun showLoginForm(signedIn: Boolean) = !signedIn
}

data class SyncPreferences(val autoSync: Boolean = SyncPolicy.DEFAULT_AUTO_SYNC, val syncDeletions: Boolean = SyncPolicy.DEFAULT_SYNC_DELETIONS)

/** Validate values at both read and write boundaries without replacing explicit preferences. */
object SettingsRules {
    const val DEFAULT_FONT_SIZE = 18.0
    const val DEFAULT_LINE_HEIGHT = 1.85
    const val DEFAULT_MARGIN = 24
    const val MAX_DAILY_GOAL = 240
    fun fontSize(value: Double, fallback: Double = DEFAULT_FONT_SIZE): Double = finite(value, fallback).coerceIn(12.0, 36.0)
    fun lineHeight(value: Double, fallback: Double = DEFAULT_LINE_HEIGHT): Double = finite(value, fallback).coerceIn(1.2, 2.5)
    fun margin(value: Int) = value.coerceIn(8, 48)
    fun pdfZoom(value: Double) = finite(value, 1.0).coerceIn(1.0, 4.0)
    fun dailyGoal(value: Int) = value.coerceIn(0, MAX_DAILY_GOAL)
    private fun finite(value: Double, fallback: Double) = if (value.isFinite()) value else fallback

    /** Keep old web defaults, IDs, extra fields and existing choices; no IndexedDB migration. */
    fun webSettings(json: String): String {
        val saved = runCatching { SyncProtocol.objectOf(json) }.getOrDefault(buildJsonObject {})
        fun numeric(key: String, default: Double) = (saved[key] as? JsonPrimitive)?.doubleOrNull ?: default
        fun boolean(key: String, default: Boolean) = (saved[key] as? JsonPrimitive)?.booleanOrNull ?: default
        fun choice(key: String, options: Set<String>, default: String) = saved.string(key).takeIf { it in options } ?: default
        return buildJsonObject {
            saved.forEach { (key, value) -> put(key, value) }
            put("fontSize", fontSize(numeric("fontSize", 19.0), 19.0))
            put("lineHeight", lineHeight(numeric("lineHeight", 1.72), 1.72))
            put("fontFamily", choice("fontFamily", setOf("serif", "sans", "literata", "lora", "alegreya", "golos", "inter", "mono"), "serif"))
            put("pageTheme", choice("pageTheme", setOf("light", "sepia", "gray", "mint", "fog", "dark", "midnight", "black"), "light"))
            put("margin", numeric("margin", 24.0).coerceIn(8.0, 64.0))
            put("justify", boolean("justify", true))
            put("pageAnimation", choice("pageAnimation", setOf("slide", "fade", "flip", "none"), "slide"))
            put("readingMode", choice("readingMode",setOf("pages","scroll"),"pages"))
            put("twoPage", boolean("twoPage",true))
            put("speechRate", numeric("speechRate", 1.0).coerceIn(0.5, 2.0))
            put("pdfZoom", pdfZoom(numeric("pdfZoom", 1.0)))
            put("darkApp", boolean("darkApp", false)); put("dailyGoal", dailyGoal(numeric("dailyGoal", 0.0).toInt()))
            put("themeMode", choice("themeMode", setOf("SYSTEM", "LIGHT", "DARK"),
                if (saved["darkApp"] is JsonPrimitive) if (boolean("darkApp", false)) "DARK" else "LIGHT" else "SYSTEM"))
            put("accentChoice", choice("accentChoice", setOf("DEFAULT", "BLUE", "GREEN", "VIOLET", "AMBER", "TEAL"), "DEFAULT"))
            put("showStats", boolean("showStats", true))
            put("autoSync", boolean("autoSync", SyncPolicy.DEFAULT_AUTO_SYNC))
            put("syncDeletions", boolean("syncDeletions", SyncPolicy.DEFAULT_SYNC_DELETIONS))
        }.toString()
    }
}

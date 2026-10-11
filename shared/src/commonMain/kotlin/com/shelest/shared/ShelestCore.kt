package com.shelest.shared

/** Platform-independent rules; storage, credentials and network stay in adapters. */
object SyncPolicy {
    const val DEFAULT_AUTO_SYNC = true
    const val DEFAULT_SYNC_DELETIONS = true
    const val ACTIVE_INTERVAL_MS = 60_000L
    const val IDLE_INTERVAL_MS = 300_000L

    fun canPublishPosition(enabled: Boolean, signedIn: Boolean) = enabled && signedIn
    fun blocksBook(hidden: Boolean, pendingDelete: Boolean, deleted: Boolean, restoring: Boolean) =
        hidden || pendingDelete || (deleted && !restoring)
    fun intervalMs(enabled: Boolean) = if (enabled) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
}

object AccountPresentation {
    fun initials(name: String): String = name.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }.take(2).map { it.take(1).uppercase() }.joinToString("").ifBlank { "Ш" }

    fun storageFraction(used: Double, quota: Double): Double =
        if (quota <= 0 || !quota.isFinite() || !used.isFinite()) 0.0 else (used / quota).coerceIn(0.0, 1.0)

    fun formatBytes(bytes: Double): String {
        val value = if (bytes.isFinite()) bytes.coerceAtLeast(0.0) else 0.0
        val unit = if (value >= 1024 * 1024 * 1024) 1024.0 * 1024 * 1024 else 1024.0 * 1024
        val rounded = kotlin.math.round(value / unit * 10).toLong()
        val number = if (rounded % 10L == 0L) "${rounded / 10}" else "${rounded / 10},${rounded % 10}"
        return "$number ${if (unit > 1024 * 1024) "ГБ" else "МБ"}"
    }
}

object AuthForm {
    fun validate(login: String, password: String, confirmation: String, register: Boolean): String {
        if (login.isBlank()) return "Введите логин"
        if (password.isEmpty()) return "Введите пароль"
        if (password.length > 128) return "Пароль должен содержать не больше 128 символов"
        if (register) {
            if (!Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,31}").matches(login.trim()))
                return "Логин: от 3 до 32 символов, латиница, цифры, точка, дефис или подчёркивание"
            if (password.length < 8) return "Пароль должен содержать не меньше 8 символов"
            if (password != confirmation) return "Пароли не совпадают"
        }
        return ""
    }
}

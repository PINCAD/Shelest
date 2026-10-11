package com.lexiread.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Design tokens — Light theme ─────────────────────────────────────────────
val LightSurface0     = Color(0xFFFFFFFF) // page / scaffold background
val LightSurface1     = Color(0xFFF7F7F7) // card / elevated surface
val LightSurface2     = Color(0xFFEFEFEF) // input fields, chips, pressed states
val LightTextPrimary  = Color(0xFF1A1A1A)
val LightTextSecondary= Color(0xFF6B6B6B)
val LightBorder       = Color(0xFFE2E2E2) // hairline, never black

// ── Design tokens — Dark theme ───────────────────────────────────────────────
val DarkSurface0      = Color(0xFF121212)
val DarkSurface1      = Color(0xFF1B1B1B)
val DarkSurface2      = Color(0xFF262626)
val DarkTextPrimary   = Color(0xFFF2F2F2)
val DarkTextSecondary = Color(0xFF9A9A9A)
val DarkBorder        = Color(0xFF313131)

// ── Single accent color — muted brick red ────────────────────────────────────
// Used for the plus button, primary links/buttons, active states. No other
// hue may appear anywhere else in the UI.
val AccentLight   = Color(0xFFA8493D)
val OnAccentLight = Color(0xFFFFFFFF)
val AccentDark    = Color(0xFFCB7C6E)
val OnAccentDark  = Color(0xFF1A1210)

// ── Reading surface ────────────────────────────────────────────────────────────
// Only the PDF page needs a colour from here: a rendered PDF carries its own white
// paper, so the surface behind it has to be white too whatever the app theme is.
// Every reflowable format takes its page colours from ReadingMode instead, which is
// the one the reader can actually choose between.
val ReadBg_Light = LightSurface0

// ── Semantic error ────────────────────────────────────────────────────────────
val ErrorRed = Color(0xFFB3261E)
val OnError  = Color(0xFFFFFFFF)

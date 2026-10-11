package com.lexiread.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// One sans-serif family for all UI chrome, including Material text fields and dialogs.
// Book fonts are selected independently by the reader engine. Exactly two weights app-wide:
// Normal and SemiBold — nothing else is used anywhere.
private val UiFont = FontFamily.SansSerif

// Fixed type scale: screen title 22sp / card title 16sp / body 15sp / caption 13sp.
val LexiReadTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp, lineHeight = 40.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.Normal,
        fontSize = 24.sp, lineHeight = 30.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    // Screen title — 22sp
    titleLarge = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    // Card title — 16sp
    titleMedium = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    // Material's default field/dialog body must match the rest of the interface.
    bodyLarge = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 28.sp,
    ),
    // Body text — 15sp
    bodyMedium = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    // Caption / meta — 13sp
    bodySmall = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = UiFont, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.sp,
    ),
)

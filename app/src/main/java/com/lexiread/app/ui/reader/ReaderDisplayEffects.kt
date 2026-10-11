package com.lexiread.app.ui.reader

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Reader-only overrides; neither system brightness nor other screens are changed. */
@Composable
internal fun ReaderDisplayEffects(brightness: Float, keepScreenOn: Boolean, autoScrolling: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn || autoScrolling) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn || autoScrolling
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(view, brightness) {
        val window = (view.context as? Activity)?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null) window.attributes = window.attributes.apply { screenBrightness = brightness }
        onDispose { if (window != null && previous != null) window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

package com.lexiread.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The three home-screen widgets, which differ only in how much room they have.
 *
 * Android has no way to declare one widget with a choice of sizes — each shape needs its own
 * receiver so the picker can show it with its own preview and its own description. They share
 * everything else; see [BookWidget].
 */
abstract class BookWidgetProvider : AppWidgetProvider() {

    protected abstract val size: WidgetSize

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // The database read cannot happen on the broadcast thread, and returning from onUpdate
        // is what tells the system the work is over — goAsync buys the time in between.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val views = BookWidget.render(context, BookWidget.load(context), size)
                appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
            } catch (e: Exception) {
                // A widget that fails to draw must not take the launcher's broadcast down with
                // it; the old view stays on screen and the next update tries again.
            } finally {
                pending.finish()
            }
        }
    }
}

/** Two cells by two: cover, title, position. */
class SmallBookWidget : BookWidgetProvider() {
    override val size = WidgetSize.SMALL
}

/**
 * Four cells by two — the middle size, and the one that was here first.
 *
 * The name is the reason it is not called MediumBookWidget: a widget already sitting on
 * somebody's home screen is bound to the provider class that declares it, and renaming the
 * class turns theirs into a grey box.
 */
class CurrentBookWidget : BookWidgetProvider() {
    override val size = WidgetSize.MEDIUM
}

/** Four by four: the current book, and the three behind it on the shelf. */
class LargeBookWidget : BookWidgetProvider() {
    override val size = WidgetSize.LARGE
}

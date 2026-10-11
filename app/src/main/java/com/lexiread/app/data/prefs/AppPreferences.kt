package com.lexiread.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * The app's one preferences file, and the keys in it.
 *
 * This used to live inside SettingsViewModel, which was fine while the only thing that read a
 * preference was a screen. The daily goal is not that: it is decided on a screen and then acted
 * on from a repository, minutes after the screen is gone. A `preferencesDataStore` delegate is a
 * singleton per file name and declaring a second one for "pebble_settings" elsewhere crashes at
 * the first read, so there is exactly one declaration and it sits here, below the UI rather than
 * inside it.
 *
 * The file name is unchanged, so every setting a reader has already chosen survives the move.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pebble_settings")

internal object PrefKeys {
    val FONT_SIZE       = floatPreferencesKey("font_size")
    val FONT_FAMILY     = stringPreferencesKey("font_family")
    val LINE_HEIGHT     = floatPreferencesKey("line_height")
    val MARGIN_DP       = intPreferencesKey("margin_dp")
    val IS_DARK         = booleanPreferencesKey("is_dark")
    val THEME_MODE      = stringPreferencesKey("app_theme_mode")
    val ACCENT_COLOR    = stringPreferencesKey("app_accent_color")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val READ_MODE       = stringPreferencesKey("read_mode")
    val JUSTIFY_TEXT    = booleanPreferencesKey("justify_text")
    val SHOW_PAGES      = booleanPreferencesKey("show_pages")
    val HORIZONTAL_PAG  = booleanPreferencesKey("horizontal_pag")
    val SHOW_PROGRESS   = booleanPreferencesKey("show_progress")
    val PAGE_ANIM       = stringPreferencesKey("page_anim")
    val TTS_NET_VOICE   = booleanPreferencesKey("tts_network_voice")
    val PDF_ZOOM        = floatPreferencesKey("pdf_zoom")
    val PDF_VERTICAL    = booleanPreferencesKey("pdf_vertical_paging")
    val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
    val KEEP_READER_SCREEN = booleanPreferencesKey("keep_reader_screen")
    val HOME_STATS      = booleanPreferencesKey("home_show_stats")

    /** Minutes a day the reader is aiming for, or 0 for no goal. See ReadingGoal. */
    val GOAL_MINUTES    = intPreferencesKey("goal_minutes")

    /**
     * The last day whose goal was celebrated, as `yyyy-MM-dd`.
     *
     * Crossing the line already happens once a day on its own — the total only grows — but a
     * reader who lowers the goal in the evening would cross it again on the next minute, and
     * be congratulated for the same afternoon twice.
     */
    val GOAL_CELEBRATED = stringPreferencesKey("goal_celebrated_date")
}

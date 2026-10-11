package com.lexiread.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.app.data.prefs.PrefKeys
import com.lexiread.app.data.prefs.settingsDataStore
import com.lexiread.app.domain.goal.ReadingGoal
import com.lexiread.app.ui.theme.AccentChoice
import com.lexiread.app.ui.theme.AppThemeMode
import com.shelest.shared.SettingsRules
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Page palettes. Each mode carries its own colours so there is exactly one place to
 * change them — they used to be spread across four parallel `when` blocks plus a set of
 * theme constants, which is three chances to add a mode and forget one.
 *
 * ARGB longs rather than hex strings: Compose wants a Color, the reader's CSS wants
 * `#RRGGBB`, and deriving both from one number keeps the swatch in the settings sheet
 * honest about what the page will actually look like.
 *
 * Entry names are persisted, so LIGHT/SEPIA/DARK keep theirs.
 */
enum class ReadingMode(
    val label: String,
    val bgArgb: Long,
    val fgArgb: Long,
    /** Whether the page is dark enough to need light status-bar icons over it. */
    val isDarkPage: Boolean
) {
    LIGHT("Белый",  0xFFFFFFFF, 0xFF1A1A1A, false),
    SEPIA("Сепия",  0xFFF8F2E3, 0xFF3B2F20, false),
    GRAY ("Серый",  0xFFE9E7E2, 0xFF2C2A26, false),
    MINT ("Мятный", 0xFFE1EBE2, 0xFF22301F, false),
    FOG  ("Голубой туман", 0xFFE8EEF0, 0xFF23343B, false),
    DARK ("Тёмный", 0xFF121212, 0xFFF2F2F2, true),
    MIDNIGHT("Ночной синий", 0xFF17232C, 0xFFD8E3E6, true),
    BLACK("Чёрный", 0xFF000000, 0xFFC7C7C7, true)
}

/**
 * How a page turn is drawn in horizontal paging mode. Purely presentational — the page
 * you land on is the same either way — so it is safe to change mid-book, and "Без
 * анимации" exists because on a slow device an animation is just latency.
 */
enum class PageTurnAnimation(val label: String, val js: String) {
    SLIDE("Сдвиг",       "slide"),
    /**
     * A sheet folding over on itself, draggable by the edge — the fold follows the finger
     * and the page is held wherever it is let go. It is the only mode drawn outside the
     * document, over a photograph of the page taken as the finger goes down, so it is also
     * the only one holding screen-sized bitmaps — two of them, since the sheet that folds
     * is the page you are leaving going forward and the page before it going back, and
     * the second has to be photographed in advance. See PageCurl.kt.
     */
    CURL ("Как в книге", "curl"),
    FADE ("Затухание",   "fade"),
    FLIP ("Поворот",     "flip"),
    NONE ("Без анимации","none")
}

/**
 * Reading faces bundled in assets/fonts. All of them carry Cyrillic — a beautiful face
 * that falls back to the system font for half the alphabet is worse than none.
 *
 * `cssName` is the @font-face family declared in buildPagedHtml; `asset` is the file it
 * loads, or null for the system monospace. The newer faces ship as single variable
 * fonts, so one file covers every weight instead of one file per weight.
 *
 * Entry names are persisted in DataStore — SERIF/SANS/MONO keep their old names so a
 * reader's saved choice survives the addition of the others.
 */
enum class ReaderFontFamily(
    val cssName: String,
    val label: String,
    val asset: String? = null,
    val boldAsset: String? = null
) {
    SERIF("Shelest PT Serif", "PT Serif", "PTSerif-Regular.ttf", "PTSerif-Bold.ttf"),
    LITERATA("Shelest Literata", "Literata", "Literata.ttf"),
    LORA("Shelest Lora", "Lora", "Lora.ttf"),
    ALEGREYA("Shelest Alegreya", "Alegreya", "Alegreya.ttf"),
    SANS("Shelest PT Sans", "PT Sans", "PTSans-Regular.ttf", "PTSans-Bold.ttf"),
    GOLOS("Shelest Golos", "Golos", "GolosText.ttf"),
    INTER("Shelest Inter", "Inter", "Inter.ttf"),
    MONO("monospace", "Моно")
}

data class ReaderSettings(
    val fontSize: Float              = 18f,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SERIF,
    val lineHeightMultiplier: Float  = 1.85f,
    val marginDp: Int                = 24,
    val readingMode: ReadingMode     = ReadingMode.SEPIA,
    val justifyText: Boolean         = true,
    val showPageNumbers: Boolean     = true,
    val horizontalScrolling: Boolean = true,
    /**
     * The thin accent rule along the top of the page. Off by default and, like every
     * other reading setting, one choice for the whole library rather than per book —
     * a reader who doesn't want a progress meter doesn't want it in book two either.
     */
    val showProgressBar: Boolean     = false,
    val pageAnimation: PageTurnAnimation = PageTurnAnimation.SLIDE,
    /**
     * Whether read-aloud may use a voice that synthesises on Google's servers. Those voices
     * are the ones that sound like a person rather than a machine, and there is no offline
     * equivalent — so this is the whole difference between a listenable book and a flat one.
     *
     * On by default because that is what makes the feature worth using, but it is a switch
     * rather than a silent choice: it means every sentence of the book crosses the network,
     * which matters on a metered connection and stops working underground. Off falls back
     * to the best installed offline voice, which still reads correctly, just tonelessly.
     */
    val preferNetworkVoice: Boolean  = true,
    /**
     * How much a PDF page is magnified, 1.0 being fitted to the width of the screen.
     *
     * PDF is the one format the font size does not reach: its pages are pictures with the type
     * already set into them, so the only way to make the print bigger is to make the page
     * bigger. Kept here with the rest of the reading settings, and so remembered between
     * sessions, because a reader who needs 1.5× needs it in the next document too — pinching
     * back to it at every opening is the same chore the font size exists to avoid.
     */
    val pdfZoom: Float               = 1f,
    /**
     * Which way a PDF turns: down, like a scrolled document, or sideways, like a book.
     *
     * Sideways by default because that is what the rest of the reader does, but a PDF is often
     * a document rather than a book — a paper, a manual — and the hand that reads those expects
     * the next page to be below this one. It also frees the sideways swipe entirely, which
     * matters once the page is magnified and cropped at the sides.
     */
    val pdfVerticalPaging: Boolean   = false,
    /** -1 uses system brightness; a positive value affects the reader window only. */
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = false
)

/**
 * What the library screen puts above the shelf, beyond the shelf itself.
 *
 * One line now that everything else above the shelf has gone: someone with four hundred books
 * wants the shelf and nothing else, and that is a reasonable way to use a reader. The shelf and
 * its search are not in this list — take those away and there is no screen.
 */
data class HomeSettings(
    /** Streak, today, this week. Hidden on its own anyway until there is something to count. */
    val showStats: Boolean = true
)

class SettingsViewModel(private val context: Context) : ViewModel() {

    val themeMode: StateFlow<AppThemeMode> = context.settingsDataStore.data
        .map { prefs ->
            AppThemeMode.entries.find { it.name == prefs[PrefKeys.THEME_MODE] }
                ?: when (prefs[PrefKeys.IS_DARK]) {
                    true -> AppThemeMode.DARK
                    false -> AppThemeMode.LIGHT
                    null -> AppThemeMode.SYSTEM
                }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppThemeMode.SYSTEM)

    val accentChoice: StateFlow<AccentChoice> = context.settingsDataStore.data
        .map { prefs -> AccentChoice.entries.find { it.name == prefs[PrefKeys.ACCENT_COLOR] }
            ?: AccentChoice.DEFAULT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccentChoice.DEFAULT)

    /** Null until DataStore has loaded, so the tour never flashes on repeat launches. */
    val onboardingDone: StateFlow<Boolean?> = context.settingsDataStore.data
        .map { it[PrefKeys.ONBOARDING_DONE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _readerSettingsLoaded = MutableStateFlow(false)
    val readerSettingsLoaded: StateFlow<Boolean> = _readerSettingsLoaded.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> = context.settingsDataStore.data
        .map { prefs ->
            ReaderSettings(
                fontSize             = SettingsRules.fontSize((prefs[PrefKeys.FONT_SIZE] ?: 18f).toDouble()).toFloat(),
                fontFamily           = ReaderFontFamily.entries.find { it.name == prefs[PrefKeys.FONT_FAMILY] }
                                       ?: ReaderFontFamily.SERIF,
                lineHeightMultiplier = SettingsRules.lineHeight((prefs[PrefKeys.LINE_HEIGHT] ?: 1.85f).toDouble()).toFloat(),
                marginDp             = SettingsRules.margin(prefs[PrefKeys.MARGIN_DP] ?: 24),
                readingMode          = ReadingMode.entries.find { it.name == prefs[PrefKeys.READ_MODE] }
                                       ?: ReadingMode.SEPIA,
                justifyText          = prefs[PrefKeys.JUSTIFY_TEXT]    ?: true,
                showPageNumbers      = prefs[PrefKeys.SHOW_PAGES]      ?: true,
                horizontalScrolling  = prefs[PrefKeys.HORIZONTAL_PAG]  ?: true,
                showProgressBar      = prefs[PrefKeys.SHOW_PROGRESS]   ?: false,
                pageAnimation        = PageTurnAnimation.entries.find { it.name == prefs[PrefKeys.PAGE_ANIM] }
                                       ?: PageTurnAnimation.SLIDE,
                preferNetworkVoice   = prefs[PrefKeys.TTS_NET_VOICE]   ?: true,
                pdfZoom              = SettingsRules.pdfZoom((prefs[PrefKeys.PDF_ZOOM] ?: 1f).toDouble()).toFloat(),
                pdfVerticalPaging    = prefs[PrefKeys.PDF_VERTICAL]    ?: false,
                brightness           = (prefs[PrefKeys.READER_BRIGHTNESS] ?: -1f).let { if (it.isFinite() && it >= 0f) it.coerceIn(.05f, 1f) else -1f },
                keepScreenOn         = prefs[PrefKeys.KEEP_READER_SCREEN] ?: false
            )
        }
        .onEach { _readerSettingsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    /**
     * Eagerly, unlike the reading settings: this one decides what the very first screen is made
     * of, and a block that appears a frame after the library does reads as the app twitching.
     */
    val homeSettings: StateFlow<HomeSettings> = context.settingsDataStore.data
        .map { prefs ->
            HomeSettings(showStats = prefs[PrefKeys.HOME_STATS] ?: true)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeSettings())

    fun setThemeMode(mode: AppThemeMode)   = update { it[PrefKeys.THEME_MODE] = mode.name }
    fun setAccentChoice(choice: AccentChoice) = update {
        if (choice == AccentChoice.DEFAULT) it.remove(PrefKeys.ACCENT_COLOR)
        else it[PrefKeys.ACCENT_COLOR] = choice.name
    }
    fun completeOnboarding() = update { it[PrefKeys.ONBOARDING_DONE] = true }
    fun setFontSize(size: Float)           = update { it[PrefKeys.FONT_SIZE]      = SettingsRules.fontSize(size.toDouble()).toFloat() }
    fun setFontFamily(family: ReaderFontFamily) = update { it[PrefKeys.FONT_FAMILY] = family.name }
    fun setLineHeight(h: Float)            = update { it[PrefKeys.LINE_HEIGHT]    = SettingsRules.lineHeight(h.toDouble()).toFloat() }
    fun setMargin(dp: Int)                 = update { it[PrefKeys.MARGIN_DP]      = SettingsRules.margin(dp) }
    fun setReadingMode(mode: ReadingMode)  = update { it[PrefKeys.READ_MODE]      = mode.name }
    fun setJustifyText(j: Boolean)         = update { it[PrefKeys.JUSTIFY_TEXT]   = j }
    fun setShowPageNumbers(s: Boolean)     = update { it[PrefKeys.SHOW_PAGES]     = s }
    fun setHorizontalScrolling(h: Boolean) = update { it[PrefKeys.HORIZONTAL_PAG] = h }
    fun setShowProgressBar(s: Boolean)     = update { it[PrefKeys.SHOW_PROGRESS]  = s }
    fun setPageAnimation(a: PageTurnAnimation) = update { it[PrefKeys.PAGE_ANIM]  = a.name }
    fun setPreferNetworkVoice(p: Boolean)  = update { it[PrefKeys.TTS_NET_VOICE]  = p }
    fun setPdfZoom(z: Float)               = update { it[PrefKeys.PDF_ZOOM]       = SettingsRules.pdfZoom(z.toDouble()).toFloat() }
    fun setPdfVerticalPaging(v: Boolean)   = update { it[PrefKeys.PDF_VERTICAL]   = v }
    fun setReaderBrightness(value: Float) = update { it[PrefKeys.READER_BRIGHTNESS] = if (value.isFinite() && value >= 0f) value.coerceIn(.05f, 1f) else -1f }
    fun setKeepReaderScreen(value: Boolean) = update { it[PrefKeys.KEEP_READER_SCREEN] = value }

    fun setHomeStats(s: Boolean)           = update { it[PrefKeys.HOME_STATS]     = s }

    /**
     * Minutes a day the reader is aiming for, 0 meaning no goal.
     *
     * Eagerly, like the home settings: the statistics screen opens onto the day's ring, and a
     * ring that fills in a frame later reads as the number correcting itself.
     */
    val dailyGoalMinutes: StateFlow<Int> = context.settingsDataStore.data
        .map { SettingsRules.dailyGoal(it[PrefKeys.GOAL_MINUTES] ?: ReadingGoal.OFF) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadingGoal.OFF)

    fun setDailyGoalMinutes(minutes: Int) {
        viewModelScope.launch {
            val value = SettingsRules.dailyGoal(minutes)
            var changed = false
            context.settingsDataStore.edit { changed = (it[PrefKeys.GOAL_MINUTES] ?: 0) != value; it[PrefKeys.GOAL_MINUTES] = value }
            if (changed) (context.applicationContext as? com.lexiread.app.LexiReadApp)?.cloud?.personal?.capture("goal", value)
        }
    }

    private fun update(transform: (MutablePreferences) -> Unit) {
        viewModelScope.launch { context.settingsDataStore.edit { transform(it) } }
    }
}

class SettingsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(context.applicationContext) as T
}

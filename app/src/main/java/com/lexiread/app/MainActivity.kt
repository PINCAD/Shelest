package com.lexiread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lexiread.app.widget.BookWidget
import kotlinx.coroutines.flow.MutableStateFlow
import com.lexiread.app.ui.discover.DiscoverScreen
import com.lexiread.app.ui.discover.DiscoverViewModelFactory
import com.lexiread.app.ui.library.LibraryScreen
import com.lexiread.app.ui.library.LibraryViewModelFactory
import com.lexiread.app.ui.reader.ReaderScreen
import com.lexiread.app.ui.reader.ReaderViewModelFactory
import com.lexiread.app.ui.settings.SettingsScreen
import com.lexiread.app.ui.settings.SettingsViewModelFactory
import com.lexiread.app.ui.settings.SettingsViewModel
import com.lexiread.app.ui.stats.StatsScreen
import com.lexiread.app.ui.stats.StatsViewModelFactory
import com.lexiread.app.ui.theme.LexiReadTheme
import com.lexiread.app.ui.theme.AppThemeMode
import com.lexiread.app.ui.vocabulary.VocabularyScreen
import com.lexiread.app.ui.vocabulary.VocabularyViewModelFactory

class MainActivity : ComponentActivity() {

    /**
     * The intent the app was last handed.
     *
     * The Activity's own `intent` is a plain field, and the activity is `singleTask`: a tap on
     * the widget while the app is already running does not create anything, it delivers a new
     * intent to the instance that exists. Nothing about a field changes recomposes, so the
     * intent is mirrored into state and the navigation below watches that instead.
     */
    private val latestIntent = MutableStateFlow<android.content.Intent?>(null)

    /** The file a share carries, which lives in an extra rather than in the intent's data. */
    private fun android.content.Intent.sharedUri(): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri
        }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent.value = intent
    }

    override fun onResume() {
        super.onResume()
        (application as LexiReadApp).cloud.foreground = true
        (application as LexiReadApp).cloud.schedule()
    }

    override fun onPause() {
        (application as LexiReadApp).cloud.foreground = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        latestIntent.value = intent
        setContent {
            val app = application as LexiReadApp
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(this)
            )
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val accentChoice by settingsViewModel.accentChoice.collectAsState()
            val onboardingDone by settingsViewModel.onboardingDone.collectAsState()
            val homeSettings by settingsViewModel.homeSettings.collectAsState()

            val isDark = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            LexiReadTheme(darkTheme = isDark, accentChoice = accentChoice) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val cloudRoute by navController.currentBackStackEntryFlow.collectAsState(initial = null)
                    androidx.compose.runtime.LaunchedEffect(cloudRoute) {
                        app.cloud.readerOpen = cloudRoute?.destination?.route?.startsWith("reader") == true
                        if (!app.cloud.readerOpen) app.cloud.schedule()
                    }
                    
                    val startIntent by latestIntent.collectAsState()

                    androidx.compose.runtime.LaunchedEffect(startIntent) {
                        val current = startIntent ?: return@LaunchedEffect
                        when (current.action) {
                            // Opened from a file manager, or sent from another app. A book that
                            // arrives out of a messenger or a mail attachment is shared rather
                            // than viewed, and carries its file in an extra instead of in the
                            // intent's own data — the two are otherwise the same errand.
                            android.content.Intent.ACTION_VIEW,
                            android.content.Intent.ACTION_SEND -> {
                                val uri = current.data ?: current.sharedUri()
                                if (uri != null) {
                                    // A big book takes several seconds to copy in and read
                                    // through, and until this was said the app opened on the
                                    // shelf and appeared to have ignored the file.
                                    toast("Открываю книгу…")
                                    val book = app.bookRepository.addBook(uri)
                                    if (book != null) {
                                        navController.navigate("reader/${book.id}")
                                    } else {
                                        toast("Не удалось открыть файл")
                                    }
                                }
                            }
                            // From the home-screen widget. "Listen" is carried as a route
                            // argument rather than acted on here, because the thing that has to
                            // hear about it is the reader, and it does not exist yet.
                            BookWidget.ACTION_OPEN_BOOK -> {
                                val bookId = current.getLongExtra(BookWidget.EXTRA_BOOK_ID, -1L)
                                if (bookId > 0L) {
                                    val listen = current.getBooleanExtra(BookWidget.EXTRA_LISTEN, false)
                                    navController.navigate("reader/$bookId?listen=$listen") {
                                        // Tapping the widget again while the same book is open
                                        // must not stack a second copy of it behind the first.
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }
                        // Consumed. Without this, every recomposition that re-reads the flow —
                        // and every return to the app — would navigate again, dragging a reader
                        // who has since gone back to the library into the book once more.
                        latestIntent.value = null
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "library",
                        enterTransition = {
                            fadeIn(tween(180)) + slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                tween(220), initialOffset = { it / 10 }
                            )
                        },
                        exitTransition = { fadeOut(tween(140)) },
                        popEnterTransition = {
                            fadeIn(tween(180)) + slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                tween(220), initialOffset = { it / 10 }
                            )
                        },
                        popExitTransition = { fadeOut(tween(140)) }
                    ) {
                        composable("library") {
                            LibraryScreen(
                                viewModel         = viewModel(
                                    factory = LibraryViewModelFactory(app.bookRepository, app.statsRepository, app.cloud)
                                ),
                                home              = homeSettings,
                                showOnboarding    = onboardingDone == false,
                                onOnboardingDone  = settingsViewModel::completeOnboarding,
                                onBookClick       = { bookId -> navController.navigate("reader/$bookId") },
                                onSettingsClick   = { navController.navigate("settings") },
                                onVocabularyClick = { navController.navigate("vocabulary") },
                                onDiscoverClick   = { navController.navigate("discover") },
                                onStatsClick      = { navController.navigate("stats") }
                            )
                        }
                        composable(
                            route     = "reader/{bookId}?listen={listen}",
                            arguments = listOf(
                                navArgument("listen") {
                                    type         = NavType.BoolType
                                    defaultValue = false
                                }
                            ),
                            enterTransition = { fadeIn(tween(220)) },
                            exitTransition = { fadeOut(tween(160)) },
                            popEnterTransition = { fadeIn(tween(180)) },
                            popExitTransition = { fadeOut(tween(160)) }
                        ) { back ->
                            val bookId = back.arguments?.getString("bookId")?.toLongOrNull()
                                ?: return@composable
                            ReaderScreen(
                                bookId            = bookId,
                                startListening    = back.arguments?.getBoolean("listen") == true,
                                viewModel         = viewModel(
                                    key     = "reader_$bookId",
                                    factory = ReaderViewModelFactory(
                                        app.bookRepository,
                                        app.definitionRepository,
                                        app.bookmarkDao,
                                        app.annotationDao,
                                        app.vocabularyDao,
                                        app.statsRepository
                                    )
                                ),
                                settingsViewModel = settingsViewModel,
                                onBack            = { navController.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel    = settingsViewModel,
                                onBack       = { navController.popBackStack() },
                                onStatsClick = { navController.navigate("stats") }
                            )
                        }
                        composable("stats") {
                            StatsScreen(
                                viewModel         = viewModel(
                                    factory = StatsViewModelFactory(app.statsRepository)
                                ),
                                settingsViewModel = settingsViewModel,
                                onBack            = { navController.popBackStack() }
                            )
                        }
                        composable("vocabulary") {
                            VocabularyScreen(
                                viewModel = viewModel(factory = VocabularyViewModelFactory(app.vocabularyDao)),
                                onBack    = { navController.popBackStack() }
                            )
                        }
                        composable("discover") {
                            DiscoverScreen(
                                viewModel = viewModel(
                                    factory = DiscoverViewModelFactory(
                                        app.bookRepository,
                                        app.bookDiscoveryRepository,
                                        app.bookSynopsisRepository
                                    )
                                ),
                                onBack    = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.lexiread.app.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexiread.app.ui.theme.AccentChoice
import com.lexiread.app.ui.theme.AppThemeMode
import com.shelest.ui.AppearancePanel
import com.shelest.ui.AppearanceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onStatsClick: () -> Unit) {
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val accent by viewModel.accentChoice.collectAsStateWithLifecycle()
    val home by viewModel.homeSettings.collectAsStateWithLifecycle()
    val goal by viewModel.dailyGoalMinutes.collectAsStateWithLifecycle()
    val dark = when (theme) { AppThemeMode.SYSTEM -> isSystemInDarkTheme(); AppThemeMode.DARK -> true; else -> false }
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }, navigationIcon = {
        IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CloudAccountCard()
            AppearancePanel(AppearanceState(theme.name, accent.name, dark, home.showStats, goal, version), { key, value ->
                when(key) {
                    "themeMode" -> AppThemeMode.entries.find { it.name == value }?.let(viewModel::setThemeMode)
                    "accentChoice" -> AccentChoice.entries.find { it.name == value }?.let(viewModel::setAccentChoice)
                    "showStats" -> viewModel.setHomeStats(value == "true")
                }
            }, onStatsClick)
        }
    }
}

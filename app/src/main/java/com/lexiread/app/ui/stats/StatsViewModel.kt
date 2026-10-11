package com.lexiread.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.app.data.repository.ReadingStats
import com.lexiread.app.data.repository.ReadingStatsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the statistics screen shows, which is one flow.
 *
 * There is no work here beyond holding the subscription: the counting all happens in
 * [ReadingStatsRepository], because the same figures have to come out identical on the library
 * strip and on the home-screen widget, and a screen that did its own arithmetic would be a third
 * answer to the same question.
 */
class StatsViewModel(repo: ReadingStatsRepository) : ViewModel() {

    val stats: StateFlow<ReadingStats> =
        repo.stats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingStats())
}

class StatsViewModelFactory(private val repo: ReadingStatsRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(repo) as T
}

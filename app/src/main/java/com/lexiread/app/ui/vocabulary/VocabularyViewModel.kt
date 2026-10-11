package com.lexiread.app.ui.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.app.data.db.VocabularyDao
import com.lexiread.app.data.model.VocabularyWord
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class VocabularyFilter(val label: String) {
    ALL("Все"),
    LEARNING("Учу"),
    LEARNED("Выучено")
}

class VocabularyViewModel(private val dao: VocabularyDao) : ViewModel() {

    private val _filter = MutableStateFlow(VocabularyFilter.ALL)
    val filter: StateFlow<VocabularyFilter> = _filter.asStateFlow()

    private val all: StateFlow<List<VocabularyWord>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val words: StateFlow<List<VocabularyWord>> = combine(all, _filter) { list, f ->
        when (f) {
            VocabularyFilter.ALL      -> list
            VocabularyFilter.LEARNING -> list.filter { !it.isLearned }
            VocabularyFilter.LEARNED  -> list.filter { it.isLearned }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val learnedCount: StateFlow<Int> = all
        .map { list -> list.count { it.isLearned } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalCount: StateFlow<Int> = all
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setFilter(value: VocabularyFilter) { _filter.value = value }

    fun toggleLearned(word: VocabularyWord) {
        viewModelScope.launch { dao.setLearned(word.id, !word.isLearned) }
    }

    fun delete(word: VocabularyWord) {
        viewModelScope.launch { dao.delete(word) }
    }
}

class VocabularyViewModelFactory(private val dao: VocabularyDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = VocabularyViewModel(dao) as T
}

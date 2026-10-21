package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.ContentEntity
import com.example.data.model.GameEntity
import com.example.data.repository.ContentRepository
import com.example.data.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameDetailsViewModel(
    private val gameRepository: GameRepository,
    private val contentRepository: ContentRepository,
    val gamePath: String
) : ViewModel() {

    val game: StateFlow<GameEntity?> = gameRepository.getGameByPath(gamePath).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val characters = game.filterNotNull().flatMapLatest { g ->
        contentRepository.getCharactersForGame(g.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stages = game.filterNotNull().flatMapLatest { g ->
        contentRepository.getStagesForGame(g.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mods = game.filterNotNull().flatMapLatest { g ->
        contentRepository.getModsForGame(g.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleContent(content: ContentEntity) {
        viewModelScope.launch {
            contentRepository.updateContent(content.copy(isEnabled = !content.isEnabled))
        }
    }
}

class GameDetailsViewModelFactory(
    private val gameRepository: GameRepository,
    private val contentRepository: ContentRepository,
    private val gamePath: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GameDetailsViewModel(gameRepository, contentRepository, gamePath) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

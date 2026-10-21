package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.ContentEntity
import com.example.data.model.GameEntity
import com.example.data.repository.ContentRepository
import com.example.data.repository.GameRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameLibraryViewModel(
    private val repository: GameRepository,
    private val contentRepository: ContentRepository
) : ViewModel() {
    
    val allGames: StateFlow<List<GameEntity>> = repository.allGames.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun importGame(title: String, folderPath: String, characters: List<ContentEntity>, stages: List<ContentEntity>) {
        viewModelScope.launch {
            val game = GameEntity(
                title = title,
                folderPath = folderPath,
                characterCount = characters.size,
                stageCount = stages.size,
                lastPlayed = System.currentTimeMillis()
            )
            repository.insertGame(game)
            
            // To properly link content to gameId, we fetch it back by path.
            repository.getGameByPath(folderPath).collect { savedGame ->
                if (savedGame != null) {
                    val charsWithId = characters.map { it.copy(gameId = savedGame.id) }
                    val stagesWithId = stages.map { it.copy(gameId = savedGame.id) }
                    contentRepository.insertContent(charsWithId + stagesWithId)
                }
            }
        }
    }
}

class GameLibraryViewModelFactory(
    private val repository: GameRepository,
    private val contentRepository: ContentRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameLibraryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GameLibraryViewModel(repository, contentRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

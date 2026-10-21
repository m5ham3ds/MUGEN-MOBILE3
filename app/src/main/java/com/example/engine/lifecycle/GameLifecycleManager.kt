package com.example.engine.lifecycle

import com.example.engine.bridge.IkemenBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GameState {
    IDLE, LOADING, RUNNING, PAUSED, ERROR
}

object GameLifecycleManager {
    private val _gameState = MutableStateFlow(GameState.IDLE)
    val gameState: StateFlow<GameState> = _gameState

    fun launchGame(gamePath: String) {
        _gameState.value = GameState.LOADING
        try {
            IkemenBridge.loadLibrary()
            IkemenBridge.initEngine(gamePath, gamePath) // Placeholder paths
            IkemenBridge.startEngine()
            _gameState.value = GameState.RUNNING
        } catch (e: Exception) {
            _gameState.value = GameState.ERROR
        }
    }

    fun pauseGame() {
        if (_gameState.value == GameState.RUNNING) {
            IkemenBridge.pauseEngine()
            _gameState.value = GameState.PAUSED
        }
    }

    fun resumeGame() {
        if (_gameState.value == GameState.PAUSED) {
            IkemenBridge.resumeEngine()
            _gameState.value = GameState.RUNNING
        }
    }

    fun stopGame() {
        if (_gameState.value != GameState.IDLE) {
            IkemenBridge.stopEngine()
            _gameState.value = GameState.IDLE
        }
    }
}

package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val folderPath: String,
    val coverUri: String? = null,
    val characterCount: Int = 0,
    val stageCount: Int = 0,
    val lastPlayed: Long = 0,
    val isFavorite: Boolean = false
)

package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ContentEntity
import com.example.data.model.ContentType
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentDao {
    @Query("SELECT * FROM game_content WHERE gameId = :gameId AND type = :type ORDER BY name ASC")
    fun getContentForGame(gameId: Int, type: ContentType): Flow<List<ContentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContent(content: List<ContentEntity>)

    @Update
    suspend fun updateContent(content: ContentEntity)

    @Delete
    suspend fun deleteContent(content: ContentEntity)

    @Query("DELETE FROM game_content WHERE gameId = :gameId")
    suspend fun clearContentForGame(gameId: Int)
}

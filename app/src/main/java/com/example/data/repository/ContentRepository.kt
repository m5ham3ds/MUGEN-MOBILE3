package com.example.data.repository

import com.example.data.db.ContentDao
import com.example.data.model.ContentEntity
import com.example.data.model.ContentType
import kotlinx.coroutines.flow.Flow

class ContentRepository(private val contentDao: ContentDao) {
    fun getCharactersForGame(gameId: Int): Flow<List<ContentEntity>> {
        return contentDao.getContentForGame(gameId, ContentType.CHARACTER)
    }

    fun getStagesForGame(gameId: Int): Flow<List<ContentEntity>> {
        return contentDao.getContentForGame(gameId, ContentType.STAGE)
    }
    
    fun getModsForGame(gameId: Int): Flow<List<ContentEntity>> {
        return contentDao.getContentForGame(gameId, ContentType.MOD)
    }

    suspend fun insertContent(contentList: List<ContentEntity>) {
        contentDao.insertContent(contentList)
    }

    suspend fun updateContent(content: ContentEntity) {
        contentDao.updateContent(content)
    }

    suspend fun deleteContent(content: ContentEntity) {
        contentDao.deleteContent(content)
    }

    suspend fun clearContentForGame(gameId: Int) {
        contentDao.clearContentForGame(gameId)
    }
}

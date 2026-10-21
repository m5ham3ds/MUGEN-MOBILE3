package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.repository.ContentRepository
import com.example.data.repository.GameRepository

class MugenApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { GameRepository(database.gameDao()) }
    val contentRepository by lazy { ContentRepository(database.contentDao()) }

    override fun onCreate() {
        super.onCreate()
        try {
            android.system.Os.setenv("GODEBUG", "asyncpreemptoff=1,sigaltstack=0,cgocheck=0,scavenge=off,installgoroot=0,hardstacklimit=0", true)
            System.setProperty("SDL_THREAD_STACK_SIZE", "8388608")
        } catch (_: Throwable) {}
        com.example.utils.LiveLogger.initialize(this)
        com.example.utils.CrashReporter.initialize(this)
        com.example.storage.MugenMobileStorage.initializeDirectories(this)
    }
}

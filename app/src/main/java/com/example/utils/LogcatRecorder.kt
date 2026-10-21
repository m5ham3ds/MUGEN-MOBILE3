package com.example.utils

import android.content.Context
import android.util.Log
import com.example.storage.MugenMobileStorage
import java.io.File

object LogcatRecorder {
    private const val TAG = "LogcatRecorder"

    fun start(context: Context) {
        Thread {
            try {
                // Clear previous logcat first (optional, but good for clean logs)
                Runtime.getRuntime().exec("logcat -c").waitFor()
                
                // Initialize directories if they don't exist
                MugenMobileStorage.initializeDirectories(context)
                
                // We will save it in the root of MUGEN_MOBILE directory
                val logFile = File(MugenMobileStorage.getBaseDir(context), "mugen_crash.log")
                Log.d(TAG, "Starting logcat recording to: ${logFile.absolutePath}")
                
                // Write logcat to file, capturing all errors, warnings, and debug info
                // The process will run continuously until the app process dies
                Runtime.getRuntime().exec(arrayOf("logcat", "-f", logFile.absolutePath, "*:D"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

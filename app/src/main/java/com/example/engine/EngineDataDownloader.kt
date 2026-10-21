package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.example.storage.MugenMobileStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

enum class DownloadState {
    IDLE, DOWNLOADING, PAUSED, EXTRACTING, SUCCESS, ERROR
}

object EngineDataDownloader {
    private val client = OkHttpClient()
    private const val REPO_ZIP_URL = "https://github.com/m5ham3ds/MUGEN/archive/refs/heads/main.zip"
    
    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState: StateFlow<DownloadState> = _downloadState
    
    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage
    
    private val _progressPercentage = MutableStateFlow(0f)
    val progressPercentage: StateFlow<Float> = _progressPercentage

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile private var shouldPause = false
    @Volatile private var shouldCancel = false
    
    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    fun startDownload(context: Context) {
        val currentState = _downloadState.value
        if (currentState == DownloadState.DOWNLOADING || currentState == DownloadState.EXTRACTING) return
        
        MugenMobileStorage.initializeDirectories(context)
        if (MugenMobileStorage.isEngineDataInstalled(context)) {
            _downloadState.value = DownloadState.IDLE
            _progressMessage.value = "Engine data is already installed."
            return
        }

        if (!isInternetAvailable(context)) {
            _downloadState.value = DownloadState.ERROR
            _progressMessage.value = "No internet connection."
            return
        }

        shouldPause = false
        shouldCancel = false
        com.example.utils.LiveLogger.i(
            com.example.utils.LiveLogger.Category.DOWNLOAD,
            "DOWNLOAD_STARTED",
            "Starting engine data download from $REPO_ZIP_URL"
        )
        
        val intent = Intent(context, DownloadService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        downloadJob = scope.launch {
            executeDownload(context)
        }
    }
    
    fun pauseDownload() {
        shouldPause = true
    }
    
    fun cancelDownload() {
        shouldCancel = true
    }
    
    private suspend fun executeDownload(context: Context) {
        _downloadState.value = DownloadState.DOWNLOADING
        val zipFile = File(context.cacheDir, "mugen_data.zip")
        
        try {
            var downloaded = if (zipFile.exists()) zipFile.length() else 0L
            
            val requestBuilder = Request.Builder().url(REPO_ZIP_URL)
            if (downloaded > 0) {
                requestBuilder.addHeader("Range", "bytes=$downloaded-")
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("Server returned ${response.code}")
            }
            
            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength()
            val totalLength = if (response.code == 206) downloaded + contentLength else contentLength
            
            val fos = FileOutputStream(zipFile, response.code == 206)
            val inputStream = body.byteStream()
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            
            while (withContext(Dispatchers.IO) { inputStream.read(buffer) }.also { bytesRead = it } != -1) {
                if (shouldCancel) {
                    fos.close()
                    inputStream.close()
                    zipFile.delete()
                    _downloadState.value = DownloadState.IDLE
                    _progressMessage.value = "Download cancelled."
                    _progressPercentage.value = 0f
                    context.stopService(Intent(context, DownloadService::class.java))
                    return
                }
                if (shouldPause) {
                    fos.close()
                    inputStream.close()
                    _downloadState.value = DownloadState.PAUSED
                    _progressMessage.value = "Download paused."
                    return
                }
                
                fos.write(buffer, 0, bytesRead)
                downloaded += bytesRead
                
                if (totalLength > 0L) {
                    _progressPercentage.value = downloaded.toFloat() / totalLength.toFloat()
                    _progressMessage.value = "Downloading: ${downloaded / 1024 / 1024} MB / ${totalLength / 1024 / 1024} MB"
                } else {
                    _progressMessage.value = "Downloading: ${downloaded / 1024 / 1024} MB"
                }
            }
            
            fos.close()
            inputStream.close()
            
            extractZip(zipFile, context)
            
        } catch (e: Exception) {
            Log.e("EngineDataDownloader", "Error", e)
            com.example.utils.LiveLogger.e(com.example.utils.LiveLogger.Category.DOWNLOAD, "DOWNLOAD_FAILED", e.message ?: "", e)
            _downloadState.value = DownloadState.ERROR
            _progressMessage.value = "Error: ${e.localizedMessage}"
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
    
    private suspend fun extractZip(zipFile: File, context: Context) {
        com.example.utils.LiveLogger.i(com.example.utils.LiveLogger.Category.DOWNLOAD, "EXTRACTION_STARTED", "Unzipping engine assets")
        _downloadState.value = DownloadState.EXTRACTING
        _progressMessage.value = "Extracting files to engine directory..."
        _progressPercentage.value = 0f
        
        withContext(Dispatchers.IO) {
            try {
                MugenMobileStorage.initializeDirectories(context)
                val targetBaseDir = MugenMobileStorage.getBaseDir(context)
                
                ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name.replace('\\', '/')
                        
                        // GitHub ZIP files enclose content inside a root directory (e.g., MUGEN-main/)
                        // Strip the root prefix so directories map directly to baseDir (chars/, stages/, data/)
                        val relativePath = if (entryName.contains("/")) {
                            entryName.substringAfter("/")
                        } else {
                            ""
                        }
                        
                        // Enforce strict separation: Downloadable content is strictly characters, stages, and game data.
                        // Native libraries (.so) must never be extracted or modified from downloaded archives.
                        if (relativePath.endsWith(".so", ignoreCase = true) || relativePath.startsWith("lib/")) {
                            Log.w("EngineDataDownloader", "Skipping native library entry in content archive: $relativePath")
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        if (relativePath.isNotEmpty()) {
                            val outFile = File(targetBaseDir, relativePath)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                
                zipFile.delete() // Cleanup

                // Automatically clean and sync select.def with any present characters
                try {
                    MugenParser.getCharacters(context)
                } catch (e: Exception) {
                    Log.w("EngineDataDownloader", "Post-extract sync notice: ${e.message}")
                }

                _downloadState.value = DownloadState.SUCCESS
                _progressMessage.value = "Engine data successfully installed!"
                context.stopService(Intent(context, DownloadService::class.java))
                
            } catch (e: Exception) {
                Log.e("EngineDataDownloader", "Extract Error", e)
                _downloadState.value = DownloadState.ERROR
                _progressMessage.value = "Extract Error: ${e.localizedMessage}"
                context.stopService(Intent(context, DownloadService::class.java))
            }
        }
    }
    
    fun reset() {
        _downloadState.value = DownloadState.IDLE
        _progressMessage.value = ""
        _progressPercentage.value = 0f
    }
}

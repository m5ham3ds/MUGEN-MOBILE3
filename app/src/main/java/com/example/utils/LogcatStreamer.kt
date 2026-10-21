package com.example.utils

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data model for a single Logcat line.
 */
data class LogcatEntry(
    val id: Long,
    val raw: String,
    val level: String, // V, D, I, W, E, F
    val tag: String,
    val message: String
)

/**
 * Real-time streaming service for Logcat with bounded circular buffer,
 * pause/resume controls, and multi-filter support.
 */
object LogcatStreamer {
    private const val TAG = "LogcatStreamer"
    private const val MAX_BUFFER_LINES = 1500

    private val scope = CoroutineScope(Dispatchers.IO)
    private var streamJob: Job? = null
    private var logcatProcess: java.lang.Process? = null

    private val bufferLock = Any()
    private val circularBuffer = ArrayDeque<LogcatEntry>(MAX_BUFFER_LINES)
    private var lineCounter = 0L

    private val _entries = MutableStateFlow<List<LogcatEntry>>(emptyList())
    val entries: StateFlow<List<LogcatEntry>> = _entries.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val isPaused = AtomicBoolean(false)

    /**
     * Starts continuous background Logcat streaming.
     */
    @Synchronized
    fun startStreaming() {
        if (streamJob?.isActive == true) return

        _isStreaming.value = true
        isPaused.set(false)

        streamJob = scope.launch {
            val pid = Process.myPid()
            try {
                // Command: read logs with time prefix for current PID
                val pb = ProcessBuilder("logcat", "-v", "time", "--pid=$pid")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                logcatProcess = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                var line: String?

                while (isActive) {
                    line = reader.readLine()
                    if (line == null) break

                    if (!isPaused.get()) {
                        val parsed = parseLogcatLine(line)
                        synchronized(bufferLock) {
                            if (circularBuffer.size >= MAX_BUFFER_LINES) {
                                circularBuffer.removeFirst()
                            }
                            circularBuffer.addLast(parsed)
                            _entries.value = circularBuffer.toList()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Logcat streaming terminated: ${e.message}")
            } finally {
                _isStreaming.value = false
            }
        }
    }

    /**
     * Pauses buffer ingestion without killing process.
     */
    fun pauseStreaming() {
        isPaused.set(true)
    }

    /**
     * Resumes buffer ingestion.
     */
    fun resumeStreaming() {
        isPaused.set(false)
    }

    /**
     * Completely stops Logcat streaming process.
     */
    @Synchronized
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        try {
            logcatProcess?.destroy()
        } catch (_: Exception) {}
        logcatProcess = null
        _isStreaming.value = false
    }

    /**
     * Clears current memory buffer.
     */
    fun clearBuffer() {
        synchronized(bufferLock) {
            circularBuffer.clear()
            _entries.value = emptyList()
        }
    }

    /**
     * Exports buffered lines into a cache file and returns shareable FileProvider URI.
     */
    fun exportLogcat(context: Context): Uri? {
        val snapshot: List<LogcatEntry>
        synchronized(bufferLock) {
            snapshot = circularBuffer.toList()
        }
        if (snapshot.isEmpty()) return null

        return try {
            val cacheDir = context.cacheDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportFile = File(cacheDir, "logcat_export_$timestamp.txt")
            exportFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("=== MUGEN MOBILE LOGCAT EXPORT ===\n")
                writer.write("Date: ${Date()}\n")
                writer.write("Process PID: ${Process.myPid()}\n")
                writer.write("Total Lines: ${snapshot.size}\n\n")
                for (entry in snapshot) {
                    writer.write(entry.raw)
                    writer.newLine()
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting logcat: ${e.message}")
            null
        }
    }

    private fun parseLogcatLine(raw: String): LogcatEntry {
        val id = ++lineCounter
        // Typical format: "05-19 12:34:56.789 D/TAG( 1234): Message..."
        var level = "D"
        var tag = "System"
        var message = raw

        try {
            val trimmed = raw.trim()
            val match = Regex("""^[0-9\-]+\s+[0-9:\.]+\s+([VDIWEF])/([^(\s:]+)(?:\(\s*[0-9]+\))?:\s*(.*)$""").find(trimmed)
            if (match != null) {
                level = match.groupValues[1]
                tag = match.groupValues[2].trim()
                message = match.groupValues[3].trim()
            } else if (trimmed.contains(" E/") || trimmed.contains(" E ")) {
                level = "E"
            } else if (trimmed.contains(" W/") || trimmed.contains(" W ")) {
                level = "W"
            } else if (trimmed.contains(" I/") || trimmed.contains(" I ")) {
                level = "I"
            }
        } catch (_: Exception) {}

        return LogcatEntry(id = id, raw = raw, level = level, tag = tag, message = message)
    }
}

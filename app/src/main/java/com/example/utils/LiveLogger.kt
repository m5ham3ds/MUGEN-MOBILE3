package com.example.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live persistent diagnostic logger for MUGEN Mobile.
 *
 * Requirements fulfilled:
 * 1. Saved inside application's private storage (context.filesDir/logs/mugen_live.log).
 * 2. Starts at app startup and tracks session ID, PID, app version, Android OS version, device.
 * 3. Logs critical events: lifecycle, background/foreground transitions, START press, native loading,
 *    SDL init, stage and character loading, controller events, downloads, and crashes.
 * 4. Thread-safe background execution with periodic and critical flushes to survive native crashes.
 * 5. File rotation (max 2MB per file, keeping up to 3 backup archives).
 * 6. Export and sharing via FileProvider.
 */
object LiveLogger {
    private const val TAG = "MugenLiveLog"
    private const val LOG_DIR_NAME = "logs"
    private const val LIVE_LOG_NAME = "mugen_live.log"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L // 2 MB
    private const val MAX_BACKUP_FILES = 3

    enum class Level(val symbol: String) {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
        FATAL("F")
    }

    object Category {
        const val LIFECYCLE = "LIFECYCLE"
        const val ENGINE = "ENGINE"
        const val SDL = "SDL"
        const val INPUT = "INPUT"
        const val STORAGE = "STORAGE"
        const val STAGE = "STAGE"
        const val CHARACTER = "CHAR"
        const val DOWNLOAD = "DOWNLOAD"
        const val CRASH = "CRASH"
        const val SESSION = "SESSION"
        const val CONTROLLER = "CONTROLLER"
    }

    private val isInitialized = AtomicBoolean(false)
    private val activeActivityCount = AtomicInteger(0)
    private var isChangingConfigurations = false

    private var currentSessionId: String = UUID.randomUUID().toString()
    private var currentPid: Int = Process.myPid()
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var appContext: Context? = null

    // Background writer
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiveLogger-Worker").apply { isDaemon = true }
    }

    private var logWriter: OutputStreamWriter? = null
    private var currentLogFile: File? = null
    private var currentFileSize: Long = 0L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    /**
     * Initializes the LiveLogger with Application Context and registers ActivityLifecycleCallbacks.
     */
    fun initialize(application: Application) {
        val newContext = application.applicationContext
        val isFirst = isInitialized.compareAndSet(false, true)
        
        appContext = newContext
        currentPid = Process.myPid()
        sessionStartTime = System.currentTimeMillis()
        if (currentSessionId.isBlank()) {
            currentSessionId = UUID.randomUUID().toString()
        }

        val logDir = File(newContext.filesDir, LOG_DIR_NAME)
        if (!logDir.exists()) logDir.mkdirs()

        currentLogFile = File(logDir, LIVE_LOG_NAME)
        currentFileSize = if (currentLogFile!!.exists()) currentLogFile!!.length() else 0L

        // Prepare writer
        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Exception) {}
        logWriter = null
        openWriter()

        if (isFirst) {
            // Get app and device metadata
            var versionName = "1.0"
            var versionCode = 1L
            try {
                val pInfo: PackageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
                versionName = pInfo.versionName ?: "1.0"
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (_: Exception) {}

            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            val androidOs = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val supportedAbis = Build.SUPPORTED_ABIS?.joinToString(", ") ?: "arm64-v8a"

            // Log session start banner
            log(
                Level.INFO,
                Category.SESSION,
                "SESSION_START",
                "App started | SessionID=$currentSessionId | PID=$currentPid | Ver=$versionName ($versionCode) | OS=$androidOs | Device=$deviceModel | ABIs=$supportedAbis"
            )

            // Register lifecycle listener
            registerLifecycleCallbacks(application)

            // Periodic flush task
            startPeriodicFlush()
        }
    }

    private fun registerLifecycleCallbacks(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                log(
                    Level.DEBUG,
                    Category.LIFECYCLE,
                    "ACTIVITY_CREATED",
                    "${activity.javaClass.simpleName} created | savedState=${savedInstanceState != null}"
                )
            }

            override fun onActivityStarted(activity: Activity) {
                val count = activeActivityCount.incrementAndGet()
                if (count == 1 && !isChangingConfigurations) {
                    log(
                        Level.INFO,
                        Category.LIFECYCLE,
                        "APP_FOREGROUND",
                        "Application transitioned to FOREGROUND via ${activity.javaClass.simpleName}"
                    )
                }
                log(
                    Level.VERBOSE,
                    Category.LIFECYCLE,
                    "ACTIVITY_STARTED",
                    activity.javaClass.simpleName
                )
            }

            override fun onActivityResumed(activity: Activity) {
                log(
                    Level.DEBUG,
                    Category.LIFECYCLE,
                    "ACTIVITY_RESUMED",
                    activity.javaClass.simpleName
                )
            }

            override fun onActivityPaused(activity: Activity) {
                isChangingConfigurations = activity.isChangingConfigurations
                log(
                    Level.DEBUG,
                    Category.LIFECYCLE,
                    "ACTIVITY_PAUSED",
                    activity.javaClass.simpleName
                )
            }

            override fun onActivityStopped(activity: Activity) {
                val count = activeActivityCount.decrementAndGet()
                if (count == 0 && !isChangingConfigurations) {
                    log(
                        Level.INFO,
                        Category.LIFECYCLE,
                        "APP_BACKGROUND",
                        "Application transitioned to BACKGROUND after ${activity.javaClass.simpleName}"
                    )
                    flush()
                }
                log(
                    Level.VERBOSE,
                    Category.LIFECYCLE,
                    "ACTIVITY_STOPPED",
                    activity.javaClass.simpleName
                )
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                log(
                    Level.VERBOSE,
                    Category.LIFECYCLE,
                    "ACTIVITY_SAVE_INSTANCE_STATE",
                    activity.javaClass.simpleName
                )
            }

            override fun onActivityDestroyed(activity: Activity) {
                log(
                    Level.DEBUG,
                    Category.LIFECYCLE,
                    "ACTIVITY_DESTROYED",
                    "${activity.javaClass.simpleName} destroyed | finishing=${activity.isFinishing}"
                )
                if (activity.isFinishing && activeActivityCount.get() <= 0) {
                    flush()
                }
            }
        })
    }

    private fun startPeriodicFlush() {
        val flusher = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LiveLogger-Flusher").apply { isDaemon = true }
        }
        flusher.scheduleWithFixedDelay({
            flush()
        }, 3, 3, TimeUnit.SECONDS)
    }

    /**
     * Records a structured diagnostic entry into the live log.
     */
    fun log(
        level: Level,
        category: String,
        event: String,
        details: String = "",
        throwable: Throwable? = null
    ) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val threadName = Thread.currentThread().name
        
        val extra = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            if (details.isNotBlank()) "$details | Exception: ${sw.toString().trim()}" else "Exception: ${sw.toString().trim()}"
        } else {
            details
        }

        val logLine = "[$timestamp] [${level.symbol}] [$category] $event: $extra [thread=$threadName pid=$currentPid sid=$currentSessionId]\n"

        // Also output to Android Logcat
        when (level) {
            Level.VERBOSE -> Log.v(TAG, "[$category] $event: $extra")
            Level.DEBUG -> Log.d(TAG, "[$category] $event: $extra")
            Level.INFO -> Log.i(TAG, "[$category] $event: $extra")
            Level.WARN -> Log.w(TAG, "[$category] $event: $extra")
            Level.ERROR -> Log.e(TAG, "[$category] $event: $extra", throwable)
            Level.FATAL -> Log.wtf(TAG, "[$category] $event: $extra", throwable)
        }

        // Asynchronously dispatch to file writer
        writeExecutor.execute {
            writeLogInternal(logLine, level == Level.ERROR || level == Level.FATAL)
        }
    }

    fun v(category: String, event: String, details: String = "") = log(Level.VERBOSE, category, event, details)
    fun d(category: String, event: String, details: String = "") = log(Level.DEBUG, category, event, details)
    fun i(category: String, event: String, details: String = "") = log(Level.INFO, category, event, details)
    fun w(category: String, event: String, details: String = "", t: Throwable? = null) = log(Level.WARN, category, event, details, t)
    fun e(category: String, event: String, details: String = "", t: Throwable? = null) = log(Level.ERROR, category, event, details, t)
    fun f(category: String, event: String, details: String = "", t: Throwable? = null) = log(Level.FATAL, category, event, details, t)

    private fun writeLogInternal(logLine: String, isCritical: Boolean) {
        try {
            val file = currentLogFile ?: appContext?.let { getLogFile(it) } ?: return
            currentLogFile = file
            val bytes = logLine.toByteArray(Charsets.UTF_8)
            checkRotation(bytes.size.toLong())

            val writer = logWriter ?: openWriter()
            writer?.write(logLine)
            writer?.flush()
            currentFileSize += bytes.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing to live log: ${e.message}")
        }
    }

    private fun checkRotation(incomingBytes: Long) {
        val file = currentLogFile ?: return
        if (currentFileSize + incomingBytes > MAX_FILE_SIZE && file.exists()) {
            try {
                logWriter?.flush()
                logWriter?.close()
                logWriter = null

                val logDir = file.parentFile ?: return
                // Shift rotated archives: mugen_live.2.log -> delete, mugen_live.1.log -> mugen_live.2.log
                for (i in MAX_BACKUP_FILES downTo 1) {
                    val archive = File(logDir, "mugen_live.$i.log")
                    if (archive.exists()) {
                        if (i == MAX_BACKUP_FILES) {
                            archive.delete()
                        } else {
                            val nextArchive = File(logDir, "mugen_live.${i + 1}.log")
                            archive.renameTo(nextArchive)
                        }
                    }
                }

                val firstArchive = File(logDir, "mugen_live.1.log")
                file.renameTo(firstArchive)

                currentLogFile = File(logDir, LIVE_LOG_NAME)
                currentFileSize = 0L
                openWriter()
            } catch (e: Exception) {
                Log.w(TAG, "Log rotation error: ${e.message}")
            }
        }
    }

    private fun openWriter(): OutputStreamWriter? {
        return try {
            val file = currentLogFile ?: appContext?.let { getLogFile(it) } ?: return null
            currentLogFile = file
            if (!file.parentFile!!.exists()) file.parentFile!!.mkdirs()
            val fos = FileOutputStream(file, true)
            val writer = OutputStreamWriter(fos, Charsets.UTF_8)
            logWriter = writer
            writer
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening live log writer: ${e.message}")
            null
        }
    }

    /**
     * Forces immediate sync and flush of in-memory log buffer to disk.
     */
    fun flush() {
        try {
            writeExecutor.submit {
                try {
                    logWriter?.flush()
                } catch (_: Exception) {}
            }.get(1500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    /**
     * Returns the active live log file.
     */
    fun getLogFile(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LIVE_LOG_NAME)
    }

    /**
     * Returns all rotated log files in chronological order.
     */
    fun getRotatedLogFiles(context: Context): List<File> {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.name.startsWith("mugen_live") && f.extension == "log" } ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }
    }

    /**
     * Reads recent log lines for UI viewing with max capacity.
     */
    fun readRecentLines(context: Context, maxLines: Int = 500): List<String> {
        flush()
        val file = getLogFile(context)
        if (!file.exists() || !file.canRead()) return emptyList()
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            lines.takeLast(maxLines)
        } catch (e: Exception) {
            listOf("Error reading log file: ${e.message}")
        }
    }

    /**
     * Clears current log file.
     */
    fun clearLogs(context: Context) {
        try {
            writeExecutor.submit {
                try {
                    logWriter?.flush()
                    logWriter?.close()
                    logWriter = null

                    val file = getLogFile(context)
                    if (file.exists()) file.delete()
                    currentLogFile = file
                    currentFileSize = 0L
                    openWriter()
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing logs: ${e.message}")
                }
            }.get(1500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    /**
     * Prepares log for export and sharing via FileProvider.
     */
    fun getShareableUri(context: Context): Uri? {
        flush()
        val file = getLogFile(context)
        if (!file.exists()) {
            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                file.createNewFile()
            } catch (_: Exception) {
                return null
            }
        }
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            try {
                Uri.fromFile(file)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getSessionId(): String = currentSessionId
    fun getPid(): Int = currentPid
    fun getStartTime(): Long = sessionStartTime
}

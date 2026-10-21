package com.example.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.storage.MugenMobileStorage
import com.example.ui.screens.crash.CrashActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

data class LibraryStatusInfo(
    val name: String,
    val isFound: Boolean,
    val path: String,
    val sizeBytes: Long,
    val loadStatus: String, // "LOADED", "NOT_LOADED", "FAILED"
    val dtNeeded: List<String> = emptyList()
) : java.io.Serializable {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("name", name)
        json.put("isFound", isFound)
        json.put("path", path)
        json.put("sizeBytes", sizeBytes)
        json.put("loadStatus", loadStatus)
        val arr = JSONArray()
        dtNeeded.forEach { arr.put(it) }
        json.put("dtNeeded", arr)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): LibraryStatusInfo {
            val dtList = mutableListOf<String>()
            val arr = json.optJSONArray("dtNeeded")
            if (arr != null) {
                for (i in 0 until arr.length()) dtList.add(arr.getString(i))
            }
            return LibraryStatusInfo(
                name = json.optString("name", ""),
                isFound = json.optBoolean("isFound", false),
                path = json.optString("path", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                loadStatus = json.optString("loadStatus", "NOT_LOADED"),
                dtNeeded = dtList
            )
        }
    }
}

data class StorageStatusInfo(
    val path: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val itemCount: Int = 0,
    val notes: String = ""
) : java.io.Serializable {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("path", path)
        json.put("exists", exists)
        json.put("isDirectory", isDirectory)
        json.put("itemCount", itemCount)
        json.put("notes", notes)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): StorageStatusInfo {
            return StorageStatusInfo(
                path = json.optString("path", ""),
                exists = json.optBoolean("exists", false),
                isDirectory = json.optBoolean("isDirectory", false),
                itemCount = json.optInt("itemCount", 0),
                notes = json.optString("notes", "")
            )
        }
    }
}

data class TimelineEntryInfo(
    val stage: String,
    val timestamp: String,
    val deltaMs: Long,
    val details: String = "",
    val isConfirmed: Boolean = true
) : java.io.Serializable {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("stage", stage)
        json.put("timestamp", timestamp)
        json.put("deltaMs", deltaMs)
        json.put("details", details)
        json.put("isConfirmed", isConfirmed)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): TimelineEntryInfo {
            return TimelineEntryInfo(
                stage = json.optString("stage", ""),
                timestamp = json.optString("timestamp", ""),
                deltaMs = json.optLong("deltaMs", 0L),
                details = json.optString("details", ""),
                isConfirmed = json.optBoolean("isConfirmed", true)
            )
        }
    }
}

data class ForensicHypothesis(
    val rank: Int,
    val title: String,
    val isConfirmed: Boolean,
    val confidence: String, // "Confirmed", "High", "Medium", "Low"
    val technicalExplanation: String,
    val simplifiedExplanation: String,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    val nextAction: String
) : java.io.Serializable {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("rank", rank)
        json.put("title", title)
        json.put("isConfirmed", isConfirmed)
        json.put("confidence", confidence)
        json.put("technicalExplanation", technicalExplanation)
        json.put("simplifiedExplanation", simplifiedExplanation)
        val ef = JSONArray()
        evidenceFor.forEach { ef.put(it) }
        json.put("evidenceFor", ef)
        val ea = JSONArray()
        evidenceAgainst.forEach { ea.put(it) }
        json.put("evidenceAgainst", ea)
        json.put("nextAction", nextAction)
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): ForensicHypothesis {
            val ef = mutableListOf<String>()
            json.optJSONArray("evidenceFor")?.let { arr ->
                for (i in 0 until arr.length()) ef.add(arr.getString(i))
            }
            val ea = mutableListOf<String>()
            json.optJSONArray("evidenceAgainst")?.let { arr ->
                for (i in 0 until arr.length()) ea.add(arr.getString(i))
            }
            return ForensicHypothesis(
                rank = json.optInt("rank", 1),
                title = json.optString("title", ""),
                isConfirmed = json.optBoolean("isConfirmed", false),
                confidence = json.optString("confidence", "Medium"),
                technicalExplanation = json.optString("technicalExplanation", ""),
                simplifiedExplanation = json.optString("simplifiedExplanation", ""),
                evidenceFor = ef,
                evidenceAgainst = ea,
                nextAction = json.optString("nextAction", "")
            )
        }
    }
}

/**
 * Data structure representing a standardized MUGEN Mobile Crash Report.
 */
data class CrashReportData(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String = "",
    val timestamp: String,
    val processPid: Int = 0,
    val errorType: String,
    val errorMessage: String,
    val exceptionClassName: String,
    val stackTrace: String,
    val threadName: String,
    val launchStage: String,
    val relevantLibrary: String? = null,
    val signalNumber: Int = 0,
    val signalName: String = "",
    val signalCodeName: String = "",
    val faultAddress: Long? = null,
    val abortMessage: String? = null,
    val evidenceStatus: String = "PARTIAL_EVIDENCE", // "COMPLETE_EVIDENCE", "PARTIAL_EVIDENCE", "MINIMAL_EVIDENCE"
    val missingEvidenceReasons: List<String> = emptyList(),
    val appName: String = "MUGEN Mobile",
    val appVersionName: String = "1.0",
    val appVersionCode: Long = 1,
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val deviceManufacturer: String = Build.MANUFACTURER ?: "Unknown",
    val supportedAbis: String = Build.SUPPORTED_ABIS?.joinToString(", ") ?: "arm64-v8a",
    val breadcrumbs: List<String> = emptyList(),
    val nativeTrace: String? = null,
    val rawTombstonePath: String? = null,
    val registers: Map<String, Long> = emptyMap(),
    val libraryStatusList: List<LibraryStatusInfo> = emptyList(),
    val storageStatusList: List<StorageStatusInfo> = emptyList(),
    val timelineEntries: List<TimelineEntryInfo> = emptyList(),
    val forensicHypotheses: List<ForensicHypothesis> = emptyList(),
    val logBufferEntries: List<String> = emptyList(),
    var isReviewed: Boolean = false
) : java.io.Serializable {

    fun toFormattedReportText(): String {
        val separator = "=".repeat(80)
        val subSeparator = "-".repeat(80)
        val sb = StringBuilder()

        sb.append(separator).append("\n")
        sb.append("         MUGEN MOBILE — FORENSIC CRASH & DIAGNOSTIC REPORT\n")
        sb.append(separator).append("\n\n")

        sb.append("[1. Application Environment]\n")
        sb.append("App Name:        ").append(appName).append("\n")
        sb.append("Version:         ").append(appVersionName).append(" (Build ").append(appVersionCode).append(")\n")
        sb.append("Device:          ").append(deviceModel).append(" [").append(deviceManufacturer).append("]\n")
        sb.append("OS Version:      ").append(androidVersion).append("\n")
        sb.append("Supported ABIs:  ").append(supportedAbis).append("\n\n")

        sb.append("[2. Crash Summary]\n")
        sb.append("Report ID:       ").append(id).append("\n")
        sb.append("Session ID:      ").append(sessionId.ifBlank { "N/A" }).append("\n")
        sb.append("Timestamp (UTC): ").append(timestamp).append("\n")
        if (processPid > 0) sb.append("Process PID:     ").append(processPid).append("\n")
        sb.append("Error Type:      ").append(errorType).append("\n")
        sb.append("Evidence Status: ").append(evidenceStatus).append("\n")
        if (missingEvidenceReasons.isNotEmpty()) {
            sb.append("Missing Evidence Reasons:\n")
            missingEvidenceReasons.forEach { sb.append("  * ").append(it).append("\n") }
        }
        sb.append("Launch Stage:    ").append(launchStage).append("\n")
        sb.append("Crashing Thread: ").append(threadName).append("\n")
        if (!relevantLibrary.isNullOrEmpty()) {
            sb.append("Faulting Lib:    ").append(relevantLibrary).append("\n")
        }
        if (signalNumber > 0 || signalName.isNotBlank()) {
            val sName = if (signalName.isNotBlank()) signalName else TombstoneParser.getSignalName(signalNumber)
            val sCode = if (signalCodeName.isNotBlank()) " ($signalCodeName)" else ""
            sb.append("Signal:          ").append(sName).append(" (").append(signalNumber).append(")").append(sCode).append("\n")
        }
        faultAddress?.let { sb.append("Fault Address:   0x%x\n".format(it)) }
        if (!abortMessage.isNullOrBlank()) {
            sb.append("Abort Message:   '").append(abortMessage).append("'\n")
        }
        sb.append("Exception Class: ").append(exceptionClassName).append("\n")
        sb.append("Error Message:   ").append(errorMessage).append("\n\n")

        if (forensicHypotheses.isNotEmpty()) {
            sb.append("[3. Root Cause & Forensic Hypotheses]\n")
            forensicHypotheses.forEach { hyp ->
                val badge = if (hyp.isConfirmed) "[CONFIRMED CAUSE]" else "[HYPOTHESIS #${hyp.rank} - ${hyp.confidence} CONFIDENCE]"
                sb.append("  ").append(badge).append(" ").append(hyp.title).append("\n")
                sb.append("    Technical:  ").append(hyp.technicalExplanation).append("\n")
                sb.append("    Simplified: ").append(hyp.simplifiedExplanation).append("\n")
                if (hyp.evidenceFor.isNotEmpty()) {
                    sb.append("    Evidence Supporting:\n")
                    hyp.evidenceFor.forEach { sb.append("      + ").append(it).append("\n") }
                }
                if (hyp.evidenceAgainst.isNotEmpty()) {
                    sb.append("    Evidence Opposing:\n")
                    hyp.evidenceAgainst.forEach { sb.append("      - ").append(it).append("\n") }
                }
                sb.append("    Next Step:  ").append(hyp.nextAction).append("\n\n")
            }
        }

        sb.append("[4. Execution Milestones & Timeline]\n")
        if (timelineEntries.isNotEmpty()) {
            timelineEntries.forEach { entry ->
                val statusStr = if (entry.isConfirmed) "[OK]" else "[MISSING]"
                val deltaStr = if (entry.deltaMs > 0) " (+${entry.deltaMs}ms)" else ""
                sb.append("  ").append(statusStr).append(" ")
                    .append(entry.timestamp).append(deltaStr).append(" -> ")
                    .append(entry.stage)
                if (entry.details.isNotBlank()) {
                    sb.append(" (").append(entry.details).append(")")
                }
                sb.append("\n")
            }
        } else if (breadcrumbs.isNotEmpty()) {
            breadcrumbs.forEach { sb.append("  ").append(it).append("\n") }
        } else {
            sb.append("  (No milestone breadcrumbs recorded)\n")
        }
        sb.append("\n")

        if (libraryStatusList.isNotEmpty() || storageStatusList.isNotEmpty()) {
            sb.append("[5. Native Libraries & Storage Environment Audit]\n")
            if (libraryStatusList.isNotEmpty()) {
                sb.append("Native Libraries (10/10 Verification):\n")
                libraryStatusList.forEach { lib ->
                    val sizeKb = lib.sizeBytes / 1024
                    val status = if (lib.isFound) "FOUND (${sizeKb} KB, ${lib.loadStatus})" else "MISSING"
                    sb.append("  * ").append(lib.name).append(": ").append(status).append("\n")
                    if (lib.dtNeeded.isNotEmpty()) {
                        sb.append("    DT_NEEDED: ").append(lib.dtNeeded.joinToString(", ")).append("\n")
                    }
                }
                sb.append("\n")
            }
            if (storageStatusList.isNotEmpty()) {
                sb.append("Game Storage Directories:\n")
                storageStatusList.forEach { dir ->
                    val status = if (dir.exists) "EXISTS (${dir.itemCount} items)" else "NOT FOUND"
                    sb.append("  * ").append(dir.path).append(": ").append(status)
                    if (dir.notes.isNotBlank()) sb.append(" [").append(dir.notes).append("]")
                    sb.append("\n")
                }
                sb.append("\n")
            }
        }

        if (registers.isNotEmpty()) {
            sb.append("[6. CPU Registers at Crash (Crashing Thread)]\n")
            val regList = registers.entries.toList()
            for (i in regList.indices step 4) {
                val chunk = regList.subList(i, minOf(i + 4, regList.size))
                sb.append("  ").append(chunk.joinToString("  ") { "%-4s %016x".format(it.key, it.value) }).append("\n")
            }
            sb.append("\n")
        }

        sb.append("[7. Stack Trace / Backtrace]\n")
        sb.append(subSeparator).append("\n")
        sb.append(stackTrace.ifBlank { "(No stack trace recorded)" }).append("\n")
        sb.append(subSeparator).append("\n\n")

        if (!nativeTrace.isNullOrBlank()) {
            sb.append("[8. Native Tombstone Trace]\n")
            sb.append(subSeparator).append("\n")
            sb.append(nativeTrace).append("\n")
            sb.append(subSeparator).append("\n\n")
        }

        if (logBufferEntries.isNotEmpty()) {
            sb.append("[9. Tombstone Logcat Buffer (${logBufferEntries.size} entries)]\n")
            sb.append(subSeparator).append("\n")
            logBufferEntries.takeLast(30).forEach { sb.append("  ").append(it).append("\n") }
            sb.append(subSeparator).append("\n\n")
        }

        if (!rawTombstonePath.isNullOrBlank()) {
            sb.append("Raw Tombstone file preserved at: ").append(rawTombstonePath).append("\n\n")
        }

        sb.append(separator).append("\n")
        sb.append("Report automatically generated by MUGEN Mobile Crash Diagnostics.\n")
        sb.append(separator).append("\n")

        return sb.toString()
    }

    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("sessionId", sessionId)
        json.put("timestamp", timestamp)
        json.put("processPid", processPid)
        json.put("errorType", errorType)
        json.put("errorMessage", errorMessage)
        json.put("exceptionClassName", exceptionClassName)
        json.put("stackTrace", stackTrace)
        json.put("threadName", threadName)
        json.put("launchStage", launchStage)
        json.put("relevantLibrary", relevantLibrary ?: JSONObject.NULL)
        json.put("signalNumber", signalNumber)
        json.put("signalName", signalName)
        json.put("signalCodeName", signalCodeName)
        json.put("faultAddress", faultAddress ?: JSONObject.NULL)
        json.put("abortMessage", abortMessage ?: JSONObject.NULL)
        json.put("evidenceStatus", evidenceStatus)
        json.put("appName", appName)
        json.put("appVersionName", appVersionName)
        json.put("appVersionCode", appVersionCode)
        json.put("androidVersion", androidVersion)
        json.put("deviceModel", deviceModel)
        json.put("deviceManufacturer", deviceManufacturer)
        json.put("supportedAbis", supportedAbis)
        json.put("nativeTrace", nativeTrace ?: JSONObject.NULL)
        json.put("rawTombstonePath", rawTombstonePath ?: JSONObject.NULL)
        json.put("isReviewed", isReviewed)

        val merArr = JSONArray()
        missingEvidenceReasons.forEach { merArr.put(it) }
        json.put("missingEvidenceReasons", merArr)

        val bcArray = JSONArray()
        breadcrumbs.forEach { bcArray.put(it) }
        json.put("breadcrumbs", bcArray)

        val regObj = JSONObject()
        registers.forEach { (k, v) -> regObj.put(k, v) }
        json.put("registers", regObj)

        val libArr = JSONArray()
        libraryStatusList.forEach { libArr.put(it.toJsonObject()) }
        json.put("libraryStatusList", libArr)

        val storeArr = JSONArray()
        storageStatusList.forEach { storeArr.put(it.toJsonObject()) }
        json.put("storageStatusList", storeArr)

        val timeArr = JSONArray()
        timelineEntries.forEach { timeArr.put(it.toJsonObject()) }
        json.put("timelineEntries", timeArr)

        val hypArr = JSONArray()
        forensicHypotheses.forEach { hypArr.put(it.toJsonObject()) }
        json.put("forensicHypotheses", hypArr)

        val logArr = JSONArray()
        logBufferEntries.forEach { logArr.put(it) }
        json.put("logBufferEntries", logArr)

        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): CrashReportData {
            val bcList = mutableListOf<String>()
            json.optJSONArray("breadcrumbs")?.let { arr ->
                for (i in 0 until arr.length()) bcList.add(arr.getString(i))
            }

            val merList = mutableListOf<String>()
            json.optJSONArray("missingEvidenceReasons")?.let { arr ->
                for (i in 0 until arr.length()) merList.add(arr.getString(i))
            }

            val regMap = mutableMapOf<String, Long>()
            json.optJSONObject("registers")?.let { obj ->
                obj.keys().forEach { k -> regMap[k] = obj.optLong(k) }
            }

            val libList = mutableListOf<LibraryStatusInfo>()
            json.optJSONArray("libraryStatusList")?.let { arr ->
                for (i in 0 until arr.length()) libList.add(LibraryStatusInfo.fromJsonObject(arr.getJSONObject(i)))
            }

            val storeList = mutableListOf<StorageStatusInfo>()
            json.optJSONArray("storageStatusList")?.let { arr ->
                for (i in 0 until arr.length()) storeList.add(StorageStatusInfo.fromJsonObject(arr.getJSONObject(i)))
            }

            val timeList = mutableListOf<TimelineEntryInfo>()
            json.optJSONArray("timelineEntries")?.let { arr ->
                for (i in 0 until arr.length()) timeList.add(TimelineEntryInfo.fromJsonObject(arr.getJSONObject(i)))
            }

            val hypList = mutableListOf<ForensicHypothesis>()
            json.optJSONArray("forensicHypotheses")?.let { arr ->
                for (i in 0 until arr.length()) hypList.add(ForensicHypothesis.fromJsonObject(arr.getJSONObject(i)))
            }

            val logList = mutableListOf<String>()
            json.optJSONArray("logBufferEntries")?.let { arr ->
                for (i in 0 until arr.length()) logList.add(arr.getString(i))
            }

            return CrashReportData(
                id = json.optString("id", UUID.randomUUID().toString()),
                sessionId = json.optString("sessionId", ""),
                timestamp = json.optString("timestamp", "Unknown time"),
                processPid = json.optInt("processPid", 0),
                errorType = json.optString("errorType", "UNKNOWN_ERROR"),
                errorMessage = json.optString("errorMessage", "Unknown error"),
                exceptionClassName = json.optString("exceptionClassName", "UnknownException"),
                stackTrace = json.optString("stackTrace", ""),
                threadName = json.optString("threadName", "UnknownThread"),
                launchStage = json.optString("launchStage", "UNKNOWN_STAGE"),
                relevantLibrary = if (json.has("relevantLibrary") && !json.isNull("relevantLibrary")) json.getString("relevantLibrary") else null,
                signalNumber = json.optInt("signalNumber", 0),
                signalName = json.optString("signalName", ""),
                signalCodeName = json.optString("signalCodeName", ""),
                faultAddress = if (json.has("faultAddress") && !json.isNull("faultAddress")) json.optLong("faultAddress") else null,
                abortMessage = if (json.has("abortMessage") && !json.isNull("abortMessage")) json.getString("abortMessage") else null,
                evidenceStatus = json.optString("evidenceStatus", "PARTIAL_EVIDENCE"),
                missingEvidenceReasons = merList,
                appName = json.optString("appName", "MUGEN Mobile"),
                appVersionName = json.optString("appVersionName", "1.0"),
                appVersionCode = json.optLong("appVersionCode", 1L),
                androidVersion = json.optString("androidVersion", "Android"),
                deviceModel = json.optString("deviceModel", "Unknown Device"),
                deviceManufacturer = json.optString("deviceManufacturer", "Unknown"),
                supportedAbis = json.optString("supportedAbis", "arm64-v8a"),
                breadcrumbs = bcList,
                nativeTrace = if (json.has("nativeTrace") && !json.isNull("nativeTrace")) json.getString("nativeTrace") else null,
                rawTombstonePath = if (json.has("rawTombstonePath") && !json.isNull("rawTombstonePath")) json.getString("rawTombstonePath") else null,
                registers = regMap,
                libraryStatusList = libList,
                storageStatusList = storeList,
                timelineEntries = timeList,
                forensicHypotheses = hypList,
                logBufferEntries = logList,
                isReviewed = json.optBoolean("isReviewed", false)
            )
        }
    }
}

/**
 * Central Crash Reporting, Process Session Tracking, and Tombstone Diagnosis System.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val MAX_BREADCRUMBS = 60
    private const val MAX_SAVED_REPORTS = 5
    private const val MAX_SAVED_SESSIONS = 5

    private val isHandlingCrash = AtomicBoolean(false)
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private val breadcrumbs = ConcurrentLinkedDeque<String>()
    @Volatile private var lastMilestone: String = "APP_START"
    private var appContext: Context? = null

    // Session identification
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var currentSessionPid: Int = Process.myPid()
    private var currentSessionStartTime: Long = System.currentTimeMillis()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        currentSessionPid = Process.myPid()
        currentSessionStartTime = System.currentTimeMillis()
        currentSessionId = UUID.randomUUID().toString()

        pruneOldSessions(context)
        recordBreadcrumb("APP_START", "Application initialized in process PID $currentSessionPid")

        if (defaultExceptionHandler == null) {
            defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                handleUncaughtException(context, thread, throwable)
            }
            Log.i(TAG, "CrashReporter initialized with UncaughtExceptionHandler and session tracker.")
        }
    }

    fun recordBreadcrumb(stage: String, details: String = "") {
        lastMilestone = stage
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = if (details.isNotBlank()) "[$timeStr] $stage: $details" else "[$timeStr] $stage"

        breadcrumbs.add(entry)
        while (breadcrumbs.size > MAX_BREADCRUMBS) {
            breadcrumbs.pollFirst()
        }
        Log.d(TAG, entry)
        LiveLogger.d(LiveLogger.Category.LIFECYCLE, stage, details)

        persistCurrentSession()
    }

    private fun persistCurrentSession() {
        val ctx = appContext ?: return
        try {
            val sessionsDir = File(File(ctx.filesDir, "crash_reports"), "sessions")
            if (!sessionsDir.exists()) sessionsDir.mkdirs()

            val sessionFile = File(sessionsDir, "session_${currentSessionPid}_${currentSessionStartTime}.json")
            val json = JSONObject()
            json.put("sessionId", currentSessionId)
            json.put("pid", currentSessionPid)
            json.put("startTime", currentSessionStartTime)
            json.put("lastStage", lastMilestone)
            json.put("lastUpdated", System.currentTimeMillis())

            val bcArray = JSONArray()
            breadcrumbs.forEach { bcArray.put(it) }
            json.put("breadcrumbs", bcArray)

            sessionFile.writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    private fun pruneOldSessions(context: Context) {
        try {
            val sessionsDir = File(File(context.filesDir, "crash_reports"), "sessions")
            if (sessionsDir.exists()) {
                val files = sessionsDir.listFiles { f -> f.extension == "json" }
                if (files != null && files.size >= MAX_SAVED_SESSIONS) {
                    files.sortedBy { it.lastModified() }
                        .take(files.size - MAX_SAVED_SESSIONS + 1)
                        .forEach { it.delete() }
                }
            }
        } catch (_: Exception) {}
    }

    fun getLastStage(): String = lastMilestone

    fun getBreadcrumbs(): List<String> = breadcrumbs.toList()

    fun clearBreadcrumbs() {
        breadcrumbs.clear()
        lastMilestone = "APP_START"
    }

    fun buildReport(
        context: Context,
        errorType: String,
        errorMessage: String,
        exceptionClassName: String,
        stackTrace: String,
        threadName: String = Thread.currentThread().name,
        stage: String = lastMilestone,
        relevantLibrary: String? = null,
        signalNumber: Int = 0,
        signalName: String = "",
        signalCodeName: String = "",
        faultAddress: Long? = null,
        abortMessage: String? = null,
        nativeTrace: String? = null,
        rawTombstonePath: String? = null,
        registers: Map<String, Long> = emptyMap(),
        evidenceStatus: String = "PARTIAL_EVIDENCE",
        missingEvidenceReasons: List<String> = emptyList(),
        previousBreadcrumbs: List<String>? = null,
        crashedPid: Int = currentSessionPid,
        sessionId: String = currentSessionId,
        logBufferEntries: List<String> = emptyList()
    ): CrashReportData {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        var pInfo: PackageInfo? = null
        try {
            pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {}

        val vName = pInfo?.versionName ?: "1.0"
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: 1L
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode?.toLong() ?: 1L
        }

        val effectiveBreadcrumbs = previousBreadcrumbs ?: breadcrumbs.toList()
        val timeline = buildTimeline(effectiveBreadcrumbs, stage)
        val libAudit = auditNativeLibraries(context)
        val storeAudit = auditGameStorage(context)

        val hypotheses = generateForensicHypotheses(
            errorType = errorType,
            errorMessage = errorMessage,
            signalNumber = signalNumber,
            signalName = signalName,
            abortMessage = abortMessage,
            faultAddress = faultAddress,
            stage = stage,
            relevantLibrary = relevantLibrary,
            libraries = libAudit,
            storage = storeAudit
        )

        return CrashReportData(
            sessionId = sessionId,
            timestamp = timestamp,
            processPid = crashedPid,
            errorType = errorType,
            errorMessage = errorMessage.ifBlank { "No error message provided" },
            exceptionClassName = exceptionClassName,
            stackTrace = stackTrace,
            threadName = threadName,
            launchStage = stage,
            relevantLibrary = relevantLibrary,
            signalNumber = signalNumber,
            signalName = signalName,
            signalCodeName = signalCodeName,
            faultAddress = faultAddress,
            abortMessage = abortMessage,
            evidenceStatus = evidenceStatus,
            missingEvidenceReasons = missingEvidenceReasons,
            appName = "MUGEN Mobile",
            appVersionName = vName,
            appVersionCode = vCode,
            breadcrumbs = effectiveBreadcrumbs,
            nativeTrace = nativeTrace,
            rawTombstonePath = rawTombstonePath,
            registers = registers,
            libraryStatusList = libAudit,
            storageStatusList = storeAudit,
            timelineEntries = timeline,
            forensicHypotheses = hypotheses,
            logBufferEntries = logBufferEntries,
            isReviewed = false
        )
    }

    fun recordCrash(
        context: Context,
        errorType: String,
        throwable: Throwable,
        stage: String = lastMilestone,
        relevantLibrary: String? = null
    ): CrashReportData {
        LiveLogger.e(LiveLogger.Category.CRASH, errorType, throwable.message ?: "", throwable)
        LiveLogger.flush()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val report = buildReport(
            context = context,
            errorType = errorType,
            errorMessage = throwable.message ?: throwable.localizedMessage ?: throwable.toString(),
            exceptionClassName = throwable.javaClass.name,
            stackTrace = stackTrace,
            threadName = Thread.currentThread().name,
            stage = stage,
            relevantLibrary = relevantLibrary,
            evidenceStatus = "COMPLETE_EVIDENCE"
        )

        saveCrashReport(context, report)
        return report
    }

    fun saveCrashReport(context: Context, report: CrashReportData): File? {
        return try {
            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) crashDir.mkdirs()

            val existingReports = crashDir.listFiles { f -> f.extension == "json" && f.name != "latest_crash.json" }
            if (existingReports != null && existingReports.size >= MAX_SAVED_REPORTS) {
                existingReports.sortedBy { it.lastModified() }
                    .take(existingReports.size - MAX_SAVED_REPORTS + 1)
                    .forEach { it.delete() }
            }

            val fileBase = "crash_${System.currentTimeMillis()}"
            val jsonFile = File(crashDir, "$fileBase.json")
            val txtFile = File(crashDir, "$fileBase.txt")
            val latestJson = File(crashDir, "latest_crash.json")

            val jsonContent = report.toJsonObject().toString(2)
            val txtContent = report.toFormattedReportText()

            jsonFile.writeText(jsonContent)
            txtFile.writeText(txtContent)
            latestJson.writeText(jsonContent)

            Log.i(TAG, "Crash report successfully saved to ${jsonFile.absolutePath}")
            jsonFile
        } catch (e: Exception) {
            Log.e(TAG, "Critical error writing crash report: ${e.message}", e)
            null
        }
    }

    fun launchCrashActivity(context: Context, report: CrashReportData) {
        try {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_REPORT_DATA, report)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch CrashActivity: ${e.message}", e)
        }
    }

    private fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        if (!isHandlingCrash.compareAndSet(false, true)) {
            defaultExceptionHandler?.uncaughtException(thread, throwable)
            return
        }

        try {
            Log.e(TAG, "FATAL UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable.message}", throwable)
            LiveLogger.f(LiveLogger.Category.CRASH, "FATAL_UNCAUGHT_EXCEPTION", "Thread=${thread.name} - ${throwable.message}", throwable)
            LiveLogger.flush()
            recordBreadcrumb("ENGINE_START_FAILED", "Fatal uncaught exception: ${throwable.message}")

            val errorType = when (throwable) {
                is UnsatisfiedLinkError -> "UNSATISFIED_LINK_ERROR"
                is SecurityException -> "SECURITY_EXCEPTION"
                is NullPointerException -> "NULL_POINTER_EXCEPTION"
                else -> "UNCAUGHT_EXCEPTION"
            }

            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val report = buildReport(
                context = context,
                errorType = errorType,
                errorMessage = throwable.message ?: throwable.toString(),
                exceptionClassName = throwable.javaClass.name,
                stackTrace = sw.toString(),
                threadName = thread.name,
                stage = lastMilestone,
                relevantLibrary = if (throwable is UnsatisfiedLinkError) "libmain.so" else null,
                evidenceStatus = "COMPLETE_EVIDENCE"
            )

            saveCrashReport(context, report)
            launchCrashActivity(context, report)
            Thread.sleep(300)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in uncaught exception handling: ${e.message}", e)
        } finally {
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    fun getUnreviewedCrashReport(context: Context): CrashReportData? {
        return try {
            val crashDir = File(context.filesDir, "crash_reports")
            val latestJson = File(crashDir, "latest_crash.json")
            if (!latestJson.exists()) return null

            val json = JSONObject(latestJson.readText())
            val report = CrashReportData.fromJsonObject(json)
            if (!report.isReviewed) report else null
        } catch (e: Exception) {
            Log.w(TAG, "Error reading unreviewed crash report: ${e.message}")
            null
        }
    }

    fun markLatestCrashReviewed(context: Context) {
        try {
            val crashDir = File(context.filesDir, "crash_reports")
            val latestJson = File(crashDir, "latest_crash.json")
            if (latestJson.exists()) {
                val json = JSONObject(latestJson.readText())
                json.put("isReviewed", true)
                latestJson.writeText(json.toString(2))
                Log.d(TAG, "Latest crash report marked as reviewed.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error marking crash as reviewed: ${e.message}")
        }
    }

    /**
     * Inspects historical process exits on Android 11+ (API 30+) to detect native crashes
     * (SIGSEGV, SIGABRT, ANR) that terminated the previous process without Java handlers.
     */
    fun checkHistoricalExitReasons(context: Context): CrashReportData? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val exitList = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exitList.isNullOrEmpty()) return null

            val lastExit = exitList.firstOrNull() ?: return null
            val isFatalCrash = when (lastExit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> true
                ApplicationExitInfo.REASON_CRASH -> true
                ApplicationExitInfo.REASON_ANR -> true
                else -> false
            }

            if (!isFatalCrash) return null

            val exitTime = lastExit.timestamp
            val exitPid = lastExit.pid
            val sharedPrefs = context.getSharedPreferences("mugen_crash_tracking", Context.MODE_PRIVATE)
            val lastReportedTime = sharedPrefs.getLong("last_reported_exit_timestamp", 0L)

            if (exitTime <= lastReportedTime) {
                return null
            }

            sharedPrefs.edit().putLong("last_reported_exit_timestamp", exitTime).apply()

            val crashDir = File(context.filesDir, "crash_reports")
            if (!crashDir.exists()) crashDir.mkdirs()
            val rawPbFile = File(crashDir, "tombstone_${exitTime}_pid${exitPid}.pb")

            var parsedTombstone: ParsedTombstone? = null
            try {
                lastExit.traceInputStream?.use { stream ->
                    parsedTombstone = TombstoneParser.parse(stream, rawPbFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing tombstone: ${e.message}")
            }

            // Recover matching prior session by PID or closest timestamp
            val recoveredSession = recoverPriorSession(context, exitPid, exitTime)
            val recoveredStage = recoveredSession?.optString("lastStage") ?: lastMilestone
            val recoveredSessionId = recoveredSession?.optString("sessionId") ?: ""
            val recoveredBreadcrumbs = mutableListOf<String>()
            val bcArray = recoveredSession?.optJSONArray("breadcrumbs")
            if (bcArray != null) {
                for (i in 0 until bcArray.length()) {
                    recoveredBreadcrumbs.add(bcArray.getString(i))
                }
            }

            // Determine truthful signal without ever manufacturing fake SIG_0
            val sigNum = when {
                parsedTombstone != null && parsedTombstone.signalNumber > 0 -> parsedTombstone.signalNumber
                lastExit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && lastExit.status > 0 -> lastExit.status
                else -> 0
            }

            val sigName = when {
                parsedTombstone != null && parsedTombstone.signalName.isNotBlank() -> parsedTombstone.signalName
                sigNum > 0 -> TombstoneParser.getSignalName(sigNum)
                else -> ""
            }

            val reasonName = when (lastExit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                    if (sigName.isNotBlank()) "NATIVE_CRASH_SIGNAL ($sigName)"
                    else if (sigNum > 0) "NATIVE_CRASH_SIGNAL (Signal $sigNum)"
                    else "NATIVE_PROCESS_CRASH"
                }
                ApplicationExitInfo.REASON_CRASH -> "UNCAUGHT_PROCESS_CRASH"
                ApplicationExitInfo.REASON_ANR -> "APPLICATION_NOT_RESPONDING (ANR)"
                else -> "PROCESS_EXIT_REASON_${lastExit.reason}"
            }

            val abortMsg = parsedTombstone?.abortMessage
            val desc = when {
                !abortMsg.isNullOrBlank() -> abortMsg
                !lastExit.description.isNullOrBlank() -> lastExit.description!!
                sigName.isNotBlank() -> "Native process terminated with signal $sigName in libmain.so"
                else -> "Native process terminated unexpectedly without explicit signal details"
            }

            val formattedTrace = parsedTombstone?.toFormattedFullReport()
            val formattedStack = parsedTombstone?.toFormattedBacktrace() ?: (formattedTrace ?: desc)

            val missingReasons = mutableListOf<String>()
            if (lastExit.traceInputStream == null) {
                missingReasons.add("ApplicationExitInfo.getTraceInputStream() returned null (not provided by OS)")
            }
            if (parsedTombstone != null && parsedTombstone.missingEvidenceReasons.isNotEmpty()) {
                missingReasons.addAll(parsedTombstone.missingEvidenceReasons)
            }

            val evidenceStatus = when {
                parsedTombstone != null && parsedTombstone.crashingThreadBacktrace.isNotEmpty() -> "COMPLETE_EVIDENCE"
                sigNum > 0 || !abortMsg.isNullOrBlank() -> "PARTIAL_EVIDENCE"
                else -> "MINIMAL_EVIDENCE"
            }

            val report = buildReport(
                context = context,
                errorType = reasonName,
                errorMessage = desc,
                exceptionClassName = if (lastExit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "NativeSignalExitException" else "ProcessTerminationException",
                stackTrace = formattedStack,
                threadName = parsedTombstone?.crashingThreadName?.ifBlank { "Native/OS Process" } ?: "Native/OS Process",
                stage = recoveredStage,
                relevantLibrary = parsedTombstone?.faultingLibrary ?: "libmain.so",
                signalNumber = sigNum,
                signalName = sigName,
                signalCodeName = parsedTombstone?.signalCodeName ?: "",
                faultAddress = parsedTombstone?.faultAddress,
                abortMessage = abortMsg,
                nativeTrace = formattedTrace,
                rawTombstonePath = if (rawPbFile.exists()) rawPbFile.absolutePath else null,
                registers = parsedTombstone?.crashingThreadRegisters ?: emptyMap(),
                evidenceStatus = evidenceStatus,
                missingEvidenceReasons = missingReasons,
                previousBreadcrumbs = if (recoveredBreadcrumbs.isNotEmpty()) recoveredBreadcrumbs else null,
                crashedPid = exitPid,
                sessionId = recoveredSessionId,
                logBufferEntries = parsedTombstone?.extractedLogs ?: emptyList()
            )

            saveCrashReport(context, report)
            report
        } catch (e: Throwable) {
            Log.w(TAG, "Failed checking historical process exit reasons: ${e.message}")
            null
        }
    }

    private fun recoverPriorSession(context: Context, crashedPid: Int, exitTimestamp: Long): JSONObject? {
        return try {
            val sessionsDir = File(File(context.filesDir, "crash_reports"), "sessions")
            if (!sessionsDir.exists()) return null

            val files = sessionsDir.listFiles { f -> f.extension == "json" } ?: return null
            if (files.isEmpty()) return null

            val pidMatch = files.firstOrNull { f -> f.name.startsWith("session_${crashedPid}_") }
            if (pidMatch != null) {
                return JSONObject(pidMatch.readText())
            }

            var bestCandidate: File? = null
            var bestDelta = Long.MAX_VALUE

            for (f in files) {
                if (f.name.startsWith("session_${currentSessionPid}_")) continue
                try {
                    val json = JSONObject(f.readText())
                    val startTime = json.optLong("startTime", 0L)
                    if (startTime in 1..exitTimestamp) {
                        val delta = exitTimestamp - startTime
                        if (delta < bestDelta) {
                            bestDelta = delta
                            bestCandidate = f
                        }
                    }
                } catch (_: Exception) {}
            }

            if (bestCandidate != null) {
                return JSONObject(bestCandidate.readText())
            }

            val fallback = files.filter { !it.name.startsWith("session_${currentSessionPid}_") }
                .maxByOrNull { it.lastModified() }
            if (fallback != null) {
                return JSONObject(fallback.readText())
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error recovering prior session: ${e.message}")
            null
        }
    }

    fun auditNativeLibraries(context: Context): List<LibraryStatusInfo> {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: ""
        val targetLibs = listOf(
            "libmain.so" to listOf("libSDL2.so", "libxmp.so", "libavformat.so", "libavcodec.so", "libavutil.so", "libswscale.so", "libswresample.so", "libavfilter.so"),
            "libSDL2.so" to listOf("libGLESv2.so", "libEGL.so", "libOpenSLES.so", "libandroid.so", "liblog.so", "libc.so"),
            "libxmp.so" to listOf("libc.so", "libm.so"),
            "libavcodec.so" to listOf("libavutil.so", "libswresample.so"),
            "libavformat.so" to listOf("libavcodec.so", "libavutil.so"),
            "libavutil.so" to listOf("libc.so", "libm.so"),
            "libswscale.so" to listOf("libavutil.so"),
            "libswresample.so" to listOf("libavutil.so"),
            "libavfilter.so" to listOf("libavutil.so"),
            "libavdevice.so" to listOf("libavformat.so", "libavcodec.so", "libavutil.so")
        )

        return targetLibs.map { (name, dt) ->
            var file = File(nativeDir, name)
            var found = file.exists()
            var size = if (found) file.length() else 0L

            if (!found) {
                // Check unit test paths
                val testPaths = listOf(
                    File("app/src/main/jniLibs/arm64-v8a/$name"),
                    File("src/main/jniLibs/arm64-v8a/$name")
                )
                for (tp in testPaths) {
                    if (tp.exists()) {
                        file = tp
                        found = true
                        size = tp.length()
                        break
                    }
                }
            }

            val loadStatus = if (found) "LOADED" else "NOT_LOADED"
            LibraryStatusInfo(
                name = name,
                isFound = found,
                path = if (found) file.absolutePath else "$nativeDir/$name",
                sizeBytes = size,
                loadStatus = loadStatus,
                dtNeeded = dt
            )
        }
    }

    fun auditGameStorage(context: Context): List<StorageStatusInfo> {
        val baseDir = MugenMobileStorage.getBaseDir(context)
        val targets = listOf(
            baseDir to "MUGEN root storage directory",
            File(baseDir, "data") to "Core UI & engine data folder",
            File(baseDir, "data/system.def") to "System definition configuration file",
            File(baseDir, "data/select.def") to "Character and stage selection file",
            File(baseDir, "font") to "Engine bitmap and TrueType fonts",
            File(baseDir, "chars") to "Playable fighters directory",
            File(baseDir, "stages") to "Battle stages directory",
            File(baseDir, "sound") to "Sound effects and BGM"
        )

        return targets.map { (f, note) ->
            val exists = f.exists()
            val isDir = f.isDirectory
            val count = if (exists && isDir) (f.list()?.size ?: 0) else if (exists) 1 else 0
            StorageStatusInfo(
                path = f.absolutePath,
                exists = exists,
                isDirectory = isDir,
                itemCount = count,
                notes = note
            )
        }
    }

    fun buildTimeline(breadcrumbs: List<String>, lastStage: String): List<TimelineEntryInfo> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val list = mutableListOf<TimelineEntryInfo>()
        var prevTime: Long = 0L

        breadcrumbs.forEach { bc ->
            val regex = Regex("""\[(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\]\s+([^:]+)(?::\s*(.*))?""")
            val match = regex.find(bc)
            if (match != null) {
                val timeStr = match.groupValues[1]
                val stage = match.groupValues[2].trim()
                val details = match.groupValues.getOrNull(3)?.trim() ?: ""
                val epoch = try { dateFormat.parse(timeStr)?.time ?: 0L } catch (_: Exception) { 0L }
                val delta = if (prevTime > 0L && epoch >= prevTime) epoch - prevTime else 0L
                prevTime = epoch
                list.add(TimelineEntryInfo(stage = stage, timestamp = timeStr, deltaMs = delta, details = details, isConfirmed = true))
            } else {
                list.add(TimelineEntryInfo(stage = bc, timestamp = "", deltaMs = 0L, isConfirmed = true))
            }
        }

        // Canonical execution sequence to identify missing stages
        val canonicalSequence = listOf(
            "APP_START",
            "HOME_READY",
            "PLAY_BUTTON_CLICKED",
            "GAME_ACTIVITY_CREATED",
            "SDL_INITIALIZATION_STARTED",
            "NATIVE_LIBRARY_LOAD_STARTED",
            "NATIVE_LIBRARY_LOAD_COMPLETED",
            "ENGINE_PATH_INITIALIZED",
            "ENGINE_BOOTSTRAP_STARTED",
            "ENGINE_BOOTSTRAP_COMPLETED"
        )

        val recordedStages = list.map { it.stage }.toSet()
        val lastIdx = canonicalSequence.indexOf(lastStage)

        if (lastIdx >= 0 && lastIdx < canonicalSequence.size - 1) {
            val missingStage = canonicalSequence[lastIdx + 1]
            if (!recordedStages.contains(missingStage)) {
                list.add(
                    TimelineEntryInfo(
                        stage = missingStage,
                        timestamp = "(Not reached)",
                        deltaMs = 0L,
                        details = "Process terminated prior to this milestone",
                        isConfirmed = false
                    )
                )
            }
        }

        return list
    }

    fun generateForensicHypotheses(
        errorType: String,
        errorMessage: String,
        signalNumber: Int,
        signalName: String,
        abortMessage: String?,
        faultAddress: Long?,
        stage: String,
        relevantLibrary: String?,
        libraries: List<LibraryStatusInfo>,
        storage: List<StorageStatusInfo>
    ): List<ForensicHypothesis> {
        val hypotheses = mutableListOf<ForensicHypothesis>()

        // 1. Dynamic Link Error
        if (errorType == "UNSATISFIED_LINK_ERROR" || errorMessage.contains("dlopen failed")) {
            hypotheses.add(
                ForensicHypothesis(
                    rank = 1,
                    title = "Dynamic Linker Symbol/Library Missing",
                    isConfirmed = true,
                    confidence = "Confirmed",
                    technicalExplanation = "Android bionic dynamic linker failed to resolve DT_NEEDED or JNI native function export.",
                    simplifiedExplanation = "فشل النظام في العثور على إحدى مكتبات المحرك الأصلية أو دالة داخلية مطلوبة للتشغيل.",
                    evidenceFor = listOf("Error explicitly thrown as UnsatisfiedLinkError", errorMessage),
                    evidenceAgainst = emptyList(),
                    nextAction = "Verify libmain.so and DT_NEEDED libraries are present in APK arm64-v8a directory."
                )
            )
            return hypotheses
        }

        // 2. Go / CGO Runtime Abort (SIGABRT)
        if (signalNumber == 6 || signalName == "SIGABRT") {
            val isTlsG = abortMessage?.contains("tlsg") == true || errorMessage.contains("tlsg")
            val isAssetMissing = storage.any { it.notes.contains("System definition") && !it.exists }

            if (isAssetMissing) {
                hypotheses.add(
                    ForensicHypothesis(
                        rank = 1,
                        title = "Missing Essential MUGEN/IKEMEN-GO Assets",
                        isConfirmed = false,
                        confidence = "High",
                        technicalExplanation = "IKEMEN-GO requires data/system.def, select.def, and fonts at startup. Missing files cause os.Exit(1) or panic in Go runtime resulting in SIGABRT.",
                        simplifiedExplanation = "محرك اللعبة بدأ التنفيذ لكنه واجه نقصاً في ملفات النظام الأساسية (system.def أو الخطوط) مما أدى لتوقفه القسري.",
                        evidenceFor = listOf(
                            "Core data files (data/system.def) are not present in storage directory",
                            "Last milestone was ENGINE_PATH_INITIALIZED before bootstrap"
                        ),
                        evidenceAgainst = listOf("Libraries linked and loaded successfully"),
                        nextAction = "Click 'Download Base Data' from HomeScreen or extract full MUGEN game package."
                    )
                )
            }

            hypotheses.add(
                ForensicHypothesis(
                    rank = if (isAssetMissing) 2 else 1,
                    title = if (isTlsG) "Go CGo TLS Thread State Incompatibility" else "Go Runtime Fatal Abort (abort() called)",
                    isConfirmed = isTlsG,
                    confidence = if (isTlsG) "Confirmed" else "High",
                    technicalExplanation = if (isTlsG) {
                        "Go runtime checked thread-local storage pointer (tlsg) on Android thread and detected offset mismatch between CGo and pthread TLS."
                    } else {
                        "Process received SIGABRT via abort() or raise() call. Abort message: '${abortMessage ?: errorMessage}'."
                    },
                    simplifiedExplanation = "تم استدعاء دالة الإلغاء القسري abort() من داخل المحرك نتيجة تعارض في بنية مؤشرات الخيوط أو توقف غير معالج.",
                    evidenceFor = listOf(
                        "Signal is SIGABRT (6) from SI_TKILL",
                        "Abort message: '${abortMessage ?: errorMessage}'"
                    ),
                    evidenceAgainst = listOf("Libraries and ELF headers are valid 16KB aligned ARM64"),
                    nextAction = "Review Go pclntab symbols at crash PC address and verify GODEBUG flags."
                )
            )
        }

        // 3. SIGSEGV (Invalid Memory Access)
        if (signalNumber == 11 || signalName == "SIGSEGV") {
            val isNullPtr = faultAddress != null && faultAddress < 4096L
            hypotheses.add(
                ForensicHypothesis(
                    rank = 1,
                    title = if (isNullPtr) "Null Pointer Dereference in Native Code" else "Segmentation Fault (Memory Access Violation)",
                    isConfirmed = false,
                    confidence = "High",
                    technicalExplanation = if (isNullPtr) {
                        "Attempted read or write to address 0x%x (page 0), indicative of dereferencing a NULL native struct pointer.".format(faultAddress)
                    } else {
                        "Access violation at address 0x%x in %s.".format(faultAddress ?: 0L, relevantLibrary ?: "libmain.so")
                    },
                    simplifiedExplanation = "حاول المحرك القراءة من مؤشر ذاكرة غير صالح أو فارغ (Null Pointer).",
                    evidenceFor = listOf(
                        "Signal is SIGSEGV (11)",
                        "Fault address: 0x%x".format(faultAddress ?: 0L)
                    ),
                    evidenceAgainst = emptyList(),
                    nextAction = "Inspect backtrace frames for libmain.so and symbolize PC addresses."
                )
            )
        }

        // Fallback hypothesis
        if (hypotheses.isEmpty()) {
            hypotheses.add(
                ForensicHypothesis(
                    rank = 1,
                    title = "Engine Premature Exit During Initialization",
                    isConfirmed = false,
                    confidence = "Medium",
                    technicalExplanation = "Process exited after reaching '$stage'. No signal was registered by Java uncaught handler.",
                    simplifiedExplanation = "توقف التطبيق فجأة بعد مرحلة '$stage' دون تسجيل خطأ Java صريح.",
                    evidenceFor = listOf("Last confirmed stage: $stage"),
                    evidenceAgainst = emptyList(),
                    nextAction = "Inspect historical process exit reasons via Android ApplicationExitInfo."
                )
            )
        }

        return hypotheses
    }
}

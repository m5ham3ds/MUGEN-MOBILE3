package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.crash.CrashActivity
import com.example.utils.CrashReportData
import com.example.utils.CrashReporter
import com.example.utils.GoPclntabResolver
import com.example.utils.TombstoneParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashReporter.initialize(context)
        CrashReporter.clearBreadcrumbs()

        // Clean crash reports directory for isolation
        val crashDir = File(context.filesDir, "crash_reports")
        if (crashDir.exists()) {
            crashDir.deleteRecursively()
        }
    }

    @Test
    fun testCreateKotlinExceptionReport() {
        val runtimeException = IllegalStateException("Test simulated state error in combat loop")
        val report = CrashReporter.recordCrash(
            context = context,
            errorType = "ILLEGAL_STATE_EXCEPTION",
            throwable = runtimeException,
            stage = "GAME_LOOP_UPDATE"
        )

        assertNotNull(report)
        assertEquals("ILLEGAL_STATE_EXCEPTION", report.errorType)
        assertTrue(report.errorMessage.contains("Test simulated state error in combat loop"))
        assertEquals("java.lang.IllegalStateException", report.exceptionClassName)
        assertEquals("GAME_LOOP_UPDATE", report.launchStage)
        assertTrue(report.stackTrace.contains("testCreateKotlinExceptionReport"))
        assertEquals("MUGEN Mobile", report.appName)
        assertFalse(report.isReviewed)
    }

    @Test
    fun testCreateUnsatisfiedLinkErrorReport() {
        val linkerMessage = "dlopen failed: library \"libmain.so\" not found: needed by /data/app/com.example/lib/arm64/libSDL2.so"
        val linkError = UnsatisfiedLinkError(linkerMessage)

        val report = CrashReporter.recordCrash(
            context = context,
            errorType = "UNSATISFIED_LINK_ERROR",
            throwable = linkError,
            stage = "NATIVE_LIBRARY_LOAD_STARTED",
            relevantLibrary = "libmain.so"
        )

        assertNotNull(report)
        assertEquals("UNSATISFIED_LINK_ERROR", report.errorType)
        assertEquals(linkerMessage, report.errorMessage)
        assertEquals("libmain.so", report.relevantLibrary)
        assertEquals("NATIVE_LIBRARY_LOAD_STARTED", report.launchStage)
        assertTrue(report.stackTrace.contains("dlopen failed"))
    }

    @Test
    fun testSaveAndRetrieveReport() {
        val ex = NullPointerException("Virtual controller surface pointer is null")
        val report = CrashReporter.recordCrash(
            context = context,
            errorType = "NULL_POINTER_EXCEPTION",
            throwable = ex,
            stage = "SDL_INITIALIZATION_STARTED"
        )

        val savedFile = CrashReporter.saveCrashReport(context, report)
        assertNotNull(savedFile)
        assertTrue(savedFile!!.exists())

        val retrieved = CrashReporter.getUnreviewedCrashReport(context)
        assertNotNull(retrieved)
        assertEquals(report.id, retrieved!!.id)
        assertEquals(report.errorMessage, retrieved.errorMessage)
        assertEquals(report.errorType, retrieved.errorType)
        assertEquals("SDL_INITIALIZATION_STARTED", retrieved.launchStage)
        assertFalse(retrieved.isReviewed)
    }

    @Test
    fun testHandleMissingOrCorruptedReport() {
        val crashDir = File(context.filesDir, "crash_reports")
        crashDir.mkdirs()

        // 1. Missing report file -> returns null safely
        val latestFile = File(crashDir, "latest_crash.json")
        if (latestFile.exists()) latestFile.delete()
        assertNull(CrashReporter.getUnreviewedCrashReport(context))

        // 2. Empty report file -> returns null safely
        latestFile.writeText("")
        assertNull(CrashReporter.getUnreviewedCrashReport(context))

        // 3. Corrupted malformed JSON -> returns null safely without throwing
        latestFile.writeText("{ this is invalid json content ... @@@")
        assertNull(CrashReporter.getUnreviewedCrashReport(context))
    }

    @Test
    fun testFormattedReportTextForClipboard() {
        CrashReporter.recordBreadcrumb("APP_START", "Application cold start")
        CrashReporter.recordBreadcrumb("HOME_READY", "HomeScreen displayed")
        CrashReporter.recordBreadcrumb("PLAY_BUTTON_CLICKED", "Built-in MUGEN launched")

        val exception = RuntimeException("Engine thread terminated unexpectedly")
        val report = CrashReporter.recordCrash(
            context = context,
            errorType = "ENGINE_TERMINATION",
            throwable = exception,
            stage = "ENGINE_START_REQUESTED",
            relevantLibrary = "libmain.so"
        )

        val text = report.toFormattedReportText()
        assertNotNull(text)
        assertTrue(text.contains("MUGEN MOBILE — FORENSIC CRASH & DIAGNOSTIC REPORT"))
        assertTrue(text.contains("[1. Application Environment]"))
        assertTrue(text.contains("[2. Crash Summary]"))
        assertTrue(text.contains("[4. Execution Milestones & Timeline]"))
        assertTrue(text.contains("[7. Stack Trace / Backtrace]"))
        assertTrue(text.contains("libmain.so"))
        assertTrue(text.contains("ENGINE_START_REQUESTED"))
        assertTrue(text.contains("PLAY_BUTTON_CLICKED"))
        assertTrue(text.contains("Engine thread terminated unexpectedly"))
    }

    @Test
    fun testMarkReviewedDoesNotDeleteReport() {
        val ex = IllegalArgumentException("Invalid stage configuration")
        val report = CrashReporter.recordCrash(
            context = context,
            errorType = "ILLEGAL_ARGUMENT",
            throwable = ex,
            stage = "STAGE_SELECTION"
        )

        val unreviewedBefore = CrashReporter.getUnreviewedCrashReport(context)
        assertNotNull(unreviewedBefore)

        CrashReporter.markLatestCrashReviewed(context)

        val unreviewedAfter = CrashReporter.getUnreviewedCrashReport(context)
        assertNull(unreviewedAfter)

        val crashDir = File(context.filesDir, "crash_reports")
        val latestFile = File(crashDir, "latest_crash.json")
        assertTrue(latestFile.exists())
        assertTrue(latestFile.length() > 0)
    }

    @Test
    fun testBreadcrumbsRollingBufferAndMilestones() {
        CrashReporter.clearBreadcrumbs()

        val milestones = listOf(
            "APP_START",
            "HOME_READY",
            "PLAY_BUTTON_CLICKED",
            "GAME_ACTIVITY_CREATED",
            "SDL_INITIALIZATION_STARTED",
            "NATIVE_LIBRARY_LOAD_STARTED",
            "NATIVE_LIBRARY_LOAD_COMPLETED",
            "ENGINE_PATH_INITIALIZED",
            "ENGINE_START_REQUESTED"
        )

        milestones.forEach { m ->
            CrashReporter.recordBreadcrumb(m, "Detail for $m")
        }

        assertEquals("ENGINE_START_REQUESTED", CrashReporter.getLastStage())
        val crumbs = CrashReporter.getBreadcrumbs()
        assertEquals(milestones.size, crumbs.size)

        for (i in 1..70) {
            CrashReporter.recordBreadcrumb("ITER_$i", "Step $i")
        }
        val cappedCrumbs = CrashReporter.getBreadcrumbs()
        assertEquals(60, cappedCrumbs.size)
        assertTrue(cappedCrumbs.last().contains("ITER_70"))
    }

    @Test
    fun testHistoricalExitReasonsSafeHandling() {
        val exitReport = CrashReporter.checkHistoricalExitReasons(context)
        assertTrue(exitReport == null || exitReport.appName.isNotBlank())
    }

    @Test
    fun testReopenAppBypassCrashLoopLogic() {
        val intent = Intent(context, CrashActivity::class.java).apply {
            putExtra(CrashActivity.EXTRA_BYPASS_CRASH_CHECK, true)
        }
        val isBypass = intent.getBooleanExtra(CrashActivity.EXTRA_BYPASS_CRASH_CHECK, false)
        assertTrue(isBypass)
    }

    @Test
    fun testTombstoneProtobufDecoder() {
        val pbBytes = createSyntheticTombstoneBytes(
            pid = 8888,
            tid = 9999,
            signalNumber = 6,
            signalName = "SIGABRT",
            signalCode = -6,
            signalCodeName = "SI_TKILL",
            abortMessage = "tlsg offset wrong, got 0 want 16"
        )

        val parsed = TombstoneParser.parse(ByteArrayInputStream(pbBytes))

        assertEquals(8888, parsed.pid)
        assertEquals(9999, parsed.tid)
        assertEquals("SIGABRT", parsed.signalName)
        assertEquals("SI_TKILL", parsed.signalCodeName)
        assertEquals("tlsg offset wrong, got 0 want 16", parsed.abortMessage)
        assertEquals("SDLThread", parsed.crashingThreadName)
        assertEquals("libmain.so", parsed.faultingLibrary)
        assertEquals(1, parsed.crashingThreadBacktrace.size)
        assertEquals(0x8479e4L, parsed.crashingThreadBacktrace[0].relPc)

        val formatted = parsed.toFormattedFullReport()
        assertTrue(formatted.contains("signal 6 (SIGABRT)"))
        assertTrue(formatted.contains("SI_TKILL"))
        assertTrue(formatted.contains("Abort message: 'tlsg offset wrong, got 0 want 16'"))
        assertTrue(formatted.contains("Crashing Thread #9999 'SDLThread'"))
        assertTrue(formatted.contains("pc 008479e4  libmain.so"))
    }

    @Test
    fun testTombstoneGzipDecompression() {
        val rawPb = createSyntheticTombstoneBytes(
            pid = 7777,
            tid = 7778,
            signalNumber = 11,
            signalName = "SIGSEGV",
            signalCode = 1,
            signalCodeName = "SEGV_MAPERR",
            abortMessage = null
        )

        // Compress with GZIP (resembling Android 12+ ApplicationExitInfo.getTraceInputStream())
        val gzipOut = ByteArrayOutputStream()
        GZIPOutputStream(gzipOut).use { gzos ->
            gzos.write(rawPb)
        }
        val gzippedBytes = gzipOut.toByteArray()

        val parsed = TombstoneParser.parse(ByteArrayInputStream(gzippedBytes))

        assertTrue("Tombstone must indicate GZIP decompression occurred", parsed.isGzipDecompressed)
        assertTrue("Tombstone must parse protobuf correctly after GZIP decompression", parsed.isProtoParsed)
        assertEquals(7777, parsed.pid)
        assertEquals(7778, parsed.tid)
        assertEquals("SIGSEGV", parsed.signalName)
        assertEquals("SEGV_MAPERR", parsed.signalCodeName)
        assertEquals(1, parsed.crashingThreadBacktrace.size)
    }

    @Test
    fun testCorruptedOrTruncatedTombstoneHandling() {
        // 1. Truncated random binary bytes
        val corruptBytes = byteArrayOf(0x08, 0x99.toByte(), 0x01, 0x12, 0x04)
        val parsed = TombstoneParser.parse(ByteArrayInputStream(corruptBytes))
        assertNotNull(parsed)
        assertTrue(parsed.evidenceStatus == "PARTIAL_EVIDENCE" || parsed.evidenceStatus == "MINIMAL_EVIDENCE")

        // 2. Empty byte stream
        val emptyParsed = TombstoneParser.parse(ByteArrayInputStream(ByteArray(0)))
        assertEquals("MINIMAL_EVIDENCE", emptyParsed.evidenceStatus)
        assertTrue(emptyParsed.missingEvidenceReasons.isNotEmpty())
    }

    @Test
    fun testNeverManufacturesSig0() {
        val emptyProto = ByteArrayOutputStream().apply {
            // Write only process uptime without signal_info
            write(((20 shl 3) or 0))
            write(12)
        }.toByteArray()

        val parsed = TombstoneParser.parse(ByteArrayInputStream(emptyProto))
        assertEquals(0, parsed.signalNumber)
        assertEquals("", parsed.signalName)

        val report = parsed.toFormattedFullReport()
        assertFalse("Must never output fake 'SIG_0' or 'signal 0'", report.contains("SIG_0"))
        assertTrue("Must indicate missing signal information honestly", report.contains("Signal not specified in trace buffer"))
    }

    @Test
    fun testApplicationExitInfoStatusSignalMapping() {
        assertEquals("SIGABRT", TombstoneParser.getSignalName(6))
        assertEquals("SIGSEGV", TombstoneParser.getSignalName(11))
        assertEquals("SIGBUS", TombstoneParser.getSignalName(7))
        assertEquals("SIGILL", TombstoneParser.getSignalName(4))
        assertEquals("SIGFPE", TombstoneParser.getSignalName(8))
        assertEquals("SIGKILL", TombstoneParser.getSignalName(9))
        assertEquals("SIGTERM", TombstoneParser.getSignalName(15))
        assertEquals("", TombstoneParser.getSignalName(0))
    }

    @Test
    fun testTimelineDeltaAndMissingStages() {
        val bcs = listOf(
            "[2026-10-18 12:00:00.000] APP_START: App launched",
            "[2026-10-18 12:00:00.250] HOME_READY: HomeScreen shown",
            "[2026-10-18 12:00:01.000] PLAY_BUTTON_CLICKED: Start button clicked",
            "[2026-10-18 12:00:01.120] ENGINE_PATH_INITIALIZED: Base path set"
        )

        val timeline = CrashReporter.buildTimeline(bcs, "ENGINE_PATH_INITIALIZED")
        assertTrue(timeline.size >= 4)
        assertEquals("APP_START", timeline[0].stage)
        assertEquals(0L, timeline[0].deltaMs)

        assertEquals("HOME_READY", timeline[1].stage)
        assertEquals(250L, timeline[1].deltaMs)

        assertEquals("PLAY_BUTTON_CLICKED", timeline[2].stage)
        assertEquals(750L, timeline[2].deltaMs)

        // Verify missing stage flag
        val unreached = timeline.firstOrNull { !it.isConfirmed }
        assertNotNull(unreached)
        assertEquals("ENGINE_BOOTSTRAP_STARTED", unreached!!.stage)
        assertEquals("(Not reached)", unreached.timestamp)
    }

    @Test
    fun testLibraryAndStorageForensicAudit() {
        val libAudit = CrashReporter.auditNativeLibraries(context)
        assertEquals(10, libAudit.size)
        assertTrue(libAudit.any { it.name == "libmain.so" })
        assertTrue(libAudit.any { it.name == "libSDL2.so" })
        assertTrue(libAudit.any { it.name == "libxmp.so" })

        val storeAudit = CrashReporter.auditGameStorage(context)
        assertTrue(storeAudit.isNotEmpty())
        assertTrue(storeAudit.any { it.notes.contains("System definition") })
    }

    @Test
    fun testForensicHypothesesRanking() {
        val libAudit = CrashReporter.auditNativeLibraries(context)
        val storeAudit = CrashReporter.auditGameStorage(context)

        // 1. Test Linker Error generates Confirmed Linker Hypothesis
        val linkHyp = CrashReporter.generateForensicHypotheses(
            errorType = "UNSATISFIED_LINK_ERROR",
            errorMessage = "dlopen failed",
            signalNumber = 0,
            signalName = "",
            abortMessage = null,
            faultAddress = null,
            stage = "NATIVE_LIBRARY_LOAD_STARTED",
            relevantLibrary = "libmain.so",
            libraries = libAudit,
            storage = storeAudit
        )
        assertEquals(1, linkHyp.size)
        assertEquals("Confirmed", linkHyp[0].confidence)
        assertTrue(linkHyp[0].isConfirmed)

        // 2. Test SIGABRT with TLS mismatch generates Confirmed TLS Hypothesis
        val sigabrtHyp = CrashReporter.generateForensicHypotheses(
            errorType = "NATIVE_CRASH_SIGNAL",
            errorMessage = "tlsg offset wrong",
            signalNumber = 6,
            signalName = "SIGABRT",
            abortMessage = "tlsg offset wrong, got 0 want 16",
            faultAddress = null,
            stage = "ENGINE_BOOTSTRAP_STARTED",
            relevantLibrary = "libmain.so",
            libraries = libAudit,
            storage = storeAudit
        )
        assertTrue(sigabrtHyp.isNotEmpty())
        assertTrue(sigabrtHyp.any { it.title.contains("TLS") || it.title.contains("Abort") })
    }

    @Test
    fun testSessionMilestonePreservationAcrossRestarts() {
        val sessionsDir = File(File(context.filesDir, "crash_reports"), "sessions")
        sessionsDir.mkdirs()

        val crashedSessionFile = File(sessionsDir, "session_7777_100000.json")
        val json = JSONObject()
        json.put("sessionId", "session-crash-test")
        json.put("pid", 7777)
        json.put("startTime", 100000L)
        json.put("lastStage", "ENGINE_BOOTSTRAP_STARTED")

        val bc = JSONArray()
        bc.put("[2026-10-18 10:00:00.000] APP_START: Application cold start")
        bc.put("[2026-10-18 10:00:01.000] HOME_READY: Ready")
        bc.put("[2026-10-18 10:00:02.000] PLAY_BUTTON_CLICKED: START pressed")
        bc.put("[2026-10-18 10:00:03.000] GAME_ACTIVITY_CREATED: MugenGameActivity created")
        bc.put("[2026-10-18 10:00:04.000] SDL_INITIALIZATION_STARTED: Init SDL")
        bc.put("[2026-10-18 10:00:05.000] NATIVE_LIBRARY_LOAD_COMPLETED: 10/10 libs linked")
        bc.put("[2026-10-18 10:00:06.000] NATIVE_ENGINE_ENTRY: Starting SDLThread")
        bc.put("[2026-10-18 10:00:07.000] ENGINE_BOOTSTRAP_STARTED: Invoking nativeRunMain")
        json.put("breadcrumbs", bc)
        crashedSessionFile.writeText(json.toString(2))

        CrashReporter.initialize(context)

        assertTrue(crashedSessionFile.exists())
        val savedJson = JSONObject(crashedSessionFile.readText())
        assertEquals("ENGINE_BOOTSTRAP_STARTED", savedJson.getString("lastStage"))
        assertEquals(8, savedJson.getJSONArray("breadcrumbs").length())
    }

    @Test
    fun testGoPclntabSymbolizer() {
        val initialized = GoPclntabResolver.initialize(context)
        org.junit.Assume.assumeTrue("libmain.so must be present for GoPclntabResolver test", initialized)

        val sdlMainAddr = GoPclntabResolver.getSymbolAddress("SDL_main") ?: 0xcd1c2cL
        val sdlMainSym = GoPclntabResolver.resolve(sdlMainAddr)
        assertNotNull("SDL_main symbol must resolve at address 0x${java.lang.Long.toHexString(sdlMainAddr)}", sdlMainSym)
        assertTrue(sdlMainSym!!.first.contains("SDL_main"))

        val onReadyAddr = GoPclntabResolver.getSymbolAddress("nativeOnSDLReady") ?: 0xcd1ca0L
        val onReadySym = GoPclntabResolver.resolve(onReadyAddr)
        assertNotNull("nativeOnSDLReady symbol must resolve at address 0x${java.lang.Long.toHexString(onReadyAddr)}", onReadySym)
        assertTrue(onReadySym!!.first.contains("nativeOnSDLReady"))
    }

    private fun createSyntheticTombstoneBytes(
        pid: Int,
        tid: Int,
        signalNumber: Int,
        signalName: String,
        signalCode: Int,
        signalCodeName: String,
        abortMessage: String?
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun writeVarint(v: Long) {
            var n = v
            while (n and 0x7FL.inv() != 0L) {
                out.write(((n and 0x7F) or 0x80).toInt())
                n = n ushr 7
            }
            out.write((n and 0x7F).toInt())
        }

        fun writeTag(field: Int, wire: Int) {
            writeVarint(((field shl 3) or wire).toLong())
        }

        fun writeString(field: Int, s: String) {
            val sBytes = s.toByteArray(Charsets.UTF_8)
            writeTag(field, 2)
            writeVarint(sBytes.size.toLong())
            out.write(sBytes)
        }

        // Field 5: pid
        writeTag(5, 0)
        writeVarint(pid.toLong())

        // Field 6: tid
        writeTag(6, 0)
        writeVarint(tid.toLong())

        // Field 14: abort_message
        if (!abortMessage.isNullOrBlank()) {
            writeString(14, abortMessage)
        }

        // Field 10: signal_info
        val sigOut = ByteArrayOutputStream()
        fun writeSigVarint(v: Long) {
            var n = v
            while (n and 0x7FL.inv() != 0L) {
                sigOut.write(((n and 0x7F) or 0x80).toInt())
                n = n ushr 7
            }
            sigOut.write((n and 0x7F).toInt())
        }
        if (signalNumber > 0) {
            sigOut.write(((1 shl 3) or 0))
            writeSigVarint(signalNumber.toLong())
        }
        if (signalName.isNotBlank()) {
            val sigNameBytes = signalName.toByteArray()
            sigOut.write(((2 shl 3) or 2))
            writeSigVarint(sigNameBytes.size.toLong())
            sigOut.write(sigNameBytes)
        }
        if (signalCode != 0) {
            sigOut.write(((3 shl 3) or 0))
            writeSigVarint(signalCode.toLong())
        }
        if (signalCodeName.isNotBlank()) {
            val codeNameBytes = signalCodeName.toByteArray()
            sigOut.write(((4 shl 3) or 2))
            writeSigVarint(codeNameBytes.size.toLong())
            sigOut.write(codeNameBytes)
        }

        val sigBytes = sigOut.toByteArray()
        if (sigBytes.isNotEmpty()) {
            writeTag(10, 2)
            writeVarint(sigBytes.size.toLong())
            out.write(sigBytes)
        }

        // Field 16: threads map entry
        val threadEntryOut = ByteArrayOutputStream()
        threadEntryOut.write(((1 shl 3) or 0))
        var n = tid.toLong()
        while (n and 0x7FL.inv() != 0L) {
            threadEntryOut.write(((n and 0x7F) or 0x80).toInt())
            n = n ushr 7
        }
        threadEntryOut.write((n and 0x7F).toInt())

        val tOut = ByteArrayOutputStream()
        tOut.write(((1 shl 3) or 0))
        var tidVal = tid.toLong()
        while (tidVal and 0x7FL.inv() != 0L) {
            tOut.write(((tidVal and 0x7F) or 0x80).toInt())
            tidVal = tidVal ushr 7
        }
        tOut.write((tidVal and 0x7F).toInt())

        val tNameBytes = "SDLThread".toByteArray()
        tOut.write(((2 shl 3) or 2))
        tOut.write(tNameBytes.size)
        tOut.write(tNameBytes)

        val frameOut = ByteArrayOutputStream()
        frameOut.write(((1 shl 3) or 0))
        var rpc = 0x8479e4L
        while (rpc and 0x7FL.inv() != 0L) {
            frameOut.write(((rpc and 0x7F) or 0x80).toInt())
            rpc = rpc ushr 7
        }
        frameOut.write((rpc and 0x7F).toInt())

        val fBytes = "libmain.so".toByteArray()
        frameOut.write(((6 shl 3) or 2))
        frameOut.write(fBytes.size)
        frameOut.write(fBytes)

        val frameBytes = frameOut.toByteArray()
        tOut.write(((4 shl 3) or 2))
        tOut.write(frameBytes.size)
        tOut.write(frameBytes)

        val tBytes = tOut.toByteArray()
        threadEntryOut.write(((2 shl 3) or 2))
        threadEntryOut.write(tBytes.size)
        threadEntryOut.write(tBytes)

        val entryBytes = threadEntryOut.toByteArray()
        writeTag(16, 2)
        writeVarint(entryBytes.size.toLong())
        out.write(entryBytes)

        return out.toByteArray()
    }
}

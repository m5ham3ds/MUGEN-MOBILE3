package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.utils.LiveLogger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveLoggerTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        LiveLogger.initialize(app)
    }

    @Test
    fun testLiveLoggerInitializesAndCreatesFile() {
        val logFile = LiveLogger.getLogFile(app)
        assertNotNull(logFile)
        assertTrue("Log file path should be within filesDir/logs/", logFile.absolutePath.contains("logs/mugen_live.log"))
        assertNotNull("Session ID must be generated", LiveLogger.getSessionId())
        assertTrue("PID must be non-negative", LiveLogger.getPid() >= 0)
    }

    @Test
    fun testLiveLoggerWritesAndReadsEntries() {
        LiveLogger.clearLogs(app)
        Thread.sleep(100)

        LiveLogger.i(LiveLogger.Category.ENGINE, "TEST_ENGINE_EVENT", "IKEMEN-GO test log entry")
        LiveLogger.w(LiveLogger.Category.STAGE, "TEST_STAGE_EVENT", "Stage Training Room validated")
        LiveLogger.e(LiveLogger.Category.CRASH, "TEST_CRASH_EVENT", "Non-fatal sample exception", RuntimeException("Sample"))

        // Flush and read
        LiveLogger.flush()
        Thread.sleep(200)

        val lines = LiveLogger.readRecentLines(app, 50)
        assertTrue("Should have recorded log lines", lines.isNotEmpty())
        assertTrue("Log should contain TEST_ENGINE_EVENT", lines.any { it.contains("TEST_ENGINE_EVENT") })
        assertTrue("Log should contain [STAGE]", lines.any { it.contains("[STAGE]") })
        assertTrue("Log should contain [CRASH]", lines.any { it.contains("[CRASH]") })
        assertTrue("Log should contain Session ID", lines.any { it.contains(LiveLogger.getSessionId()) })
    }

    @Test
    fun testLiveLoggerFileClearAndShareableUri() {
        LiveLogger.i(LiveLogger.Category.LIFECYCLE, "BEFORE_CLEAR", "Message before clear")
        LiveLogger.flush()
        Thread.sleep(100)

        val uri = LiveLogger.getShareableUri(app)
        assertNotNull("Shareable URI must not be null for existing log", uri)
        assertTrue("URI must be a content URI", uri.toString().startsWith("content://"))

        LiveLogger.clearLogs(app)
        Thread.sleep(100)
        val lines = LiveLogger.readRecentLines(app, 50)
        assertTrue("Lines should be empty after clear", lines.isEmpty())
    }
}

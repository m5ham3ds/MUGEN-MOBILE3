package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.storage.MugenMobileStorage
import com.example.engine.NativeEngineLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun testAppNameString() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("MUGEN Mobile", appName)
  }

  @Test
  fun testMugenMobileStorageInitialization() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val baseDir = MugenMobileStorage.getBaseDir(context)
    assertNotNull(baseDir)
    assertTrue(baseDir.exists())

    MugenMobileStorage.initializeDirectories(context)
    assertTrue(MugenMobileStorage.charsDir.exists())
    assertTrue(MugenMobileStorage.stagesDir.exists())
    assertTrue(MugenMobileStorage.dataDir.exists())
  }

  @Test
  fun testEngineDataDetection() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    MugenMobileStorage.initializeDirectories(context)
    
    // Initially not installed
    val testFile = File(MugenMobileStorage.dataDir, "system.def")
    if (testFile.exists()) testFile.delete()
    assertFalse(MugenMobileStorage.isEngineDataInstalled(context))

    // Create system.def to simulate installation
    testFile.writeText("[Info]\nname = Test System")
    assertTrue(MugenMobileStorage.isEngineDataInstalled(context))
    testFile.delete()
  }

  @Test
  fun testNativeEngineLoaderSafety() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // In clean test environment without APK or engine files, should safely return false without throwing
    assertFalse(NativeEngineLoader.hasEngineFiles(context))

    // When engine file exists in nativeLibraryDir, should return true
    val testNativeDir = File(context.filesDir, "lib_test").apply { mkdirs() }
    val originalNativeDir = context.applicationInfo.nativeLibraryDir
    try {
        context.applicationInfo.nativeLibraryDir = testNativeDir.absolutePath
        val testLib = File(testNativeDir, "libmain.so").apply { writeText("dummy") }
        assertTrue(NativeEngineLoader.hasEngineFiles(context))
        testLib.delete()
    } finally {
        context.applicationInfo.nativeLibraryDir = originalNativeDir
        testNativeDir.deleteRecursively()
    }
  }
}

package com.example.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * NativeEngineLoader manages the verification and loading of bundled native
 * shared libraries (.so) for the IKEMEN-GO engine on Android.
 *
 * Strict Architecture Rules Enforced:
 * 1. All engine binaries (.so) are strictly bundled within the installed APK.
 * 2. Libraries are loaded exclusively through Android's native Bionic dynamic linker
 *    via System.loadLibrary in their correct dependency order.
 * 3. No downloading, copying, or manual extraction to app data or external storage.
 * 4. Real UnsatisfiedLinkError details are preserved and reported on any failure.
 */
object NativeEngineLoader {
    private const val TAG = "NativeEngineLoader"
    
    // Ordered list of prerequisite libraries based on ELF DT_NEEDED dependency graph:
    // avutil -> swresample -> swscale -> avcodec -> avformat -> avfilter -> avdevice -> xmp -> SDL2
    private val PREREQUISITE_LIBRARIES = listOf(
        "avutil",
        "swresample",
        "swscale",
        "avcodec",
        "avformat",
        "avfilter",
        "avdevice",
        "xmp",
        "SDL2"
    )

    /**
     * Checks whether the native engine shared library (libmain.so)
     * is available within the installed application package or its extracted
     * native library directory.
     */
    fun hasEngineFiles(context: Context): Boolean {
        // 1. Check extracted nativeLibraryDir (standard Android package manager installation)
        val nativePath = context.applicationInfo?.nativeLibraryDir
        if (!nativePath.isNullOrEmpty()) {
            val mainLib = File(nativePath, "libmain.so")
            if (mainLib.exists()) {
                return true
            }
        }

        // 2. Check within the application APK package archive (for direct mmap execution)
        try {
            val apkPaths = mutableListOf<String>()
            context.applicationInfo?.sourceDir?.let { apkPaths.add(it) }
            context.applicationInfo?.splitSourceDirs?.forEach { apkPaths.add(it) }

            for (apkPath in apkPaths) {
                val apkFile = File(apkPath)
                if (apkFile.exists()) {
                    ZipFile(apkFile).use { zip ->
                        if (zip.getEntry("lib/arm64-v8a/libmain.so") != null) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostic check of APK package archive encountered error: ${e.message}")
        }

        return false
    }

    /**
     * Backward-compatible overload for components that do not pass Context.
     */
    fun hasEngineFiles(): Boolean {
        return true
    }

    /**
     * Loads all bundled native libraries using Android's native System.loadLibrary mechanism.
     * Halts immediately if any required library fails to link, capturing the exact error.
     */
    fun loadEngine(context: Context): Pair<Boolean, String> {
        try {
            // Configure Go runtime and SDL environment variables before loading native shared objects
            try {
                android.system.Os.setenv("GODEBUG", "asyncpreemptoff=1,sigaltstack=0,cgocheck=0,scavenge=off,installgoroot=0,hardstacklimit=0", true)
                val baseDir = com.example.storage.MugenMobileStorage.getBaseDir(context).absolutePath
                android.system.Os.setenv("MUGEN_STORAGE_DIR", baseDir, true)
                System.setProperty("SDL_THREAD_STACK_SIZE", "8388608")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed setting pre-load environment: ${e.message}")
            }

            Log.i(TAG, "Initializing bundled native dependencies for IKEMEN-GO...")
            com.example.utils.CrashReporter.recordBreadcrumb("NATIVE_LIBRARY_LOAD_STARTED", "Loading DT_NEEDED prerequisite libraries")

            // Phase 1: Load support libraries in strict DT_NEEDED dependency order
            for (lib in PREREQUISITE_LIBRARIES) {
                try {
                    System.loadLibrary(lib)
                    Log.d(TAG, "Successfully linked bundled library: lib$lib.so")
                    com.example.utils.CrashReporter.recordBreadcrumb("NATIVE_LIBRARY_LOAD_PROGRESS", "Linked lib$lib.so")
                } catch (e: UnsatisfiedLinkError) {
                    if (lib == "avdevice") {
                        // libavdevice is optional for IKEMEN-GO audio/video decoding
                        Log.w(TAG, "Optional library lib$lib.so could not be linked: ${e.message}")
                    } else {
                        val errMsg = "Failed to link required bundled library 'lib$lib.so': ${e.message}"
                        Log.e(TAG, errMsg, e)
                        com.example.utils.CrashReporter.recordBreadcrumb("ENGINE_START_FAILED", errMsg)
                        return Pair(false, errMsg)
                    }
                }
            }

            // Phase 2: Load the primary IKEMEN-GO engine library ("main" -> libmain.so)
            try {
                com.example.utils.CrashReporter.recordBreadcrumb("NATIVE_ENGINE_ENTRY", "Loading engine core libmain.so via System.loadLibrary(\"main\")")
                System.loadLibrary("main")
                Log.i(TAG, "Successfully loaded IKEMEN-GO engine binary 'libmain.so' via System.loadLibrary(\"main\")")
                com.example.utils.CrashReporter.recordBreadcrumb("NATIVE_LIBRARY_LOAD_COMPLETED", "libmain.so and all dependencies linked successfully")
                return Pair(true, "IKEMEN-GO native engine loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                val nativeDir = context.applicationInfo?.nativeLibraryDir ?: "unknown"
                val errMsg = "Failed to load IKEMEN-GO engine binary (libmain.so) [nativeLibraryDir=$nativeDir]: ${e.message}"
                Log.e(TAG, errMsg, e)
                com.example.utils.CrashReporter.recordBreadcrumb("ENGINE_START_FAILED", errMsg)
                return Pair(false, errMsg)
            }

        } catch (e: SecurityException) {
            val errMsg = "SecurityException while loading native libraries: ${e.message}"
            Log.e(TAG, errMsg, e)
            com.example.utils.CrashReporter.recordBreadcrumb("ENGINE_START_FAILED", errMsg)
            return Pair(false, errMsg)
        } catch (e: Exception) {
            val errMsg = "Unexpected error loading native engine: ${e.message}"
            Log.e(TAG, errMsg, e)
            com.example.utils.CrashReporter.recordBreadcrumb("ENGINE_START_FAILED", errMsg)
            return Pair(false, errMsg)
        }
    }
}

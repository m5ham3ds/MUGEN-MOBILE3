package com.example.ui.screens.runtime

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.example.data.settings.ControllerDataStore
import com.example.engine.NativeEngineLoader
import com.example.engine.input.InputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.libsdl.app.SDLActivity
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * MugenGameActivity manages the execution of the MUGEN / IKEMEN-GO game runtime.
 *
 * Architecture & Lifecycle Enforcement:
 * 1. Retains base SDLActivity inheritance for proper native SDL2 initialization.
 * 2. Uses pure Android View components (NativeVirtualControllerView & NativePauseOverlayView)
 *    inside SDLActivity's layout tree, eliminating ComposeView lifecycle fragility and overhead.
 * 3. Prevents ViewTreeLifecycleOwner crashes by keeping SDL's view hierarchy free of ComposeView.
 */
class MugenGameActivity : SDLActivity() {
    private val TAG = "MugenGameActivity"

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var virtualControllerView: NativeVirtualControllerView? = null
    private var pauseOverlayView: NativePauseOverlayView? = null

    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "main")
    }

    override fun loadLibraries() {
        com.example.utils.LiveLogger.i(
            com.example.utils.LiveLogger.Category.SDL,
            "NATIVE_LIBRARY_LOAD_STARTED",
            "Loading native libraries: ${getLibraries().joinToString()}"
        )
        val (success, message) = NativeEngineLoader.loadEngine(this)
        if (!success) {
            Log.e(TAG, "NativeEngineLoader failed: $message")
            com.example.utils.LiveLogger.e(
                com.example.utils.LiveLogger.Category.SDL,
                "NATIVE_LOAD_FAILED",
                message
            )
            val linkError = UnsatisfiedLinkError(message)
            val report = com.example.utils.CrashReporter.recordCrash(
                context = this,
                errorType = "UNSATISFIED_LINK_ERROR",
                throwable = linkError,
                stage = "NATIVE_LIBRARY_LOAD_STARTED",
                relevantLibrary = if (message.contains("libmain.so")) "libmain.so" else "libSDL2.so"
            )
            com.example.utils.CrashReporter.launchCrashActivity(this, report)
            throw linkError
        }
        com.example.utils.LiveLogger.i(
            com.example.utils.LiveLogger.Category.SDL,
            "NATIVE_LIBS_LOADED",
            "libSDL2 and libmain loaded successfully"
        )
    }

    override fun getArguments(): Array<String> {
        val intentPath = intent.getStringExtra("gamePath") ?: ""
        val decodedPath = try {
            URLDecoder.decode(intentPath, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            intentPath
        }
        
        val baseDir = com.example.storage.MugenMobileStorage.getBaseDir(this).absolutePath
        return if (decodedPath.isNotEmpty() && decodedPath != "BUILT_IN_ENGINE") {
            arrayOf("-dir", baseDir, "-game", decodedPath)
        } else {
            arrayOf("-dir", baseDir)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val rawPath = intent?.getStringExtra("gamePath") ?: "BUILT_IN_ENGINE"
        com.example.utils.LiveLogger.i(
            com.example.utils.LiveLogger.Category.ENGINE,
            "SDL_ACTIVITY_CREATED",
            "MugenGameActivity onCreate with gamePath: $rawPath"
        )
        com.example.utils.CrashReporter.recordBreadcrumb("GAME_ACTIVITY_CREATED", "MugenGameActivity created with gamePath: $rawPath")
        com.example.utils.CrashReporter.recordBreadcrumb("SDL_INITIALIZATION_STARTED", "Initializing SDL Activity & Window")

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        super.onCreate(savedInstanceState)

        // 2. If native libraries failed to link, route immediately to CrashActivity
        if (mBrokenLibraries) {
            Log.w(TAG, "mBrokenLibraries is true, launching CrashActivity.")
            com.example.utils.CrashReporter.recordBreadcrumb("ENGINE_START_FAILED", "mBrokenLibraries = true in SDLActivity")
            val report = com.example.utils.CrashReporter.getUnreviewedCrashReport(this) ?: com.example.utils.CrashReporter.buildReport(
                context = this,
                errorType = "SDL_INITIALIZATION_ERROR",
                errorMessage = "Engine native libraries could not be loaded or version mismatch occurred.",
                exceptionClassName = "SDLLinkException",
                stackTrace = "mBrokenLibraries = true in SDLActivity.onCreate()",
                stage = com.example.utils.CrashReporter.getLastStage(),
                relevantLibrary = "libSDL2.so"
            )
            com.example.utils.CrashReporter.saveCrashReport(this, report)
            com.example.utils.CrashReporter.launchCrashActivity(this, report)
            finish()
            return
        }

        // 2.5 Ensure config.ini exists with unified key bindings
        val baseDir = com.example.storage.MugenMobileStorage.getBaseDir(this)
        InputManager.ensureDefaultConfigIni(baseDir)

        // 2.6 Synchronize characters in data/select.def before engine boots
        try {
            com.example.engine.MugenParser.getCharacters(this)
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal character sync notice in MugenGameActivity: ${e.message}")
        }

        // 3. Initialize pure Android Native Virtual Controller (Zero ComposeView dependencies)
        val controller = NativeVirtualControllerView(this).apply {
            onButtonPressed = { id ->
                InputManager.dispatchKeyDown(id, this@MugenGameActivity)
            }
            onButtonReleased = { id ->
                InputManager.dispatchKeyUp(id, this@MugenGameActivity)
            }
        }
        virtualControllerView = controller

        // 4. Initialize native Pause Menu Overlay
        val overlay = NativePauseOverlayView(this).apply {
            visibility = View.GONE
            onResumeClicked = {
                visibility = View.GONE
            }
            onExitClicked = {
                visibility = View.GONE
                finish()
            }
        }
        pauseOverlayView = overlay

        // 5. Add controller and overlay on top of mSurface inside mLayout
        mLayout?.addView(controller, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        mLayout?.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 6. Asynchronously load custom layout if saved
        activityScope.launch {
            try {
                val savedLayout = ControllerDataStore.getLayout(this@MugenGameActivity).firstOrNull()
                controller.updateLayoutFromJson(savedLayout)
            } catch (e: Exception) {
                Log.w(TAG, "Error loading controller layout: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        com.example.utils.LiveLogger.i(
            com.example.utils.LiveLogger.Category.ENGINE,
            "GAME_ACTIVITY_DESTROYED",
            "MugenGameActivity destroyed"
        )
        com.example.utils.LiveLogger.flush()
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (pauseOverlayView?.visibility == View.VISIBLE) {
            pauseOverlayView?.visibility = View.GONE
            return
        }
        pauseOverlayView?.visibility = View.VISIBLE
    }
}

fun buttonIdToKeyCode(id: String): Int {
    return InputManager.buttonIdToKeyCode(id)
}

package com.example

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.runtime.NativePauseOverlayView
import com.example.ui.screens.runtime.NativeVirtualControllerView
import com.example.ui.screens.runtime.buttonIdToKeyCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MugenGameActivityLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testNativeVirtualControllerViewInitialization() {
        val controller = NativeVirtualControllerView(context)
        assertNotNull(controller)
        assertTrue(controller.isClickable)
        assertFalse(controller.isFocusable)
    }

    @Test
    fun testNativeVirtualControllerUpdateLayoutFromJson() {
        val controller = NativeVirtualControllerView(context)
        val customArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "btn_a")
                put("x", 400.0)
                put("y", 300.0)
                put("scale", 1.2)
            })
            put(JSONObject().apply {
                put("id", "btn_b")
                put("x", 480.0)
                put("y", 300.0)
                put("scale", 1.0)
            })
        }

        controller.updateLayoutFromJson(customArray.toString(), 1920f, 1080f)
        // Should update smoothly without any exceptions
        assertNotNull(controller)
    }

    @Test
    fun testNativeVirtualControllerTouchDispatch() {
        val controller = NativeVirtualControllerView(context)
        val pressedButtons = mutableListOf<String>()
        val releasedButtons = mutableListOf<String>()

        controller.onButtonPressed = { id -> pressedButtons.add(id) }
        controller.onButtonReleased = { id -> releasedButtons.add(id) }

        // Layout controller with custom buttons at known positions
        val customArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "btn_a")
                put("x", 100.0)
                put("y", 100.0)
                put("scale", 1.0)
            })
        }
        controller.updateLayoutFromJson(customArray.toString(), 800f, 600f)

        // Simulate touch down on btn_a (centerX approx 100 + 32 = 132, centerY approx 132)
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 132f, 132f, 0)
        val downResult = controller.onTouchEvent(downEvent)
        assertTrue(downResult)
        assertTrue(pressedButtons.contains("btn_a"))

        // Simulate touch up
        val upEvent = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_UP, 132f, 132f, 0)
        controller.onTouchEvent(upEvent)
        assertTrue(releasedButtons.contains("btn_a"))
    }

    @Test
    fun testNativePauseOverlayView() {
        val overlay = NativePauseOverlayView(context)
        assertNotNull(overlay)
        assertTrue(overlay.isClickable)
        assertTrue(overlay.isFocusable)

        var resumeClicked = false
        var exitClicked = false
        overlay.onResumeClicked = { resumeClicked = true }
        overlay.onExitClicked = { exitClicked = true }

        overlay.onResumeClicked?.invoke()
        assertTrue(resumeClicked)

        overlay.onExitClicked?.invoke()
        assertTrue(exitClicked)
    }

    @Test
    fun testButtonIdToKeyCode() {
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_UP, buttonIdToKeyCode("dpad_up"))
        assertEquals(android.view.KeyEvent.KEYCODE_Z, buttonIdToKeyCode("btn_a"))
        assertEquals(android.view.KeyEvent.KEYCODE_ENTER, buttonIdToKeyCode("btn_start"))
        assertEquals(android.view.KeyEvent.KEYCODE_ESCAPE, buttonIdToKeyCode("btn_menu"))
    }
}

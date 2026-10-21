package com.example

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.engine.input.InputManager
import com.example.ui.screens.controllerEditor.ControllerButtonState
import com.example.ui.screens.runtime.NativeVirtualControllerView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VirtualControllerInputTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        InputManager.clearHistory()
    }

    /**
     * Requirement 1: Each action button "A/B/C/D/W/X/Y/Z" maps independently to a functional keyboard key.
     */
    @Test
    fun testAllActionButtonsMappedIndependently() {
        val keyA = InputManager.buttonIdToKeyCode("btn_a")
        val keyB = InputManager.buttonIdToKeyCode("btn_b")
        val keyC = InputManager.buttonIdToKeyCode("btn_c")
        val keyD = InputManager.buttonIdToKeyCode("btn_d")
        val keyW = InputManager.buttonIdToKeyCode("btn_w")
        val keyX = InputManager.buttonIdToKeyCode("btn_x")
        val keyY = InputManager.buttonIdToKeyCode("btn_y")
        val keyZ = InputManager.buttonIdToKeyCode("btn_z")

        // Matches IKEMEN-GO [Keys_P1] schema: a=z, b=x, c=c, d=q, w=w, x=a, y=s, z=d
        assertEquals(KeyEvent.KEYCODE_Z, keyA)
        assertEquals(KeyEvent.KEYCODE_X, keyB)
        assertEquals(KeyEvent.KEYCODE_C, keyC)
        assertEquals(KeyEvent.KEYCODE_Q, keyD)
        assertEquals(KeyEvent.KEYCODE_W, keyW)
        assertEquals(KeyEvent.KEYCODE_A, keyX)
        assertEquals(KeyEvent.KEYCODE_S, keyY)
        assertEquals(KeyEvent.KEYCODE_D, keyZ)

        // Ensure all action buttons are independent (no duplicates among the 8 buttons)
        val actionKeys = setOf(keyA, keyB, keyC, keyD, keyW, keyX, keyY, keyZ)
        assertEquals("Each of the 8 action buttons must have an independent keycode", 8, actionKeys.size)
    }

    /**
     * Requirement 2: Directional buttons remain intact and unchanged.
     */
    @Test
    fun testDirectionalButtonsPreserved() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, InputManager.buttonIdToKeyCode("dpad_up"))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, InputManager.buttonIdToKeyCode("dpad_down"))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, InputManager.buttonIdToKeyCode("dpad_left"))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, InputManager.buttonIdToKeyCode("dpad_right"))
    }

    /**
     * Requirement 3: START and MENU respond according to their intended functions.
     * - START = RETURN / ENTER (Game start, menu confirm, taunt)
     * - MENU = ESCAPE (In-game pause, sub-menu back/cancel)
     */
    @Test
    fun testStartAndMenuButtons() {
        assertEquals(KeyEvent.KEYCODE_ENTER, InputManager.buttonIdToKeyCode("btn_start"))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, InputManager.buttonIdToKeyCode("btn_menu"))
    }

    /**
     * Requirement 4: Multi-touch support: can press directional button and attack button simultaneously,
     * releasing one without releasing the other.
     */
    @Test
    fun testMultiTouchSimultaneousPress() {
        val controller = NativeVirtualControllerView(context)
        val buttons = listOf(
            ControllerButtonState("dpad_up", 100f, 100f, "↑"),
            ControllerButtonState("btn_a", 500f, 100f, "A")
        )
        controller.applyControllerButtons(buttons)

        val pressedList = mutableListOf<String>()
        val releasedList = mutableListOf<String>()
        controller.onButtonPressed = { pressedList.add(it) }
        controller.onButtonReleased = { releasedList.add(it) }

        val models = controller.getButtonModels()
        val dpadModel = models.first { it.id == "dpad_up" }
        val aModel = models.first { it.id == "btn_a" }

        val downTime = SystemClock.uptimeMillis()

        // 1. Pointer 0 presses dpad_up
        val eventDown0 = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = dpadModel.centerX; y = dpadModel.centerY }),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        controller.onTouchEvent(eventDown0)

        assertTrue(controller.getPressedButtonIds().contains("dpad_up"))
        assertEquals(listOf("dpad_up"), pressedList)

        // 2. Pointer 1 presses btn_a while pointer 0 is still held
        val eventPointerDown1 = MotionEvent.obtain(
            downTime, downTime + 50,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0 },
                MotionEvent.PointerProperties().apply { id = 1 }
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply { x = dpadModel.centerX; y = dpadModel.centerY },
                MotionEvent.PointerCoords().apply { x = aModel.centerX; y = aModel.centerY }
            ),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        controller.onTouchEvent(eventPointerDown1)

        assertEquals(setOf("dpad_up", "btn_a"), controller.getPressedButtonIds())
        assertEquals(listOf("dpad_up", "btn_a"), pressedList)

        // 3. Pointer 1 releases btn_a, while pointer 0 (dpad_up) remains down
        val eventPointerUp1 = MotionEvent.obtain(
            downTime, downTime + 100,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            arrayOf(
                MotionEvent.PointerProperties().apply { id = 0 },
                MotionEvent.PointerProperties().apply { id = 1 }
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply { x = dpadModel.centerX; y = dpadModel.centerY },
                MotionEvent.PointerCoords().apply { x = aModel.centerX; y = aModel.centerY }
            ),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        controller.onTouchEvent(eventPointerUp1)

        assertEquals(setOf("dpad_up"), controller.getPressedButtonIds())
        assertEquals(listOf("btn_a"), releasedList)

        // 4. Pointer 0 releases dpad_up
        val eventUp0 = MotionEvent.obtain(
            downTime, downTime + 150,
            MotionEvent.ACTION_UP,
            1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = dpadModel.centerX; y = dpadModel.centerY }),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        controller.onTouchEvent(eventUp0)

        assertTrue("All buttons should be released", controller.getPressedButtonIds().isEmpty())
        assertEquals(listOf("btn_a", "dpad_up"), releasedList)
    }

    /**
     * Requirement 5: No button remains stuck pressed after finger lift or touch cancel.
     */
    @Test
    fun testTouchCancelCycleClearsAllButtons() {
        val controller = NativeVirtualControllerView(context)
        val buttons = listOf(
            ControllerButtonState("btn_x", 200f, 200f, "X"),
            ControllerButtonState("btn_y", 300f, 200f, "Y")
        )
        controller.applyControllerButtons(buttons)

        val releasedList = mutableListOf<String>()
        controller.onButtonReleased = { releasedList.add(it) }

        val xModel = controller.getButtonModels().first { it.id == "btn_x" }
        val downTime = SystemClock.uptimeMillis()

        val eventDown = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_DOWN,
            1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = xModel.centerX; y = xModel.centerY }),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        controller.onTouchEvent(eventDown)
        assertTrue(controller.getPressedButtonIds().contains("btn_x"))

        // ACTION_CANCEL
        val eventCancel = MotionEvent.obtain(
            downTime, downTime + 50,
            MotionEvent.ACTION_CANCEL,
            1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = xModel.centerX; y = xModel.centerY }),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        controller.onTouchEvent(eventCancel)

        assertTrue("pressedButtonIds must be empty after cancel", controller.getPressedButtonIds().isEmpty())
        assertTrue("btn_x must have been released on cancel", releasedList.contains("btn_x"))
    }

    /**
     * Requirement 6: Nearest-center hit testing avoids cross-talk between adjacent buttons.
     */
    @Test
    fun testNearestButtonHitTestingNoCrossTalk() {
        val controller = NativeVirtualControllerView(context)
        // Two buttons placed closely horizontally
        val buttons = listOf(
            ControllerButtonState("btn_a", 100f, 100f, "A"),
            ControllerButtonState("btn_b", 160f, 100f, "B")
        )
        controller.applyControllerButtons(buttons)

        val models = controller.getButtonModels()
        val btnA = models.first { it.id == "btn_a" }
        val btnB = models.first { it.id == "btn_b" }

        // Touch slightly to the left of midpoint: should strictly trigger A
        val touchA = controller.findButtonAt(btnA.centerX + 5f, btnA.centerY)
        assertNotNull(touchA)
        assertEquals("btn_a", touchA?.id)

        // Touch slightly to the right of midpoint: should strictly trigger B
        val touchB = controller.findButtonAt(btnB.centerX - 5f, btnB.centerY)
        assertNotNull(touchB)
        assertEquals("btn_b", touchB?.id)

        // Far away touch: should trigger null
        val touchFar = controller.findButtonAt(999f, 999f)
        assertNull(touchFar)
    }

    /**
     * Requirement 7: Dynamic config.ini parsing and fallback mechanism.
     */
    @Test
    fun testConfigIniDynamicParsingAndFallback() {
        val customIni = """
            [Config]
            EscOpensMenu = 1

            [Keys_P1]
            Joystick = -1
            GUID     = 
            up       = UP
            down     = DOWN
            left     = LEFT
            right    = RIGHT
            a        = a
            b        = b
            c        = c
            x        = x
            y        = y
            z        = z
            start    = SPACE
            menu     = ESCAPE
        """.trimIndent()

        InputManager.parseConfigIni(customIni)

        // Custom mapping: a maps to KEYCODE_A, start maps to KEYCODE_SPACE
        val tempDir = File(context.cacheDir, "test_game_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val saveDir = File(tempDir, "save")
        saveDir.mkdirs()
        File(saveDir, "config.ini").writeText(customIni)

        val keyA = InputManager.buttonIdToKeyCode("btn_a", context, gameDir = tempDir)
        val keyStart = InputManager.buttonIdToKeyCode("btn_start", context, gameDir = tempDir)

        assertEquals(KeyEvent.KEYCODE_A, keyA)
        assertEquals(KeyEvent.KEYCODE_SPACE, keyStart)

        // Clear and verify fallback
        InputManager.clearHistory()
        val fallbackA = InputManager.buttonIdToKeyCode("btn_a", null)
        val fallbackStart = InputManager.buttonIdToKeyCode("btn_start", null)
        assertEquals(KeyEvent.KEYCODE_Z, fallbackA)
        assertEquals(KeyEvent.KEYCODE_ENTER, fallbackStart)

        tempDir.deleteRecursively()
    }

    /**
     * Requirement 8: Diagnostic logging and event history tracking.
     */
    @Test
    fun testDiagnosticLoggingAndEventHistory() {
        InputManager.clearHistory()

        // Dispatch button down and up
        val downResult = InputManager.dispatchKeyDown("btn_a")
        val upResult = InputManager.dispatchKeyUp("btn_a")

        // In Robolectric environment without loaded libSDL2.so, onNativeKeyDown throws UnsatisfiedLinkError
        // The manager safely catches it, logs the diagnostic error, and returns false
        assertFalse(downResult)
        assertFalse(upResult)

        val events = InputManager.getRecentEvents()
        assertTrue("Diagnostic events must be logged", events.isNotEmpty())

        val downEvent = events.firstOrNull { it.action == "DOWN" }
        assertNotNull(downEvent)
        assertEquals("btn_a", downEvent?.buttonId)
        assertEquals(KeyEvent.KEYCODE_Z, downEvent?.keyCode)
        assertEquals("SDL_KEYDOWN", downEvent?.engineEvent)
        assertNotNull(downEvent?.errorMessage)

        val upEvent = events.firstOrNull { it.action == "UP" }
        assertNotNull(upEvent)
        assertEquals("btn_a", upEvent?.buttonId)
        assertEquals(KeyEvent.KEYCODE_Z, upEvent?.keyCode)
        assertEquals("SDL_KEYUP", upEvent?.engineEvent)
    }

    /**
     * Requirement 9: ensureDefaultConfigIni initializes save/config.ini properly.
     */
    @Test
    fun testEnsureDefaultConfigIni() {
        val tempDir = File(context.cacheDir, "test_ensure_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        InputManager.ensureDefaultConfigIni(tempDir)

        val configFile = File(tempDir, "save/config.ini")
        assertTrue("save/config.ini should be created", configFile.exists())

        val content = configFile.readText()
        assertTrue(content.contains("[Keys_P1]"))
        assertTrue(content.contains("a        = z"))
        assertTrue(content.contains("start    = RETURN"))
        assertTrue(content.contains("menu     = ESCAPE"))

        tempDir.deleteRecursively()
    }

    /**
     * Requirement 10: ensureDefaultConfigIni patches existing config.ini if menu=Not used or EscOpensMenu is missing.
     */
    @Test
    fun testEnsureDefaultConfigIniPatchesExistingConfig() {
        val tempDir = File(context.cacheDir, "test_patch_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val saveDir = File(tempDir, "save")
        saveDir.mkdirs()
        val oldConfig = """
            [Config]
            EscOpensMenu = 0

            [Keys_P1]
            Joystick = -1
            GUID     = 
            up       = UP
            down     = DOWN
            left     = LEFT
            right    = RIGHT
            a        = z
            b        = x
            c        = c
            x        = a
            y        = s
            z        = d
            start    = RETURN
            menu     = Not used
        """.trimIndent()
        File(saveDir, "config.ini").writeText(oldConfig)

        InputManager.ensureDefaultConfigIni(tempDir)

        val patchedContent = File(saveDir, "config.ini").readText()
        assertTrue("EscOpensMenu must be set to 1", patchedContent.contains("EscOpensMenu = 1"))
        assertTrue("menu must be updated to ESCAPE", patchedContent.contains("menu = ESCAPE"))
        assertFalse("menu = Not used must be gone", patchedContent.contains("menu = Not used"))

        tempDir.deleteRecursively()
    }
}

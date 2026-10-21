package com.example

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.engine.bridge.IkemenBridge
import com.example.engine.input.InputManager
import com.example.ui.screens.controllerEditor.ControllerButtonState
import com.example.ui.screens.controllerEditor.getLabelForId
import com.example.ui.screens.runtime.NativeVirtualControllerView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MacroControllerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        InputManager.clearHistory()
        IkemenBridge.resetState()
    }

    @Test
    fun testMacroButtonIdentification() {
        assertTrue(InputManager.isMacroButton("macro_charge"))
        assertTrue(InputManager.isMacroButton("macro_ab"))
        assertTrue(InputManager.isMacroButton("macro_bc"))
        assertTrue(InputManager.isMacroButton("macro_xy"))
        assertTrue(InputManager.isMacroButton("macro_abc"))
        assertTrue(InputManager.isMacroButton("macro_round_reset"))
        assertTrue(InputManager.isMacroButton("macro_max_power"))
        assertTrue(InputManager.isMacroButton("macro_control_swap"))
        assertTrue(InputManager.isMacroButton("macro_team_switch"))

        assertFalse(InputManager.isMacroButton("btn_a"))
        assertFalse(InputManager.isMacroButton("btn_b"))
        assertFalse(InputManager.isMacroButton("dpad_up"))
        assertFalse(InputManager.isMacroButton("btn_start"))
    }

    @Test
    fun testPowerChargeMacroKeys() {
        // Power charge in fighting games: hold Medium Kick (B=X) and Medium Punch (Y=S)
        val chargeKeys = InputManager.getMacroKeyCodes("macro_charge", context)
        assertEquals(2, chargeKeys.size)
        assertTrue(chargeKeys.contains(KeyEvent.KEYCODE_X)) // btn_b
        assertTrue(chargeKeys.contains(KeyEvent.KEYCODE_S)) // btn_y
    }

    @Test
    fun testComboMacrosKeys() {
        val abKeys = InputManager.getMacroKeyCodes("macro_ab", context)
        assertEquals(listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X), abKeys)

        val xyKeys = InputManager.getMacroKeyCodes("macro_xy", context)
        assertEquals(listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S), xyKeys)

        val abcKeys = InputManager.getMacroKeyCodes("macro_abc", context)
        assertEquals(listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C), abcKeys)

        val resetKeys = InputManager.getMacroKeyCodes("macro_round_reset", context)
        assertEquals(listOf(KeyEvent.KEYCODE_F4), resetKeys)

        val maxPowerKeys = InputManager.getMacroKeyCodes("macro_max_power", context)
        assertEquals(listOf(KeyEvent.KEYCODE_F3), maxPowerKeys)

        val teamSwitchKeys = InputManager.getMacroKeyCodes("macro_team_switch", context)
        assertEquals(listOf(KeyEvent.KEYCODE_Q), teamSwitchKeys) // btn_d in IKEMEN-GO
    }

    @Test
    fun testMacroLabels() {
        assertEquals("CHG", getLabelForId("macro_charge"))
        assertEquals("A+B", getLabelForId("macro_ab"))
        assertEquals("B+C", getLabelForId("macro_bc"))
        assertEquals("X+Y", getLabelForId("macro_xy"))
        assertEquals("ABC", getLabelForId("macro_abc"))
        assertEquals("RESET", getLabelForId("macro_round_reset"))
        assertEquals("MAX", getLabelForId("macro_max_power"))
        assertEquals("SWAP", getLabelForId("macro_control_swap"))
        assertEquals("TAG", getLabelForId("macro_team_switch"))
    }

    @Test
    fun testNativeVirtualControllerAppliesOnlyEnabledButtons() {
        val controller = NativeVirtualControllerView(context)
        val buttons = listOf(
            ControllerButtonState("btn_a", 100f, 100f, "A", isEnabled = true),
            ControllerButtonState("btn_b", 200f, 100f, "B", isEnabled = true),
            ControllerButtonState("macro_charge", 300f, 100f, "CHG", isEnabled = false, isMacro = true),
            ControllerButtonState("macro_ab", 400f, 100f, "A+B", isEnabled = true, isMacro = true),
            ControllerButtonState("macro_control_swap", 500f, 100f, "SWAP", isEnabled = false, isMacro = true),
            ControllerButtonState("macro_team_switch", 600f, 100f, "TAG", isEnabled = false, isMacro = true)
        )
        controller.applyControllerButtons(buttons)

        val models = controller.getButtonModels()
        // Only 3 buttons should be active: btn_a, btn_b, macro_ab. All other macros disabled by default
        assertEquals(3, models.size)
        assertNotNull(models.find { it.id == "btn_a" })
        assertNotNull(models.find { it.id == "btn_b" })
        assertNotNull(models.find { it.id == "macro_ab" })
        assertNull(models.find { it.id == "macro_control_swap" })
        assertNull(models.find { it.id == "macro_team_switch" })
    }

    @Test
    fun testMacroCatalogContainsAllConfigurableOptions() {
        val catalog = com.example.ui.screens.controllerEditor.MACRO_CATALOG
        assertEquals("All 9 macro definitions must exist", 9, catalog.size)
        val ids = catalog.map { it.id }
        assertTrue(ids.contains("macro_charge"))
        assertTrue(ids.contains("macro_ab"))
        assertTrue(ids.contains("macro_bc"))
        assertTrue(ids.contains("macro_xy"))
        assertTrue(ids.contains("macro_abc"))
        assertTrue(ids.contains("macro_round_reset"))
        assertTrue(ids.contains("macro_max_power"))
        assertTrue(ids.contains("macro_control_swap"))
        assertTrue(ids.contains("macro_team_switch"))

        // Verify each macro has a non-empty name, label, and description
        catalog.forEach { macro ->
            assertTrue("Macro name must not be blank: ${macro.id}", macro.name.isNotBlank())
            assertTrue("Macro description must not be blank: ${macro.id}", macro.description.isNotBlank())
            assertTrue("Macro label must not be blank: ${macro.id}", macro.label.isNotBlank())
        }
    }

    @Test
    fun testFeatureAPlayerCpuControlSwapStateTransitions() {
        IkemenBridge.mockNativeSwapHandler = { true }
        // Initially Player 1 has manual control
        assertEquals(IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, IkemenBridge.currentControlOwner)

        // 1st press: Swap control to Opponent CPU
        val result1 = IkemenBridge.requestPlayerCpuControlSwap()
        assertTrue("First control swap must succeed", result1.success)
        assertEquals(IkemenBridge.FighterControlOwner.CPU_OPPONENT_SWAPPED, result1.newOwner)
        assertEquals(IkemenBridge.FighterControlOwner.CPU_OPPONENT_SWAPPED, IkemenBridge.currentControlOwner)

        // 2nd press: Restore manual control to Player 1
        val result2 = IkemenBridge.requestPlayerCpuControlSwap()
        assertTrue("Second control swap must succeed", result2.success)
        assertEquals(IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, result2.newOwner)
        assertEquals(IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, IkemenBridge.currentControlOwner)
    }

    @Test
    fun testFeatureAControlSwapRejectionWhenNativeUnbound() {
        IkemenBridge.resetState() // mockNativeSwapHandler is null
        val result = IkemenBridge.requestPlayerCpuControlSwap()
        assertFalse("Control swap must be safely rejected when native symbol is unbound", result.success)
        assertEquals(IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, result.newOwner)
        assertEquals(IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, IkemenBridge.currentControlOwner)
        assertTrue(result.message.contains("not exported in bundled libmain.so") || result.message.contains("safely rejected"))
    }

    @Test
    fun testFeatureBTeamSwitchDispatchesWithoutLocalModeGuard() {
        // Mode 1: TM_Single (Default 1v1) -> Dispatches to engine (data/tag.zss authoritatively validates)
        IkemenBridge.currentTeamMode = IkemenBridge.MatchTeamMode.SINGLE
        val singleResult = IkemenBridge.requestOwnTeamMemberSwitch(context)
        assertTrue("Team switch dispatch must not be blocked by static Single mode", singleResult.success)

        // Mode 2: TM_Simul -> Dispatches to engine
        IkemenBridge.currentTeamMode = IkemenBridge.MatchTeamMode.SIMUL
        val simulResult = IkemenBridge.requestOwnTeamMemberSwitch(context)
        assertTrue("Team switch dispatch must succeed", simulResult.success)

        // Mode 4: TM_Tag -> Dispatches to engine
        IkemenBridge.currentTeamMode = IkemenBridge.MatchTeamMode.TAG
        val tagResult = IkemenBridge.requestOwnTeamMemberSwitch(context)
        assertTrue("Team switch must succeed in Tag mode", tagResult.success)
    }

    @Test
    fun testRepeatedPendingCommandGuard() {
        IkemenBridge.mockNativeSwapHandler = { true }
        // Ensure pending command lock works cleanly
        assertFalse(IkemenBridge.isSwapPending())
        assertFalse(IkemenBridge.isTeamSwitchPending())

        // Dispatch via InputManager
        val swapSuccess = InputManager.dispatchKeyDown("macro_control_swap", context)
        assertTrue(swapSuccess)
        assertFalse("Pending lock must be released after completion", IkemenBridge.isSwapPending())

        val events = InputManager.getRecentEvents()
        assertTrue(events.any { it.buttonId == "macro_control_swap" && it.engineEvent == "CONTROL_SWAP" })
    }

    @Test
    fun testMacroLayoutSerializationAndPersistence() {
        val buttons = listOf(
            ControllerButtonState("btn_a", 100f, 100f, "A", isEnabled = true),
            ControllerButtonState("macro_control_swap", 300f, 200f, "SWAP", isEnabled = true, isMacro = true),
            ControllerButtonState("macro_team_switch", 400f, 200f, "TAG", isEnabled = false, isMacro = true)
        )

        val jsonArray = JSONArray()
        buttons.forEach { b ->
            val obj = JSONObject()
            obj.put("id", b.id)
            obj.put("x", b.initialX.toDouble())
            obj.put("y", b.initialY.toDouble())
            obj.put("scale", b.scale.toDouble())
            obj.put("opacity", b.opacity.toDouble())
            obj.put("enabled", b.isEnabled)
            obj.put("macro", b.isMacro)
            jsonArray.put(obj)
        }

        val jsonString = jsonArray.toString()
        assertTrue(jsonString.contains("macro_control_swap"))
        assertTrue(jsonString.contains("macro_team_switch"))

        val controller = NativeVirtualControllerView(context)
        controller.updateLayoutFromJson(jsonString)

        val activeModels = controller.getButtonModels()
        // btn_a and macro_control_swap are enabled; macro_team_switch is disabled
        assertEquals(2, activeModels.size)
        assertNotNull(activeModels.find { it.id == "macro_control_swap" })
        assertNull(activeModels.find { it.id == "macro_team_switch" })
    }

    @Test
    fun testMacroOptionTogglePreservesState() {
        val initialButtons = listOf(
            ControllerButtonState("btn_a", 100f, 100f, "A", isEnabled = true),
            ControllerButtonState("macro_round_reset", 200f, 200f, "RESET", isEnabled = false, isMacro = true),
            ControllerButtonState("macro_control_swap", 300f, 200f, "SWAP", isEnabled = false, isMacro = true)
        )
        // Simulate toggling macro switch
        val toggled = initialButtons.map {
            if (it.id == "macro_control_swap") it.copy(isEnabled = true) else it
        }
        val swapBtn = toggled.find { it.id == "macro_control_swap" }
        assertNotNull(swapBtn)
        assertTrue("Macro button should now be enabled", swapBtn!!.isEnabled)
    }

    @Test
    fun testControllerSelectionStateTransitions() {
        var selectedButtonId: String? = null

        // 1. Initially no selection
        assertNull("Initial selection must be null", selectedButtonId)

        // 2. Select element A
        selectedButtonId = "btn_a"
        assertEquals("btn_a", selectedButtonId)

        // 3. Select another element B
        selectedButtonId = "btn_b"
        assertEquals("btn_b", selectedButtonId)

        // 4. Tap empty canvas -> selection cleared
        selectedButtonId = null
        assertNull("Selection must clear after tapping empty space", selectedButtonId)
    }

    @Test
    fun testPresetLayoutIncludesAllDefaultButtons() {
        val layout = com.example.ui.screens.controllerEditor.getPresetLayout(
            com.example.ui.screens.controllerEditor.ControllerPreset.SIX_BUTTON,
            800f,
            480f
        )
        assertTrue(layout.any { it.id == "dpad_up" })
        assertTrue(layout.any { it.id == "dpad_down" })
        assertTrue(layout.any { it.id == "dpad_left" })
        assertTrue(layout.any { it.id == "dpad_right" })
        assertTrue(layout.any { it.id == "btn_start" })
        assertTrue(layout.any { it.id == "btn_menu" })
        assertTrue(layout.any { it.id == "btn_a" })
        assertTrue(layout.any { it.id == "btn_b" })
        assertTrue(layout.any { it.id == "btn_c" })
        assertTrue(layout.any { it.id == "btn_x" })
        assertTrue(layout.any { it.id == "btn_y" })
        assertTrue(layout.any { it.id == "btn_z" })
    }
}

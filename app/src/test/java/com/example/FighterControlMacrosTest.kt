package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.bridge.IkemenBridge
import com.example.engine.input.InputManager
import com.example.utils.LiveLogger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FighterControlMacrosTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        LiveLogger.initialize(context as Application)
        InputManager.clearHistory()
        IkemenBridge.resetState()
    }

    @Test
    fun testRepeatableControlSwapWithoutMatchRestart() {
        IkemenBridge.mockNativeSwapHandler = { true }
        // Initially Player 1 has control
        assertEquals(IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, IkemenBridge.currentControlOwner)

        // Multiple repeatable swaps back and forth
        for (cycle in 1..5) {
            val swapToCpu = InputManager.dispatchKeyDown("macro_control_swap", context)
            InputManager.dispatchKeyUp("macro_control_swap", context)
            assertTrue("Cycle $cycle: Swap to CPU must succeed", swapToCpu)
            assertEquals("Cycle $cycle: Control must be transferred to opponent",
                IkemenBridge.FighterControlOwner.CPU_OPPONENT_SWAPPED, IkemenBridge.currentControlOwner)

            val swapBackToPlayer = InputManager.dispatchKeyDown("macro_control_swap", context)
            InputManager.dispatchKeyUp("macro_control_swap", context)
            assertTrue("Cycle $cycle: Swap back to Player must succeed", swapBackToPlayer)
            assertEquals("Cycle $cycle: Control must be restored to Player 1",
                IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, IkemenBridge.currentControlOwner)
        }
    }

    @Test
    fun testControlSwapSafelyRejectedWithoutFakeSuccess() {
        IkemenBridge.resetState() // mockNativeSwapHandler is null
        val swapResult = InputManager.dispatchKeyDown("macro_control_swap", context)
        InputManager.dispatchKeyUp("macro_control_swap", context)

        assertFalse("Swap must be rejected when native symbol is unbound", swapResult)
        assertEquals("Player 1 must retain control without fake flip",
            IkemenBridge.FighterControlOwner.HUMAN_PLAYER_1, IkemenBridge.currentControlOwner)

        val events = InputManager.getRecentEvents()
        val swapEvent = events.lastOrNull { it.buttonId == "macro_control_swap" && it.action == "DOWN" }
        assertNotNull(swapEvent)
        assertFalse(swapEvent!!.isDispatched)
        assertNotNull(swapEvent.errorMessage)
        assertTrue(swapEvent.errorMessage!!.contains("not exported in bundled libmain.so"))
    }

    @Test
    fun testTeamSwitchDispatchedWithoutLocalModeGuard() {
        InputManager.testSdlKeyDispatcher = { _, _ -> true }
        val allModes = listOf(
            IkemenBridge.MatchTeamMode.SINGLE,
            IkemenBridge.MatchTeamMode.SIMUL,
            IkemenBridge.MatchTeamMode.TURNS,
            IkemenBridge.MatchTeamMode.TAG
        )

        for (mode in allModes) {
            IkemenBridge.currentTeamMode = mode
            val success = InputManager.dispatchKeyDown("macro_team_switch", context)
            InputManager.dispatchKeyUp("macro_team_switch", context)

            assertTrue("Team switch key must be dispatched in ${mode.displayName} to engine tag.zss", success)

            val events = InputManager.getRecentEvents()
            val lastDownEvent = events.lastOrNull { it.buttonId == "macro_team_switch" && it.action == "DOWN" }
            assertNotNull(lastDownEvent)
            assertTrue(lastDownEvent!!.isDispatched)
        }
    }

    @Test
    fun testTeamSwitchSuccessInTagMode() {
        InputManager.testSdlKeyDispatcher = { _, _ -> true }
        IkemenBridge.currentTeamMode = IkemenBridge.MatchTeamMode.TAG
        val success = InputManager.dispatchKeyDown("macro_team_switch", context)
        InputManager.dispatchKeyUp("macro_team_switch", context)

        assertTrue("Team switch must succeed in Tag mode", success)

        val events = InputManager.getRecentEvents()
        val lastDownEvent = events.lastOrNull { it.buttonId == "macro_team_switch" && it.action == "DOWN" }
        assertNotNull(lastDownEvent)
        assertTrue(lastDownEvent!!.isDispatched)
    }

    @Test
    fun testDiagnosticEventsRecordedOnControlSwapAndTeamSwitch() {
        IkemenBridge.currentTeamMode = IkemenBridge.MatchTeamMode.SINGLE

        // 1. Trigger control swap
        InputManager.dispatchKeyDown("macro_control_swap", context)
        InputManager.dispatchKeyUp("macro_control_swap", context)

        // 2. Trigger team switch in unsupported mode
        InputManager.dispatchKeyDown("macro_team_switch", context)
        InputManager.dispatchKeyUp("macro_team_switch", context)

        val events = InputManager.getRecentEvents()
        assertTrue("Must record control swap event", events.any { it.buttonId == "macro_control_swap" && it.engineEvent == "CONTROL_SWAP" })
        assertTrue("Must record team switch event", events.any { it.buttonId == "macro_team_switch" && it.engineEvent == "TEAM_SWITCH" })

        // Check LiveLogger file output
        LiveLogger.flush()
        Thread.sleep(100)
        val logLines = LiveLogger.readRecentLines(context, 40)
        assertTrue("Must log control swap", logLines.any { it.contains("CONTROL_SWAP") })
        assertTrue("Must log team switch", logLines.any { it.contains("TEAM_SWITCH") })
    }
}

package com.example.engine.input

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.example.storage.MugenMobileStorage
import org.libsdl.app.SDLActivity
import java.io.File
import java.util.LinkedList

/**
 * Authoritative input manager and key-mapping bridge for MUGEN Mobile / IKEMEN-GO.
 *
 * Resolves virtual controller button touch events into precise SDL2 keyboard events
 * recognized by the IKEMEN-GO engine core (embedded in libmain.so):
 *
 * 1. Directional D-pad: KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT -> SDL_SCANCODE_UP/DOWN/LEFT/RIGHT.
 * 2. Action buttons (6-button layout):
 *    - A: KEYCODE_Z -> a = z (Light Kick / Menu Confirm)
 *    - B: KEYCODE_X -> b = x (Medium Kick / Menu Back)
 *    - C: KEYCODE_C -> c = c (Heavy Kick)
 *    - X: KEYCODE_A -> x = a (Light Punch)
 *    - Y: KEYCODE_S -> y = s (Medium Punch)
 *    - Z: KEYCODE_D -> z = d (Heavy Punch)
 * 3. System buttons:
 *    - START: KEYCODE_ENTER -> start = RETURN (Menu Select / Taunt / Game Start)
 *    - MENU:  KEYCODE_ESCAPE -> EscOpensMenu = 1 (In-game Pause / Sub-menu Cancel)
 *
 * Supports dynamic configuration loading from <gameDir>/save/config.ini.
 */
object InputManager {
    private const val TAG = "MugenInput"

    data class InputDiagnosticEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val buttonId: String,
        val keyCode: Int,
        val action: String, // "DOWN" or "UP"
        val engineEvent: String, // "SDL_KEYDOWN" or "SDL_KEYUP"
        val isDispatched: Boolean,
        val errorMessage: String? = null
    )

    private val eventHistory = LinkedList<InputDiagnosticEvent>()
    private const val MAX_HISTORY = 30

    // Cached custom key mappings read from save/config.ini for P1 and P2
    private val cachedConfigKeys = HashMap<String, Int>()
    private val cachedConfigKeysP2 = HashMap<String, Int>()
    private var lastLoadedConfigPath: String? = null

    // Tracks exact keycodes dispatched on DOWN per button ID so that UP releases identical keycodes
    // even if mapping or SWAP state changes in-between (guarantees no stuck buttons)
    private val activeDispatchedKeys = java.util.concurrent.ConcurrentHashMap<String, MutableList<Int>>()

    private fun trackDispatchedKey(buttonId: String, keyCode: Int) {
        activeDispatchedKeys.computeIfAbsent(buttonId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(keyCode)
    }

    private fun trackDispatchedKeys(buttonId: String, keyCodes: List<Int>) {
        val list = activeDispatchedKeys.computeIfAbsent(buttonId) { java.util.Collections.synchronizedList(mutableListOf()) }
        list.addAll(keyCodes)
    }

    private fun releaseDispatchedKeys(buttonId: String): List<Int> {
        return activeDispatchedKeys.remove(buttonId) ?: emptyList()
    }

    // Hook for testing native SDL key dispatch without libSDL2.so in JVM unit tests
    var testSdlKeyDispatcher: ((keyCode: Int, isDown: Boolean) -> Boolean)? = null

    fun invokeNativeKeyDown(keyCode: Int): Boolean {
        testSdlKeyDispatcher?.let { return it.invoke(keyCode, true) }
        return try {
            SDLActivity.onNativeKeyDown(keyCode)
            true
        } catch (e: Throwable) {
            val err = "onNativeKeyDown failed: ${e.javaClass.simpleName} - ${e.message}"
            com.example.utils.LiveLogger.e(com.example.utils.LiveLogger.Category.INPUT, "SDL_KEYDOWN_ERROR", err)
            false
        }
    }

    fun invokeNativeKeyUp(keyCode: Int): Boolean {
        testSdlKeyDispatcher?.let { return it.invoke(keyCode, false) }
        return try {
            SDLActivity.onNativeKeyUp(keyCode)
            true
        } catch (e: Throwable) {
            val err = "onNativeKeyUp failed: ${e.javaClass.simpleName} - ${e.message}"
            com.example.utils.LiveLogger.e(com.example.utils.LiveLogger.Category.INPUT, "SDL_KEYUP_ERROR", err)
            false
        }
    }

    /**
     * Authoritative default key mapping based on IKEMEN-GO [Keys_P1] schema.
     */
    val DEFAULT_KEY_MAPPING = mapOf(
        "dpad_up" to KeyEvent.KEYCODE_DPAD_UP,
        "dpad_down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "dpad_left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "dpad_right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "btn_a" to KeyEvent.KEYCODE_Z,       // IKEMEN [Keys_P1] a = z
        "btn_b" to KeyEvent.KEYCODE_X,       // IKEMEN [Keys_P1] b = x
        "btn_c" to KeyEvent.KEYCODE_C,       // IKEMEN [Keys_P1] c = c
        "btn_d" to KeyEvent.KEYCODE_Q,       // IKEMEN [Keys_P1] d = q
        "btn_w" to KeyEvent.KEYCODE_W,       // IKEMEN [Keys_P1] w = w
        "btn_x" to KeyEvent.KEYCODE_A,       // IKEMEN [Keys_P1] x = a
        "btn_y" to KeyEvent.KEYCODE_S,       // IKEMEN [Keys_P1] y = s
        "btn_z" to KeyEvent.KEYCODE_D,       // IKEMEN [Keys_P1] z = d
        "btn_start" to KeyEvent.KEYCODE_ENTER, // IKEMEN [Keys_P1] start = RETURN
        "btn_menu" to KeyEvent.KEYCODE_ESCAPE  // IKEMEN EscOpensMenu = 1 / menu = ESCAPE
    )

    /**
     * Authoritative default key mapping based on IKEMEN-GO [Keys_P2] schema.
     */
    val DEFAULT_KEY_MAPPING_P2 = mapOf(
        "dpad_up" to KeyEvent.KEYCODE_I,        // IKEMEN [Keys_P2] up = i
        "dpad_down" to KeyEvent.KEYCODE_K,      // IKEMEN [Keys_P2] down = k
        "dpad_left" to KeyEvent.KEYCODE_J,      // IKEMEN [Keys_P2] left = j
        "dpad_right" to KeyEvent.KEYCODE_L,     // IKEMEN [Keys_P2] right = l
        "btn_a" to KeyEvent.KEYCODE_NUMPAD_1,   // IKEMEN [Keys_P2] a = 1
        "btn_b" to KeyEvent.KEYCODE_NUMPAD_2,   // IKEMEN [Keys_P2] b = 2
        "btn_c" to KeyEvent.KEYCODE_NUMPAD_3,   // IKEMEN [Keys_P2] c = 3
        "btn_d" to KeyEvent.KEYCODE_NUMPAD_0,   // IKEMEN [Keys_P2] d = 0
        "btn_w" to KeyEvent.KEYCODE_NUMPAD_7,   // IKEMEN [Keys_P2] w = 7
        "btn_x" to KeyEvent.KEYCODE_NUMPAD_4,   // IKEMEN [Keys_P2] x = 4
        "btn_y" to KeyEvent.KEYCODE_NUMPAD_5,   // IKEMEN [Keys_P2] y = 5
        "btn_z" to KeyEvent.KEYCODE_NUMPAD_6,   // IKEMEN [Keys_P2] z = 6
        "btn_start" to KeyEvent.KEYCODE_ENTER,  // IKEMEN [Keys_P2] start = RETURN
        "btn_menu" to KeyEvent.KEYCODE_ESCAPE
    )

    /**
     * Returns the Android KeyEvent keycode mapped to the given virtual button ID.
     * Checks cached config.ini first, then falls back to DEFAULT_KEY_MAPPING.
     * When SWAP is active (CPU_OPPONENT_SWAPPED), routes to Player 2's key bindings.
     */
    fun buttonIdToKeyCode(id: String, context: Context? = null, gameDir: File? = null): Int {
        val targetDir = gameDir ?: if (context != null) {
            try {
                MugenMobileStorage.getBaseDir(context)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        if (targetDir != null) {
            checkReloadConfig(targetDir)
        }

        val isSwapped = com.example.engine.bridge.IkemenBridge.currentControlOwner ==
                com.example.engine.bridge.IkemenBridge.FighterControlOwner.CPU_OPPONENT_SWAPPED

        return if (isSwapped) {
            cachedConfigKeysP2[id] ?: DEFAULT_KEY_MAPPING_P2[id] ?: cachedConfigKeys[id] ?: DEFAULT_KEY_MAPPING[id] ?: 0
        } else {
            cachedConfigKeys[id] ?: DEFAULT_KEY_MAPPING[id] ?: 0
        }
    }

    /**
     * Reloads key bindings from <gameDir>/save/config.ini if the file changed or hasn't been read.
     */
    @Synchronized
    fun checkReloadConfig(gameDir: File) {
        val configFile = File(gameDir, "save/config.ini")
        val currentPath = configFile.absolutePath
        if (!configFile.exists()) {
            if (lastLoadedConfigPath == currentPath) return
            cachedConfigKeys.clear()
            cachedConfigKeysP2.clear()
            lastLoadedConfigPath = currentPath
            return
        }

        try {
            val text = configFile.readText()
            parseConfigIni(text)
            lastLoadedConfigPath = currentPath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read config.ini: ${e.message}")
        }
    }

    /**
     * Parses [Keys_P1] and [Keys_P2] sections of an IKEMEN-GO config.ini file into keycodes.
     */
    fun parseConfigIni(iniContent: String) {
        cachedConfigKeys.clear()
        cachedConfigKeysP2.clear()
        var currentSection = ""

        for (rawLine in iniContent.lines()) {
            val line = rawLine.trim()
            if (line.startsWith(";") || line.startsWith("#") || line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim()
                continue
            }

            val inP1 = currentSection.equals("Keys_P1", ignoreCase = true)
            val inP2 = currentSection.equals("Keys_P2", ignoreCase = true)

            if ((inP1 || inP2) && line.contains("=")) {
                val parts = line.split("=", limit = 2)
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()

                val buttonId = when (key) {
                    "up" -> "dpad_up"
                    "down" -> "dpad_down"
                    "left" -> "dpad_left"
                    "right" -> "dpad_right"
                    "a" -> "btn_a"
                    "b" -> "btn_b"
                    "c" -> "btn_c"
                    "d" -> "btn_d"
                    "w" -> "btn_w"
                    "x" -> "btn_x"
                    "y" -> "btn_y"
                    "z" -> "btn_z"
                    "start" -> "btn_start"
                    "menu" -> "btn_menu"
                    else -> null
                }

                if (buttonId != null) {
                    val code = stringToKeyCode(value)
                    if (code != 0) {
                        if (inP1) {
                            cachedConfigKeys[buttonId] = code
                        } else {
                            cachedConfigKeysP2[buttonId] = code
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts an IKEMEN / MUGEN key name string (e.g. "UP", "RETURN", "z", "ESCAPE")
     * into an Android KeyEvent keycode.
     */
    fun stringToKeyCode(keyName: String): Int {
        val upper = keyName.trim().uppercase()
        return when (upper) {
            "UP" -> KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "RETURN", "ENTER" -> KeyEvent.KEYCODE_ENTER
            "ESCAPE", "ESC" -> KeyEvent.KEYCODE_ESCAPE
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            "TAB" -> KeyEvent.KEYCODE_TAB
            "BACKSPACE" -> KeyEvent.KEYCODE_DEL
            "LSHIFT", "SHIFT" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "RSHIFT" -> KeyEvent.KEYCODE_SHIFT_RIGHT
            "LCTRL", "CTRL" -> KeyEvent.KEYCODE_CTRL_LEFT
            "RCTRL" -> KeyEvent.KEYCODE_CTRL_RIGHT
            "LALT", "ALT" -> KeyEvent.KEYCODE_ALT_LEFT
            "RALT" -> KeyEvent.KEYCODE_ALT_RIGHT
            "A" -> KeyEvent.KEYCODE_A
            "B" -> KeyEvent.KEYCODE_B
            "C" -> KeyEvent.KEYCODE_C
            "D" -> KeyEvent.KEYCODE_D
            "E" -> KeyEvent.KEYCODE_E
            "F" -> KeyEvent.KEYCODE_F
            "G" -> KeyEvent.KEYCODE_G
            "H" -> KeyEvent.KEYCODE_H
            "I" -> KeyEvent.KEYCODE_I
            "J" -> KeyEvent.KEYCODE_J
            "K" -> KeyEvent.KEYCODE_K
            "L" -> KeyEvent.KEYCODE_L
            "M" -> KeyEvent.KEYCODE_M
            "N" -> KeyEvent.KEYCODE_N
            "O" -> KeyEvent.KEYCODE_O
            "P" -> KeyEvent.KEYCODE_P
            "Q" -> KeyEvent.KEYCODE_Q
            "R" -> KeyEvent.KEYCODE_R
            "S" -> KeyEvent.KEYCODE_S
            "T" -> KeyEvent.KEYCODE_T
            "U" -> KeyEvent.KEYCODE_U
            "V" -> KeyEvent.KEYCODE_V
            "W" -> KeyEvent.KEYCODE_W
            "X" -> KeyEvent.KEYCODE_X
            "Y" -> KeyEvent.KEYCODE_Y
            "Z" -> KeyEvent.KEYCODE_Z
            "0" -> KeyEvent.KEYCODE_0
            "1" -> KeyEvent.KEYCODE_1
            "2" -> KeyEvent.KEYCODE_2
            "3" -> KeyEvent.KEYCODE_3
            "4" -> KeyEvent.KEYCODE_4
            "5" -> KeyEvent.KEYCODE_5
            "6" -> KeyEvent.KEYCODE_6
            "7" -> KeyEvent.KEYCODE_7
            "8" -> KeyEvent.KEYCODE_8
            "9" -> KeyEvent.KEYCODE_9
            else -> 0
        }
    }

    /**
     * Ensures that <gameDir>/save/config.ini exists with valid [Config] and [Keys_P1] sections,
     * guaranteeing total synchronization between the engine and the virtual controller.
     * If the file already exists, updates any missing or disabled settings (like menu = Not used or EscOpensMenu).
     */
    fun ensureDefaultConfigIni(gameDir: File) {
        val saveDir = File(gameDir, "save")
        if (!saveDir.exists()) {
            saveDir.mkdirs()
        }
        val configFile = File(saveDir, "config.ini")
        if (!configFile.exists()) {
            try {
                val content = buildString {
                    appendLine("; Generated by MUGEN Mobile Engine Configurator")
                    appendLine("[Config]")
                    appendLine("EscOpensMenu = 1")
                    appendLine()
                    appendLine("[Keys_P1]")
                    appendLine("Joystick = -1")
                    appendLine("GUID     = ")
                    appendLine("up       = UP")
                    appendLine("down     = DOWN")
                    appendLine("left     = LEFT")
                    appendLine("right    = RIGHT")
                    appendLine("a        = z")
                    appendLine("b        = x")
                    appendLine("c        = c")
                    appendLine("x        = a")
                    appendLine("y        = s")
                    appendLine("z        = d")
                    appendLine("start    = RETURN")
                    appendLine("d        = q")
                    appendLine("w        = w")
                    appendLine("menu     = ESCAPE")
                    appendLine()
                    appendLine("[Keys_P2]")
                    appendLine("Joystick = -1")
                    appendLine("GUID     = ")
                    appendLine("up       = i")
                    appendLine("down     = k")
                    appendLine("left     = j")
                    appendLine("right    = l")
                    appendLine("a        = 1")
                    appendLine("b        = 2")
                    appendLine("c        = 3")
                    appendLine("x        = 4")
                    appendLine("y        = 5")
                    appendLine("z        = 6")
                    appendLine("start    = RETURN")
                    appendLine("d        = 0")
                    appendLine("w        = 7")
                    appendLine("menu     = ESCAPE")
                }
                configFile.writeText(content)
                Log.i(TAG, "Initialized default save/config.ini with unified [Keys_P1] and [Keys_P2] at ${configFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create save/config.ini: ${e.message}")
            }
        } else {
            // Existing file: verify and patch essential settings line-by-line
            try {
                val lines = configFile.readLines()
                val updatedLines = mutableListOf<String>()
                var currentSection = ""
                var foundEscOpensMenu = false
                var foundMenuInP1 = false
                var foundStartInP1 = false
                var foundP2Section = false
                var modified = false

                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        currentSection = line.substring(1, line.length - 1).trim()
                        if (currentSection.equals("Keys_P2", ignoreCase = true)) {
                            foundP2Section = true
                        }
                        updatedLines.add(rawLine)
                        continue
                    }

                    if (currentSection.equals("Config", ignoreCase = true) && line.startsWith("EscOpensMenu", ignoreCase = true)) {
                        updatedLines.add("EscOpensMenu = 1")
                        foundEscOpensMenu = true
                        if (line != "EscOpensMenu = 1") modified = true
                        continue
                    }

                    if (currentSection.equals("Keys_P1", ignoreCase = true)) {
                        if (line.startsWith("menu", ignoreCase = true) && line.contains("=")) {
                            val parts = line.split("=", limit = 2)
                            val v = parts[1].trim()
                            if (v.equals("Not used", ignoreCase = true) || v.isEmpty() || v == "0") {
                                updatedLines.add("menu = ESCAPE")
                                modified = true
                            } else {
                                updatedLines.add(rawLine)
                            }
                            foundMenuInP1 = true
                            continue
                        }
                        if (line.startsWith("start", ignoreCase = true) && line.contains("=")) {
                            val parts = line.split("=", limit = 2)
                            val v = parts[1].trim()
                            if (v.equals("Not used", ignoreCase = true) || v.isEmpty() || v == "0") {
                                updatedLines.add("start = RETURN")
                                modified = true
                            } else {
                                updatedLines.add(rawLine)
                            }
                            foundStartInP1 = true
                            continue
                        }
                    }

                    updatedLines.add(rawLine)
                }

                // If EscOpensMenu was never present in [Config], insert it
                if (!foundEscOpensMenu) {
                    val configIdx = updatedLines.indexOfFirst { it.trim().equals("[Config]", ignoreCase = true) }
                    if (configIdx != -1) {
                        updatedLines.add(configIdx + 1, "EscOpensMenu = 1")
                    } else {
                        updatedLines.add(0, "[Config]")
                        updatedLines.add(1, "EscOpensMenu = 1")
                        updatedLines.add(2, "")
                    }
                    modified = true
                }

                // If menu was not present in [Keys_P1], append it under [Keys_P1]
                if (!foundMenuInP1) {
                    val p1Idx = updatedLines.indexOfFirst { it.trim().equals("[Keys_P1]", ignoreCase = true) }
                    if (p1Idx != -1) {
                        updatedLines.add(p1Idx + 1, "menu = ESCAPE")
                        modified = true
                    }
                }

                // If [Keys_P2] section is completely absent, append standard P2 bindings
                if (!foundP2Section) {
                    updatedLines.add("")
                    updatedLines.add("[Keys_P2]")
                    updatedLines.add("Joystick = -1")
                    updatedLines.add("GUID     = ")
                    updatedLines.add("up       = i")
                    updatedLines.add("down     = k")
                    updatedLines.add("left     = j")
                    updatedLines.add("right    = l")
                    updatedLines.add("a        = 1")
                    updatedLines.add("b        = 2")
                    updatedLines.add("c        = 3")
                    updatedLines.add("x        = 4")
                    updatedLines.add("y        = 5")
                    updatedLines.add("z        = 6")
                    updatedLines.add("start    = RETURN")
                    updatedLines.add("d        = 0")
                    updatedLines.add("w        = 7")
                    updatedLines.add("menu     = ESCAPE")
                    modified = true
                }

                if (modified) {
                    configFile.writeText(updatedLines.joinToString("\n"))
                    Log.i(TAG, "Synchronized existing save/config.ini with engine keybindings at ${configFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update existing save/config.ini: ${e.message}")
            }
        }
    }

    /**
     * Determines whether the given button ID represents a composite macro button.
     */
    fun isMacroButton(id: String): Boolean {
        return id.startsWith("macro_")
    }

    /**
     * Returns the list of underlying keycodes dispatched simultaneously for a macro button.
     */
    fun getMacroKeyCodes(id: String, context: Context? = null): List<Int> {
        return when (id) {
            "macro_charge" -> listOf(
                buttonIdToKeyCode("btn_b", context),
                buttonIdToKeyCode("btn_y", context)
            ) // B + Y (Medium Kick + Medium Punch = standard power charge)
            "macro_ab" -> listOf(
                buttonIdToKeyCode("btn_a", context),
                buttonIdToKeyCode("btn_b", context)
            )
            "macro_bc" -> listOf(
                buttonIdToKeyCode("btn_b", context),
                buttonIdToKeyCode("btn_c", context)
            )
            "macro_xy" -> listOf(
                buttonIdToKeyCode("btn_x", context),
                buttonIdToKeyCode("btn_y", context)
            )
            "macro_abc" -> listOf(
                buttonIdToKeyCode("btn_a", context),
                buttonIdToKeyCode("btn_b", context),
                buttonIdToKeyCode("btn_c", context)
            )
            "macro_xyz" -> listOf(
                buttonIdToKeyCode("btn_x", context),
                buttonIdToKeyCode("btn_y", context),
                buttonIdToKeyCode("btn_z", context)
            )
            "macro_round_reset" -> listOf(KeyEvent.KEYCODE_F4) // F4 in IKEMEN-GO = Round Reset
            "macro_max_power" -> listOf(KeyEvent.KEYCODE_F3)   // F3 in IKEMEN-GO = Max Power
            "macro_team_switch" -> listOf(buttonIdToKeyCode("btn_d", context)) // Button D (Tag / Dodge in IKEMEN-GO)
            "macro_control_swap" -> emptyList() // Routed directly through IkemenBridge
            else -> emptyList()
        }
    }

    /**
     * Dispatches a key down event to SDL2 or the engine bridge.
     * Returns true if successfully delivered.
     */
    fun dispatchKeyDown(buttonId: String, context: Context? = null): Boolean {
        // Special case: Player <-> CPU Control Swap (Feature A)
        if (buttonId == "macro_control_swap") {
            val result = com.example.engine.bridge.IkemenBridge.requestPlayerCpuControlSwap()
            logEvent(buttonId, 0, "DOWN", "CONTROL_SWAP", result.success, if (!result.success) result.message else null)
            return result.success
        }

        // Special case: Switch Own Team Member (Feature B)
        // Dispatches the engine's authoritative TAG keycode directly to SDLActivity
        if (buttonId == "macro_team_switch") {
            val tagKeyCode = buttonIdToKeyCode("btn_d", context)
            if (tagKeyCode == 0) {
                val err = "No keycode mapped for TAG button (btn_d)"
                logEvent(buttonId, 0, "DOWN", "TEAM_SWITCH", false, err)
                com.example.utils.LiveLogger.w(com.example.utils.LiveLogger.Category.INPUT, "TAG_DISPATCH_FAILED", err)
                return false
            }

            trackDispatchedKey(buttonId, tagKeyCode)

            val success = invokeNativeKeyDown(tagKeyCode)
            if (success) {
                com.example.utils.LiveLogger.i(
                    com.example.utils.LiveLogger.Category.INPUT,
                    "TAG_KEYDOWN_DISPATCHED",
                    "Dispatched TAG KeyDown (btn_d) -> keyCode=$tagKeyCode"
                )
            }

            com.example.engine.bridge.IkemenBridge.requestOwnTeamMemberSwitch(context)
            logEvent(buttonId, tagKeyCode, "DOWN", "TEAM_SWITCH", success, if (!success) "SDL dispatch error" else null)
            return success
        }

        if (isMacroButton(buttonId)) {
            val keyCodes = getMacroKeyCodes(buttonId, context)
            if (keyCodes.isEmpty()) {
                logEvent(buttonId, 0, "DOWN", "SDL_KEYDOWN", false, "Unrecognized macro ID")
                return false
            }
            trackDispatchedKeys(buttonId, keyCodes)
            var allSuccess = true
            for (code in keyCodes) {
                val ok = invokeNativeKeyDown(code)
                if (!ok) allSuccess = false
                logEvent(buttonId, code, "DOWN", "SDL_KEYDOWN", ok, if (!ok) "SDL dispatch error" else null)
            }
            com.example.utils.LiveLogger.d(
                com.example.utils.LiveLogger.Category.INPUT,
                "MACRO_DOWN",
                "Dispatched macro $buttonId -> keyCodes=$keyCodes"
            )
            return allSuccess
        }

        val keyCode = buttonIdToKeyCode(buttonId, context)
        if (keyCode == 0) {
            logEvent(buttonId, 0, "DOWN", "SDL_KEYDOWN", false, "Unrecognized button ID")
            Log.w(TAG, "Button down ignored: no keyCode mapping for button '$buttonId'")
            return false
        }

        trackDispatchedKey(buttonId, keyCode)

        val success = invokeNativeKeyDown(keyCode)
        logEvent(buttonId, keyCode, "DOWN", "SDL_KEYDOWN", success, if (!success) "SDL dispatch error" else null)
        if (success) {
            Log.d(TAG, "Dispatched DOWN: id='$buttonId' -> keyCode=$keyCode (SDL_KEYDOWN)")
        }
        return success
    }

    /**
     * Dispatches a key up event to SDL2.
     * Uses tracked dispatched keys to ensure the exact keycode is released,
     * preventing any stuck buttons even if SWAP state toggled during touch.
     */
    fun dispatchKeyUp(buttonId: String, context: Context? = null): Boolean {
        // Special case: Player <-> CPU Control Swap (Feature A)
        if (buttonId == "macro_control_swap") {
            logEvent(buttonId, 0, "UP", "CONTROL_SWAP", true)
            return true
        }

        // Special case: Switch Own Team Member (Feature B)
        if (buttonId == "macro_team_switch") {
            val keyCodesToRelease = releaseDispatchedKeys(buttonId).ifEmpty {
                val fallbackCode = buttonIdToKeyCode("btn_d", context)
                if (fallbackCode != 0) listOf(fallbackCode) else emptyList()
            }
            var allSuccess = true
            for (code in keyCodesToRelease) {
                val ok = invokeNativeKeyUp(code)
                if (!ok) allSuccess = false
                logEvent(buttonId, code, "UP", "TEAM_SWITCH", ok, if (!ok) "SDL dispatch error" else null)
            }
            com.example.utils.LiveLogger.i(
                com.example.utils.LiveLogger.Category.INPUT,
                "TAG_KEYUP_DISPATCHED",
                "Released TAG KeyUp (btn_d) -> keyCodes=$keyCodesToRelease"
            )
            return allSuccess
        }

        if (isMacroButton(buttonId)) {
            val keyCodes = releaseDispatchedKeys(buttonId).ifEmpty {
                getMacroKeyCodes(buttonId, context)
            }
            if (keyCodes.isEmpty()) {
                logEvent(buttonId, 0, "UP", "SDL_KEYUP", false, "Unrecognized macro ID")
                return false
            }
            var allSuccess = true
            for (code in keyCodes) {
                val ok = invokeNativeKeyUp(code)
                if (!ok) allSuccess = false
                logEvent(buttonId, code, "UP", "SDL_KEYUP", ok, if (!ok) "SDL dispatch error" else null)
            }
            com.example.utils.LiveLogger.d(
                com.example.utils.LiveLogger.Category.INPUT,
                "MACRO_UP",
                "Released macro $buttonId -> keyCodes=$keyCodes"
            )
            return allSuccess
        }

        val trackedKeys = releaseDispatchedKeys(buttonId)
        val keyCode = if (trackedKeys.isNotEmpty()) trackedKeys.first() else buttonIdToKeyCode(buttonId, context)
        if (keyCode == 0) {
            logEvent(buttonId, 0, "UP", "SDL_KEYUP", false, "Unrecognized button ID")
            Log.w(TAG, "Button up ignored: no keyCode mapping for button '$buttonId'")
            return false
        }

        val success = invokeNativeKeyUp(keyCode)
        logEvent(buttonId, keyCode, "UP", "SDL_KEYUP", success, if (!success) "SDL dispatch error" else null)
        if (success) {
            Log.d(TAG, "Dispatched UP: id='$buttonId' -> keyCode=$keyCode (SDL_KEYUP)")
        }
        return success
    }

    @Synchronized
    private fun logEvent(
        buttonId: String,
        keyCode: Int,
        action: String,
        engineEvent: String,
        isSuccess: Boolean,
        errorMessage: String? = null
    ) {
        val event = InputDiagnosticEvent(
            buttonId = buttonId,
            keyCode = keyCode,
            action = action,
            engineEvent = engineEvent,
            isDispatched = isSuccess,
            errorMessage = errorMessage
        )
        if (eventHistory.size >= MAX_HISTORY) {
            eventHistory.removeFirst()
        }
        eventHistory.addLast(event)
    }

    @Synchronized
    fun getRecentEvents(): List<InputDiagnosticEvent> {
        return ArrayList(eventHistory)
    }

    @Synchronized
    fun clearHistory() {
        eventHistory.clear()
        cachedConfigKeys.clear()
        cachedConfigKeysP2.clear()
        activeDispatchedKeys.clear()
        lastLoadedConfigPath = null
        testSdlKeyDispatcher = null
    }
}

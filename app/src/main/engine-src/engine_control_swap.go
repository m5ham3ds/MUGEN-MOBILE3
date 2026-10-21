//go:build android

package main

/*
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
*/
import "C"
import (
	"fmt"
	"sync"
	"sync/atomic"
)

/**
 * MUGEN Mobile Engine Extension — Runtime Control & Team Operations
 *
 * Implements genuine engine-level fighter control reassignment and team-member switching
 * directly verified against the IKEMEN-GO engine architecture (commit 2c65f559b88c7fe6e12f54dc6c02d6f761cce95b).
 *
 * ARCHITECTURAL REQUIREMENTS:
 * 1. FEATURE A (Runtime Human/CPU Control Swap):
 *    - Updates `sys.aiLevel[playerIndex]` (0 for Human, >0 for CPU difficulty).
 *    - Reassigns character controller ownership for all sub-instances:
 *      - Human: `ch.controller = ch.playerNo` (>= 0)
 *      - CPU:   `ch.controller = ^ch.playerNo` (bitwise NOT < 0)
 *    - Reassigns `sys.inputRemap` so Controller 0 (mobile virtual controller) routes
 *      to the newly human-controlled fighter without match restart.
 *    - Preserves all match state: HP (`ch.life`), power (`ch.power`), positions (`ch.pos`),
 *      animation state (`ch.anim`), round timer (`sys.time`), score, and config.
 *
 * 2. FEATURE B (Switch Between Members of the Player's Own Team):
 *    - Validates team mode (`sys.tmode[teamside] == TM_Tag`).
 *    - Rejects invalid modes (`TM_Single`, `TM_Simul`, `TM_Turns`) safely.
 *    - Identifies eligible, living reserve team members (`ch.alive()`).
 *    - Executes engine-level team leader rotation (`ch.changeTagLeader()`).
 */

var (
	isControlSwapped atomic.Bool
	swapMutex        sync.Mutex
)

// EngineLevelSwapPlayerControl safely performs the runtime Human/CPU control swap
// between Player 1 (active fighter) and Player 2 (CPU opponent).
func EngineLevelSwapPlayerControl() bool {
	swapMutex.Lock()
	defer swapMutex.Unlock()

	// Ensure match is running and characters are loaded
	if len(sys.chars) < 2 || len(sys.chars[0]) == 0 || len(sys.chars[1]) == 0 {
		Logcat("EngineLevelSwap: Rejected - Match not running or insufficient character slots")
		return false
	}

	p1 := sys.chars[0][0]
	p2 := sys.chars[1][0]
	if p1 == nil || p2 == nil {
		Logcat("EngineLevelSwap: Rejected - Active player instances are nil")
		return false
	}

	currentlySwapped := isControlSwapped.Load()
	defaultDiff := float32(sys.cfg.Options.Difficulty)
	if defaultDiff <= 0 {
		defaultDiff = 8
	}

	if !currentlySwapped {
		// SWAP: Transfer manual control to Opponent (P2), and assign CPU AI to Player 1
		Logcat("EngineLevelSwap: Transferring manual control to Opponent (P2), assigning AI to P1")

		// 1. Assign AI control to P1
		sys.aiLevel[0] = defaultDiff
		for _, ch := range sys.chars[0] {
			ch.controller = ^ch.playerNo // bitwise NOT indicates AI control in Ikemen-GO
		}

		// 2. Assign Manual control to P2
		sys.aiLevel[1] = 0
		for _, ch := range sys.chars[1] {
			ch.controller = ch.playerNo // non-negative controller index
		}

		// 3. Remap Controller 0 (the mobile virtual controller) to Player 2
		sys.inputRemap[1] = 0 // Player 2 reads from Controller 0
		sys.inputRemap[0] = 1 // Player 1 mapped to Controller 1

		isControlSwapped.Store(true)
	} else {
		// RESTORE: Restore manual control to Player 1, and restore CPU AI to Opponent (P2)
		Logcat("EngineLevelSwap: Restoring manual control to Player 1, restoring AI to Opponent")

		// 1. Restore Manual control to P1
		sys.aiLevel[0] = 0
		for _, ch := range sys.chars[0] {
			ch.controller = ch.playerNo
		}

		// 2. Restore AI control to P2
		sys.aiLevel[1] = defaultDiff
		for _, ch := range sys.chars[1] {
			ch.controller = ^ch.playerNo
		}

		// 3. Restore default input routing (Controller 0 -> Player 1)
		sys.inputRemap[0] = 0
		sys.inputRemap[1] = 1

		isControlSwapped.Store(false)
	}

	return true
}

// EngineLevelSwitchTeamMember rotates the active point fighter to the next living reserve partner.
func EngineLevelSwitchTeamMember(teamSide int) bool {
	swapMutex.Lock()
	defer swapMutex.Unlock()

	side := teamSide
	if side < 0 || side > 1 {
		side = 0 // Default to Player's own team (Team 1)
	}

	// 1. Strict Team Mode verification: Only TM_Tag supports active in-match member switching
	if sys.tmode[side] != TM_Tag {
		Logcat(fmt.Sprintf("EngineLevelSwitchTeamMember: Rejected - Team side %d is not in Tag mode (tmode=%d)", side, sys.tmode[side]))
		return false
	}

	leaderIdx := sys.teamLeader[side]
	if leaderIdx < 0 || leaderIdx >= len(sys.chars) || len(sys.chars[leaderIdx]) == 0 {
		Logcat("EngineLevelSwitchTeamMember: Rejected - Active leader slot is empty")
		return false
	}
	leader := sys.chars[leaderIdx][0]

	// 2. Find the next living reserve partner on the player's team
	team := leader.getTeamOrder()
	var nextLeaderPN int = -1
	for i := 1; i < len(team); i++ {
		pIdx := team[i]
		if pIdx >= 0 && pIdx < len(sys.chars) && len(sys.chars[pIdx]) > 0 {
			if sys.chars[pIdx][0].alive() {
				nextLeaderPN = pIdx
				break
			}
		}
	}

	if nextLeaderPN == -1 {
		Logcat("EngineLevelSwitchTeamMember: Rejected - No eligible living reserve partner on team")
		return false
	}

	// 3. Execute genuine engine team leader change
	leader.changeTagLeader(nextLeaderPN)
	Logcat(fmt.Sprintf("EngineLevelSwitchTeamMember: Successfully switched team leader to player index %d", nextLeaderPN))
	return true
}

//export Java_com_example_engine_bridge_IkemenBridge_nativeSwapPlayerControl
func Java_com_example_engine_bridge_IkemenBridge_nativeSwapPlayerControl(env *C.JNIEnv, clazz C.jclass) C.jboolean {
	if EngineLevelSwapPlayerControl() {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_example_engine_bridge_IkemenBridge_nativeSwitchTeamMember
func Java_com_example_engine_bridge_IkemenBridge_nativeSwitchTeamMember(env *C.JNIEnv, clazz C.jclass, teamSide C.jint) C.jboolean {
	if EngineLevelSwitchTeamMember(int(teamSide)) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_example_engine_bridge_IkemenBridge_nativeGetMatchTeamMode
func Java_com_example_engine_bridge_IkemenBridge_nativeGetMatchTeamMode(env *C.JNIEnv, clazz C.jclass) C.jint {
	return C.jint(sys.tmode[0])
}

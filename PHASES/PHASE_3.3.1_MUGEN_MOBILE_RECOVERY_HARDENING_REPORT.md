# PHASE 3.3.1 — MUGEN Mobile Workspace Save/Restore Recovery Hardening

**Date:** 2026-10-20  
**Status:** `IMPLEMENTED` — source-level checks performed; Gradle test execution is `BLOCKED` in the current environment.  
**Scope:** Character workspace, immutable Vault, save/restore recovery, editor save entry points, and automated regression tests only.

## 1. Scope and protected assets

This is a follow-up to PHASE 3.3, not PHASE 3.4 and not PHASE 4. The working copy was made from the user's `MUGEN-MOBILE.zip`; test sources from `MUGEN-MOBILE-TEST.zip` were integrated under the app's standard Gradle test source sets.

The following assets were not changed:

- `app/src/main/jniLibs/arm64-v8a/libmain.so` — SHA-256 remains `3b841d8223b42455fc144cc96744892a076188207297686d64d2fc71453499`.
- `app/src/main/engine-src/engine_control_swap.go` — SHA-256 remains `0ef64d41605cdd85280d972ae7375db6b54bbeeedcc42036c90201f15ea5443a`.
- The only original file contents changed in this repair pass are the four source files listed below and `PHASES/INDEX.md` (this phase's index entry). Every other original file's contents were byte-for-byte unchanged in the working-copy comparison. The `gradlew` executable permission was enabled for Unix-like environments; its script contents were not changed. Added test and report files are listed separately in this report.

The old `m5ham3ds/Ikemen-GO` repository was not accessed or modified. No native binary or TAG/SWAP implementation was changed.

## 2. Changes implemented

### 2.1 Vault snapshot and integrity handling

File: `app/src/main/java/com/example/engine/workspace/CharacterWorkspaceManager.kt`

- An existing manifest is now treated as immutable: if it exists and integrity verification fails, snapshot capture refuses to replace the damaged Vault from the mutable workspace.
- A Vault payload directory with files but no manifest is treated as an orphaned recovery case; snapshot capture refuses to overwrite that payload.
- Integrity validation checks manifest identity, non-empty fingerprints, safe relative paths, valid SHA-256 metadata and non-negative sizes, per-file size/hash, exact Vault file set, and fingerprint presence for essential files.
- Initial `CharacterMetadata` creation now uses the atomic metadata writer rather than direct `writeText`.

### 2.2 Fail-closed save guards

Files:

- `app/src/main/java/com/example/engine/workspace/CharacterWorkspaceManager.kt`
- `app/src/main/java/com/example/engine/DefEditor.kt`
- `app/src/main/java/com/example/engine/MugenParser.kt`
- `app/src/main/java/com/example/ui/screens/characters/CompleteCharacterEditorDialog.kt`

- Save paths reject unsafe character IDs and file paths escaping the active `chars/<characterId>` workspace.
- Save is rejected when workspace metadata/status reports `CORRUPTED`, when any unresolved `.pre_atomic_bak` is found anywhere under the character workspace, or when an existing Vault fails integrity verification.
- The multi-file save records and verifies backups before starting replacement; the attempted target is registered before operations that may move it. Replacements use staged files and rename operations, not direct byte-copy over the live target.
- Committed file contents are checked against the expected length and SHA-256 before metadata is committed. If a later stage fails, the transaction tries to roll back in reverse order and verifies restored hashes.
- The single-file compatibility API `saveCharacterFileSafe` now checks the same ID/workspace/Vault protections and refuses to use an unverified backup for recovery. If backup creation fails before commit, it does not restore from that potentially partial backup.
- `DefEditor.save()` and `CnsEditor.save()` no longer write edited text directly. They route through `CharacterWorkspaceManager` and throw on an unsuccessful safe save. Their compatibility entry points require a context and a file directly inside a managed character directory.
- The UI Save button is disabled when the Vault is unhealthy or the character workspace is marked `CORRUPTED`.

### 2.3 Atomic metadata update and double-failure handling

File: `app/src/main/java/com/example/engine/workspace/CharacterWorkspaceManager.kt`

- Metadata writes use a synchronized temp file (`flush` + `fd.sync`), a previous-byte copy verified by size/hash, staged replacement, and post-write verification.
- If the new metadata write fails, the writer attempts to restore and verify the previous metadata bytes. If that recovery also fails, it raises a distinct `AtomicWriteRecoveryException` instead of propagating an ordinary save failure.
- The calling save/restore paths then preserve the previous metadata copy and create a durable `.characterId.recovery_required` marker. `getCharacterStatus` returns `CORRUPTED` while the marker exists, preventing further editing from proceeding as though recovery had succeeded.
- A successful Vault-based restore rewrites metadata, clears the marker, and best-effort removes stale metadata rollback copies that were superseded by the verified restore.
- If updating `CORRUPTED` status itself fails, the code attempts to preserve the durable recovery marker.

### 2.4 Whole-directory restore and rollback

File: `app/src/main/java/com/example/engine/workspace/CharacterWorkspaceManager.kt`

- Restore validates the Vault before touching the workspace and stages every manifest file, rechecking each size and SHA-256.
- The staged file set must exactly match the Vault manifest before commit.
- The current character directory is secured as a whole before replacement, including extra/untracked files that are not present in the Vault.
- The new workspace directory is verified against the exact expected file set and hashes. A successful restore no longer leaves unvaulted files in the active character folder.
- If commit or metadata update fails, restore tries to reinstate the entire prior workspace tree and verifies file hashes. If rollback fails, the emergency restore directory is preserved and the character is marked `CORRUPTED` (or marked through the recovery sentinel if metadata cannot be safely updated).
- A successful restore is the recovery path for a character whose workspace status is corrupted, provided the Vault itself is healthy.

### 2.5 Tests integrated and strengthened

The supplied test sources were placed under:

- `app/src/test/java/com/example/` — 14 Kotlin test source files, currently containing **123 `@Test` annotations**.
- `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt` — **1 instrumented test**.

Coverage now includes Vault-corruption save rejection, attempts to overwrite an existing damaged Vault, single-file compatibility API protections, blocked edits for `CORRUPTED` workspaces, exact workspace file-set verification after restore, rollback restoring workspace-only files, failure of metadata commit during restore, and a metadata-write + metadata-rollback double failure that must preserve prior metadata and block editing until restore.

Three new unit tests were added in this repair pass:

1. `testMetadataDoubleFailurePreservesPreviousMetadataAndBlocksFurtherEditsUntilRestore`
2. `testRestoreMetadataWriteFailureRollsBackExactWorkspaceAndMetadata`
3. `testNestedEmergencyBackupBlocksSaveAndIsRemovedOnlyByVerifiedRestore`

Existing tests were also strengthened to assert direct save-path behavior and exact rollback file-set/hash preservation. A malformed screenshot artifact from the test ZIP (`test/screenshots/greeting.png`, invalid PNG signature) was not copied into the project; the screenshot test generates that file at runtime, and the output directory is retained via `.gitkeep`.

## 3. Validation performed in this environment

| Check | Result | Evidence / limitation |
|---|---|---|
| Original project ZIP integrity | `PASS` | `ZipFile.testzip()` returned `None` for `MUGEN-MOBILE.zip`. |
| Test ZIP integrity | `PASS` | `ZipFile.testzip()` returned `None` for `MUGEN-MOBILE-TEST.zip`. |
| Integration of test source files | `PASS` | 14 unit-test Kotlin files and one Android instrumentation test found in expected source sets. |
| Unit test annotation inventory | `PASS` (inventory only) | 123 `@Test` annotations found under `app/src/test/java`. This is not a test execution result. |
| Kotlin syntax/parser pass on changed manager/editor/UI and relevant unit-test sources | `PASS` for syntax parsing only | `kotlinc` emitted no parser-style diagnostics (`expecting`, `unexpected tokens`, etc.). Full compilation could not be completed standalone because Android/project/JUnit dependencies are not available to the standalone compiler invocation. |
| Protected engine assets | `PASS` | SHA-256 values of `libmain.so` and `engine_control_swap.go` match the uploaded project's values exactly. |
| Gradle unit tests | `BLOCKED` | `./gradlew` needed Gradle `9.3.1`; the wrapper download failed with `UnknownHostException: services.gradle.org`. No unit-test pass/fail count was generated in this environment. |
| Android instrumented test | `NOT RUN` | Requires a working Android Gradle build and compatible emulator/device. |
| Real `libmain.so` character-load/match test | `NOT RUN` | No actual engine match was run. Source-level path consistency must not be treated as runtime proof. |

`gradlew` has been marked executable for Unix-like environments. The final test command attempted here was `./gradlew testDebugUnitTest --no-daemon --stacktrace`; the wrapper attempted to acquire the missing Gradle 9.3.1 distribution, then stopped with `UnknownHostException: services.gradle.org`. An earlier `bash gradlew testDebugUnitTest --offline --no-daemon` attempt hit the same distribution-fetch blocker. No unit-test execution result was generated.

## 4. Remaining validation and blockers

1. Run the actual Gradle build and unit suite in an environment with Gradle 9.3.1 available and repository access: `./gradlew testDebugUnitTest --no-daemon --stacktrace`.
2. Run the instrumentation test only after a compatible Android emulator/device and successful APK build are available: `./gradlew connectedDebugAndroidTest --no-daemon --stacktrace`.
3. Test the app's editor UI and engine behavior. Use a disposable character/workspace, change a supported `.def` field and `.cns` value, save, restart/reload the game, and verify the engine actually consumes the workspace copy. Check reset confirmation and recovery from a deliberately injected save/restore failure in a non-production fixture.
4. If Google AI Studio can build/preview this imported project, it may be used for an initial APK/UI attempt. Its documented Android Build limitations include single-activity/single-module support and no NDK/native-code support; therefore compatibility with this app's prebuilt engine library is uncertain. The agent must report actual tasks/results and must not claim tests passed if the environment cannot run Gradle or the native engine.

## 5. Scope exclusions

- No PHASE 3.4 engine load-path verification was started.
- No PHASE 4 or automatic balancing work was started.
- No changes were made to TAG/SWAP, `libmain.so`, original-source content, or the separate old engine fork.
- This report records source changes and checks performed for PHASE 3.3.1 only; it does not supersede or rewrite the historical reports.

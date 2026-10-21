package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.engine.workspace.CharacterWorkspaceStatus
import com.example.engine.workspace.CharacterMetadata
import com.example.storage.MugenMobileStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Test Suite for PHASE 3 — Complete Mobile Character Editor.
 *
 * Verifies:
 * 1. Field reading, parsing, and comment preservation across .def and .cns.
 * 2. Multi-tier validation rules for health, scales, coordinates, and localcoord.
 * 3. Cancelation discarding without disk mutations.
 * 4. Audit status enforcement: Reset Character is forbidden if vault is missing or corrupted.
 * 5. Bit-for-bit immutability of Original Sources throughout editing and restoration.
 * 6. Protection against direct writes bypassing CharacterWorkspaceManager.
 * 7. Dependent files inspection accuracy (sizes, presence, missing status).
 * 8. Legacy character discovery and safe separation prior to editing.
 * 9. Combined atomic save and metadata version increments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteCharacterEditorTest {

    private lateinit var context: Context
    private lateinit var tempGameDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempGameDir = File(context.cacheDir, "test_game_editor_${System.currentTimeMillis()}").apply { mkdirs() }
        MugenMobileStorage.setCustomBaseDir(context, tempGameDir.absolutePath)
        CharacterWorkspaceManager.clearRegisteredSourcesForTesting()

        val vaultDir = CharacterWorkspaceManager.getVaultDir(context)
        if (vaultDir.exists()) vaultDir.deleteRecursively()
        val metaDir = CharacterWorkspaceManager.getMetadataDir(context)
        if (metaDir.exists()) metaDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        CharacterWorkspaceManager.clearRegisteredSourcesForTesting()
        tempGameDir.deleteRecursively()
        val vaultDir = CharacterWorkspaceManager.getVaultDir(context)
        if (vaultDir.exists()) vaultDir.deleteRecursively()
        val metaDir = CharacterWorkspaceManager.getMetadataDir(context)
        if (metaDir.exists()) metaDir.deleteRecursively()
    }

    private fun createSampleCharacter(baseFolder: File, charName: String): File {
        val charDir = File(baseFolder, charName).apply { mkdirs() }
        val defContent = """
            ; Character Definition File
            ; Author: Test Author
            [Info]
            name = "$charName"
            displayname = "$charName Fighter"
            versiondate = "01/01/2026"
            mugenversion = "1.0"
            author = "TestAuthor"
            localcoord = 320,240
            
            [Files]
            cmd = $charName.cmd
            cns = $charName.cns
            anim = $charName.air
            sprite = $charName.sff
            sound = $charName.snd
            st = $charName.cns
            
            ; Custom unknown section that must be preserved
            [Arcade]
            intro.storyboard = 
            ending.storyboard = 
        """.trimIndent()
        File(charDir, "$charName.def").writeText(defContent)

        val cnsContent = """
            ; Character Constants and State File
            ; Elecbyte format
            [Data]
            life = 1000 ; Starting vitality
            attack = 100 ; Normal attack multiplier
            defence = 100 ; Normal defence multiplier
            fall.defence_up = 50
            airjuggle = 15
            sparkno = 2
            guard.sparkno = 40
            
            [Size]
            xscale = 1 ; Horizontal scale
            yscale = 1 ; Vertical scale
            ground.back = 15
            ground.front = 16
            air.back = 12
            air.front = 12
            head.pos = -5, -90
            mid.pos = -5, -60
            shadowoffset = 0
            
            [Velocity]
            walk.fwd = 2.4
            walk.back = -2.2
            run.fwd = 6.5, 0
            run.back = -4.5, -3.8
            jump.neu = 0, -8.4
            jump.back = -2.55
            jump.fwd = 2.5
            
            [Movement]
            airjump.num = 1
            airjump.height = 35
            yaccel = 0.44
            stand.friction = 0.85
            crouch.friction = 0.82
            
            ; State section must be completely untouched
            [Statedef 0]
            type = S
            physics = S
            sprpriority = 0
        """.trimIndent()
        File(charDir, "$charName.cns").writeText(cnsContent)

        File(charDir, "$charName.cmd").writeText("; command file\n[Command]\nname=\"a\"\ncommand=a")
        File(charDir, "$charName.air").writeText("; animation file\n[Begin Action 0]\n0,0, 0,0, -1")
        File(charDir, "$charName.sff").writeBytes(ByteArray(128) { 0x42 })

        return charDir
    }

    @Test
    fun testDefEditorParsesAllSupportedFieldsAndPreservesComments() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "DefTestGuy")
        val defFile = File(charDir, "DefTestGuy.def")

        // 1. Read fields
        val defEditor = DefEditor(defFile, context)
        assertEquals("DefTestGuy Fighter", defEditor.getProperty("info", "displayname"))
        assertEquals("TestAuthor", defEditor.getProperty("info", "author"))
        assertEquals("01/01/2026", defEditor.getProperty("info", "versiondate"))
        assertEquals("1.0", defEditor.getProperty("info", "mugenversion"))
        assertEquals("320,240", defEditor.getProperty("info", "localcoord"))

        // 2. Modify fields
        defEditor.setProperty("info", "displayname", "Super Def Fighter")
        defEditor.setProperty("info", "author", "NewChampion")
        defEditor.setProperty("info", "localcoord", "1280,720")

        // 3. Save via CharacterWorkspaceManager
        val saveResult = CharacterWorkspaceManager.saveDefChanges(defEditor, "DefTestGuy", context)
        assertTrue(saveResult.success)

        // 4. Verify comments and unknown sections are preserved on disk
        val savedText = defFile.readText()
        assertTrue("Comment header must be preserved", savedText.contains("; Character Definition File"))
        assertTrue("Unknown section must be preserved", savedText.contains("[Arcade]"))
        assertTrue("Intro storyboard key preserved", savedText.contains("intro.storyboard"))
        assertTrue("Updated displayname present", savedText.contains("displayname = \"Super Def Fighter\""))
        assertTrue("Updated author present", savedText.contains("author = \"NewChampion\""))
        assertTrue("Updated localcoord present", savedText.contains("localcoord = 1280,720"))
    }

    @Test
    fun testCnsEditorParsesAllFourSectionsAndPreservesFormatting() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "CnsTestGuy")
        val cnsFile = File(charDir, "CnsTestGuy.cns")

        val cnsEditor = CnsEditor(cnsFile, context)

        // 1. Verify reading across all 4 sections
        assertEquals("1000", cnsEditor.getProperty("[data]", "life"))
        assertEquals("100", cnsEditor.getProperty("[data]", "attack"))
        assertEquals("100", cnsEditor.getProperty("[data]", "defence"))
        assertEquals("50", cnsEditor.getProperty("[data]", "fall.defence_up"))
        assertEquals("1", cnsEditor.getProperty("[size]", "xscale"))
        assertEquals("-5, -90", cnsEditor.getProperty("[size]", "head.pos"))
        assertEquals("2.4", cnsEditor.getProperty("[velocity]", "walk.fwd"))
        assertEquals("1", cnsEditor.getProperty("[movement]", "airjump.num"))
        assertEquals("0.85", cnsEditor.getProperty("[movement]", "stand.friction"))

        // 2. Modify values across sections
        cnsEditor.setProperty("[data]", "life", "2500")
        cnsEditor.setProperty("[data]", "attack", "150")
        cnsEditor.setProperty("[size]", "xscale", "1.25")
        cnsEditor.setProperty("[velocity]", "walk.fwd", "3.0")
        cnsEditor.setProperty("[movement]", "airjump.num", "2")

        val saveResult = CharacterWorkspaceManager.saveCnsChanges(cnsEditor, "CnsTestGuy", context)
        assertTrue(saveResult.success)

        // 3. Verify disk file has preserved comments and custom state definitions
        val savedText = cnsFile.readText()
        assertTrue(savedText.contains("life = 2500 ; Starting vitality"))
        assertTrue(savedText.contains("attack = 150 ; Normal attack multiplier"))
        assertTrue(savedText.contains("xscale = 1.25 ; Horizontal scale"))
        assertTrue(savedText.contains("walk.fwd = 3.0"))
        assertTrue(savedText.contains("airjump.num = 2"))
        assertTrue("State definitions must remain intact", savedText.contains("[Statedef 0]"))
        assertTrue("Physics key must remain intact", savedText.contains("physics = S"))
    }

    @Test
    fun testCharacterEditorValidatorFieldRules() {
        // Life validation
        assertTrue(CharacterEditorValidator.validateLife("1000").isValid)
        assertTrue(CharacterEditorValidator.validateLife(" 2500 ").isValid)
        assertFalse(CharacterEditorValidator.validateLife("0").isValid)
        assertFalse(CharacterEditorValidator.validateLife("-10").isValid)
        assertFalse(CharacterEditorValidator.validateLife("abc").isValid)
        assertFalse(CharacterEditorValidator.validateLife("200000").isValid)

        // Attack & Defence validation
        assertTrue(CharacterEditorValidator.validateAttack("120").isValid)
        assertFalse(CharacterEditorValidator.validateAttack("-5").isValid)
        assertTrue(CharacterEditorValidator.validateDefence("100").isValid)
        assertFalse(CharacterEditorValidator.validateDefence("0").isValid)

        // Scale validation
        assertTrue(CharacterEditorValidator.validateScale("1.0", "xscale").isValid)
        assertTrue(CharacterEditorValidator.validateScale("0.75", "yscale").isValid)
        assertFalse(CharacterEditorValidator.validateScale("0", "xscale").isValid)
        assertFalse(CharacterEditorValidator.validateScale("-1.5", "yscale").isValid)
        assertFalse(CharacterEditorValidator.validateScale("invalid", "xscale").isValid)

        // Localcoord validation
        assertTrue(CharacterEditorValidator.validateLocalcoord("320,240").isValid)
        assertTrue(CharacterEditorValidator.validateLocalcoord(" 1280 , 720 ").isValid)
        assertTrue(CharacterEditorValidator.validateLocalcoord("").isValid) // Blank is allowed
        assertFalse(CharacterEditorValidator.validateLocalcoord("320").isValid)
        assertFalse(CharacterEditorValidator.validateLocalcoord("-320, 240").isValid)
        assertFalse(CharacterEditorValidator.validateLocalcoord("abc, def").isValid)

        // Coordinate pair validation
        assertTrue(CharacterEditorValidator.validateCoordinates("-5, -90", "head.pos").isValid)
        assertTrue(CharacterEditorValidator.validateCoordinates("0, 0", "mid.pos").isValid)
        assertFalse(CharacterEditorValidator.validateCoordinates("10", "head.pos").isValid)
        assertFalse(CharacterEditorValidator.validateCoordinates("a, b", "head.pos").isValid)
    }

    @Test
    fun testCancellingEditsLeavesAllFilesUntouched() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "CancelGuy")
        val defFile = File(charDir, "CancelGuy.def")
        val cnsFile = File(charDir, "CancelGuy.cns")

        val initialDefSha = CharacterWorkspaceManager.calculateSha256(defFile)
        val initialCnsSha = CharacterWorkspaceManager.calculateSha256(cnsFile)

        // Simulate opening editors and changing in-memory state
        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "Altered But Cancelled")
        cnsEditor.setProperty("[data]", "life", "99999")

        // User cancels: NO save is called!

        // Verify files on disk remain 100% bit-for-bit identical
        assertEquals(initialDefSha, CharacterWorkspaceManager.calculateSha256(defFile))
        assertEquals(initialCnsSha, CharacterWorkspaceManager.calculateSha256(cnsFile))
    }

    @Test
    fun testAuditStatusPreventsResetWhenVaultIsCorruptedOrMissing() {
        // Case 1: Character with no vault manifest
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        createSampleCharacter(charsWorkspace, "NoVaultChar")
        val statusNoVault = CharacterWorkspaceManager.getCharacterAuditStatus("NoVaultChar", context)
        assertFalse("Cannot reset character without vault", statusNoVault.canResetToPristine)
        assertFalse("Vault is not healthy when missing", statusNoVault.isVaultHealthy)
        assertEquals("غير موثقة المصدر", statusNoVault.statusBadgeTitle)

        // Case 2: Character with corrupted vault
        val charDir2 = createSampleCharacter(charsWorkspace, "CorruptedVaultChar")
        val snapshot = CharacterWorkspaceManager.capturePristineSnapshot("CorruptedVaultChar", charDir2, context)
        assertTrue(snapshot.success)

        // Tamper with vault file
        val vaultCns = File(File(CharacterWorkspaceManager.getVaultDir(context), "CorruptedVaultChar"), "files/CorruptedVaultChar.cns")
        vaultCns.setWritable(true)
        vaultCns.writeText("; Corrupted vault payload")

        val statusCorrupted = CharacterWorkspaceManager.getCharacterAuditStatus("CorruptedVaultChar", context)
        assertFalse("Cannot reset when vault is corrupted", statusCorrupted.canResetToPristine)
        assertFalse("Vault marked unhealthy on hash mismatch", statusCorrupted.isVaultHealthy)
        assertEquals(CharacterWorkspaceStatus.CORRUPTED, statusCorrupted.workspaceStatus)
        assertEquals("خزينة غير صالحة", statusCorrupted.statusBadgeTitle)

        // Case 3: Verified healthy character
        val charDir3 = createSampleCharacter(charsWorkspace, "HealthyChar")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir3, context)
        val statusHealthy = CharacterWorkspaceManager.getCharacterAuditStatus("HealthyChar", context)
        assertTrue("Can reset when vault is healthy", statusHealthy.canResetToPristine)
        assertTrue("Vault is healthy", statusHealthy.isVaultHealthy)
    }

    @Test
    fun testImmutabilityOfOriginalSourceThroughoutEditorLifecycle() {
        val sourcesDir = CharacterWorkspaceManager.getSourcesDir(context)
        val sourceCharDir = createSampleCharacter(sourcesDir, "LifecycleGuy")
        val importResult = CharacterWorkspaceManager.importCharacter(sourceCharDir, "LifecycleGuy", context)
        assertTrue(importResult.success)

        val sourceDef = File(sourceCharDir, "LifecycleGuy.def")
        val sourceCns = File(sourceCharDir, "LifecycleGuy.cns")
        val initialDefSha = CharacterWorkspaceManager.calculateSha256(sourceDef)
        val initialCnsSha = CharacterWorkspaceManager.calculateSha256(sourceCns)

        val workspaceFolder = importResult.workspaceFolder!!
        val workspaceDef = File(workspaceFolder, "LifecycleGuy.def")
        val workspaceCns = File(workspaceFolder, "LifecycleGuy.cns")

        // 1. Modify both .def and .cns via editor
        val defEditor = DefEditor(workspaceDef, context)
        val cnsEditor = CnsEditor(workspaceCns, context)

        defEditor.setProperty("info", "displayname", "Champion Lifecycle")
        cnsEditor.setProperty("[data]", "life", "3800")
        cnsEditor.setProperty("[data]", "attack", "175")

        val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
            characterId = "LifecycleGuy",
            defEditor = defEditor,
            cnsEditor = cnsEditor,
            context = context
        )
        assertTrue(saveResult.success)

        // Verify workspace files changed
        assertNotEquals(initialDefSha, CharacterWorkspaceManager.calculateSha256(workspaceDef))
        assertNotEquals(initialCnsSha, CharacterWorkspaceManager.calculateSha256(workspaceCns))

        // Verify Original Sources remain 100% BIT-FOR-BIT UNTOUCHED
        assertEquals("Source def must remain untouched after edit", initialDefSha, CharacterWorkspaceManager.calculateSha256(sourceDef))
        assertEquals("Source cns must remain untouched after edit", initialCnsSha, CharacterWorkspaceManager.calculateSha256(sourceCns))

        // 2. Restore character
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("LifecycleGuy", context)
        assertTrue(restoreResult.success)

        // Workspace files restored
        assertEquals(initialDefSha, CharacterWorkspaceManager.calculateSha256(workspaceDef))
        assertEquals(initialCnsSha, CharacterWorkspaceManager.calculateSha256(workspaceCns))

        // Original Sources STILL 100% BIT-FOR-BIT UNTOUCHED
        assertEquals("Source def must remain untouched after restore", initialDefSha, CharacterWorkspaceManager.calculateSha256(sourceDef))
        assertEquals("Source cns must remain untouched after restore", initialCnsSha, CharacterWorkspaceManager.calculateSha256(sourceCns))
    }

    @Test
    fun testDefEditorAndCnsEditorDirectSaveRejectsProtectedPaths() {
        val sourcesDir = CharacterWorkspaceManager.getSourcesDir(context)
        val sourceCharDir = createSampleCharacter(sourcesDir, "DirectSaveForbiddenGuy")
        val sourceDef = File(sourceCharDir, "DirectSaveForbiddenGuy.def")
        val sourceCns = File(sourceCharDir, "DirectSaveForbiddenGuy.cns")

        val defEditor = DefEditor(sourceDef, context)
        defEditor.setProperty("info", "displayname", "Hacked Title")

        var defSecurityException = false
        try {
            defEditor.save()
        } catch (_: SecurityException) {
            defSecurityException = true
        }
        assertTrue("Direct save on source def must throw SecurityException", defSecurityException)

        val cnsEditor = CnsEditor(sourceCns, context)
        cnsEditor.setProperty("[data]", "life", "99999")

        var cnsSecurityException = false
        try {
            cnsEditor.save()
        } catch (_: SecurityException) {
            cnsSecurityException = true
        }
        assertTrue("Direct save on source cns must throw SecurityException", cnsSecurityException)
    }

    @Test
    fun testDependentFilesInspectionReportsAccurateSizesAndMissingStatus() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "DepInspectorGuy")
        val defFile = File(charDir, "DepInspectorGuy.def")

        // Delete .snd to simulate missing audio file
        val sndFile = File(charDir, "DepInspectorGuy.snd")
        if (sndFile.exists()) sndFile.delete()

        val defEditor = DefEditor(defFile, context)
        val deps = defEditor.getDependentFiles()

        val sffDep = deps.find { it.key == "sprite" }
        assertNotNull(sffDep)
        assertTrue("Sprite file should exist", sffDep!!.exists)
        assertTrue("Sprite size should be > 0", sffDep.sizeBytes > 0)

        val sndDep = deps.find { it.key == "sound" }
        assertNotNull(sndDep)
        assertFalse("Sound file should be flagged as missing", sndDep!!.exists)
        assertEquals(0L, sndDep.sizeBytes)
    }

    @Test
    fun testFullCombinedSaveUpdatesMetadataAndVersion() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "VersionGuy")
        val defFile = File(charDir, "VersionGuy.def")
        val cnsFile = File(charDir, "VersionGuy.cns")

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "Version Hero")
        cnsEditor.setProperty("[data]", "life", "1800")

        val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
            characterId = "VersionGuy",
            defEditor = defEditor,
            cnsEditor = cnsEditor,
            context = context
        )
        assertTrue(saveResult.success)

        val meta = CharacterWorkspaceManager.getCharacterMetadata("VersionGuy", context)
        assertNotNull(meta)
        assertEquals(CharacterWorkspaceStatus.MODIFIED, meta!!.status)
        assertEquals("Version must increment by exactly 1 in a combined transaction", 2, meta.version)
        assertTrue("History should record changes", meta.changeHistory.isNotEmpty())
    }

    @Test
    fun testAtomicMultiFileSaveRollbackWhenSecondFileFailsLeavesCharacterIntact() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "AtomicRollbackGuy")
        val defFile = File(charDir, "AtomicRollbackGuy.def")
        val cnsFile = File(charDir, "AtomicRollbackGuy.cns")

        // Capture baseline state
        val originalDefSha = CharacterWorkspaceManager.calculateSha256(defFile)
        val originalCnsSha = CharacterWorkspaceManager.calculateSha256(cnsFile)
        val originalDefText = defFile.readText()
        val originalCnsText = cnsFile.readText()

        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val initialMeta = CharacterWorkspaceManager.getCharacterMetadata("AtomicRollbackGuy", context)
        val initialVersion = initialMeta?.version ?: 1

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "This Should Be Rolled Back")
        cnsEditor.setProperty("[data]", "life", "99999")

        // Simulate failure on the CNS file during commit by making cnsFile a non-empty directory
        // In Linux and Java 21, renaming/replacing a file over a non-empty directory throws DirectoryNotEmptyException
        cnsFile.delete()
        cnsFile.mkdirs()
        File(cnsFile, "blocking_child.bin").writeText("cannot overwrite non-empty dir")

        val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
            characterId = "AtomicRollbackGuy",
            defEditor = defEditor,
            cnsEditor = cnsEditor,
            context = context
        )

        // 1. Transaction must report failure
        assertFalse("Atomic multi-file save must return failure when second file fails", saveResult.success)

        // 2. Def file must have been rolled back to its exact pre-save state (NO half-saved state!)
        assertEquals("Def file must be rolled back to pre-save SHA-256", originalDefSha, CharacterWorkspaceManager.calculateSha256(defFile))
        assertEquals("Def file content must match pre-save text exactly", originalDefText, defFile.readText())

        // 3. Metadata must NOT have been updated or version incremented
        val afterMeta = CharacterWorkspaceManager.getCharacterMetadata("AtomicRollbackGuy", context)
        assertEquals("Metadata version must not increment on aborted transaction", initialVersion, afterMeta?.version ?: 1)

        // Clean up blocking directory
        cnsFile.deleteRecursively()
        cnsFile.writeText(originalCnsText)
    }

    @Test
    fun testSingleVersionIncrementOnMultiFileSave() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "SingleIncGuy")
        val defFile = File(charDir, "SingleIncGuy.def")
        val cnsFile = File(charDir, "SingleIncGuy.cns")

        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val initialMeta = CharacterWorkspaceManager.getCharacterMetadata("SingleIncGuy", context)
        val v0 = initialMeta?.version ?: 1

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "Single Inc Hero")
        cnsEditor.setProperty("[data]", "life", "3000")
        cnsEditor.setProperty("[size]", "xscale", "1.5")

        val res = CharacterWorkspaceManager.saveCompleteCharacterEdits("SingleIncGuy", defEditor, cnsEditor, context)
        assertTrue(res.success)

        val afterMeta = CharacterWorkspaceManager.getCharacterMetadata("SingleIncGuy", context)
        assertNotNull(afterMeta)
        assertEquals("Version should increment by exactly 1 for a unified multi-file edit", v0 + 1, afterMeta!!.version)
        assertEquals(CharacterWorkspaceStatus.MODIFIED, afterMeta.status)
    }

    @Test
    fun testCharacterAuditStatusReportsCorruptedWhenWorkspaceRollbackFailsOrCorrupted() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "CorruptedAuditGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)

        val metaFile = File(CharacterWorkspaceManager.getMetadataDir(context), "CorruptedAuditGuy.json")
        val meta = CharacterMetadata.fromJson(metaFile.readText())
        meta.status = CharacterWorkspaceStatus.CORRUPTED
        metaFile.writeText(meta.toJson())

        val audit = CharacterWorkspaceManager.getCharacterAuditStatus("CorruptedAuditGuy", context)
        assertEquals(CharacterWorkspaceStatus.CORRUPTED, audit.workspaceStatus)
        assertEquals("حالة مساحة العمل تالفة", audit.statusBadgeTitle)
        assertTrue("Subtitle must explain rollback failure/inconsistency", audit.statusBadgeSubtitle.contains("غير متسقة"))
        assertTrue("Can reset to pristine must still be allowed if vault is healthy", audit.canResetToPristine)
    }

    @Test
    fun testRestoreRollbackFailurePreservesEmergencyBackupAndMarksCorrupted() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "RollbackFailGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        File(charDir, "user_extra_file.tmp").writeText("must be retained in rollback backup")

        CharacterWorkspaceManager.simulateRestoreCommitFailureForTesting = true
        CharacterWorkspaceManager.simulateRollbackFailureForTesting = true
        try {
            val res = CharacterWorkspaceManager.restoreSingleCharacter("RollbackFailGuy", context)
            assertFalse("Injected restore commit failure must be reported", res.success)
            assertTrue("Critical message must report rollback failure", res.errorMessage?.contains("rollback failed") == true)

            val meta = CharacterWorkspaceManager.getCharacterMetadata("RollbackFailGuy", context)
            assertNotNull("Metadata must exist", meta)
            assertEquals("Workspace must be marked CORRUPTED after failed rollback", CharacterWorkspaceStatus.CORRUPTED, meta!!.status)

            val emergencyDirs = charsWorkspace.listFiles { f -> f.isDirectory && f.name.startsWith(".restore_backup_RollbackFailGuy_") }
            assertTrue("Complete pre-restore workspace must be preserved for emergency recovery", !emergencyDirs.isNullOrEmpty())
            val emergencyExtra = emergencyDirs!!.firstOrNull()?.let { File(it, "user_extra_file.tmp") }
            assertTrue("Emergency backup must include workspace-only files", emergencyExtra?.isFile == true)
        } finally {
            CharacterWorkspaceManager.simulateRestoreCommitFailureForTesting = false
            CharacterWorkspaceManager.simulateRollbackFailureForTesting = false
        }

        // A subsequent healthy restore is the recovery path and should remove stale unvaulted files.
        val recovery = CharacterWorkspaceManager.restoreSingleCharacter("RollbackFailGuy", context)
        assertTrue("A healthy vault must allow recovery", recovery.success)
        assertFalse("Unvaulted extra file must not survive successful restore", File(charDir, "user_extra_file.tmp").exists())
        assertEquals(CharacterWorkspaceStatus.RESTORED, CharacterWorkspaceManager.getCharacterStatus("RollbackFailGuy", context))
    }

    @Test
    fun testAtomicMultiFileSaveFailsWhenDefFileReplacementBlocked() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "DefBlockGuy")
        val defFile = File(charDir, "DefBlockGuy.def")
        val cnsFile = File(charDir, "DefBlockGuy.cns")

        val initialCnsSha = CharacterWorkspaceManager.calculateSha256(cnsFile)
        val initialCnsText = cnsFile.readText()
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val initialMeta = CharacterWorkspaceManager.getCharacterMetadata("DefBlockGuy", context)
        val initialVersion = initialMeta?.version ?: 1

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "Def Fail Attempt")
        cnsEditor.setProperty("[data]", "life", "9999")

        // Block defFile replacement by making it a non-empty directory
        val originalDefText = defFile.readText()
        defFile.delete()
        defFile.mkdirs()
        File(defFile, "blocker.bin").writeText("cannot overwrite dir")

        val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
            characterId = "DefBlockGuy",
            defEditor = defEditor,
            cnsEditor = cnsEditor,
            context = context
        )

        // 1. Transaction must report failure
        assertFalse("Multi-file save must return failure when DEF replacement fails", saveResult.success)

        // 2. CNS file must NOT have been changed
        assertEquals("CNS file must remain untouched", initialCnsSha, CharacterWorkspaceManager.calculateSha256(cnsFile))
        assertEquals("CNS content must match pre-save text", initialCnsText, cnsFile.readText())

        // 3. Metadata version must not increment
        val afterMeta = CharacterWorkspaceManager.getCharacterMetadata("DefBlockGuy", context)
        assertEquals("Version must not increment on aborted transaction", initialVersion, afterMeta?.version ?: 1)

        // Clean up blocker
        defFile.deleteRecursively()
        defFile.writeText(originalDefText)
    }

    @Test
    fun testMultiFileSaveRollbackFailurePreservesEmergencyBackupAndMarksCorrupted() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "RollbackFailSaveGuy")
        val defFile = File(charDir, "RollbackFailSaveGuy.def")
        val cnsFile = File(charDir, "RollbackFailSaveGuy.cns")

        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val initialMeta = CharacterWorkspaceManager.getCharacterMetadata("RollbackFailSaveGuy", context)
        val initialVersion = initialMeta?.version ?: 1

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "Altered Def")
        cnsEditor.setProperty("[data]", "life", "5000")

        // Inject a commit failure after the DEF replacement so rollback of a real partial transaction is exercised.
        CharacterWorkspaceManager.simulateCommitFailureAfterReplacementsForTesting = 1
        CharacterWorkspaceManager.simulateRollbackFailureForTesting = true

        try {
            val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
                characterId = "RollbackFailSaveGuy",
                defEditor = defEditor,
                cnsEditor = cnsEditor,
                context = context
            )

            // 1. Must report failure (no false success!)
            assertFalse("Save must fail", saveResult.success)
            assertTrue("Error message must report critical rollback failure",
                saveResult.errorMessage?.contains("CRITICAL: Multi-file atomic save failed AND rollback failed") == true)

            // 2. Emergency backup for defFile must be PRESERVED and NOT deleted!
            val emergencyDefBak = File(charDir, "RollbackFailSaveGuy.def.pre_atomic_bak")
            assertTrue("Pre-atomic backup must be preserved on disk for emergency recovery", emergencyDefBak.exists())

            // 3. Status must be marked CORRUPTED in metadata
            val afterMeta = CharacterWorkspaceManager.getCharacterMetadata("RollbackFailSaveGuy", context)
            assertEquals("Workspace status must be CORRUPTED", CharacterWorkspaceStatus.CORRUPTED, afterMeta?.status)
            assertEquals("Version must not increment", initialVersion, afterMeta?.version)

            // 4. Audit status must report CORRUPTED with correct Arabic badge
            val auditStatus = CharacterWorkspaceManager.getCharacterAuditStatus("RollbackFailSaveGuy", context)
            assertEquals(CharacterWorkspaceStatus.CORRUPTED, auditStatus.workspaceStatus)
            assertEquals("حالة مساحة العمل تالفة", auditStatus.statusBadgeTitle)
            assertTrue(auditStatus.statusBadgeSubtitle.contains("غير متسقة"))
            assertTrue("User can still heal by resetting to vault", auditStatus.canResetToPristine)

        } finally {
            CharacterWorkspaceManager.simulateCommitFailureAfterReplacementsForTesting = null
            CharacterWorkspaceManager.simulateRollbackFailureForTesting = false
        }
    }

    @Test
    fun testAtomicMultiFileSaveRollbackWhenMetadataWriteFails() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "MetaFailGuy")
        val defFile = File(charDir, "MetaFailGuy.def")
        val cnsFile = File(charDir, "MetaFailGuy.cns")

        val initialDefSha = CharacterWorkspaceManager.calculateSha256(defFile)
        val initialCnsSha = CharacterWorkspaceManager.calculateSha256(cnsFile)
        val initialDefText = defFile.readText()
        val initialCnsText = cnsFile.readText()

        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val metaDir = CharacterWorkspaceManager.getMetadataDir(context)
        val metaFile = File(metaDir, "MetaFailGuy.json")
        val initialMetaText = metaFile.readText()
        val initialMeta = CharacterMetadata.fromJson(initialMetaText)

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)

        defEditor.setProperty("info", "displayname", "Meta Fail Def Alteration")
        cnsEditor.setProperty("[data]", "life", "7777")

        // Inject a failure immediately before the atomic metadata commit, after both data files are replaced.
        CharacterWorkspaceManager.simulateMetadataWriteFailureForTesting = true
        try {
            val saveResult = CharacterWorkspaceManager.saveCompleteCharacterEdits(
                characterId = "MetaFailGuy",
                defEditor = defEditor,
                cnsEditor = cnsEditor,
                context = context
            )

            // 1. Must report failure (no false success!)
            assertFalse("Transaction must fail when metadata write fails", saveResult.success)
            assertTrue("Error message must indicate rollback",
                saveResult.errorMessage?.contains("Multi-file atomic save failed and was completely rolled back") == true)

            // 2. Both files must have been rolled back to their exact pre-save state
            assertEquals("Def file must be rolled back", initialDefSha, CharacterWorkspaceManager.calculateSha256(defFile))
            assertEquals("Def text must match initial", initialDefText, defFile.readText())
            assertEquals("Cns file must be rolled back", initialCnsSha, CharacterWorkspaceManager.calculateSha256(cnsFile))
            assertEquals("Cns text must match initial", initialCnsText, cnsFile.readText())

            // 3. Metadata on disk must remain byte-for-byte unchanged, not merely match selected fields.
            val afterMeta = CharacterWorkspaceManager.getCharacterMetadata("MetaFailGuy", context)
            assertEquals("Version must remain unchanged", initialMeta.version, afterMeta?.version)
            assertEquals("Change history must not contain aborted edits", initialMeta.changeHistory.size, afterMeta?.changeHistory?.size)
            assertEquals("Metadata JSON bytes/text must be unchanged after a failed transaction", initialMetaText, metaFile.readText())

            // 4. In-memory editor lines must NOT have been falsely committed
            assertEquals("DefEditor lines must not have been committed", initialDefText, defEditor.lines.joinToString("\n"))

        } finally {
            CharacterWorkspaceManager.simulateMetadataWriteFailureForTesting = false
        }
    }

    @Test
    fun testSaveIsBlockedWhenVaultIntegrityIsCorruptedAndLeavesWorkspaceMetadataUntouched() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "CorruptVaultSaveGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)

        val defFile = File(charDir, "CorruptVaultSaveGuy.def")
        val cnsFile = File(charDir, "CorruptVaultSaveGuy.cns")
        val originalDef = defFile.readBytes()
        val originalCns = cnsFile.readBytes()
        val metadataFile = File(CharacterWorkspaceManager.getMetadataDir(context), "CorruptVaultSaveGuy.json")
        val originalMetadata = metadataFile.readBytes()

        val vaultCns = File(File(CharacterWorkspaceManager.getVaultDir(context), "CorruptVaultSaveGuy"), "files/CorruptVaultSaveGuy.cns")
        vaultCns.setWritable(true)
        vaultCns.writeText("tampered vault content")
        vaultCns.setReadOnly()

        val rewriteAttempt = CharacterWorkspaceManager.capturePristineSnapshot("CorruptVaultSaveGuy", charDir, context)
        assertFalse("An existing corrupted vault must never be silently overwritten from mutable workspace", rewriteAttempt.success)
        assertEquals("The corrupted vault payload must be preserved for forensic recovery", "tampered vault content", vaultCns.readText())

        val defEditor = DefEditor(defFile, context)
        val cnsEditor = CnsEditor(cnsFile, context)
        defEditor.setProperty("info", "displayname", "must not save")
        cnsEditor.setProperty("[data]", "life", "9999")

        val result = CharacterWorkspaceManager.saveCompleteCharacterEdits("CorruptVaultSaveGuy", defEditor, cnsEditor, context)
        assertFalse("Save must be blocked when vault hashes do not match the manifest", result.success)
        assertTrue(result.errorMessage?.contains("vault integrity check failed") == true)
        assertArrayEquals("DEF workspace bytes must stay untouched", originalDef, defFile.readBytes())
        assertArrayEquals("CNS workspace bytes must stay untouched", originalCns, cnsFile.readBytes())
        assertArrayEquals("Metadata bytes must stay untouched", originalMetadata, metadataFile.readBytes())
        assertFalse("No emergency backup should be created by a rejected save", File(charDir, "CorruptVaultSaveGuy.def.pre_atomic_bak").exists())

        val legacySingleFileSave = CharacterWorkspaceManager.saveCharacterFileSafe(
            characterId = "CorruptVaultSaveGuy",
            relativePath = cnsFile.name,
            updatedContent = cnsFile.readText() + "\n; blocked legacy save\n",
            changes = emptyList(),
            context = context
        )
        assertFalse("Legacy single-file API must refuse a corrupted vault", legacySingleFileSave.success)
        assertArrayEquals("Legacy single-file API must not alter active CNS", originalCns, cnsFile.readBytes())

        val directEditor = CnsEditor(cnsFile, context)
        directEditor.setProperty("[data]", "life", "12345")
        var directSaveBlocked = false
        try {
            directEditor.save()
        } catch (_: Exception) {
            directSaveBlocked = true
        }
        assertTrue("Legacy CnsEditor.save() must route through and obey the vault integrity guard", directSaveBlocked)
        assertArrayEquals("Direct save must not bypass corrupt-vault protection", originalCns, cnsFile.readBytes())
    }

    @Test
    fun testNestedEmergencyBackupBlocksSaveAndIsRemovedOnlyByVerifiedRestore() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "NestedEmergencyBackupGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val nestedBackup = File(charDir, "extra/recovery.cns.pre_atomic_bak").apply {
            parentFile!!.mkdirs()
            writeText("emergency backup")
        }
        assertEquals("An emergency backup anywhere below the character folder must fail closed",
            CharacterWorkspaceStatus.CORRUPTED,
            CharacterWorkspaceManager.getCharacterStatus("NestedEmergencyBackupGuy", context))

        val editor = CnsEditor(File(charDir, "NestedEmergencyBackupGuy.cns"), context)
        editor.setProperty("[data]", "life", "7777")
        val save = CharacterWorkspaceManager.saveCnsChanges(editor, "NestedEmergencyBackupGuy", context)
        assertFalse("Save must be blocked while a nested emergency backup is unresolved", save.success)

        val restore = CharacterWorkspaceManager.restoreSingleCharacter("NestedEmergencyBackupGuy", context)
        assertTrue("Healthy Vault restore should recover the character: ${restore.errorMessage}", restore.success)
        assertFalse("Successful restore must remove the nested unvaulted emergency file", nestedBackup.exists())
        assertEquals(CharacterWorkspaceStatus.RESTORED,
            CharacterWorkspaceManager.getCharacterStatus("NestedEmergencyBackupGuy", context))
    }

    @Test
    fun testSaveIsBlockedForCorruptedWorkspaceUntilVaultRestore() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "CorruptWorkspaceSaveGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val defFile = File(charDir, "CorruptWorkspaceSaveGuy.def")
        val cnsFile = File(charDir, "CorruptWorkspaceSaveGuy.cns")
        val metadataFile = File(CharacterWorkspaceManager.getMetadataDir(context), "CorruptWorkspaceSaveGuy.json")
        val metadata = CharacterMetadata.fromJson(metadataFile.readText()).apply { status = CharacterWorkspaceStatus.CORRUPTED }
        metadataFile.writeText(metadata.toJson())

        val editor = CnsEditor(cnsFile, context)
        editor.setProperty("[data]", "life", "4444")
        val save = CharacterWorkspaceManager.saveCnsChanges(editor, "CorruptWorkspaceSaveGuy", context)
        assertFalse("A corrupted workspace must not accept edits", save.success)
        assertTrue(save.errorMessage?.contains("CORRUPTED") == true)

        val restore = CharacterWorkspaceManager.restoreSingleCharacter("CorruptWorkspaceSaveGuy", context)
        assertTrue("A healthy vault must remain a recovery route: ${restore.errorMessage}", restore.success)
        assertEquals(CharacterWorkspaceStatus.RESTORED, CharacterWorkspaceManager.getCharacterStatus("CorruptWorkspaceSaveGuy", context))
        assertEquals("1000", CnsEditor(cnsFile, context).getProperty("[data]", "life"))
    }

    @Test
    fun testRestoreRemovesUnvaultedWorkspaceFilesAndMatchesExactVaultFileSet() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "ExtraFilesRestoreGuy")
        val snapshot = CharacterWorkspaceManager.capturePristineSnapshot("ExtraFilesRestoreGuy", charDir, context)
        assertTrue(snapshot.success)
        val manifest = CharacterWorkspaceManager.getVaultManifest("ExtraFilesRestoreGuy", context)!!

        File(charDir, "unvaulted.tmp").writeText("must be removed")
        File(charDir, "unexpected/nested.bin").apply { parentFile!!.mkdirs(); writeText("also must be removed") }
        File(charDir, "ExtraFilesRestoreGuy.cns").writeText("[Data]\nlife = 9999")

        val restore = CharacterWorkspaceManager.restoreSingleCharacter("ExtraFilesRestoreGuy", context)
        assertTrue("Restore must succeed: ${restore.errorMessage}", restore.success)
        assertFalse("Root-level unvaulted file must be removed", File(charDir, "unvaulted.tmp").exists())
        assertFalse("Unvaulted nested directory must be removed", File(charDir, "unexpected").exists())
        val actualPaths = charDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(charDir).path }.toSet()
        assertEquals("Restored file paths must exactly match the vault manifest", manifest.files.keys, actualPaths)
        for ((relativePath, fingerprint) in manifest.files) {
            val restoredFile = File(charDir, relativePath)
            assertEquals("Restored SHA-256 must match $relativePath", fingerprint.sha256, CharacterWorkspaceManager.calculateSha256(restoredFile))
        }
    }

    @Test
    fun testRestoreCommitFailureRollsBackEntireWorkspaceIncludingUnvaultedFiles() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "RestoreTransactionGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        File(charDir, "not_in_vault.tmp").writeText("preserve on rollback")
        File(charDir, "nested/extra.dat").apply { parentFile!!.mkdirs(); writeText("nested preserve") }
        val before = charDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(charDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }

        CharacterWorkspaceManager.simulateRestoreCommitFailureForTesting = true
        try {
            val result = CharacterWorkspaceManager.restoreSingleCharacter("RestoreTransactionGuy", context)
            assertFalse("Injected restore failure must fail", result.success)
            assertTrue(result.errorMessage?.contains("previous workspace was recovered") == true)
        } finally {
            CharacterWorkspaceManager.simulateRestoreCommitFailureForTesting = false
        }
        val after = charDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(charDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }
        assertEquals("Rollback must restore the exact previous workspace tree", before, after)
    }

    @Test
    fun testMetadataDoubleFailurePreservesPreviousMetadataAndBlocksFurtherEditsUntilRestore() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "MetadataDoubleFailGuy")
        val defFile = File(charDir, "MetadataDoubleFailGuy.def")
        val cnsFile = File(charDir, "MetadataDoubleFailGuy.cns")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)

        val metadataDir = CharacterWorkspaceManager.getMetadataDir(context)
        val metadataFile = File(metadataDir, "MetadataDoubleFailGuy.json")
        val originalMetadata = metadataFile.readText()
        val originalDefHash = CharacterWorkspaceManager.calculateSha256(defFile)
        val originalCnsHash = CharacterWorkspaceManager.calculateSha256(cnsFile)
        val defEditor = DefEditor(defFile, context).apply { setProperty("info", "displayname", "Must roll back") }
        val cnsEditor = CnsEditor(cnsFile, context).apply { setProperty("[data]", "life", "9999") }

        CharacterWorkspaceManager.simulateMetadataAtomicFailureAfterReplaceForTesting = true
        CharacterWorkspaceManager.simulateMetadataRollbackFailureForTesting = true
        try {
            val result = CharacterWorkspaceManager.saveCompleteCharacterEdits(
                characterId = "MetadataDoubleFailGuy",
                defEditor = defEditor,
                cnsEditor = cnsEditor,
                context = context
            )
            assertFalse("Metadata double failure must never report save success", result.success)
            assertTrue("Message must disclose metadata recovery failure", result.errorMessage?.contains("metadata", ignoreCase = true) == true)
            assertTrue("Durable recovery marker must remain", File(metadataDir, ".MetadataDoubleFailGuy.recovery_required").isFile)
            assertEquals("Active DEF must be rolled back", originalDefHash, CharacterWorkspaceManager.calculateSha256(defFile))
            assertEquals("Active CNS must be rolled back", originalCnsHash, CharacterWorkspaceManager.calculateSha256(cnsFile))
            val previousBackups = metadataDir.listFiles { f -> f.name.startsWith(".MetadataDoubleFailGuy.json.previous_") } ?: emptyArray()
            assertTrue("Previous metadata must be preserved for manual/restore recovery", previousBackups.isNotEmpty())
            assertTrue("At least one preserved previous metadata copy must match the exact original bytes",
                previousBackups.any { it.readText() == originalMetadata })
            assertEquals("Status must fail closed until recovery", CharacterWorkspaceStatus.CORRUPTED,
                CharacterWorkspaceManager.getCharacterStatus("MetadataDoubleFailGuy", context))
        } finally {
            CharacterWorkspaceManager.simulateMetadataAtomicFailureAfterReplaceForTesting = false
            CharacterWorkspaceManager.simulateMetadataRollbackFailureForTesting = false
        }

        val recovery = CharacterWorkspaceManager.restoreSingleCharacter("MetadataDoubleFailGuy", context)
        assertTrue("A verified vault restore must recover after metadata double failure: ${recovery.errorMessage}", recovery.success)
        assertFalse("Successful restore must clear recovery marker", File(metadataDir, ".MetadataDoubleFailGuy.recovery_required").exists())
        assertEquals(CharacterWorkspaceStatus.RESTORED, CharacterWorkspaceManager.getCharacterStatus("MetadataDoubleFailGuy", context))
        metadataDir.listFiles { f -> f.name.startsWith(".MetadataDoubleFailGuy.json.previous_") }?.forEach { it.delete() }
    }

    @Test
    fun testRestoreMetadataWriteFailureRollsBackExactWorkspaceAndMetadata() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "RestoreMetaFailGuy")
        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        File(charDir, "not-in-vault.extra").writeText("preserve until restore transaction succeeds")
        File(charDir, "RestoreMetaFailGuy.cns").appendText("\n; user edit\n")

        val originalTree = charDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(charDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }
        val metadataFile = File(CharacterWorkspaceManager.getMetadataDir(context), "RestoreMetaFailGuy.json")
        val originalMetadata = metadataFile.readText()

        CharacterWorkspaceManager.simulateMetadataWriteFailureForTesting = true
        try {
            val failed = CharacterWorkspaceManager.restoreSingleCharacter("RestoreMetaFailGuy", context)
            assertFalse("Restore must fail when metadata commit is injected to fail", failed.success)
        } finally {
            CharacterWorkspaceManager.simulateMetadataWriteFailureForTesting = false
        }

        val afterFailedRestore = charDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(charDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }
        assertEquals("Failed restore must return exact previous workspace tree", originalTree, afterFailedRestore)
        assertEquals("Failed restore must preserve metadata byte-for-byte", originalMetadata, metadataFile.readText())

        val success = CharacterWorkspaceManager.restoreSingleCharacter("RestoreMetaFailGuy", context)
        assertTrue("Retry should succeed after failure injection is cleared: ${success.errorMessage}", success.success)
        assertFalse("Successful restore must remove files not in the vault", File(charDir, "not-in-vault.extra").exists())
    }

    @Test
    fun testVersionNumberAndChangeHistoryConsistencyAcrossSaveLifecycle() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacter(charsWorkspace, "LifecycleHistoryGuy")
        val defFile = File(charDir, "LifecycleHistoryGuy.def")
        val cnsFile = File(charDir, "LifecycleHistoryGuy.cns")

        CharacterWorkspaceManager.ensureVaultSnapshot(charDir, context)
        val initialMeta = CharacterWorkspaceManager.getCharacterMetadata("LifecycleHistoryGuy", context)!!
        val v0 = initialMeta.version

        // Phase 1: Successful save -> version increments by 1
        val defEditor1 = DefEditor(defFile, context)
        val cnsEditor1 = CnsEditor(cnsFile, context)
        defEditor1.setProperty("info", "displayname", "Hero V2")
        cnsEditor1.setProperty("[data]", "life", "1200")

        val res1 = CharacterWorkspaceManager.saveCompleteCharacterEdits("LifecycleHistoryGuy", defEditor1, cnsEditor1, context)
        assertTrue(res1.success)

        val metaV2 = CharacterWorkspaceManager.getCharacterMetadata("LifecycleHistoryGuy", context)!!
        assertEquals("Version increments to v0 + 1", v0 + 1, metaV2.version)
        val countAfterSuccess = metaV2.changeHistory.size
        assertTrue("History must have records", countAfterSuccess >= 2)

        // Phase 2: Failed save (cns blocked) -> version remains v0 + 1, history not polluted
        val defEditor2 = DefEditor(defFile, context)
        val cnsEditor2 = CnsEditor(cnsFile, context)
        defEditor2.setProperty("info", "displayname", "Aborted V3")

        val originalCnsText = cnsFile.readText()
        cnsFile.delete()
        cnsFile.mkdirs()
        File(cnsFile, "block.txt").writeText("block")

        val res2 = CharacterWorkspaceManager.saveCompleteCharacterEdits("LifecycleHistoryGuy", defEditor2, cnsEditor2, context)
        assertFalse(res2.success)

        val metaAfterFail = CharacterWorkspaceManager.getCharacterMetadata("LifecycleHistoryGuy", context)!!
        assertEquals("Version must NOT increment on failure", v0 + 1, metaAfterFail.version)
        assertEquals("History count must not increase on failure", countAfterSuccess, metaAfterFail.changeHistory.size)

        cnsFile.deleteRecursively()
        cnsFile.writeText(originalCnsText)

        // Phase 3: Subsequent successful save -> version increments to v0 + 2
        val defEditor3 = DefEditor(defFile, context)
        val cnsEditor3 = CnsEditor(cnsFile, context)
        cnsEditor3.setProperty("[data]", "life", "1500")

        val res3 = CharacterWorkspaceManager.saveCompleteCharacterEdits("LifecycleHistoryGuy", defEditor3, cnsEditor3, context)
        assertTrue(res3.success)

        val metaV3 = CharacterWorkspaceManager.getCharacterMetadata("LifecycleHistoryGuy", context)!!
        assertEquals("Version must increment to v0 + 2", v0 + 2, metaV3.version)
        assertTrue("History must record the new edit", metaV3.changeHistory.size > countAfterSuccess)
    }

    @Test
    fun testSha256FingerprintsOfOriginalSourcesAndVaultRemainBitForBitIdenticalBeforeAndAfterAllFailures() {
        val sourcesDir = CharacterWorkspaceManager.getSourcesDir(context)
        val sourceCharDir = createSampleCharacter(sourcesDir, "AuditProvenanceGuy")
        val importResult = CharacterWorkspaceManager.importCharacter(sourceCharDir, "AuditProvenanceGuy", context)
        assertTrue(importResult.success)

        val vaultCharDir = File(CharacterWorkspaceManager.getVaultDir(context), "AuditProvenanceGuy/files")

        // Collect SHA-256 fingerprints before tests
        val sourceFilesBefore = sourceCharDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(sourceCharDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }
        val vaultFilesBefore = vaultCharDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(vaultCharDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }

        assertTrue("Source files must be non-empty", sourceFilesBefore.isNotEmpty())
        assertTrue("Vault files must be non-empty", vaultFilesBefore.isNotEmpty())

        val workspaceFolder = importResult.workspaceFolder!!
        val workspaceDef = File(workspaceFolder, "AuditProvenanceGuy.def")
        val workspaceCns = File(workspaceFolder, "AuditProvenanceGuy.cns")

        // 1. A non-regular .cns target must be rejected before the transaction touches the .def file.
        val defEditor = DefEditor(workspaceDef, context)
        val cnsEditor = CnsEditor(workspaceCns, context)
        defEditor.setProperty("info", "displayname", "Should Fail")

        val originalCnsText = workspaceCns.readText()
        workspaceCns.delete()
        workspaceCns.mkdirs()
        File(workspaceCns, "block.txt").writeText("block")
        val failRes1 = CharacterWorkspaceManager.saveCompleteCharacterEdits("AuditProvenanceGuy", defEditor, cnsEditor, context)
        assertFalse(failRes1.success)
        workspaceCns.deleteRecursively()
        workspaceCns.writeText(originalCnsText)

        // 2. A mid-transaction failure plus failed rollback must preserve the backup and never touch source/vault.
        val defEditor2 = DefEditor(workspaceDef, context)
        val cnsEditor2 = CnsEditor(workspaceCns, context)
        defEditor2.setProperty("info", "displayname", "Should Fail 2")

        CharacterWorkspaceManager.simulateCommitFailureAfterReplacementsForTesting = 1
        CharacterWorkspaceManager.simulateRollbackFailureForTesting = true
        try {
            val failRes2 = CharacterWorkspaceManager.saveCompleteCharacterEdits("AuditProvenanceGuy", defEditor2, cnsEditor2, context)
            assertFalse(failRes2.success)
            assertTrue(failRes2.errorMessage?.contains("rollback failed") == true)
        } finally {
            CharacterWorkspaceManager.simulateCommitFailureAfterReplacementsForTesting = null
            CharacterWorkspaceManager.simulateRollbackFailureForTesting = false
        }

        // 3. Restore to heal workspace
        val restoreRes = CharacterWorkspaceManager.restoreSingleCharacter("AuditProvenanceGuy", context)
        assertTrue(restoreRes.success)

        // Verify SHA-256 fingerprints AFTER all failure injections
        val sourceFilesAfter = sourceCharDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(sourceCharDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }
        val vaultFilesAfter = vaultCharDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(vaultCharDir).path to CharacterWorkspaceManager.calculateSha256(it)
        }

        assertEquals("Original sources count must not change", sourceFilesBefore.size, sourceFilesAfter.size)
        assertEquals("Vault files count must not change", vaultFilesBefore.size, vaultFilesAfter.size)

        for ((relPath, shaBefore) in sourceFilesBefore) {
            assertEquals("Source file $relPath must remain 100% bit-for-bit identical", shaBefore, sourceFilesAfter[relPath])
        }
        for ((relPath, shaBefore) in vaultFilesBefore) {
            assertEquals("Vault file $relPath must remain 100% bit-for-bit identical", shaBefore, vaultFilesAfter[relPath])
        }
    }
}

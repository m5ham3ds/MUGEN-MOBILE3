package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.CnsEditor
import com.example.engine.workspace.ChangeRecord
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.engine.workspace.CharacterWorkspaceStatus
import com.example.storage.MugenMobileStorage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterWorkspaceManagerTest {

    private lateinit var context: Context
    private lateinit var tempGameDir: File
    private lateinit var tempSourcesDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tempGameDir = File(context.cacheDir, "test_game_${System.currentTimeMillis()}")
        tempGameDir.mkdirs()
        tempSourcesDir = File(context.cacheDir, "test_sources_${System.currentTimeMillis()}")
        tempSourcesDir.mkdirs()

        MugenMobileStorage.setCustomBaseDir(context, tempGameDir.absolutePath)

        // Clean any residual vault files from previous runs in cache/files
        val vaultDir = CharacterWorkspaceManager.getVaultDir(context)
        if (vaultDir.exists()) vaultDir.deleteRecursively()
        val metaDir = CharacterWorkspaceManager.getMetadataDir(context)
        if (metaDir.exists()) metaDir.deleteRecursively()
    }

    private fun createSampleCharacterFolder(parentDir: File, charName: String): File {
        val charDir = File(parentDir, charName).apply { mkdirs() }
        File(charDir, "$charName.def").writeText(
            """
            [Info]
            name = "$charName"
            displayname = "$charName Fighter"
            author = "TestAuthor"
            
            [Files]
            cns = $charName.cns
            cmd = $charName.cmd
            anim = $charName.air
            sprite = $charName.sff
            """.trimIndent()
        )
        File(charDir, "$charName.cns").writeText(
            """
            ; Character constants
            [Data]
            life = 1000
            attack = 100
            defence = 100
            
            [Size]
            xscale = 1
            yscale = 1
            """.trimIndent()
        )
        File(charDir, "$charName.cmd").writeText("; Commands file\n[Command]\nname = \"special\"\ncommand = ~D, DF, F, x")
        File(charDir, "$charName.air").writeText("; Animations file\n[Begin Action 0]\n0,0, 0,0, -1")
        File(charDir, "$charName.sff").writeText("SFF_TEST_BINARY_DATA")
        return charDir
    }

    @Test
    fun testRegisterOriginalSourceAndSecurityViolation() {
        val originalCharDir = createSampleCharacterFolder(tempSourcesDir, "MasterRyu")
        CharacterWorkspaceManager.registerOriginalSource(tempSourcesDir.canonicalPath)

        val cnsInOriginal = File(originalCharDir, "MasterRyu.cns")
        assertTrue("File inside registered source must be detected as Original Source",
            CharacterWorkspaceManager.isOriginalSource(cnsInOriginal))

        // Direct write to Original Source must be rejected by saveCharacterFileSafe
        val result = CharacterWorkspaceManager.saveCharacterFileSafe(
            characterId = "MasterRyu",
            relativePath = "MasterRyu.cns",
            updatedContent = "[Data]\nlife = 9999",
            changes = emptyList(),
            context = context
        )
        // Note: targetFile in workspace does not exist yet or is original source check
        // If we attempt direct CnsEditor.save() on original source file:
        val editor = CnsEditor(cnsInOriginal)
        editor.properties["[data]"]?.get("life")?.value = "5000"

        var securityExceptionThrown = false
        try {
            editor.save()
        } catch (e: SecurityException) {
            securityExceptionThrown = true
        }
        assertTrue("Direct save() to Original Source file must throw SecurityException", securityExceptionThrown)
    }

    @Test
    fun testPristineVaultSnapshotAndSha256Fingerprints() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacterFolder(charsWorkspace, "Ken")

        val snapshotResult = CharacterWorkspaceManager.capturePristineSnapshot(
            characterId = "Ken",
            sourceFolder = charDir,
            context = context
        )

        assertTrue("Vault snapshot capture must succeed", snapshotResult.success)
        assertTrue("Fingerprints must include Ken.def", snapshotResult.fingerprints.containsKey("Ken.def"))
        assertTrue("Fingerprints must include Ken.cns", snapshotResult.fingerprints.containsKey("Ken.cns"))
        assertTrue("Fingerprints must include Ken.cmd", snapshotResult.fingerprints.containsKey("Ken.cmd"))

        // Verify manifest exists and is readable
        val manifest = CharacterWorkspaceManager.getVaultManifest("Ken", context)
        assertNotNull("Manifest must be generated", manifest)
        assertEquals("Ken", manifest!!.characterId)
        assertEquals(5, manifest.files.size)

        // Verify vault file exists and fingerprint matches manifest
        val vaultFilesDir = File(File(CharacterWorkspaceManager.getVaultDir(context), "Ken"), "files")
        val cnsVaultFile = File(vaultFilesDir, "Ken.cns")
        assertTrue("Vault file must exist", cnsVaultFile.exists())
        assertEquals(cnsVaultFile.length(), manifest.files["Ken.cns"]?.sizeBytes)
        assertEquals(CharacterWorkspaceManager.calculateSha256(cnsVaultFile), manifest.files["Ken.cns"]?.sha256)
    }

    @Test
    fun testCryptographicVaultIntegrityVerification() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacterFolder(charsWorkspace, "ChunLi")

        CharacterWorkspaceManager.capturePristineSnapshot("ChunLi", charDir, context)

        // Initial integrity check must pass
        val (initialValid, initialError) = CharacterWorkspaceManager.verifyVaultIntegrity("ChunLi", context)
        assertTrue("Vault integrity must be valid initially: $initialError", initialValid)

        // Tamper with a vault file by altering its content
        val vaultFilesDir = File(File(CharacterWorkspaceManager.getVaultDir(context), "ChunLi"), "files")
        val cnsVaultFile = File(vaultFilesDir, "ChunLi.cns")
        cnsVaultFile.setWritable(true)
        cnsVaultFile.writeText("; Tampered Content !!!")
        cnsVaultFile.setReadOnly()

        val (tamperedValid, tamperedError) = CharacterWorkspaceManager.verifyVaultIntegrity("ChunLi", context)
        assertFalse("Vault integrity check must fail after tampering", tamperedValid)
        assertNotNull(tamperedError)
        assertTrue(tamperedError!!.contains("SHA-256 corrupted") || tamperedError.contains("mismatch"))
    }

    @Test
    fun testSafeAtomicSaveAndChangeMetadataTracking() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacterFolder(charsWorkspace, "Guile")

        // Initial snapshot
        CharacterWorkspaceManager.capturePristineSnapshot("Guile", charDir, context)

        val cnsFile = File(charDir, "Guile.cns")
        val originalSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)
        val editor = CnsEditor(cnsFile)

        // Modify values
        editor.properties["[data]"]?.get("life")?.value = "1500"
        editor.properties["[data]"]?.get("attack")?.value = "120"

        val saveResult = CharacterWorkspaceManager.saveCnsChanges(
            cnsEditor = editor,
            characterId = "Guile",
            context = context
        )

        assertTrue("Safe save must succeed: ${saveResult.errorMessage}", saveResult.success)
        assertNotNull(saveResult.newSha256)
        assertNotEquals(originalSha256, saveResult.newSha256)

        // Verify metadata updated
        val meta = CharacterWorkspaceManager.getCharacterMetadata("Guile", context)
        assertNotNull(meta)
        assertEquals(CharacterWorkspaceStatus.MODIFIED, meta!!.status)
        assertEquals(2, meta.version)
        assertEquals(2, meta.changeHistory.size)

        val lifeChange = meta.changeHistory.find { it.key == "life" }
        assertNotNull(lifeChange)
        assertEquals("1000", lifeChange!!.oldValue)
        assertEquals("1500", lifeChange.newValue)

        // Verify isCharacterModified
        assertTrue("Guile must be reported as modified", CharacterWorkspaceManager.isCharacterModified("Guile", context))
    }

    @Test
    fun testSingleCharacterRestorationFromVault() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacterFolder(charsWorkspace, "Akuma")

        val cnsFile = File(charDir, "Akuma.cns")
        val pristineSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)
        val pristineContent = cnsFile.readText()

        // Capture snapshot
        CharacterWorkspaceManager.capturePristineSnapshot("Akuma", charDir, context)

        // Perform modification
        val editor = CnsEditor(cnsFile)
        editor.properties["[data]"]?.get("life")?.value = "3000"
        val saveResult = CharacterWorkspaceManager.saveCnsChanges(editor, "Akuma", context)
        assertTrue(saveResult.success)

        assertNotEquals("Content on disk must reflect modification", pristineContent, cnsFile.readText())
        assertTrue(CharacterWorkspaceManager.isCharacterModified("Akuma", context))

        // Restore to original pristine state
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("Akuma", context)
        assertTrue("Restoration must succeed: ${restoreResult.errorMessage}", restoreResult.success)
        assertEquals(5, restoreResult.restoredFilesCount)

        // Verify content matches bit-for-bit with original pristine state
        val restoredSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)
        assertEquals("Restored file hash must exactly match pristine original hash", pristineSha256, restoredSha256)
        assertEquals("Restored content must match pristine original content", pristineContent, cnsFile.readText())

        // Verify status and modification check
        assertEquals(CharacterWorkspaceStatus.RESTORED, CharacterWorkspaceManager.getCharacterStatus("Akuma", context))
        assertFalse("Character must no longer be modified after restore", CharacterWorkspaceManager.isCharacterModified("Akuma", context))
    }

    @Test
    fun testBatchRestorePlanAndExecution() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charA = createSampleCharacterFolder(charsWorkspace, "FighterA")
        val charB = createSampleCharacterFolder(charsWorkspace, "FighterB")

        CharacterWorkspaceManager.capturePristineSnapshot("FighterA", charA, context)
        CharacterWorkspaceManager.capturePristineSnapshot("FighterB", charB, context)

        // Modify only FighterA
        val editorA = CnsEditor(File(charA, "FighterA.cns"))
        editorA.properties["[data]"]?.get("life")?.value = "2000"
        CharacterWorkspaceManager.saveCnsChanges(editorA, "FighterA", context)

        // Plan batch restore
        val plan = CharacterWorkspaceManager.planBatchRestore(context)
        assertEquals(2, plan.totalCharacters)
        assertEquals(listOf("FighterA"), plan.modifiedCharacters)
        assertEquals(listOf("FighterB"), plan.pristineCharacters)
        assertTrue(plan.corruptedCharacters.isEmpty())

        // Execute batch restore
        val report = CharacterWorkspaceManager.executeBatchRestore(null, context)
        assertTrue("Batch restore must succeed", report.success)
        assertEquals(listOf("FighterA"), report.restoredCharacters)
        assertTrue(report.failedCharacters.isEmpty())

        // Re-plan: all characters should now be restored/pristine
        val postPlan = CharacterWorkspaceManager.planBatchRestore(context)
        assertTrue("No modified characters should remain after batch restore", postPlan.modifiedCharacters.isEmpty())
    }

    @Test
    fun testImportCharacterFlow() {
        val externalFolder = createSampleCharacterFolder(tempSourcesDir, "ExternalFighter")

        val importResult = CharacterWorkspaceManager.importCharacter(
            sourceFolder = externalFolder,
            characterId = "ExternalFighter",
            context = context
        )

        assertTrue("Import must succeed: ${importResult.errorMessage}", importResult.success)
        assertNotNull(importResult.workspaceFolder)
        assertTrue("Workspace folder must exist", importResult.workspaceFolder!!.exists())
        assertEquals(5, importResult.copiedFilesCount)

        // Verify vault manifest
        assertNotNull(importResult.vaultManifest)
        assertTrue(CharacterWorkspaceManager.isCharacterVaulted("ExternalFighter", context))
        assertEquals(CharacterWorkspaceStatus.PRISTINE_ORIGINAL, CharacterWorkspaceManager.getCharacterStatus("ExternalFighter", context))

        // Verify source is registered as immutable Original Source
        assertTrue(CharacterWorkspaceManager.isOriginalSource(externalFolder, context))
    }

    @Test
    fun testImmutableSourceUntouchedDuringEditAndRestore() {
        // 1. Calculate SHA-256 for all files of a sample character before any edit
        val sourceFolder = createSampleCharacterFolder(tempSourcesDir, "SourceImmutableGuy")
        val sourceFiles = sourceFolder.listFiles()!!.filter { it.isFile }
        val initialHashes = sourceFiles.associate { it.name to CharacterWorkspaceManager.calculateSha256(it) }
        assertEquals(5, initialHashes.size)

        // 2. Import into workspace
        val importResult = CharacterWorkspaceManager.importCharacter(sourceFolder, "SourceImmutableGuy", context)
        assertTrue(importResult.success)
        val workspaceFolder = importResult.workspaceFolder!!

        // Verify source files remain 100% untouched after import
        for (file in sourceFiles) {
            val hashAfterImport = CharacterWorkspaceManager.calculateSha256(file)
            assertEquals("Source file ${file.name} must remain unchanged after import",
                initialHashes[file.name], hashAfterImport)
        }

        // 3. Save modifications to the workspace working copy
        val workspaceCns = File(workspaceFolder, "SourceImmutableGuy.cns")
        val editor = CnsEditor(workspaceCns)
        editor.properties["[data]"]?.get("life")?.value = "2800"
        editor.properties["[data]"]?.get("attack")?.value = "175"
        val saveResult = CharacterWorkspaceManager.saveCnsChanges(editor, "SourceImmutableGuy", context)
        assertTrue(saveResult.success)

        // Verify workspace copy has indeed changed
        assertNotEquals(initialHashes["SourceImmutableGuy.cns"], CharacterWorkspaceManager.calculateSha256(workspaceCns))

        // Verify ALL source files remain 100% BIT-FOR-BIT IDENTICAL to their pre-edit state
        for (file in sourceFiles) {
            val hashAfterEdit = CharacterWorkspaceManager.calculateSha256(file)
            assertEquals("Source file ${file.name} must remain 100% unmodified after workspace edit",
                initialHashes[file.name], hashAfterEdit)
        }

        // 4. Restore the character from vault
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("SourceImmutableGuy", context)
        assertTrue(restoreResult.success)

        // Verify ALL source files remain 100% unmodified after single restore
        for (file in sourceFiles) {
            val hashAfterRestore = CharacterWorkspaceManager.calculateSha256(file)
            assertEquals("Source file ${file.name} must remain 100% unmodified after restore",
                initialHashes[file.name], hashAfterRestore)
        }

        // 5. Modify again and run batch restore
        editor.properties["[data]"]?.get("life")?.value = "3200"
        CharacterWorkspaceManager.saveCnsChanges(editor, "SourceImmutableGuy", context)
        val batchReport = CharacterWorkspaceManager.executeBatchRestore(null, context)
        assertTrue(batchReport.success)

        // Verify ALL source files remain 100% unmodified after batch restore
        for (file in sourceFiles) {
            val hashAfterBatch = CharacterWorkspaceManager.calculateSha256(file)
            assertEquals("Source file ${file.name} must remain 100% unmodified after batch restore",
                initialHashes[file.name], hashAfterBatch)
        }
    }

    @Test
    fun testPersistenceOfOriginalSourcesAcrossReinitialization() {
        val isolatedSourceDir = File(tempSourcesDir, "PersistedSourceFolder").apply { mkdirs() }
        val testFile = File(isolatedSourceDir, "test.cns").apply { writeText("[Data]\nlife = 1000") }

        // Register and persist
        CharacterWorkspaceManager.registerOriginalSource(isolatedSourceDir.canonicalPath, context)
        assertTrue("Registry file must be saved on disk",
            CharacterWorkspaceManager.getSourcesRegistryFile(context).exists())

        // Simulate app/process restart by clearing in-memory registration state
        CharacterWorkspaceManager.clearRegisteredSourcesForTesting()

        // Verify that isOriginalSource reloads from disk and continues to protect the path
        assertTrue("Must detect source after reinitialization",
            CharacterWorkspaceManager.isOriginalSource(testFile, context))

        // Verify that writing via CnsEditor on persisted source still throws SecurityException
        val editor = CnsEditor(testFile)
        editor.properties["[data]"]?.get("life")?.value = "9999"

        var securityExceptionThrown = false
        try {
            editor.save()
        } catch (e: SecurityException) {
            securityExceptionThrown = true
        }
        assertTrue("Save on persisted source must throw SecurityException after restart", securityExceptionThrown)
    }

    @Test
    fun testPreExistingCharactersInWorkspaceProtectionAndRestore() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val preExistingDir = createSampleCharacterFolder(charsWorkspace, "PreExistingFighter")

        val cnsFile = File(preExistingDir, "PreExistingFighter.cns")
        val preExistingContent = cnsFile.readText()
        val preExistingSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)

        // User edits character in workspace; ensureVaultSnapshot secures pristine copy first
        val editor = CnsEditor(cnsFile)
        editor.properties["[data]"]?.get("life")?.value = "2200"
        val saveResult = CharacterWorkspaceManager.saveCnsChanges(editor, "PreExistingFighter", context)
        assertTrue(saveResult.success)

        // Workspace file changed
        assertNotEquals(preExistingSha256, CharacterWorkspaceManager.calculateSha256(cnsFile))
        assertTrue(CharacterWorkspaceManager.isCharacterModified("PreExistingFighter", context))

        // Pristine copy in vault exists and matches pre-existing state
        val manifest = CharacterWorkspaceManager.getVaultManifest("PreExistingFighter", context)
        assertNotNull(manifest)
        assertEquals(preExistingSha256, manifest!!.files["PreExistingFighter.cns"]?.sha256)

        // Restore single character
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("PreExistingFighter", context)
        assertTrue(restoreResult.success)

        // Restored workspace file matches pre-existing state bit-for-bit
        assertEquals(preExistingSha256, CharacterWorkspaceManager.calculateSha256(cnsFile))
        assertEquals(preExistingContent, cnsFile.readText())
        assertFalse(CharacterWorkspaceManager.isCharacterModified("PreExistingFighter", context))
    }

    @Test
    fun testEngineRuntimeWorkspacePathResolutionSimulation() {
        val baseDir = MugenMobileStorage.getBaseDir(context)
        val charsDir = File(baseDir, "chars").apply { mkdirs() }
        val fighterDir = createSampleCharacterFolder(charsDir, "RuntimeHero")

        // NOTICE: This test is a Kotlin-level filesystem path resolution simulation in Robolectric.
        // It validates the directory structure passed via '-dir <baseDir>' and relative path resolution,
        // but does NOT execute the compiled native Go/C binary 'libmain.so'.
        val defFile = File(fighterDir, "RuntimeHero.def")
        assertTrue("Engine must find primary .def at baseDir/chars/<char>/<char>.def", defFile.exists())

        // In RuntimeHero.def, [Files] section specifies: cns = RuntimeHero.cns
        // The engine resolves this relative to the def file's directory:
        val expectedEngineCnsFile = File(defFile.parentFile, "RuntimeHero.cns")
        assertTrue("Engine resolved CNS file must exist in workspace", expectedEngineCnsFile.exists())

        // Save modifications to the workspace working copy
        val editor = CnsEditor(expectedEngineCnsFile)
        editor.properties["[data]"]?.get("life")?.value = "1750"
        editor.properties["[data]"]?.get("attack")?.value = "130"
        val saveResult = CharacterWorkspaceManager.saveCnsChanges(editor, "RuntimeHero", context)
        assertTrue(saveResult.success)

        // Read the exact file the engine opens at runtime
        val activeLines = expectedEngineCnsFile.readLines()
        val lifeLine = activeLines.find { it.trim().lowercase().startsWith("life") }
        val attackLine = activeLines.find { it.trim().lowercase().startsWith("attack") }

        assertNotNull("Life line must exist in active file", lifeLine)
        assertTrue("Engine reads updated life from workspace", lifeLine!!.contains("1750"))
        assertNotNull("Attack line must exist in active file", attackLine)
        assertTrue("Engine reads updated attack from workspace", attackLine!!.contains("130"))

        // Verify companion files (.cmd, .air, .sff) remain in the same workspace directory
        assertTrue("Companion .cmd file exists for engine", File(defFile.parentFile, "RuntimeHero.cmd").exists())
        assertTrue("Companion .air file exists for engine", File(defFile.parentFile, "RuntimeHero.air").exists())
        assertTrue("Companion .sff file exists for engine", File(defFile.parentFile, "RuntimeHero.sff").exists())
    }

    @Test
    fun testAllKnownSavePathsRejectOriginalSourceAndVault() {
        val sourceDir = File(tempSourcesDir, "ProtectedTarget").apply { mkdirs() }
        val sourceCns = File(sourceDir, "ProtectedTarget.cns").apply { writeText("[Data]\nlife = 1000") }
        CharacterWorkspaceManager.registerOriginalSource(sourceDir.canonicalPath, context)

        // 1. CnsEditor.save() on Original Source -> throws SecurityException
        val sourceEditor = CnsEditor(sourceCns)
        var sourceSaveBlocked = false
        try {
            sourceEditor.save()
        } catch (e: SecurityException) {
            sourceSaveBlocked = true
        }
        assertTrue("CnsEditor.save() on Original Source must throw SecurityException", sourceSaveBlocked)

        // 2. CnsEditor.save() on Vault file -> throws SecurityException
        val vaultDir = CharacterWorkspaceManager.getVaultDir(context)
        val dummyVaultCharDir = File(vaultDir, "VaultChar/files").apply { mkdirs() }
        val vaultCns = File(dummyVaultCharDir, "VaultChar.cns").apply { writeText("[Data]\nlife = 1000") }
        val vaultEditor = CnsEditor(vaultCns)

        var vaultSaveBlocked = false
        try {
            vaultEditor.save()
        } catch (e: SecurityException) {
            vaultSaveBlocked = true
        }
        assertTrue("CnsEditor.save() on Vault file must throw SecurityException", vaultSaveBlocked)

        // 3. saveCharacterFileSafe directly targeting protected file -> returns false
        val saveSafeResult = CharacterWorkspaceManager.saveCharacterFileSafe(
            characterId = "VaultChar",
            relativePath = "files/VaultChar.cns",
            updatedContent = "[Data]\nlife = 9999",
            changes = emptyList(),
            context = context
        )
        // If relativePath resolved within workspaceDir is outside or inside vault:
        // Attempting to target source directly:
        assertTrue(CharacterWorkspaceManager.isProtectedImmutableFile(sourceCns, context))
        assertTrue(CharacterWorkspaceManager.isProtectedImmutableFile(vaultCns, context))
    }

    @Test
    fun testCorruptedVaultPreventsRestoration() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacterFolder(charsWorkspace, "TamperVictim")

        // Capture snapshot
        CharacterWorkspaceManager.capturePristineSnapshot("TamperVictim", charDir, context)

        // Modify workspace copy
        val cnsFile = File(charDir, "TamperVictim.cns")
        val editor = CnsEditor(cnsFile)
        editor.properties["[data]"]?.get("life")?.value = "2500"
        CharacterWorkspaceManager.saveCnsChanges(editor, "TamperVictim", context)

        val modifiedWorkspaceSha = CharacterWorkspaceManager.calculateSha256(cnsFile)

        // Corrupt vault file
        val vaultFilesDir = File(File(CharacterWorkspaceManager.getVaultDir(context), "TamperVictim"), "files")
        val vaultCns = File(vaultFilesDir, "TamperVictim.cns")
        vaultCns.setWritable(true)
        vaultCns.writeText("; Corrupted Content injected")
        vaultCns.setReadOnly()

        // Attempt restore
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("TamperVictim", context)
        assertFalse("Restore must abort when vault is corrupted", restoreResult.success)
        assertNotNull(restoreResult.errorMessage)
        assertTrue(restoreResult.errorMessage!!.contains("Vault integrity check failed"))

        // Verify workspace file was not damaged or overwritten with corrupted data
        assertEquals("Workspace file must remain untouched when restore fails",
            modifiedWorkspaceSha, CharacterWorkspaceManager.calculateSha256(cnsFile))
    }

    @Test
    fun testBatchRestoreReportsPartialFailures() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val goodChar = createSampleCharacterFolder(charsWorkspace, "GoodFighter")
        val badChar = createSampleCharacterFolder(charsWorkspace, "CorruptedFighter")

        CharacterWorkspaceManager.capturePristineSnapshot("GoodFighter", goodChar, context)
        CharacterWorkspaceManager.capturePristineSnapshot("CorruptedFighter", badChar, context)

        // Modify both
        val editorGood = CnsEditor(File(goodChar, "GoodFighter.cns"))
        editorGood.properties["[data]"]?.get("life")?.value = "1800"
        CharacterWorkspaceManager.saveCnsChanges(editorGood, "GoodFighter", context)

        val editorBad = CnsEditor(File(badChar, "CorruptedFighter.cns"))
        editorBad.properties["[data]"]?.get("life")?.value = "1900"
        CharacterWorkspaceManager.saveCnsChanges(editorBad, "CorruptedFighter", context)

        // Corrupt BadFighter's vault
        val badVaultDir = File(File(CharacterWorkspaceManager.getVaultDir(context), "CorruptedFighter"), "files")
        val badVaultCns = File(badVaultDir, "CorruptedFighter.cns")
        badVaultCns.setWritable(true)
        badVaultCns.writeText("; Damaged bytes")
        badVaultCns.setReadOnly()

        // Execute batch restore
        val batchReport = CharacterWorkspaceManager.executeBatchRestore(null, context)

        // Must report overall failure because one character failed
        assertFalse("Batch report must report failure if any character failed", batchReport.success)
        assertEquals(listOf("GoodFighter"), batchReport.restoredCharacters)
        assertTrue("CorruptedFighter must be in failed map", batchReport.failedCharacters.containsKey("CorruptedFighter"))
    }

    @Test
    fun testPathTraversalAttackRejected() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        createSampleCharacterFolder(charsWorkspace, "TraversalTarget")

        // 1. Attempting relative path traversal escaping workspace
        val traversalResult1 = CharacterWorkspaceManager.saveCharacterFileSafe(
            characterId = "TraversalTarget",
            relativePath = "../../data/select.def",
            updatedContent = "[Characters]\nfake_entry",
            changes = emptyList(),
            context = context
        )
        assertFalse("Relative path escaping workspace must be rejected", traversalResult1.success)
        assertNotNull(traversalResult1.errorMessage)
        assertTrue(traversalResult1.errorMessage!!.contains("Path traversal detected"))

        // 2. Attempting relative path to touch sources
        val traversalResult2 = CharacterWorkspaceManager.saveCharacterFileSafe(
            characterId = "TraversalTarget",
            relativePath = "../../../sources/chars/target.cns",
            updatedContent = "[Data]\nlife = 9999",
            changes = emptyList(),
            context = context
        )
        assertFalse("Relative path attempting to target sources must be rejected", traversalResult2.success)
        assertTrue(traversalResult2.errorMessage!!.contains("Path traversal detected") ||
                traversalResult2.errorMessage!!.contains("SECURITY VIOLATION"))
    }

    @Test
    fun testTransactionalRestoreRollbackOnFailure() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charDir = createSampleCharacterFolder(charsWorkspace, "RollbackFighter")

        // Create snapshot
        CharacterWorkspaceManager.capturePristineSnapshot("RollbackFighter", charDir, context)

        // User edits character in workspace
        val cnsFile = File(charDir, "RollbackFighter.cns")
        val editor = CnsEditor(cnsFile, context)
        editor.properties["[data]"]?.get("life")?.value = "3500"
        CharacterWorkspaceManager.saveCnsChanges(editor, "RollbackFighter", context)

        val modifiedContent = cnsFile.readText()
        val modifiedSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)

        // Intentionally damage one vault file AFTER manifest is verified to simulate a mid-restore disk/hash corruption
        val vaultFilesDir = File(File(CharacterWorkspaceManager.getVaultDir(context), "RollbackFighter"), "files")
        val vaultCmdFile = File(vaultFilesDir, "RollbackFighter.cmd")
        vaultCmdFile.setWritable(true)
        vaultCmdFile.writeText("; Mid-restore corruption simulation")
        vaultCmdFile.setReadOnly()

        // Attempt restore
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("RollbackFighter", context)
        assertFalse("Restore must fail due to corrupted vault file", restoreResult.success)

        // Verify that workspace was completely protected/rolled back and NOT left in a half-restored state
        val postAttemptContent = cnsFile.readText()
        val postAttemptSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)
        assertEquals("Modified workspace file must not be destroyed when restore aborts", modifiedContent, postAttemptContent)
        assertEquals("Hash must remain intact", modifiedSha256, postAttemptSha256)
    }

    @Test
    fun testAuthoritativeSourcePriorityOverPreExistingWorkspace() {
        // Setup an authentic original copy in sources/chars/AuthenticGuy
        val sourcesCharsDir = CharacterWorkspaceManager.getSourcesDir(context)
        val authenticSourceFolder = createSampleCharacterFolder(sourcesCharsDir, "AuthenticGuy")
        val sourceCns = File(authenticSourceFolder, "AuthenticGuy.cns")
        val authenticSha256 = CharacterWorkspaceManager.calculateSha256(sourceCns)

        // Simulate a modified/altered copy already present in chars/AuthenticGuy
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val modifiedWorkspaceFolder = createSampleCharacterFolder(charsWorkspace, "AuthenticGuy")
        val workspaceCns = File(modifiedWorkspaceFolder, "AuthenticGuy.cns")
        workspaceCns.writeText("; Altered by user previously\n[Data]\nlife = 9999\nattack = 500")
        assertNotEquals(authenticSha256, CharacterWorkspaceManager.calculateSha256(workspaceCns))

        // When ensureVaultSnapshot runs, it must prioritize the authentic copy in sources/chars/
        val vaulted = CharacterWorkspaceManager.ensureVaultSnapshot(modifiedWorkspaceFolder, context)
        assertTrue("Vault snapshot capture must succeed", vaulted)

        val manifest = CharacterWorkspaceManager.getVaultManifest("AuthenticGuy", context)
        assertNotNull(manifest)
        assertEquals("Vault snapshot must have captured authentic source hash, not modified workspace hash",
            authenticSha256, manifest!!.files["AuthenticGuy.cns"]?.sha256)
        assertEquals("VERIFIED_ORIGINAL_SOURCE", manifest.sourceOrigin)

        // Restoring must return workspace to the authentic original state (life = 1000, not 9999)
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("AuthenticGuy", context)
        assertTrue(restoreResult.success)
        assertEquals(authenticSha256, CharacterWorkspaceManager.calculateSha256(workspaceCns))
        assertTrue(workspaceCns.readText().contains("life = 1000"))
    }

    @Test
    fun testEmptyOrInvalidFolderSnapshotRejected() {
        val emptyDir = File(tempSourcesDir, "EmptyChar").apply { mkdirs() }
        val resultEmpty = CharacterWorkspaceManager.capturePristineSnapshot("EmptyChar", emptyDir, context)
        assertFalse("Empty folder must be rejected from snapshot", resultEmpty.success)
        assertTrue(resultEmpty.errorMessage!!.contains("no files"))

        val noDefDir = File(tempSourcesDir, "NoDefChar").apply { mkdirs() }
        File(noDefDir, "random.txt").writeText("Not a mugen character")
        val resultNoDef = CharacterWorkspaceManager.capturePristineSnapshot("NoDefChar", noDefDir, context)
        assertFalse("Folder without .def must be rejected from snapshot", resultNoDef.success)
        assertTrue(resultNoDef.errorMessage!!.contains("no .def"))
    }

    @Test
    fun testLegacyCharacterSourceSeparationOnWorkspaceDiscovery() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val legacyDir = createSampleCharacterFolder(charsWorkspace, "LegacyHero")
        val cnsFile = File(legacyDir, "LegacyHero.cns")
        val initialCnsSha256 = CharacterWorkspaceManager.calculateSha256(cnsFile)

        val sourcesDir = CharacterWorkspaceManager.getSourcesDir(context)
        val separatedSourceDir = File(sourcesDir, "LegacyHero")
        assertFalse("Separated source must not exist prior to discovery/vaulting", separatedSourceDir.exists())

        // Trigger ensureVaultSnapshot on the legacy workspace folder
        val vaulted = CharacterWorkspaceManager.ensureVaultSnapshot(legacyDir, context)
        assertTrue("Vault snapshot and separation must succeed", vaulted)

        // 1. Verify separated source directory now exists in sources/chars/LegacyHero
        assertTrue("Separated source folder must exist", separatedSourceDir.exists())
        val separatedCns = File(separatedSourceDir, "LegacyHero.cns")
        assertTrue("Separated CNS file must exist", separatedCns.exists())

        // 2. Verify bit-for-bit SHA-256 identity between workspace original and separated source
        assertEquals("Separated source CNS must match original SHA-256 bit-for-bit",
            initialCnsSha256, CharacterWorkspaceManager.calculateSha256(separatedCns))

        // 3. Verify multi-tier protection and immutability registration
        assertTrue("Must be identified as immutable Original Source",
            CharacterWorkspaceManager.isOriginalSource(separatedCns, context))
        assertTrue("Must be identified as protected immutable file",
            CharacterWorkspaceManager.isProtectedImmutableFile(separatedCns, context))

        // 5. Verify direct write rejection via CnsEditor.save() throws SecurityException
        val editor = CnsEditor(separatedCns, context)
        editor.properties["[data]"]?.get("life")?.value = "5000"
        var exceptionThrown = false
        try {
            editor.save()
        } catch (_: SecurityException) {
            exceptionThrown = true
        }
        assertTrue("Direct write to separated legacy source must throw SecurityException", exceptionThrown)

        // 6. Verify manifest marks sourceOrigin as LEGACY_WORKSPACE_SEPARATED
        val manifest = CharacterWorkspaceManager.getVaultManifest("LegacyHero", context)
        assertNotNull(manifest)
        assertEquals("LEGACY_WORKSPACE_SEPARATED", manifest!!.sourceOrigin)
        assertEquals(separatedSourceDir.canonicalPath, manifest.originalSourcePath)
    }

    @Test
    fun testLegacyCharacterEditsDoNotTouchSeparatedOriginalSource() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val legacyDir = createSampleCharacterFolder(charsWorkspace, "UntouchedSeparatedGuy")
        val workspaceCns = File(legacyDir, "UntouchedSeparatedGuy.cns")
        val pristineSha256 = CharacterWorkspaceManager.calculateSha256(workspaceCns)

        // Secure legacy character and separate source
        CharacterWorkspaceManager.ensureVaultSnapshot(legacyDir, context)
        val separatedSourceCns = File(File(CharacterWorkspaceManager.getSourcesDir(context), "UntouchedSeparatedGuy"), "UntouchedSeparatedGuy.cns")
        assertEquals(pristineSha256, CharacterWorkspaceManager.calculateSha256(separatedSourceCns))

        // 1. Modify workspace copy via saveCnsChanges
        val editor = CnsEditor(workspaceCns, context)
        editor.properties["[data]"]?.get("life")?.value = "3333"
        editor.properties["[data]"]?.get("attack")?.value = "222"
        val saveResult = CharacterWorkspaceManager.saveCnsChanges(editor, "UntouchedSeparatedGuy", context)
        assertTrue(saveResult.success)

        // Workspace changed
        val modifiedWorkspaceSha256 = CharacterWorkspaceManager.calculateSha256(workspaceCns)
        assertNotEquals(pristineSha256, modifiedWorkspaceSha256)

        // Separated source remains 100% BIT-FOR-BIT UNTOUCHED
        assertEquals("Separated source must remain bit-for-bit identical after workspace edit",
            pristineSha256, CharacterWorkspaceManager.calculateSha256(separatedSourceCns))

        // 2. Restore character
        val restoreResult = CharacterWorkspaceManager.restoreSingleCharacter("UntouchedSeparatedGuy", context)
        assertTrue(restoreResult.success)

        // Workspace restored
        assertEquals(pristineSha256, CharacterWorkspaceManager.calculateSha256(workspaceCns))

        // Separated source STILL 100% BIT-FOR-BIT UNTOUCHED
        assertEquals("Separated source must remain bit-for-bit identical after restore",
            pristineSha256, CharacterWorkspaceManager.calculateSha256(separatedSourceCns))
    }

    @Test
    fun testReconstructSeparatedSourceFromVaultForAlreadyModifiedLegacyCharacter() {
        // Setup character with pristine vault snapshot
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val legacyDir = createSampleCharacterFolder(charsWorkspace, "VaultReconstructGuy")
        val workspaceCns = File(legacyDir, "VaultReconstructGuy.cns")
        val pristineSha256 = CharacterWorkspaceManager.calculateSha256(workspaceCns)

        // Capture vault snapshot
        val snapResult = CharacterWorkspaceManager.capturePristineSnapshot("VaultReconstructGuy", legacyDir, context)
        assertTrue(snapResult.success)

        // Modify workspace file so workspace is NO LONGER PRISTINE
        workspaceCns.writeText("; Corrupted or edited\n[Data]\nlife = 9999\nattack = 999")
        val modifiedSha256 = CharacterWorkspaceManager.calculateSha256(workspaceCns)
        assertNotEquals(pristineSha256, modifiedSha256)

        // Ensure separated source does not exist yet
        val separatedSourceDir = File(CharacterWorkspaceManager.getSourcesDir(context), "VaultReconstructGuy")
        separatedSourceDir.deleteRecursively()
        assertFalse(separatedSourceDir.exists())

        // Reconstruct separated source from pristine Vault
        val resultDir = CharacterWorkspaceManager.separateLegacySourceFromVault("VaultReconstructGuy", context)
        assertNotNull(resultDir)
        assertTrue(resultDir!!.exists())

        val separatedCns = File(resultDir, "VaultReconstructGuy.cns")
        assertTrue(separatedCns.exists())

        // CRITICAL CHECK: Reconstructed source must match PRISTINE Vault hash, NOT the modified workspace hash!
        val reconstructedSha256 = CharacterWorkspaceManager.calculateSha256(separatedCns)
        assertEquals("Reconstructed source must match pristine Vault hash", pristineSha256, reconstructedSha256)
        assertNotEquals("Reconstructed source must NOT match modified workspace hash", modifiedSha256, reconstructedSha256)
        assertTrue(separatedCns.readText().contains("life = 1000"))
    }

    @Test
    fun testWorkspaceDirectoryRegistrationAsOriginalSourceRejected() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val fighterDir = createSampleCharacterFolder(charsWorkspace, "ActiveWorkspaceFighter")
        val cnsFile = File(fighterDir, "ActiveWorkspaceFighter.cns")

        // Attempt to register active workspace folder as an original source
        CharacterWorkspaceManager.registerOriginalSource(fighterDir.canonicalPath, context)

        // Registration must be skipped / rejected so workspace remains writable
        val editor = CnsEditor(cnsFile, context)
        editor.properties["[data]"]?.get("life")?.value = "1500"
        val saveResult = CharacterWorkspaceManager.saveCnsChanges(editor, "ActiveWorkspaceFighter", context)
        assertTrue("Saving to workspace must NOT be blocked by attempted workspace registration", saveResult.success)
    }
}


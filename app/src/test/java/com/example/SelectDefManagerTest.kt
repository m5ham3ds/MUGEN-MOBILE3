package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.CharacterValidationStatus
import com.example.engine.CharacterValidator
import com.example.engine.SelectDefManager
import com.example.engine.ValidatedCharacter
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
class SelectDefManagerTest {

    private lateinit var context: Context
    private lateinit var tempGameDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tempGameDir = File(context.cacheDir, "test_game_${System.currentTimeMillis()}")
        tempGameDir.mkdirs()
        MugenMobileStorage.setCustomBaseDir(context, tempGameDir.absolutePath)
    }

    @Test
    fun testBrokenDummyEntryDetection() {
        assertTrue(SelectDefManager.isBrokenDummyEntry("chars/"))
        assertTrue(SelectDefManager.isBrokenDummyEntry("chars\\"))
        assertTrue(SelectDefManager.isBrokenDummyEntry("chars"))
        assertTrue(SelectDefManager.isBrokenDummyEntry("  chars/  "))
        assertTrue(SelectDefManager.isBrokenDummyEntry(""))
        assertTrue(SelectDefManager.isBrokenDummyEntry("stages/default/"))

        assertFalse(SelectDefManager.isBrokenDummyEntry("kfm"))
        assertFalse(SelectDefManager.isBrokenDummyEntry("kfm/kfm.def"))
        assertFalse(SelectDefManager.isBrokenDummyEntry("Goku/ssj.def, random"))
        assertFalse(SelectDefManager.isBrokenDummyEntry("randomselect"))
    }

    @Test
    fun testKeyExtractionAndNormalization() {
        assertEquals("kfm", SelectDefManager.extractCharacterKey("kfm, stages/mybg.def"))
        assertEquals("kfm", SelectDefManager.extractCharacterKey("kfm, random, order = 2"))
        assertEquals("Goku/ssj.def", SelectDefManager.extractCharacterKey("Goku/ssj.def, stages/field.def"))
        assertEquals("kfm/kfm.def", SelectDefManager.extractCharacterKey("chars/kfm/kfm.def"))

        assertEquals("kfm", SelectDefManager.normalizeKey("kfm"))
        assertEquals("kfm", SelectDefManager.normalizeKey("kfm.def"))
        assertEquals("kfm", SelectDefManager.normalizeKey("kfm/kfm"))
        assertEquals("kfm", SelectDefManager.normalizeKey("kfm/kfm.def"))
        assertEquals("kfm", SelectDefManager.normalizeKey("chars/kfm/kfm.def"))
        assertEquals("goku/ssj", SelectDefManager.normalizeKey("Goku/ssj.def"))
    }

    @Test
    fun testBuildSelectEntry() {
        assertEquals("kfm", CharacterValidator.buildSelectEntry("kfm", "kfm.def"))
        assertEquals("Ryu", CharacterValidator.buildSelectEntry("Ryu", "ryu.def"))
        assertEquals("Goku/ssj.def", CharacterValidator.buildSelectEntry("Goku", "ssj.def"))
        assertEquals("Ken/ken_cvs.def", CharacterValidator.buildSelectEntry("Ken", "ken_cvs.def"))
    }

    @Test
    fun testSelectDefSyncWithDeduplicationAndCommentPreservation() {
        val dataDir = File(tempGameDir, "data")
        dataDir.mkdirs()
        val selectDef = File(dataDir, "select.def")

        // Initial select.def with existing characters, comments, options, and broken chars/ lines
        val initialContent = """
            ;---------------------------------------------------------------------
            [Characters]
            ; Street Fighter characters
            Ryu, stages/sf_stage.def, order = 1
            ; Broken placeholder lines that should be purged
            chars/
            chars/
            
            ; Other fighters
            Ken
            randomselect

            ;---------------------------------------------------------------------
            [ExtraStages]
            stages/default/Training Room.def
            stages/default/

            ;---------------------------------------------------------------------
            [Options]
            arcade.maxmatches = 6,1,1,0,0,0,0,0,0,0
            team.maxmatches = 4,1,1,0,0,0,0,0,0,0
        """.trimIndent()
        selectDef.writeText(initialContent)

        // Validated characters:
        // - Ryu: already present (should NOT be duplicated)
        // - Ken: already present (should NOT be duplicated)
        // - ChunLi: new valid character (should be ADDED)
        // - Goku: new valid character with custom def name (should be ADDED as Goku/ssj.def)
        val dummyFolder1 = File(tempGameDir, "chars/Ryu")
        val dummyFolder2 = File(tempGameDir, "chars/Ken")
        val dummyFolder3 = File(tempGameDir, "chars/ChunLi")
        val dummyFolder4 = File(tempGameDir, "chars/Goku")

        val charList = listOf(
            ValidatedCharacter(
                folder = dummyFolder1, folderName = "Ryu", defFile = File(dummyFolder1, "Ryu.def"),
                defFileName = "Ryu.def", selectEntry = "Ryu", displayName = "Ryu", author = "Capcom",
                status = CharacterValidationStatus.VALID, sffFile = null, airFile = null, cnsFile = null, cmdFile = null,
                missingFiles = emptyList(), hasSmallPortrait = true, hasBigPortrait = true, previewUri = null,
                validationMessage = "Ready"
            ),
            ValidatedCharacter(
                folder = dummyFolder2, folderName = "Ken", defFile = File(dummyFolder2, "Ken.def"),
                defFileName = "Ken.def", selectEntry = "Ken", displayName = "Ken", author = "Capcom",
                status = CharacterValidationStatus.VALID, sffFile = null, airFile = null, cnsFile = null, cmdFile = null,
                missingFiles = emptyList(), hasSmallPortrait = true, hasBigPortrait = true, previewUri = null,
                validationMessage = "Ready"
            ),
            ValidatedCharacter(
                folder = dummyFolder3, folderName = "ChunLi", defFile = File(dummyFolder3, "ChunLi.def"),
                defFileName = "ChunLi.def", selectEntry = "ChunLi", displayName = "Chun-Li", author = "Capcom",
                status = CharacterValidationStatus.VALID, sffFile = null, airFile = null, cnsFile = null, cmdFile = null,
                missingFiles = emptyList(), hasSmallPortrait = true, hasBigPortrait = true, previewUri = null,
                validationMessage = "Ready"
            ),
            ValidatedCharacter(
                folder = dummyFolder4, folderName = "Goku", defFile = File(dummyFolder4, "ssj.def"),
                defFileName = "ssj.def", selectEntry = "Goku/ssj.def", displayName = "Son Goku", author = "Bandai",
                status = CharacterValidationStatus.VALID_MISSING_PORTRAIT, sffFile = null, airFile = null, cnsFile = null, cmdFile = null,
                missingFiles = emptyList(), hasSmallPortrait = false, hasBigPortrait = true, previewUri = null,
                validationMessage = "Ready, missing small icon"
            )
        )

        // Execute sync
        val syncResult = SelectDefManager.syncCharacters(context, charList)

        assertTrue("Sync should succeed", syncResult.success)
        assertTrue("Sync should report changes", syncResult.hasChanges)
        assertEquals(listOf("ChunLi", "Goku/ssj.def"), syncResult.addedCharacters)
        assertTrue(syncResult.purgedBrokenEntries.contains("chars/"))
        assertEquals(4, syncResult.totalRegistered) // Ryu, Ken, ChunLi, Goku

        // Check backup file
        assertNotNull(syncResult.backupPath)
        val bakFile = File(syncResult.backupPath!!)
        assertTrue("Backup file must exist", bakFile.exists())
        assertEquals(initialContent, bakFile.readText())

        // Read updated select.def
        val updatedText = selectDef.readText()

        // 1. Existing character with options preserved
        assertTrue(updatedText.contains("Ryu, stages/sf_stage.def, order = 1"))
        // 2. Existing Ken preserved
        assertTrue(updatedText.contains("Ken"))
        // 3. New characters added
        assertTrue(updatedText.contains("ChunLi"))
        assertTrue(updatedText.contains("Goku/ssj.def"))
        // 4. Broken dummy lines removed
        assertFalse(updatedText.contains("\nchars/\n"))
        // 5. Sections and options preserved
        assertTrue(updatedText.contains("[ExtraStages]"))
        assertTrue(updatedText.contains("[Options]"))
        assertTrue(updatedText.contains("arcade.maxmatches = 6,1,1,0,0,0,0,0,0,0"))
        // 6. Comments preserved
        assertTrue(updatedText.contains("; Street Fighter characters"))

        // 7. Verify Idempotence: running sync again produces NO duplicates and NO changes
        val secondSync = SelectDefManager.syncCharacters(context, charList)
        assertTrue("Second sync should succeed", secondSync.success)
        assertFalse("Second sync should have no changes", secondSync.hasChanges)
        assertTrue("No new characters added on second run", secondSync.addedCharacters.isEmpty())
        assertEquals(4, secondSync.totalRegistered)

        tempGameDir.deleteRecursively()
    }

    @Test
    fun testCharacterValidatorFolderWithoutDef() {
        val charDir = File(tempGameDir, "chars/EmptyGuy")
        charDir.mkdirs()

        val validated = CharacterValidator.validateFolder(charDir, context)
        assertEquals(CharacterValidationStatus.INVALID_NO_DEF, validated.status)
        assertFalse(validated.hasSmallPortrait)
        assertFalse(validated.hasBigPortrait)
        assertTrue(validated.missingFiles.isNotEmpty())

        tempGameDir.deleteRecursively()
    }

    @Test
    fun testCharacterValidatorMissingEssentialFiles() {
        val charDir = File(tempGameDir, "chars/BrokenGuy")
        charDir.mkdirs()

        // Write a .def that points to non-existent .sff and .air
        val defContent = """
            [Info]
            name = "Broken Guy"
            displayname = "Broken"
            author = "Unknown"

            [Files]
            cmd = broken.cmd
            cns = broken.cns
            st = broken.cns
            sprite = broken.sff
            anim = broken.air
        """.trimIndent()
        File(charDir, "BrokenGuy.def").writeText(defContent)

        val validated = CharacterValidator.validateFolder(charDir, context)
        assertEquals(CharacterValidationStatus.INVALID_MISSING_ESSENTIAL_FILES, validated.status)
        assertTrue("Missing files list should include .sff and .air", validated.missingFiles.size >= 2)

        tempGameDir.deleteRecursively()
    }

    @Test
    fun testCharacterValidatorValidCharacter() {
        val charDir = File(tempGameDir, "chars/GoodGuy")
        charDir.mkdirs()

        // Create actual files
        File(charDir, "good.cmd").writeText("; cmd")
        File(charDir, "good.cns").writeText("; cns\n[data]\nlife = 1000")
        File(charDir, "good.air").writeText("; air")
        File(charDir, "good.sff").writeText("dummy sff")

        val defContent = """
            [Info]
            name = "GoodGuy"
            displayname = "Good Guy"
            author = "Tester"

            [Files]
            cmd = good.cmd
            cns = good.cns
            st = good.cns
            sprite = good.sff
            anim = good.air
        """.trimIndent()
        File(charDir, "GoodGuy.def").writeText(defContent)

        val validated = CharacterValidator.validateFolder(charDir, context)
        // Since dummy sff doesn't have 9000 sprites, status is VALID_MISSING_PORTRAIT (playable, missing portrait)
        assertEquals(CharacterValidationStatus.VALID_MISSING_PORTRAIT, validated.status)
        assertEquals("Good Guy", validated.displayName)
        assertEquals("GoodGuy", validated.selectEntry)
        assertTrue(validated.missingFiles.isEmpty())

        tempGameDir.deleteRecursively()
    }
}

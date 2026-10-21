package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.storage.MugenMobileStorage
import com.example.ui.screens.characters.CompleteCharacterEditorDialog
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Compose UI acceptance tests for CompleteCharacterEditorDialog in PHASE 3.1.
 *
 * Verifies real user-facing behavior:
 * 1. Initial file values loading and audit status badge display.
 * 2. Live instant search filtering.
 * 3. Unsaved changes tracking and cancel protection dialog.
 * 4. Restoration disabling when vault is unverified or missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteCharacterEditorUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var tempGameDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempGameDir = File(context.cacheDir, "test_ui_editor_${System.currentTimeMillis()}").apply { mkdirs() }
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
            ; Character Definition
            [Info]
            name = "$charName"
            displayname = "$charName Champion"
            author = "UiDev"
            versiondate = "2026-10-20"
            mugenversion = "1.0"
            localcoord = 320,240
            
            [Files]
            cmd = $charName.cmd
            cns = $charName.cns
            anim = $charName.air
            sprite = $charName.sff
            sound = $charName.snd
            st = $charName.cns
        """.trimIndent()
        File(charDir, "$charName.def").writeText(defContent)

        val cnsContent = """
            [Data]
            life = 1000
            attack = 100
            defence = 100
            fall.defence_up = 50
            airjuggle = 15
            sparkno = 2
            guard.sparkno = 40
            
            [Size]
            xscale = 1
            yscale = 1
            head.pos = -5, -90
            mid.pos = -5, -60
            
            [Velocity]
            walk.fwd = 2.4
            walk.back = -2.2
            jump.neu = 0, -8.4
            
            [Movement]
            airjump.num = 0
            airjump.height = 35
        """.trimIndent()
        File(charDir, "$charName.cns").writeText(cnsContent)
        File(charDir, "$charName.sff").writeBytes(ByteArray(64))

        return charDir
    }

    @Test
    fun testDialogRendersInitialCharacterDataFromFiles() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charFolder = createSampleCharacter(charsWorkspace, "UiHero")
        CharacterWorkspaceManager.ensureVaultSnapshot(charFolder, context)

        val charData = CharacterData(
            folderName = "UiHero",
            displayName = "UiHero Champion",
            imageUri = null,
            cnsFile = File(charFolder, "UiHero.cns"),
            defFile = File(charFolder, "UiHero.def"),
            defFileName = "UiHero.def"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                CompleteCharacterEditorDialog(
                    character = charData,
                    onDismiss = {}
                )
            }
        }

        // Verify title & folder metadata rendered (header text + textfield = 2 nodes)
        composeTestRule.onAllNodesWithText("UiHero Champion").assertCountEquals(2)
        composeTestRule.onNodeWithText("المجلد: UiHero | .def: UiHero.def").assertIsDisplayed()

        // Verify sections rendered in hierarchy
        composeTestRule.onNodeWithText("1. هوية المقاتل (.def)").assertIsDisplayed()
        composeTestRule.onNodeWithText("2. الحيوية والهجوم والدفاع (.cns - [Data])").assertExists()

        // Verify initial life value loaded in composition tree
        composeTestRule.onNodeWithText("1000").assertExists()
    }

    @Test
    fun testSaveButtonDisabledWhenVaultIsCorrupted() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charFolder = createSampleCharacter(charsWorkspace, "UiCorruptVaultHero")
        val snapshot = CharacterWorkspaceManager.ensureVaultSnapshot(charFolder, context)
        assertTrue(snapshot)
        val vaultCns = File(File(CharacterWorkspaceManager.getVaultDir(context), "UiCorruptVaultHero"), "files/UiCorruptVaultHero.cns")
        vaultCns.setWritable(true)
        vaultCns.writeText("tampered vault bytes")
        vaultCns.setReadOnly()

        val charData = CharacterData(
            folderName = "UiCorruptVaultHero",
            displayName = "UiCorruptVaultHero Champion",
            imageUri = null,
            cnsFile = File(charFolder, "UiCorruptVaultHero.cns"),
            defFile = File(charFolder, "UiCorruptVaultHero.def"),
            defFileName = "UiCorruptVaultHero.def"
        )
        composeTestRule.setContent {
            MyApplicationTheme {
                CompleteCharacterEditorDialog(character = charData, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("حفظ التعديلات").assertIsNotEnabled()
        composeTestRule.onNodeWithText("خزينة غير صالحة").assertIsDisplayed()
    }

    @Test
    fun testLiveSearchFiltersSectionsAndFields() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charFolder = createSampleCharacter(charsWorkspace, "SearchHero")
        CharacterWorkspaceManager.ensureVaultSnapshot(charFolder, context)

        val charData = CharacterData(
            folderName = "SearchHero",
            displayName = "SearchHero Champion",
            imageUri = null,
            cnsFile = File(charFolder, "SearchHero.cns"),
            defFile = File(charFolder, "SearchHero.def"),
            defFileName = "SearchHero.def"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                CompleteCharacterEditorDialog(
                    character = charData,
                    onDismiss = {}
                )
            }
        }

        // Search for "هجوم"
        composeTestRule.onNodeWithText("بحث فوري في الحقول والخصائص...").performTextInput("هجوم")

        // Section 2 matches and is displayed
        composeTestRule.onNodeWithText("2. الحيوية والهجوم والدفاع (.cns - [Data])").assertIsDisplayed()

        // Section 1 does not match "هجوم" and should disappear
        composeTestRule.onNodeWithText("1. هوية المقاتل (.def)").assertDoesNotExist()
    }

    @Test
    fun testUnsavedChangesTrackingAndCancelConfirmation() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charFolder = createSampleCharacter(charsWorkspace, "CancelHero")
        CharacterWorkspaceManager.ensureVaultSnapshot(charFolder, context)

        val charData = CharacterData(
            folderName = "CancelHero",
            displayName = "CancelHero Champion",
            imageUri = null,
            cnsFile = File(charFolder, "CancelHero.cns"),
            defFile = File(charFolder, "CancelHero.def"),
            defFileName = "CancelHero.def"
        )

        var dismissed = false
        composeTestRule.setContent {
            MyApplicationTheme {
                CompleteCharacterEditorDialog(
                    character = charData,
                    onDismiss = { dismissed = true }
                )
            }
        }

        // Initially no unsaved badge
        composeTestRule.onNodeWithText("تعديلات معلقة غير محفوظة (1 حقول معدلة)").assertDoesNotExist()

        // Change display name using the editable text field specifically
        composeTestRule.onNode(hasSetTextAction() and hasText("CancelHero Champion")).performTextReplacement("New Name")

        // Unsaved changes badge must appear
        composeTestRule.onNodeWithText("تعديلات معلقة غير محفوظة (1 حقول معدلة)").assertIsDisplayed()

        // Click Cancel
        composeTestRule.onNodeWithText("إلغاء").performClick()

        // Must show confirmation dialog
        composeTestRule.onNodeWithText("تجاهل التعديلات؟").assertIsDisplayed()
        assertFalse("Dialog should not be dismissed yet", dismissed)

        // Confirm cancellation
        composeTestRule.onNodeWithText("تجاهل وإغلاق").performClick()
        assertTrue("Dialog should be dismissed after confirming cancellation", dismissed)
    }

    @Test
    fun testRestoreButtonDisabledWhenVaultMissing() {
        val charsWorkspace = MugenMobileStorage.getCharsDir(context).apply { mkdirs() }
        val charFolder = createSampleCharacter(charsWorkspace, "NoVaultHero")
        // NOTE: No Vault snapshot is captured!

        val charData = CharacterData(
            folderName = "NoVaultHero",
            displayName = "NoVaultHero Champion",
            imageUri = null,
            cnsFile = File(charFolder, "NoVaultHero.cns"),
            defFile = File(charFolder, "NoVaultHero.def"),
            defFileName = "NoVaultHero.def"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                CompleteCharacterEditorDialog(
                    character = charData,
                    onDismiss = {}
                )
            }
        }

        // Restore button must be disabled/forbidden
        composeTestRule.onNodeWithText("الاستعادة محظورة (سلامة الأصل غير مثبتة)").assertIsDisplayed()
        composeTestRule.onNodeWithText("استعادة الأصل").assertDoesNotExist()
    }
}

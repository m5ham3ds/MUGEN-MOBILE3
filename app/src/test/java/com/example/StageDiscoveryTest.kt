package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.MugenParser
import com.example.engine.SelectDefManager
import com.example.engine.StageData
import com.example.engine.StageValidationStatus
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
class StageDiscoveryTest {

    private lateinit var context: Context
    private lateinit var tempGameDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tempGameDir = File(context.cacheDir, "stage_test_${System.currentTimeMillis()}")
        tempGameDir.mkdirs()
        MugenMobileStorage.setCustomBaseDir(context, tempGameDir.absolutePath)
    }

    @Test
    fun testRecursiveStageDiscoveryAndValidation() {
        val stagesDir = File(tempGameDir, "stages")
        stagesDir.mkdirs()

        // 1. Root level stage: stages/flat_stage.def with flat_stage.sff
        val flatDef = File(stagesDir, "flat_stage.def")
        flatDef.writeText(
            """
            [Info]
            name = "Flat Arena"
            displayname = "Flat Arena"
            author = "TestAuthor"

            [Camera]
            startx = 0

            [BGdef]
            spr = flat_stage.sff
            """.trimIndent()
        )
        File(stagesDir, "flat_stage.sff").writeText("dummy sff content")

        // 2. Nested stage: stages/default/Training Room.def with default.sff
        val subDir = File(stagesDir, "default")
        subDir.mkdirs()
        val trainingDef = File(subDir, "Training Room.def")
        trainingDef.writeText(
            """
            [Info]
            name = "Training Room"
            displayname = "Training Room"
            author = "Elecbyte"

            [Camera]
            startx = 0

            [BGdef]
            spr = default.sff
            """.trimIndent()
        )
        File(subDir, "default.sff").writeText("dummy default sff")

        // 3. Broken stage: stages/broken/broken.def pointing to missing SFF
        val brokenDir = File(stagesDir, "broken")
        brokenDir.mkdirs()
        val brokenDef = File(brokenDir, "broken.def")
        brokenDef.writeText(
            """
            [Info]
            name = "Broken Stage"
            displayname = "Broken Stage"

            [BGdef]
            spr = missing_nowhere.sff
            """.trimIndent()
        )

        // Execute discovery
        val discoveredStages = MugenParser.getStages(context)
        assertEquals("Should have discovered all 3 stages recursively", 3, discoveredStages.size)

        val flatStage = discoveredStages.find { it.defFileName == "flat_stage.def" }
        assertNotNull(flatStage)
        assertEquals("Flat Arena", flatStage!!.displayName)
        assertEquals("stages/flat_stage.def", flatStage.relativePath)
        assertEquals(StageValidationStatus.READY, flatStage.status)

        val trainingStage = discoveredStages.find { it.defFileName == "Training Room.def" }
        assertNotNull(trainingStage)
        assertEquals("Training Room", trainingStage!!.displayName)
        assertEquals("stages/default/Training Room.def", trainingStage.relativePath)
        assertEquals(StageValidationStatus.READY, trainingStage.status)

        val brokenStage = discoveredStages.find { it.defFileName == "broken.def" }
        assertNotNull(brokenStage)
        assertEquals(StageValidationStatus.MISSING_SFF, brokenStage!!.status)

        tempGameDir.deleteRecursively()
    }

    @Test
    fun testSelectDefExtraStagesManagement() {
        val dataDir = File(tempGameDir, "data")
        dataDir.mkdirs()
        val selectDef = File(dataDir, "select.def")
        selectDef.writeText(
            """
            [Characters]
            kfm

            [ExtraStages]
            stages/default/Training Room.def

            [Options]
            arcade.maxmatches = 6
            """.trimIndent()
        )

        // 1. Query extra stages
        val initialStages = SelectDefManager.getRegisteredExtraStages(context)
        assertEquals(listOf("stages/default/Training Room.def"), initialStages)
        assertTrue(SelectDefManager.isExtraStageRegistered(context, "stages/default/Training Room.def"))
        assertFalse(SelectDefManager.isExtraStageRegistered(context, "stages/new_arena.def"))

        // 2. Add extra stage
        val addResult = SelectDefManager.addExtraStage(context, "stages/new_arena.def")
        assertTrue("Adding new stage should succeed", addResult)
        assertTrue(SelectDefManager.isExtraStageRegistered(context, "stages/new_arena.def"))

        // Check updated file content
        val updatedText = selectDef.readText()
        assertTrue(updatedText.contains("stages/new_arena.def"))
        assertTrue("Options section must be preserved", updatedText.contains("[Options]"))

        // 3. Remove extra stage
        val removeResult = SelectDefManager.removeExtraStage(context, "stages/default/Training Room.def")
        assertTrue("Removing stage should succeed", removeResult)
        assertFalse(SelectDefManager.isExtraStageRegistered(context, "stages/default/Training Room.def"))
        assertTrue(SelectDefManager.isExtraStageRegistered(context, "stages/new_arena.def"))

        // 4. Sync all ready stages
        val readyStages = listOf(
            StageData("stage1.def", "Stage 1", relativePath = "stages/stage1.def", status = StageValidationStatus.READY),
            StageData("stage2.def", "Stage 2", relativePath = "stages/stage2.def", status = StageValidationStatus.READY),
            StageData("broken.def", "Broken", relativePath = "stages/broken.def", status = StageValidationStatus.MISSING_SFF)
        )

        val addedCount = SelectDefManager.syncAllReadyStages(context, readyStages)
        assertEquals("Should add 2 ready stages (ignoring broken one)", 2, addedCount)
        assertTrue(SelectDefManager.isExtraStageRegistered(context, "stages/stage1.def"))
        assertTrue(SelectDefManager.isExtraStageRegistered(context, "stages/stage2.def"))
        assertFalse(SelectDefManager.isExtraStageRegistered(context, "stages/broken.def"))

        tempGameDir.deleteRecursively()
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.engine.gemini.GeminiCharacterProposal
import com.example.engine.gemini.GeminiRosterBalancer
import com.example.utils.GeminiKeyStoreManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GeminiKeyStoreManager.deleteApiKey(context)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testSaveAndRetrieveApiKey() {
        val testKey = "AIzaSyDummyKeyForTesting-123456789"

        assertFalse("Key should not exist initially", GeminiKeyStoreManager.hasApiKey(context))
        assertNull("Initial key retrieval should be null", GeminiKeyStoreManager.getApiKey(context))

        val saved = GeminiKeyStoreManager.saveApiKey(context, testKey)
        assertTrue("Saving valid key must succeed", saved)

        assertTrue("hasApiKey must return true after save", GeminiKeyStoreManager.hasApiKey(context))
        val retrieved = GeminiKeyStoreManager.getApiKey(context)
        assertEquals("Retrieved key must match saved key exactly", testKey, retrieved)
    }

    @Test
    fun testDeleteApiKey() {
        val testKey = "AIzaSyKeyToDelete-987654321"
        GeminiKeyStoreManager.saveApiKey(context, testKey)
        assertTrue(GeminiKeyStoreManager.hasApiKey(context))

        val deleted = GeminiKeyStoreManager.deleteApiKey(context)
        assertTrue("Deleting key must succeed", deleted)
        assertFalse("hasApiKey must be false after deletion", GeminiKeyStoreManager.hasApiKey(context))
        assertNull("getApiKey must return null after deletion", GeminiKeyStoreManager.getApiKey(context))
    }

    @Test
    fun testApiKeyMasking() {
        // Standard length API key
        val longKey = "AIzaSyD-1234567890abcdef"
        val masked = GeminiKeyStoreManager.maskKey(longKey)
        assertEquals("AIza••••••••cdef", masked)
        assertFalse("Masked key must not expose middle characters", masked.contains("1234567890"))

        // Short key (<= 8 chars)
        val shortKey = "12345678"
        assertEquals("••••••••", GeminiKeyStoreManager.maskKey(shortKey))

        // Empty and null keys
        assertEquals("", GeminiKeyStoreManager.maskKey(""))
        assertEquals("", GeminiKeyStoreManager.maskKey(null))
    }

    @Test
    fun testIvUniquenessAndRandomness() {
        val key = "AIzaSyTestingUniqueIVsEveryTime"

        // First encryption
        GeminiKeyStoreManager.saveApiKey(context, key)
        val storageFile = File(context.noBackupFilesDir, "gemini_secure_key.dat")
        assertTrue(storageFile.exists())
        val firstIv = storageFile.readBytes().copyOfRange(0, 12)

        // Second encryption with same key
        GeminiKeyStoreManager.saveApiKey(context, key)
        val secondIv = storageFile.readBytes().copyOfRange(0, 12)

        assertEquals("IV length must be exactly 12 bytes", 12, firstIv.size)
        assertEquals("IV length must be exactly 12 bytes", 12, secondIv.size)
        assertFalse(
            "Every encryption operation must generate a new unique random IV",
            firstIv.contentEquals(secondIv)
        )
    }

    @Test
    fun testCorruptedDataRecovery() {
        // Save valid key first
        GeminiKeyStoreManager.saveApiKey(context, "AIzaSyValidBeforeCorruption")
        val storageFile = File(context.noBackupFilesDir, "gemini_secure_key.dat")
        assertTrue(storageFile.exists())

        // Overwrite file with corrupt garbage bytes
        FileOutputStream(storageFile).use {
            it.write(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0xFF.toByte(), 0xEE.toByte()))
        }

        // Must handle corruption safely without crashing
        val recovered = GeminiKeyStoreManager.getApiKey(context)
        assertNull("Corrupted data must return null safely", recovered)
        assertFalse("Corrupted key file must be purged", GeminiKeyStoreManager.hasApiKey(context))
    }

    @Test
    fun testGeminiRosterBalancerClamping() {
        val config = RosterBalancingEngine.DEFAULT_CONFIG

        // Clamping Life
        assertEquals(config.lifeDampingCap, GeminiRosterBalancer.clampStat(StatType.LIFE, 99999, config))
        assertEquals(config.lifeDampingFloor, GeminiRosterBalancer.clampStat(StatType.LIFE, 10, config))
        assertEquals(1000, GeminiRosterBalancer.clampStat(StatType.LIFE, 1000, config))

        // Clamping Attack
        assertEquals(config.attackDampingCap, GeminiRosterBalancer.clampStat(StatType.ATTACK, 500, config))
        assertEquals(config.attackDampingFloor, GeminiRosterBalancer.clampStat(StatType.ATTACK, 20, config))
        assertEquals(105, GeminiRosterBalancer.clampStat(StatType.ATTACK, 105, config))

        // Clamping Defence
        assertEquals(config.defenceDampingCap, GeminiRosterBalancer.clampStat(StatType.DEFENCE, 600, config))
        assertEquals(config.defenceDampingFloor, GeminiRosterBalancer.clampStat(StatType.DEFENCE, 15, config))
        assertEquals(95, GeminiRosterBalancer.clampStat(StatType.DEFENCE, 95, config))
    }

    @Test
    fun testGeminiRosterBalancerMergeAndValidation() {
        val config = RosterBalancingEngine.DEFAULT_CONFIG
        val inputs = listOf(
            CharacterStatInput("BossChar", "Boss Bison", 3200, 250, 180),
            CharacterStatInput("NormalChar", "Ryu", 1000, 100, 100)
        )

        val baselineSummary = RosterBalancingEngine.analyzeRoster(inputs, config)

        // Simulated raw Gemini output with wild, unconstrained numbers
        val rawGeminiProposals = listOf(
            GeminiCharacterProposal(
                characterId = "BossChar",
                proposedLife = 99999, // Exceeds cap
                proposedAttack = 500, // Exceeds cap
                proposedDefence = 400, // Exceeds cap
                archetype = "Boss",
                rationale = "كبح قوة الزعيم مع الإبقاء على هيبته القتالية."
            )
        )

        val validatedSummary = GeminiRosterBalancer.mergeAndValidateGeminiProposals(
            inputs = inputs,
            baselineSummary = baselineSummary,
            geminiProposals = rawGeminiProposals,
            config = config
        )

        val bossProposal = validatedSummary.proposals.first { it.characterId == "BossChar" }
        assertEquals("Life must be clamped to allowable cap", config.lifeDampingCap, bossProposal.proposedLife)
        assertEquals("Attack must be clamped to allowable cap", config.attackDampingCap, bossProposal.proposedAttack)
        assertEquals("Defence must be clamped to allowable cap", config.defenceDampingCap, bossProposal.proposedDefence)
        assertTrue("Rationale must be preserved", bossProposal.overallExplanation.contains("كبح قوة الزعيم"))
        assertTrue("Must include [Gemini AI] tag", bossProposal.overallExplanation.contains("[Gemini AI]"))
    }

    @Test
    fun testFallbackWhenGeminiFailsOrKeyMissing() = runBlocking {
        val inputs = listOf(
            CharacterStatInput("Ryu", "Ryu", 1000, 100, 100),
            CharacterStatInput("Dan", "Dan", 700, 70, 75)
        )

        // 1. Missing / null API key
        val resultNoKey = GeminiRosterBalancer.analyze(inputs, null)
        assertFalse("Must not be AI powered without key", resultNoKey.isAiPowered)
        assertNull(resultNoKey.aiModelName)
        assertNull(resultNoKey.aiErrorMessage)
        assertEquals(2, resultNoKey.summary.totalCharactersScanned)
        assertEquals(1, resultNoKey.summary.charactersNeedingAdjustmentCount)

        // 2. Empty API key
        val resultEmptyKey = GeminiRosterBalancer.analyze(inputs, "   ")
        assertFalse(resultEmptyKey.isAiPowered)
        assertEquals(2, resultEmptyKey.summary.totalCharactersScanned)
    }

    @Test
    fun testNoDiskModificationDuringAnalysis() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "gemini_analysis_immutability_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val cnsContent = """
                [Data]
                life = 3500
                attack = 200
                defence = 150
            """.trimIndent()
            val cnsFile = File(tempDir, "fighter.cns")
            cnsFile.writeText(cnsContent)

            val originalHash = sha256(cnsFile)
            val originalLength = cnsFile.length()

            val charData = CharacterData(
                folderName = "ImmFighter",
                displayName = "Immutable Fighter",
                imageUri = null,
                cnsFile = cnsFile
            )

            val summary = RosterBalancingEngine.analyzeCharacterDataRoster(listOf(charData))
            assertEquals(1, summary.charactersNeedingAdjustmentCount)

            // Verify file was never touched
            assertEquals(originalLength, cnsFile.length())
            assertEquals(originalHash, sha256(cnsFile))
            assertEquals(cnsContent, cnsFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSanitizeErrorMessageRedactsKeys() {
        val rawSecret = "AIzaSyD1234567890abcdefghijklmnopqrstuv"
        val rawMessage = "Invalid request containing secret $rawSecret in header"
        val sanitized = com.example.engine.gemini.GeminiApiClient.sanitizeErrorMessage(rawMessage, rawSecret)

        assertFalse("Sanitized message must not contain raw secret", sanitized.contains(rawSecret))
        assertTrue("Sanitized message must contain redaction placeholder", sanitized.contains("[REDACTED_API_KEY]"))
    }

    @Test
    fun testEmptyKeyValidation() = runBlocking {
        val result = com.example.engine.gemini.GeminiApiClient.testConnection("")
        assertTrue("Must be an error result", result is com.example.engine.gemini.GeminiResult.Error)
        val error = result as com.example.engine.gemini.GeminiResult.Error
        assertEquals(400, error.code)
        assertTrue(error.isAuthError)
        assertTrue(error.message.contains("لم يتم إدخال مفتاح"))
    }
}

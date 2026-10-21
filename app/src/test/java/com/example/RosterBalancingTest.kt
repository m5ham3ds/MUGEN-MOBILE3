package com.example

import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Unit Test Suite for PHASE 4.1 — Pure Roster Balancing Engine.
 *
 * Validates:
 * 1. Correct statistical calculations (mean, median, stdDev, quartiles, IQR).
 * 2. Robust handling of edge rosters (empty, single character, invalid/missing data).
 * 3. Identity-preserving policies (normal values generate no proposals).
 * 4. Exactness of all mandatory example calculations (3000->1350, 500->750, 250->125, 60->80).
 * 5. Respecting lower and upper bounds after rounding.
 * 6. Exclusion of no-op proposals (proposals that result in zero change).
 * 7. Generation of rich, human-readable Arabic explanations for all adjustments.
 * 8. Detection of extreme outliers vs mild out-of-target deviations.
 * 9. Preservation and immutability of character IDs, names, and original values.
 * 10. Strict determinism: identical inputs produce identical outputs across repeated runs.
 * 11. Boundary edge cases around target thresholds.
 * 12. Verification that no file writes, saves, or mutations occur during analysis.
 */
class RosterBalancingTest {

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testStatisticsCalculation() {
        // Known dataset: 800, 900, 1000, 1100, 1200
        val values = listOf(800, 900, 1000, 1100, 1200)
        val metrics = RosterBalancingEngine.calculateMetrics(values)

        assertNotNull("Metrics must not be null for non-empty list", metrics)
        assertEquals(5, metrics!!.count)
        assertEquals(800, metrics.min)
        assertEquals(1200, metrics.max)
        assertEquals(1000.0, metrics.mean, 0.001)
        assertEquals(1000.0, metrics.median, 0.001)
        assertEquals(850.0, metrics.q1, 0.001)
        assertEquals(1150.0, metrics.q3, 0.001)
        assertEquals(300.0, metrics.iqr, 0.001)
        // Variance = (40000 + 10000 + 0 + 10000 + 40000) / 5 = 20000; stdDev = sqrt(20000) ≈ 141.421
        assertEquals(141.421, metrics.standardDeviation, 0.01)
    }

    @Test
    fun testEmptyAndSingleCharacterRosters() {
        // 1. Empty roster
        val emptySummary = RosterBalancingEngine.analyzeRoster(emptyList())
        assertEquals(0, emptySummary.totalCharactersScanned)
        assertEquals(0, emptySummary.analyzedCharactersCount)
        assertEquals(0, emptySummary.balancedCharactersCount)
        assertEquals(0, emptySummary.charactersNeedingAdjustmentCount)
        assertNull(emptySummary.lifeMetrics)
        assertNull(emptySummary.attackMetrics)
        assertNull(emptySummary.defenceMetrics)
        assertTrue(emptySummary.proposals.isEmpty())

        // 2. Single character roster
        val singleInput = listOf(
            CharacterStatInput(
                characterId = "Ryu",
                displayName = "Ryu",
                life = 1000,
                attack = 100,
                defence = 100
            )
        )
        val singleSummary = RosterBalancingEngine.analyzeRoster(singleInput)
        assertEquals(1, singleSummary.totalCharactersScanned)
        assertEquals(1, singleSummary.analyzedCharactersCount)
        assertEquals(1, singleSummary.balancedCharactersCount)
        assertEquals(0, singleSummary.charactersNeedingAdjustmentCount)

        val lifeM = singleSummary.lifeMetrics
        assertNotNull(lifeM)
        assertEquals(1, lifeM!!.count)
        assertEquals(1000, lifeM.min)
        assertEquals(1000, lifeM.max)
        assertEquals(1000.0, lifeM.mean, 0.001)
        assertEquals(1000.0, lifeM.median, 0.001)
        assertEquals(0.0, lifeM.standardDeviation, 0.001)
        assertEquals(1000.0, lifeM.q1, 0.001)
        assertEquals(1000.0, lifeM.q3, 0.001)
        assertEquals(0.0, lifeM.iqr, 0.001)
    }

    @Test
    fun testMissingAndInvalidValuesHandling() {
        val inputs = listOf(
            // Missing all stats
            CharacterStatInput("Char1", "EmptyChar", null, null, null),
            // Explicit error
            CharacterStatInput("Char2", "CorruptedChar", null, null, null, "ملف CNS تالف"),
            // Partial stats (valid life and attack, null defence)
            CharacterStatInput("Char3", "PartialChar", 1000, 100, null)
        )

        val summary = RosterBalancingEngine.analyzeRoster(inputs)
        assertEquals(3, summary.totalCharactersScanned)
        assertEquals(1, summary.analyzedCharactersCount) // Only Char3 is analyzed
        assertEquals(2, summary.unanalyzableCharactersCount)

        val unanalyzableIds = summary.unanalyzableCharacters.map { it.characterId }
        assertTrue(unanalyzableIds.contains("Char1"))
        assertTrue(unanalyzableIds.contains("Char2"))

        // Char3 analyzed without inventing fake defence
        val char3Proposal = summary.proposals.firstOrNull { it.characterId == "Char3" }
        assertNotNull(char3Proposal)
        assertNull(char3Proposal!!.originalDefence)
        assertNull(char3Proposal.proposedDefence)
        assertFalse(char3Proposal.hasAdjustments)
    }

    @Test
    fun testNormalValuesProduceNoProposals() {
        val standardInputs = listOf(
            CharacterStatInput("Ryu", "Ryu", 1000, 100, 100),
            CharacterStatInput("Ken", "Ken", 950, 105, 95),
            CharacterStatInput("ChunLi", "Chun-Li", 900, 110, 90),
            CharacterStatInput("Zangief", "Zangief", 1150, 95, 110)
        )

        val summary = RosterBalancingEngine.analyzeRoster(standardInputs)
        assertEquals(4, summary.totalCharactersScanned)
        assertEquals(4, summary.balancedCharactersCount)
        assertEquals(0, summary.charactersNeedingAdjustmentCount)

        for (proposal in summary.proposals) {
            assertFalse("${proposal.displayName} must not have adjustments", proposal.hasAdjustments)
            assertTrue(proposal.statProposals.isEmpty())
            assertEquals(StatSeverity.NORMAL, proposal.overallSeverity)
            assertEquals(proposal.originalLife, proposal.proposedLife)
            assertEquals(proposal.originalAttack, proposal.proposedAttack)
            assertEquals(proposal.originalDefence, proposal.proposedDefence)
        }
    }

    @Test
    fun testMandatoryExamples() {
        // Example 1: Life = 3000 -> 1350
        val lifeHigh = RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 3000)
        assertEquals(1350, lifeHigh)

        // Example 2: Life = 500 -> 750
        val lifeLow = RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 500)
        assertEquals(750, lifeLow)

        // Example 3: Attack = 250 -> 125
        val attackHigh = RosterBalancingEngine.calculateProposedValue(StatType.ATTACK, 250)
        assertEquals(125, attackHigh)

        // Example 4: Attack = 60 -> 80
        val attackLow = RosterBalancingEngine.calculateProposedValue(StatType.ATTACK, 60)
        assertEquals(80, attackLow)

        // Defence follows the same damping as Attack
        val defenceHigh = RosterBalancingEngine.calculateProposedValue(StatType.DEFENCE, 250)
        assertEquals(125, defenceHigh)

        val defenceLow = RosterBalancingEngine.calculateProposedValue(StatType.DEFENCE, 60)
        assertEquals(80, defenceLow)
    }

    @Test
    fun testRoundingAndCapBounds() {
        val config = RosterBalancingEngine.DEFAULT_CONFIG

        // Extreme boss Life must not exceed cap (1350)
        assertEquals(config.lifeDampingCap, RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 5000))
        assertEquals(config.lifeDampingCap, RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 10000))
        assertEquals(config.lifeDampingCap, RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 100000))

        // Extreme low Life must not fall below floor (750)
        assertEquals(config.lifeDampingFloor, RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 100))
        assertEquals(config.lifeDampingFloor, RosterBalancingEngine.calculateProposedValue(StatType.LIFE, 1))

        // Extreme high Attack / Defence must not exceed cap (125)
        assertEquals(config.attackDampingCap, RosterBalancingEngine.calculateProposedValue(StatType.ATTACK, 500))
        assertEquals(config.defenceDampingCap, RosterBalancingEngine.calculateProposedValue(StatType.DEFENCE, 1000))

        // Extreme low Attack / Defence must not fall below floor (75)
        assertEquals(config.attackDampingFloor, RosterBalancingEngine.calculateProposedValue(StatType.ATTACK, 10))
        assertEquals(config.defenceDampingFloor, RosterBalancingEngine.calculateProposedValue(StatType.DEFENCE, 5))
    }

    @Test
    fun testNoOpProposalsExcluded() {
        // Values exactly at the target thresholds
        assertNull("Life exactly at max target 1150 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.LIFE, 1150))
        assertNull("Life exactly at min target 850 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.LIFE, 850))
        assertNull("Attack exactly at max target 115 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.ATTACK, 115))
        assertNull("Attack exactly at min target 85 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.ATTACK, 85))

        // Standard middle values
        assertNull("Life at 1000 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.LIFE, 1000))
        assertNull("Attack at 100 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.ATTACK, 100))
        assertNull("Defence at 100 must produce no proposal",
            RosterBalancingEngine.analyzeStat(StatType.DEFENCE, 100))
    }

    @Test
    fun testArabicExplanationsGenerated() {
        // 1. Extreme Boss Nerf
        val bossLifeProposal = RosterBalancingEngine.analyzeStat(StatType.LIFE, 3000)
        assertNotNull(bossLifeProposal)
        assertTrue(bossLifeProposal!!.explanation.contains("خفض"))
        assertTrue(bossLifeProposal.explanation.contains("3000"))
        assertTrue(bossLifeProposal.explanation.contains("1350"))
        assertTrue(bossLifeProposal.explanation.contains("الزعيم"))

        // 2. Extreme Weak Buff
        val weakLifeProposal = RosterBalancingEngine.analyzeStat(StatType.LIFE, 500)
        assertNotNull(weakLifeProposal)
        assertTrue(weakLifeProposal!!.explanation.contains("رفع"))
        assertTrue(weakLifeProposal.explanation.contains("500"))
        assertTrue(weakLifeProposal.explanation.contains("750"))

        // 3. Attack Nerf
        val bossAttackProposal = RosterBalancingEngine.analyzeStat(StatType.ATTACK, 250)
        assertNotNull(bossAttackProposal)
        assertTrue(bossAttackProposal!!.explanation.contains("كبح"))
        assertTrue(bossAttackProposal.explanation.contains("250%"))
        assertTrue(bossAttackProposal.explanation.contains("125%"))

        // 4. Attack Buff
        val weakAttackProposal = RosterBalancingEngine.analyzeStat(StatType.ATTACK, 60)
        assertNotNull(weakAttackProposal)
        assertTrue(weakAttackProposal!!.explanation.contains("تعزيز"))
        assertTrue(weakAttackProposal.explanation.contains("60%"))
        assertTrue(weakAttackProposal.explanation.contains("80%"))
    }

    @Test
    fun testSeverityClassification() {
        val config = RosterBalancingEngine.DEFAULT_CONFIG

        // Life
        assertEquals(StatSeverity.NORMAL, RosterBalancingEngine.classifySeverity(StatType.LIFE, 1000, config))
        assertEquals(StatSeverity.NORMAL, RosterBalancingEngine.classifySeverity(StatType.LIFE, 850, config))
        assertEquals(StatSeverity.NORMAL, RosterBalancingEngine.classifySeverity(StatType.LIFE, 1150, config))
        assertEquals(StatSeverity.OUT_OF_TARGET_RANGE, RosterBalancingEngine.classifySeverity(StatType.LIFE, 1250, config))
        assertEquals(StatSeverity.OUT_OF_TARGET_RANGE, RosterBalancingEngine.classifySeverity(StatType.LIFE, 800, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.LIFE, 1401, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.LIFE, 3000, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.LIFE, 749, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.LIFE, 500, config))

        // Attack
        assertEquals(StatSeverity.NORMAL, RosterBalancingEngine.classifySeverity(StatType.ATTACK, 100, config))
        assertEquals(StatSeverity.OUT_OF_TARGET_RANGE, RosterBalancingEngine.classifySeverity(StatType.ATTACK, 120, config))
        assertEquals(StatSeverity.OUT_OF_TARGET_RANGE, RosterBalancingEngine.classifySeverity(StatType.ATTACK, 80, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.ATTACK, 135, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.ATTACK, 70, config))

        // Defence
        assertEquals(StatSeverity.NORMAL, RosterBalancingEngine.classifySeverity(StatType.DEFENCE, 100, config))
        assertEquals(StatSeverity.OUT_OF_TARGET_RANGE, RosterBalancingEngine.classifySeverity(StatType.DEFENCE, 120, config))
        assertEquals(StatSeverity.OUT_OF_TARGET_RANGE, RosterBalancingEngine.classifySeverity(StatType.DEFENCE, 80, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.DEFENCE, 140, config))
        assertEquals(StatSeverity.EXTREME_OUTLIER, RosterBalancingEngine.classifySeverity(StatType.DEFENCE, 65, config))
    }

    @Test
    fun testImmutabilityOfInputModels() {
        val input = CharacterStatInput("OmegaAkuma", "Omega Akuma (Boss)", 3500, 200, 150)
        val proposal = RosterBalancingEngine.analyzeCharacterInput(input)

        assertNotNull(proposal)
        assertEquals("OmegaAkuma", proposal!!.characterId)
        assertEquals("Omega Akuma (Boss)", proposal.displayName)
        assertEquals(3500, proposal.originalLife)
        assertEquals(200, proposal.originalAttack)
        assertEquals(150, proposal.originalDefence)
        assertTrue(proposal.hasAdjustments)
        assertEquals(StatSeverity.EXTREME_OUTLIER, proposal.overallSeverity)
    }

    @Test
    fun testDeterminism() {
        val roster = listOf(
            CharacterStatInput("Ryu", "Ryu", 1000, 100, 100),
            CharacterStatInput("BossBison", "M. Bison (Boss)", 2800, 180, 140),
            CharacterStatInput("Dan", "Dan Hibiki", 700, 70, 75),
            CharacterStatInput("Akuma", "Shin Akuma", 850, 140, 85)
        )

        val run1 = RosterBalancingEngine.analyzeRoster(roster)
        val run2 = RosterBalancingEngine.analyzeRoster(roster)

        assertEquals(run1.totalCharactersScanned, run2.totalCharactersScanned)
        assertEquals(run1.analyzedCharactersCount, run2.analyzedCharactersCount)
        assertEquals(run1.balancedCharactersCount, run2.balancedCharactersCount)
        assertEquals(run1.charactersNeedingAdjustmentCount, run2.charactersNeedingAdjustmentCount)
        assertEquals(run1.lifeMetrics, run2.lifeMetrics)
        assertEquals(run1.attackMetrics, run2.attackMetrics)
        assertEquals(run1.defenceMetrics, run2.defenceMetrics)
        assertEquals(run1.proposals, run2.proposals)
    }

    @Test
    fun testNoFileModificationsOccur() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "roster_balance_immutability_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val cnsContent = """
                [Data]
                life = 3500
                attack = 220
                defence = 160
            """.trimIndent()
            val cnsFile = File(tempDir, "fighter.cns")
            cnsFile.writeText(cnsContent)

            val originalHash = sha256(cnsFile)
            val originalLength = cnsFile.length()
            val originalLastModified = cnsFile.lastModified()

            // Run analysis through the engine
            val charData = CharacterData(
                folderName = "TestFighter",
                displayName = "Test Fighter",
                imageUri = null,
                cnsFile = cnsFile
            )

            val summary = RosterBalancingEngine.analyzeCharacterDataRoster(listOf(charData))
            assertEquals(1, summary.totalCharactersScanned)
            assertEquals(1, summary.charactersNeedingAdjustmentCount)

            // Verify file was NOT modified in any way
            assertTrue(cnsFile.exists())
            assertEquals("Length must be untouched", originalLength, cnsFile.length())
            assertEquals("SHA-256 must be bit-for-bit identical", originalHash, sha256(cnsFile))
            assertEquals("File content must remain completely identical", cnsContent, cnsFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

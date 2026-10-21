package com.example.engine

import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Types of core fighter stats supported for roster balancing.
 */
enum class StatType(val arabicName: String, val unit: String) {
    LIFE("نقاط الحياة (Life)", ""),
    ATTACK("قوة الهجوم (Attack)", "%"),
    DEFENCE("قوة الدفاع (Defence)", "%")
}

/**
 * Direction of proposed stat adjustment.
 */
enum class AdjustmentDirection {
    BUFF,
    NERF,
    UNCHANGED
}

/**
 * Severity classification of a stat's deviation from target balance.
 */
enum class StatSeverity(val arabicLabel: String) {
    NORMAL("طبيعية ومتوازنة"),
    OUT_OF_TARGET_RANGE("خارج النطاق المستهدف"),
    EXTREME_OUTLIER("شاذة بشدة")
}

/**
 * Configurable, named balance parameters and thresholds.
 * Eliminates magic numbers across the codebase and allows policy tuning.
 */
data class BalancingConfig(
    // Target Ranges
    val lifeTargetMin: Int = 850,
    val lifeTargetMax: Int = 1150,
    val attackTargetMin: Int = 85,
    val attackTargetMax: Int = 115,
    val defenceTargetMin: Int = 85,
    val defenceTargetMax: Int = 115,

    // Extreme Outlier Classification Bounds
    val lifeExtremeLow: Int = 750,
    val lifeExtremeHigh: Int = 1400,
    val attackExtremeLow: Int = 75,
    val attackExtremeHigh: Int = 130,
    val defenceExtremeLow: Int = 75,
    val defenceExtremeHigh: Int = 130,

    // Damping Output Caps and Floors
    val lifeDampingFloor: Int = 750,
    val lifeDampingCap: Int = 1350,
    val attackDampingFloor: Int = 75,
    val attackDampingCap: Int = 125,
    val defenceDampingFloor: Int = 75,
    val defenceDampingCap: Int = 125,

    // Gradual Damping Divisors / Multipliers
    val lifeHighDivisor: Double = 3.5,
    val lifeLowDivisor: Double = 2.5,
    val statHighDivisor: Double = 3.0,
    val statLowFactor: Double = 0.2
)

/**
 * Proposed adjustment for a single combat stat.
 */
data class StatProposal(
    val statType: StatType,
    val originalValue: Int,
    val proposedValue: Int,
    val delta: Int,
    val direction: AdjustmentDirection,
    val severity: StatSeverity,
    val explanation: String
)

/**
 * Lightweight, in-memory representation of a character's core combat stats for analysis.
 */
data class CharacterStatInput(
    val characterId: String,
    val displayName: String,
    val life: Int?,
    val attack: Int?,
    val defence: Int?,
    val rawError: String? = null
)

/**
 * Complete balance analysis and proposals for a single character.
 */
data class CharacterBalanceProposal(
    val characterId: String,
    val displayName: String,
    val originalLife: Int?,
    val proposedLife: Int?,
    val originalAttack: Int?,
    val proposedAttack: Int?,
    val originalDefence: Int?,
    val proposedDefence: Int?,
    val statProposals: List<StatProposal>,
    val hasAdjustments: Boolean,
    val overallSeverity: StatSeverity,
    val overallExplanation: String
)

/**
 * Diagnostic record for a character that could not be analyzed.
 */
data class UnanalyzableCharacterInfo(
    val characterId: String,
    val displayName: String,
    val reason: String
)

/**
 * Statistical distribution metrics for a combat attribute across the roster.
 */
data class StatMetrics(
    val count: Int,
    val min: Int,
    val max: Int,
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    val q1: Double,
    val q3: Double,
    val iqr: Double
)

/**
 * Comprehensive summary of roster balancing analysis.
 */
data class RosterBalancingSummary(
    val totalCharactersScanned: Int,
    val analyzedCharactersCount: Int,
    val balancedCharactersCount: Int,
    val charactersNeedingAdjustmentCount: Int,
    val unanalyzableCharactersCount: Int,
    val lifeMetrics: StatMetrics?,
    val attackMetrics: StatMetrics?,
    val defenceMetrics: StatMetrics?,
    val proposals: List<CharacterBalanceProposal>,
    val unanalyzableCharacters: List<UnanalyzableCharacterInfo>
)

/**
 * Pure, standalone engine for analyzing MUGEN character combat stats and generating
 * explainable, identity-preserving balance proposals.
 *
 * Guaranteed Invariants:
 * 1. Zero file system mutations: performs purely in-memory analysis without writing or deleting any file.
 * 2. Deterministic: identical inputs always yield identical outputs.
 * 3. Non-flattening: preserves archetypes (bosses remain heavier, speedsters remain lighter).
 * 4. No no-op proposals: excludes adjustments where proposed value equals original value.
 */
object RosterBalancingEngine {

    val DEFAULT_CONFIG = BalancingConfig()

    /**
     * Classifies the severity of a stat's deviation from standard target balance.
     */
    fun classifySeverity(
        statType: StatType,
        value: Int,
        config: BalancingConfig = DEFAULT_CONFIG
    ): StatSeverity {
        return when (statType) {
            StatType.LIFE -> {
                when {
                    value < config.lifeExtremeLow || value > config.lifeExtremeHigh -> StatSeverity.EXTREME_OUTLIER
                    value < config.lifeTargetMin || value > config.lifeTargetMax -> StatSeverity.OUT_OF_TARGET_RANGE
                    else -> StatSeverity.NORMAL
                }
            }
            StatType.ATTACK -> {
                when {
                    value < config.attackExtremeLow || value > config.attackExtremeHigh -> StatSeverity.EXTREME_OUTLIER
                    value < config.attackTargetMin || value > config.attackTargetMax -> StatSeverity.OUT_OF_TARGET_RANGE
                    else -> StatSeverity.NORMAL
                }
            }
            StatType.DEFENCE -> {
                when {
                    value < config.defenceExtremeLow || value > config.defenceExtremeHigh -> StatSeverity.EXTREME_OUTLIER
                    value < config.defenceTargetMin || value > config.defenceTargetMax -> StatSeverity.OUT_OF_TARGET_RANGE
                    else -> StatSeverity.NORMAL
                }
            }
        }
    }

    /**
     * Calculates the proposed balanced value for a stat using non-linear gradual damping.
     * Applies Math.round to the nearest integer and enforces damping caps and floors.
     */
    fun calculateProposedValue(
        statType: StatType,
        currentValue: Int,
        config: BalancingConfig = DEFAULT_CONFIG
    ): Int {
        return when (statType) {
            StatType.LIFE -> {
                when {
                    currentValue > config.lifeTargetMax -> {
                        val raw = config.lifeTargetMax.toDouble() + (currentValue - config.lifeTargetMax) / config.lifeHighDivisor
                        val rounded = Math.round(raw).toInt()
                        minOf(config.lifeDampingCap, rounded)
                    }
                    currentValue < config.lifeTargetMin -> {
                        val raw = config.lifeTargetMin.toDouble() - (config.lifeTargetMin - currentValue) / config.lifeLowDivisor
                        val rounded = Math.round(raw).toInt()
                        maxOf(config.lifeDampingFloor, rounded)
                    }
                    else -> currentValue
                }
            }
            StatType.ATTACK -> {
                when {
                    currentValue > config.attackTargetMax -> {
                        val raw = config.attackTargetMax.toDouble() + (currentValue - config.attackTargetMax) / config.statHighDivisor
                        val rounded = Math.round(raw).toInt()
                        minOf(config.attackDampingCap, rounded)
                    }
                    currentValue < config.attackTargetMin -> {
                        val raw = config.attackTargetMin.toDouble() - (config.attackTargetMin - currentValue) * config.statLowFactor
                        val rounded = Math.round(raw).toInt()
                        maxOf(config.attackDampingFloor, rounded)
                    }
                    else -> currentValue
                }
            }
            StatType.DEFENCE -> {
                when {
                    currentValue > config.defenceTargetMax -> {
                        val raw = config.defenceTargetMax.toDouble() + (currentValue - config.defenceTargetMax) / config.statHighDivisor
                        val rounded = Math.round(raw).toInt()
                        minOf(config.defenceDampingCap, rounded)
                    }
                    currentValue < config.defenceTargetMin -> {
                        val raw = config.defenceTargetMin.toDouble() - (config.defenceTargetMin - currentValue) * config.statLowFactor
                        val rounded = Math.round(raw).toInt()
                        maxOf(config.defenceDampingFloor, rounded)
                    }
                    else -> currentValue
                }
            }
        }
    }

    /**
     * Formulates an explainable, human-readable Arabic explanation for a proposed stat change.
     */
    fun generateStatExplanation(
        statType: StatType,
        originalValue: Int,
        proposedValue: Int,
        severity: StatSeverity
    ): String {
        val delta = proposedValue - originalValue
        val isBuff = delta > 0
        val isExtreme = severity == StatSeverity.EXTREME_OUTLIER

        return when (statType) {
            StatType.LIFE -> {
                when {
                    !isBuff && isExtreme ->
                        "خفض نقاط الحياة من $originalValue إلى $proposedValue لكبح قيمة الزعيم الشاذة مع الحفاظ على كونه مقاتلاً ضخماً أعلى من المتوسط."
                    !isBuff ->
                        "تخفيض تدريجي لنقاط الحياة من $originalValue إلى $proposedValue لتقريب المقاتل من النطاق المتوازن مع مراعاة وزنه الدفاعي."
                    isBuff && isExtreme ->
                        "رفع نقاط الحياة من $originalValue إلى $proposedValue لتعزيز مقاتل ضعيف جداً وتمكينه من الصمود في المعارك التنافسية."
                    else ->
                        "تحسين طفيف لنقاط الحياة من $originalValue إلى $proposedValue للوصول إلى الحد الأدنى المقبول مع الحفاظ على سرعته وخفته."
                }
            }
            StatType.ATTACK -> {
                when {
                    !isBuff && isExtreme ->
                        "كبح قوة الهجوم المفرطة من $originalValue% إلى $proposedValue% لمنع إنهاء الجولات بضربات خاطفة غير عادلة."
                    !isBuff ->
                        "تعديل طفيف للهجوم من $originalValue% إلى $proposedValue% لضبط توازن الضرر ضمن النطاق المقبول."
                    isBuff && isExtreme ->
                        "تعزيز قوة الهجوم من $originalValue% إلى $proposedValue% لمنح المقاتل قدرة هجومية مجدية ومؤثرة."
                    else ->
                        "تحسين بسيط لضرر الهجوم من $originalValue% إلى $proposedValue% لمطابقة الحد التنافسي الأدنى."
                }
            }
            StatType.DEFENCE -> {
                when {
                    !isBuff && isExtreme ->
                        "تعديل الدفاع المفرط من $originalValue% إلى $proposedValue% لتمكين المنافسين من إلحاق ضرر عادل بالضربات الناجحة."
                    !isBuff ->
                        "ضبط طفيف للدفاع من $originalValue% إلى $proposedValue% لتفادي الحصانة المفرطة."
                    isBuff && isExtreme ->
                        "رفع الدفاع الهش من $originalValue% إلى $proposedValue% لتفادي خسارة المقاتل لكامل طاقته من هجمة واحدة."
                    else ->
                        "تحسين بسيط لصلابة الدفاع من $originalValue% إلى $proposedValue% لدعم الاستقرار التنافسي."
                }
            }
        }
    }

    /**
     * Analyzes a single stat and returns a StatProposal if adjustment is required.
     * Returns null if the stat is already balanced or if calculation results in a no-op (delta == 0).
     */
    fun analyzeStat(
        statType: StatType,
        currentValue: Int,
        config: BalancingConfig = DEFAULT_CONFIG
    ): StatProposal? {
        val proposed = calculateProposedValue(statType, currentValue, config)
        if (proposed == currentValue) {
            return null
        }

        val delta = proposed - currentValue
        val direction = if (delta > 0) AdjustmentDirection.BUFF else AdjustmentDirection.NERF
        val severity = classifySeverity(statType, currentValue, config)
        val explanation = generateStatExplanation(statType, currentValue, proposed, severity)

        return StatProposal(
            statType = statType,
            originalValue = currentValue,
            proposedValue = proposed,
            delta = delta,
            direction = direction,
            severity = severity,
            explanation = explanation
        )
    }

    /**
     * Calculates statistical distribution metrics (mean, median, stdDev, quartiles, IQR)
     * for a collection of integer values. Returns null if collection is empty.
     */
    fun calculateMetrics(values: List<Int>): StatMetrics? {
        if (values.isEmpty()) return null

        val count = values.size
        val sorted = values.sorted()
        val min = sorted.first()
        val max = sorted.last()
        val mean = values.average()

        val variance = sorted.sumOf { (it - mean).pow(2) } / count
        val stdDev = sqrt(variance)

        fun medianOf(list: List<Int>): Double {
            val sz = list.size
            if (sz == 0) return 0.0
            return if (sz % 2 == 1) {
                list[sz / 2].toDouble()
            } else {
                (list[sz / 2 - 1] + list[sz / 2]) / 2.0
            }
        }

        val median = medianOf(sorted)
        val q1: Double
        val q3: Double

        if (count == 1) {
            q1 = sorted[0].toDouble()
            q3 = sorted[0].toDouble()
        } else {
            val lowerHalf = sorted.subList(0, count / 2)
            val upperHalf = sorted.subList(if (count % 2 == 1) count / 2 + 1 else count / 2, count)
            q1 = medianOf(lowerHalf)
            q3 = medianOf(upperHalf)
        }

        val iqr = q3 - q1

        return StatMetrics(
            count = count,
            min = min,
            max = max,
            mean = mean,
            median = median,
            standardDeviation = stdDev,
            q1 = q1,
            q3 = q3,
            iqr = iqr
        )
    }

    /**
     * Analyzes a single CharacterStatInput and constructs a CharacterBalanceProposal.
     * Returns null if the character has no analyzable combat stats.
     */
    fun analyzeCharacterInput(
        input: CharacterStatInput,
        config: BalancingConfig = DEFAULT_CONFIG
    ): CharacterBalanceProposal? {
        if (input.rawError != null) return null
        if (input.life == null && input.attack == null && input.defence == null) return null

        val statProposals = mutableListOf<StatProposal>()

        val propLife = input.life?.let { analyzeStat(StatType.LIFE, it, config) }
        val propAttack = input.attack?.let { analyzeStat(StatType.ATTACK, it, config) }
        val propDefence = input.defence?.let { analyzeStat(StatType.DEFENCE, it, config) }

        propLife?.let { statProposals.add(it) }
        propAttack?.let { statProposals.add(it) }
        propDefence?.let { statProposals.add(it) }

        val hasAdjustments = statProposals.isNotEmpty()

        // Determine overall severity
        val severities = mutableListOf<StatSeverity>()
        input.life?.let { severities.add(classifySeverity(StatType.LIFE, it, config)) }
        input.attack?.let { severities.add(classifySeverity(StatType.ATTACK, it, config)) }
        input.defence?.let { severities.add(classifySeverity(StatType.DEFENCE, it, config)) }

        val overallSeverity = when {
            severities.contains(StatSeverity.EXTREME_OUTLIER) -> StatSeverity.EXTREME_OUTLIER
            severities.contains(StatSeverity.OUT_OF_TARGET_RANGE) -> StatSeverity.OUT_OF_TARGET_RANGE
            else -> StatSeverity.NORMAL
        }

        val overallExplanation = if (!hasAdjustments) {
            "المقاتل متوازن وضمن النطاقات القتالية المقبولة؛ لا يتطلب أي تعديل."
        } else {
            val parts = statProposals.map { "${it.statType.arabicName}: ${it.explanation}" }
            parts.joinToString("\n")
        }

        return CharacterBalanceProposal(
            characterId = input.characterId,
            displayName = input.displayName,
            originalLife = input.life,
            proposedLife = propLife?.proposedValue ?: input.life,
            originalAttack = input.attack,
            proposedAttack = propAttack?.proposedValue ?: input.attack,
            originalDefence = input.defence,
            proposedDefence = propDefence?.proposedValue ?: input.defence,
            statProposals = statProposals,
            hasAdjustments = hasAdjustments,
            overallSeverity = overallSeverity,
            overallExplanation = overallExplanation
        )
    }

    /**
     * Analyzes an entire roster from pre-parsed CharacterStatInput objects.
     * Pure and deterministic: does not interact with the filesystem.
     */
    fun analyzeRoster(
        inputs: List<CharacterStatInput>,
        config: BalancingConfig = DEFAULT_CONFIG
    ): RosterBalancingSummary {
        val proposals = mutableListOf<CharacterBalanceProposal>()
        val unanalyzable = mutableListOf<UnanalyzableCharacterInfo>()

        val lifeValues = mutableListOf<Int>()
        val attackValues = mutableListOf<Int>()
        val defenceValues = mutableListOf<Int>()

        for (input in inputs) {
            if (input.rawError != null) {
                unanalyzable.add(UnanalyzableCharacterInfo(input.characterId, input.displayName, input.rawError))
                continue
            }

            val proposal = analyzeCharacterInput(input, config)
            if (proposal == null) {
                unanalyzable.add(
                    UnanalyzableCharacterInfo(
                        input.characterId,
                        input.displayName,
                        "البيانات القتالية مفقودة أو غير صالحة للقراءة"
                    )
                )
            } else {
                proposals.add(proposal)
                input.life?.let { lifeValues.add(it) }
                input.attack?.let { attackValues.add(it) }
                input.defence?.let { defenceValues.add(it) }
            }
        }

        val balancedCount = proposals.count { !it.hasAdjustments }
        val needingAdjustmentCount = proposals.count { it.hasAdjustments }

        return RosterBalancingSummary(
            totalCharactersScanned = inputs.size,
            analyzedCharactersCount = proposals.size,
            balancedCharactersCount = balancedCount,
            charactersNeedingAdjustmentCount = needingAdjustmentCount,
            unanalyzableCharactersCount = unanalyzable.size,
            lifeMetrics = calculateMetrics(lifeValues),
            attackMetrics = calculateMetrics(attackValues),
            defenceMetrics = calculateMetrics(defenceValues),
            proposals = proposals,
            unanalyzableCharacters = unanalyzable
        )
    }

    /**
     * High-level entry point that safely parses CharacterData objects from MugenParser.
     * Reads CNS files in-memory only without performing any modifications.
     */
    fun analyzeCharacterDataRoster(
        characters: List<CharacterData>,
        config: BalancingConfig = DEFAULT_CONFIG
    ): RosterBalancingSummary {
        val inputs = mutableListOf<CharacterStatInput>()

        for (char in characters) {
            val cns = char.cnsFile
            if (cns == null || !cns.isFile || !cns.canRead()) {
                inputs.add(
                    CharacterStatInput(
                        characterId = char.folderName,
                        displayName = char.displayName,
                        life = null,
                        attack = null,
                        defence = null,
                        rawError = "ملف CNS غير متوفر أو تعذر العثور عليه"
                    )
                )
                continue
            }

            try {
                val editor = CnsEditor(cns)
                val rawLife = editor.getProperty("[data]", "life")
                val rawAttack = editor.getProperty("[data]", "attack")
                val rawDefence = editor.getProperty("[data]", "defence")

                val life = rawLife?.trim()?.toIntOrNull()
                val attack = rawAttack?.trim()?.toIntOrNull()
                val defence = rawDefence?.trim()?.toIntOrNull()

                if (life == null && attack == null && defence == null) {
                    inputs.add(
                        CharacterStatInput(
                            characterId = char.folderName,
                            displayName = char.displayName,
                            life = null,
                            attack = null,
                            defence = null,
                            rawError = "تعذر قراءة خصائص [data] في ملف CNS (القيم مفقودة أو غير رقمية)"
                        )
                    )
                } else {
                    inputs.add(
                        CharacterStatInput(
                            characterId = char.folderName,
                            displayName = char.displayName,
                            life = life,
                            attack = attack,
                            defence = defence,
                            rawError = null
                        )
                    )
                }
            } catch (e: Exception) {
                inputs.add(
                    CharacterStatInput(
                        characterId = char.folderName,
                        displayName = char.displayName,
                        life = null,
                        attack = null,
                        defence = null,
                        rawError = "خطأ أثناء قراءة ملف CNS: ${e.message}"
                    )
                )
            }
        }

        return analyzeRoster(inputs, config)
    }
}

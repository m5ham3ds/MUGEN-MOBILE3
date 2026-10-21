package com.example.engine

/**
 * Validation result for a single editor field.
 */
data class FieldValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        val VALID = FieldValidationResult(true, null)
        fun invalid(message: String) = FieldValidationResult(false, message)
    }
}

/**
 * Validation engine for character attributes across .def and .cns files.
 * Enforces valid numeric bounds, coordinate syntax, and reasonable ranges.
 */
object CharacterEditorValidator {

    fun validateLife(value: String): FieldValidationResult {
        val trimmed = value.trim()
        val num = trimmed.toIntOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("قيمة الحياة (Life) يجب أن تكون رقماً صحيحاً")
            num <= 0 -> FieldValidationResult.invalid("قيمة الحياة يجب أن تكون أكبر من 0")
            num > 100000 -> FieldValidationResult.invalid("قيمة الحياة كبيرة جداً (الحد الأقصى 100,000)")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateAttack(value: String): FieldValidationResult {
        val trimmed = value.trim()
        val num = trimmed.toIntOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("قيمة الهجوم (Attack) يجب أن تكون رقماً صحيحاً")
            num <= 0 -> FieldValidationResult.invalid("قيمة الهجوم يجب أن تكون أكبر من 0")
            num > 10000 -> FieldValidationResult.invalid("قيمة الهجوم كبيرة جداً (الحد الأقصى 10,000)")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateDefence(value: String): FieldValidationResult {
        val trimmed = value.trim()
        val num = trimmed.toIntOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("قيمة الدفاع (Defence) يجب أن تكون رقماً صحيحاً")
            num <= 0 -> FieldValidationResult.invalid("قيمة الدفاع يجب أن تكون أكبر من 0")
            num > 10000 -> FieldValidationResult.invalid("قيمة الدفاع كبيرة جداً (الحد الأقصى 10,000)")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateScale(value: String, fieldName: String): FieldValidationResult {
        val trimmed = value.trim()
        val num = trimmed.toFloatOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("$fieldName يجب أن يكون رقماً عشرياً")
            num <= 0f -> FieldValidationResult.invalid("$fieldName يجب أن يكون أكبر من 0")
            num > 20f -> FieldValidationResult.invalid("$fieldName كبير جداً (الحد الأقصى 20.0)")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateNonNegativeInt(value: String, fieldName: String): FieldValidationResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.VALID
        val num = trimmed.toIntOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("$fieldName يجب أن يكون رقماً صحيحاً")
            num < 0 -> FieldValidationResult.invalid("$fieldName لا يمكن أن يكون سالباً")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateInteger(value: String, fieldName: String): FieldValidationResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.VALID
        val num = trimmed.toIntOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("$fieldName يجب أن يكون رقماً صحيحاً")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateFloat(value: String, fieldName: String): FieldValidationResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.VALID
        val num = trimmed.toFloatOrNull()
        return when {
            num == null -> FieldValidationResult.invalid("$fieldName يجب أن يكون رقماً صالحاً")
            else -> FieldValidationResult.VALID
        }
    }

    fun validateCoordinates(value: String, fieldName: String): FieldValidationResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.VALID
        val parts = trimmed.split(",").map { it.trim() }
        if (parts.size != 2) {
            return FieldValidationResult.invalid("$fieldName يجب أن يتكون من قيمتين مفصولتين بفاصلة (x, y)")
        }
        val x = parts[0].toFloatOrNull()
        val y = parts[1].toFloatOrNull()
        if (x == null || y == null) {
            return FieldValidationResult.invalid("قيم الإحداثيات في $fieldName غير صالحة")
        }
        return FieldValidationResult.VALID
    }

    fun validateLocalcoord(value: String): FieldValidationResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.VALID
        val parts = trimmed.split(",").map { it.trim() }
        if (parts.size != 2) {
            return FieldValidationResult.invalid("أبعاد الدقة (Localcoord) يجب أن تكون بصيغة: العرض, الارتفاع (مثال: 320, 240)")
        }
        val w = parts[0].toIntOrNull()
        val h = parts[1].toIntOrNull()
        if (w == null || h == null || w <= 0 || h <= 0) {
            return FieldValidationResult.invalid("أبعاد الدقة يجب أن تكون أرقاماً صحيحة موجبة أكبر من 0")
        }
        return FieldValidationResult.VALID
    }

    fun validateVelocity(value: String, fieldName: String): FieldValidationResult {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.VALID
        val parts = trimmed.split(",").map { it.trim() }
        for (part in parts) {
            if (part.toFloatOrNull() == null) {
                return FieldValidationResult.invalid("قيمة السرعة في $fieldName غير صالحة")
            }
        }
        return FieldValidationResult.VALID
    }
}

package com.example.engine

import android.content.Context
import android.util.Log
import com.example.storage.MugenMobileStorage
import java.io.File
import java.io.FileOutputStream

data class SelectDefSyncResult(
    val success: Boolean,
    val addedCharacters: List<String>,
    val retainedCharacters: List<String>,
    val purgedBrokenEntries: List<String>,
    val totalRegistered: Int,
    val backupPath: String?,
    val hasChanges: Boolean,
    val errorMessage: String? = null
)

data class SelectDefAnalysis(
    val fileExists: Boolean,
    val filePath: String,
    val charactersSectionFound: Boolean,
    val registeredKeys: List<String>,
    val hasRandomSelect: Boolean,
    val brokenEntriesFound: List<String>,
    val extraStagesCount: Int
)

object SelectDefManager {
    private const val TAG = "SelectDefManager"

    /**
     * Authoritative location of select.def inside the active game directory.
     */
    fun getSelectDefFile(context: Context? = null): File {
        val baseDir = MugenMobileStorage.getBaseDir(context)
        val dataDir = File(baseDir, "data")
        if (!dataDir.exists()) dataDir.mkdirs()
        return File(dataDir, "select.def")
    }

    /**
     * Creates a safe .bak backup of the existing select.def before any modifications.
     */
    fun createBackup(selectDefFile: File): File? {
        if (!selectDefFile.exists() || selectDefFile.length() == 0L) return null
        return try {
            val bakFile = File(selectDefFile.parentFile, "${selectDefFile.name}.bak")
            selectDefFile.copyTo(bakFile, overwrite = true)
            Log.i(TAG, "Created select.def backup at: ${bakFile.absolutePath}")
            bakFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating select.def backup: ${e.message}")
            null
        }
    }

    /**
     * Analyzes select.def to extract currently registered characters, broken dummy lines,
     * and section information without modifying the file.
     */
    fun analyzeSelectDef(selectDefFile: File): SelectDefAnalysis {
        if (!selectDefFile.exists() || !selectDefFile.canRead()) {
            return SelectDefAnalysis(
                fileExists = false,
                filePath = selectDefFile.absolutePath,
                charactersSectionFound = false,
                registeredKeys = emptyList(),
                hasRandomSelect = false,
                brokenEntriesFound = emptyList(),
                extraStagesCount = 0
            )
        }

        var inChars = false
        var inExtraStages = false
        var charsSectionFound = false
        var hasRandom = false
        val keys = mutableListOf<String>()
        val broken = mutableListOf<String>()
        var extraStages = 0

        try {
            selectDefFile.forEachLine { rawLine ->
                val line = rawLine.trim()
                val clean = line.substringBefore(";").trim()

                if (clean.startsWith("[") && clean.endsWith("]")) {
                    val sec = clean.substring(1, clean.length - 1).trim().lowercase()
                    inChars = (sec == "characters")
                    inExtraStages = (sec == "extrastages")
                    if (inChars) charsSectionFound = true
                } else if (inChars && clean.isNotEmpty()) {
                    if (clean.equals("randomselect", ignoreCase = true)) {
                        hasRandom = true
                    } else if (isBrokenDummyEntry(clean)) {
                        broken.add(clean)
                    } else {
                        val key = extractCharacterKey(clean)
                        if (key.isNotEmpty()) {
                            keys.add(key)
                        }
                    }
                } else if (inExtraStages && clean.isNotEmpty()) {
                    extraStages++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing select.def: ${e.message}")
        }

        return SelectDefAnalysis(
            fileExists = true,
            filePath = selectDefFile.absolutePath,
            charactersSectionFound = charsSectionFound,
            registeredKeys = keys,
            hasRandomSelect = hasRandom,
            brokenEntriesFound = broken,
            extraStagesCount = extraStages
        )
    }

    /**
     * Checks if an entry is an invalid dummy placeholder like "chars/" or "chars\".
     */
    fun isBrokenDummyEntry(entry: String): Boolean {
        val trimmed = entry.trim().lowercase()
        return trimmed == "chars/" || trimmed == "chars\\" ||
                trimmed == "chars" || trimmed.isEmpty() ||
                trimmed == "chars/." || trimmed == "stages/default/" ||
                trimmed == "stages/default\\"
    }

    /**
     * Normalizes a character key for deduplication comparison.
     * Examples:
     * - "kfm" -> "kfm"
     * - "chars/kfm/kfm.def" -> "kfm"
     * - "kfm/kfm.def" -> "kfm"
     * - "Goku/ssj.def" -> "goku/ssj"
     * - "kfm, stages/mybg.def" -> "kfm"
     */
    fun extractCharacterKey(entryLine: String): String {
        val clean = entryLine.substringBefore(";").trim()
        val firstToken = clean.substringBefore(",").trim().replace('\\', '/')
        val strippedChars = firstToken.removePrefix("chars/").removePrefix("./chars/")
        return strippedChars.trim()
    }

    fun normalizeKey(key: String): String {
        val clean = key.trim().replace('\\', '/').lowercase()
            .removePrefix("chars/").removePrefix("./chars/")
        val withoutDef = if (clean.endsWith(".def")) clean.removeSuffix(".def") else clean
        // If it's in format folder/folder (e.g. "kfm/kfm"), reduce to "kfm"
        if (withoutDef.contains("/")) {
            val parts = withoutDef.split("/")
            if (parts.size == 2 && parts[0] == parts[1]) {
                return parts[0]
            }
        }
        return withoutDef
    }

    /**
     * Synchronizes discovered valid characters into data/select.def in an idempotent, safe manner.
     *
     * Invariants guaranteed:
     * 1. Never replaces the file with a blank template.
     * 2. Preserves all comments, options, extra stages, and custom character parameters (order, music, stages).
     * 3. Creates select.def.bak before modifying.
     * 4. Cleans out invalid broken entries like "chars/".
     * 5. Prevents duplicate registrations.
     * 6. Appends newly discovered characters safely into [Characters] (before randomselect if present).
     */
    @Synchronized
    fun syncCharacters(context: Context? = null, validCharacters: List<ValidatedCharacter>): SelectDefSyncResult {
        val selectDefFile = getSelectDefFile(context)

        // Ensure parent directory exists
        selectDefFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // If file doesn't exist, create an initial robust template with full documentation
        if (!selectDefFile.exists() || selectDefFile.length() == 0L) {
            val initialTemplate = buildDefaultSelectDefTemplate()
            selectDefFile.writeText(initialTemplate)
            Log.i(TAG, "Initialized default select.def structure at: ${selectDefFile.absolutePath}")
        }

        // 1. Create backup
        val backupFile = createBackup(selectDefFile)

        // 2. Read existing lines
        val lines = try {
            selectDefFile.readLines().toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading select.def: ${e.message}")
            return SelectDefSyncResult(
                success = false,
                addedCharacters = emptyList(),
                retainedCharacters = emptyList(),
                purgedBrokenEntries = emptyList(),
                totalRegistered = 0,
                backupPath = backupFile?.absolutePath,
                hasChanges = false,
                errorMessage = "Failed reading select.def: ${e.message}"
            )
        }

        // 3. Parse lines and sections
        var charsSectionStart = -1
        var charsSectionEnd = -1
        val charactersSectionName = "[Characters]"

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            val clean = trimmed.substringBefore(";").trim()
            if (clean.equals(charactersSectionName, ignoreCase = true)) {
                charsSectionStart = i
            } else if (charsSectionStart != -1 && clean.startsWith("[") && clean.endsWith("]")) {
                charsSectionEnd = i
                break
            }
        }

        if (charsSectionStart == -1) {
            // [Characters] section missing! Safely prepend it
            lines.add(0, charactersSectionName)
            lines.add(1, "; Characters auto-registered by MUGEN Mobile")
            charsSectionStart = 0
            charsSectionEnd = lines.size
        } else if (charsSectionEnd == -1) {
            charsSectionEnd = lines.size
        }

        // 4. Analyze characters inside [Characters]
        val existingEntries = mutableListOf<String>()
        val existingNormalizedKeys = mutableSetOf<String>()
        val purgedEntries = mutableListOf<String>()
        var randomSelectIndexInSlice = -1

        val newCharsLines = mutableListOf<String>()

        for (i in (charsSectionStart + 1) until charsSectionEnd) {
            val originalLine = lines[i]
            val trimmed = originalLine.trim()
            val clean = trimmed.substringBefore(";").trim()

            if (clean.isEmpty()) {
                // Keep empty lines/formatting
                newCharsLines.add(originalLine)
                continue
            }

            if (clean.startsWith(";")) {
                // Keep comments
                newCharsLines.add(originalLine)
                continue
            }

            if (clean.equals("randomselect", ignoreCase = true)) {
                randomSelectIndexInSlice = newCharsLines.size
                newCharsLines.add(originalLine)
                continue
            }

            if (isBrokenDummyEntry(clean)) {
                Log.i(TAG, "Purging invalid dummy entry from select.def: $clean")
                purgedEntries.add(clean)
                continue
            }

            // Valid existing character line
            val key = extractCharacterKey(clean)
            val normKey = normalizeKey(key)
            existingEntries.add(key)
            existingNormalizedKeys.add(normKey)
            newCharsLines.add(originalLine)
        }

        // 5. Determine which valid characters need to be added
        val added = mutableListOf<String>()
        val toInsertLines = mutableListOf<String>()

        for (char in validCharacters) {
            // Only add characters whose validation status is VALID or VALID_MISSING_PORTRAIT
            if (char.status != CharacterValidationStatus.VALID &&
                char.status != CharacterValidationStatus.VALID_MISSING_PORTRAIT) {
                Log.w(TAG, "Skipping invalid character '${char.folderName}': ${char.validationMessage}")
                continue
            }

            val selectEntry = char.selectEntry
            val normEntry = normalizeKey(selectEntry)
            val normFolder = normalizeKey(char.folderName)

            // Deduplication check: is it already registered?
            if (existingNormalizedKeys.contains(normEntry) || existingNormalizedKeys.contains(normFolder)) {
                Log.d(TAG, "Character '${char.folderName}' already registered in select.def, skipping duplicate.")
                continue
            }

            // Register new character
            toInsertLines.add(selectEntry)
            added.add(selectEntry)
            existingNormalizedKeys.add(normEntry)
            existingNormalizedKeys.add(normFolder)
            Log.i(TAG, "Adding new character to select.def: $selectEntry")
        }

        val hasChanges = added.isNotEmpty() || purgedEntries.isNotEmpty()

        if (hasChanges) {
            // Insert new lines before randomselect if present, or at the end of [Characters]
            val insertionIndex = if (randomSelectIndexInSlice != -1) {
                randomSelectIndexInSlice
            } else {
                newCharsLines.size
            }

            newCharsLines.addAll(insertionIndex, toInsertLines)

            // Reconstruct the full file
            val finalLines = mutableListOf<String>()
            // 1. Lines before [Characters] + [Characters] header
            for (i in 0..charsSectionStart) {
                finalLines.add(lines[i])
            }
            // 2. Updated [Characters] body
            finalLines.addAll(newCharsLines)
            // 3. Remaining sections ([ExtraStages], [Options], etc.)
            for (i in charsSectionEnd until lines.size) {
                val line = lines[i]
                // Also clean up broken dummy lines in [ExtraStages] if present
                val clean = line.trim().substringBefore(";").trim()
                if (isBrokenDummyEntry(clean)) {
                    purgedEntries.add(clean)
                    continue
                }
                finalLines.add(line)
            }

            // Write back atomically
            try {
                val tempFile = File(selectDefFile.parentFile, "select.def.tmp")
                FileOutputStream(tempFile).use { fos ->
                    fos.write(finalLines.joinToString("\n").toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
                if (!tempFile.renameTo(selectDefFile)) {
                    // Fallback to direct write if rename fails
                    selectDefFile.writeText(finalLines.joinToString("\n"))
                    tempFile.delete()
                }
                Log.i(TAG, "Successfully updated select.def: added ${added.size}, purged ${purgedEntries.size}, total ${existingEntries.size + added.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing updated select.def: ${e.message}")
                return SelectDefSyncResult(
                    success = false,
                    addedCharacters = emptyList(),
                    retainedCharacters = existingEntries,
                    purgedBrokenEntries = purgedEntries,
                    totalRegistered = existingEntries.size,
                    backupPath = backupFile?.absolutePath,
                    hasChanges = false,
                    errorMessage = "Error writing select.def: ${e.message}"
                )
            }
        }

        return SelectDefSyncResult(
            success = true,
            addedCharacters = added,
            retainedCharacters = existingEntries,
            purgedBrokenEntries = purgedEntries,
            totalRegistered = existingEntries.size + added.size,
            backupPath = backupFile?.absolutePath,
            hasChanges = hasChanges
        )
    }

    /**
     * Retrieves the list of stages currently registered under [ExtraStages] in select.def.
     */
    fun getRegisteredExtraStages(context: Context? = null): List<String> {
        val file = getSelectDefFile(context)
        if (!file.exists() || !file.canRead()) return emptyList()

        val stages = mutableListOf<String>()
        var inExtraStages = false

        try {
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                val clean = line.substringBefore(";").trim()

                if (clean.startsWith("[") && clean.endsWith("]")) {
                    val sec = clean.substring(1, clean.length - 1).trim().lowercase()
                    inExtraStages = (sec == "extrastages")
                } else if (inExtraStages && clean.isNotEmpty()) {
                    if (!isBrokenDummyEntry(clean)) {
                        stages.add(clean.replace('\\', '/'))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading extra stages from select.def: ${e.message}")
        }
        return stages
    }

    /**
     * Checks if a given stage is already registered in [ExtraStages].
     */
    fun isExtraStageRegistered(context: Context? = null, stageRelativePath: String): Boolean {
        val registered = getRegisteredExtraStages(context)
        val targetNorm = stageRelativePath.trim().replace('\\', '/').lowercase()
        return registered.any { it.trim().replace('\\', '/').lowercase() == targetNorm }
    }

    /**
     * Adds an extra stage to [ExtraStages] in select.def if not already registered.
     */
    @Synchronized
    fun addExtraStage(context: Context? = null, stageRelativePath: String): Boolean {
        val file = getSelectDefFile(context)
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(buildDefaultSelectDefTemplate())
        }

        if (isExtraStageRegistered(context, stageRelativePath)) {
            Log.d(TAG, "Stage already registered in [ExtraStages]: $stageRelativePath")
            return true
        }

        createBackup(file)

        return try {
            val lines = file.readLines().toMutableList()
            var extraStagesIdx = -1
            var nextSectionIdx = -1

            for (i in lines.indices) {
                val clean = lines[i].trim().substringBefore(";").trim()
                if (clean.equals("[ExtraStages]", ignoreCase = true)) {
                    extraStagesIdx = i
                } else if (extraStagesIdx != -1 && clean.startsWith("[") && clean.endsWith("]")) {
                    nextSectionIdx = i
                    break
                }
            }

            val formattedEntry = stageRelativePath.trim().replace('\\', '/')

            if (extraStagesIdx == -1) {
                // Section missing: append it
                lines.add("")
                lines.add(";---------------------------------------------------------------------")
                lines.add("[ExtraStages]")
                lines.add(formattedEntry)
            } else {
                val insertAt = if (nextSectionIdx != -1) nextSectionIdx else lines.size
                lines.add(insertAt, formattedEntry)
            }

            file.writeText(lines.joinToString("\n"))
            Log.i(TAG, "Successfully added stage to [ExtraStages]: $formattedEntry")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding extra stage to select.def: ${e.message}")
            false
        }
    }

    /**
     * Removes an extra stage from [ExtraStages] in select.def.
     */
    @Synchronized
    fun removeExtraStage(context: Context? = null, stageRelativePath: String): Boolean {
        val file = getSelectDefFile(context)
        if (!file.exists() || !file.canRead()) return false

        createBackup(file)

        return try {
            val lines = file.readLines()
            val updatedLines = mutableListOf<String>()
            var inExtraStages = false
            val targetNorm = stageRelativePath.trim().replace('\\', '/').lowercase()
            var removed = false

            for (line in lines) {
                val clean = line.trim().substringBefore(";").trim()
                if (clean.startsWith("[") && clean.endsWith("]")) {
                    val sec = clean.substring(1, clean.length - 1).trim().lowercase()
                    inExtraStages = (sec == "extrastages")
                    updatedLines.add(line)
                } else if (inExtraStages) {
                    val cleanNorm = clean.replace('\\', '/').lowercase()
                    if (cleanNorm == targetNorm) {
                        removed = true
                        continue // skip this line
                    }
                    updatedLines.add(line)
                } else {
                    updatedLines.add(line)
                }
            }

            if (removed) {
                file.writeText(updatedLines.joinToString("\n"))
                Log.i(TAG, "Removed stage from [ExtraStages]: $stageRelativePath")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing extra stage: ${e.message}")
            false
        }
    }

    /**
     * Registers all valid ready stages into [ExtraStages] in select.def.
     * Returns the count of newly added stages.
     */
    @Synchronized
    fun syncAllReadyStages(context: Context? = null, readyStages: List<StageData>): Int {
        var addedCount = 0
        for (stage in readyStages) {
            if (stage.status == StageValidationStatus.READY && !isExtraStageRegistered(context, stage.relativePath)) {
                if (addExtraStage(context, stage.relativePath)) {
                    addedCount++
                }
            }
        }
        return addedCount
    }

    private fun buildDefaultSelectDefTemplate(): String {
        return """
            ;---------------------------------------------------------------------
            [Characters]
            ; How to add characters:
            ; Use the format: charname, stagefilename
            ; If a character's folder has the same name as their .def file, only enter the folder's name.
            ; e.g. kfm, stages/mybg.def
            ;
            ; Insert your characters below:
            randomselect

            ;---------------------------------------------------------------------
            [ExtraStages]
            ; Put extra stages here. They will be available in VS and Watch Modes.
            ; e.g. stages/default/Training Room.def

            ;---------------------------------------------------------------------
            [Options]
            ; Maximum number of matches in Arcade Mode
            arcade.maxmatches = 6,1,1,0,0,0,0,0,0,0
            team.maxmatches = 4,1,1,0,0,0,0,0,0,0
        """.trimIndent()
    }
}

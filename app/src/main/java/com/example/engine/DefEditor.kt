package com.example.engine

import android.content.Context
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.engine.workspace.SafeSaveResult
import com.example.storage.MugenMobileStorage
import java.io.File

/**
 * Inspection model for dependent files declared inside a character's .def file.
 * Strictly read-only inspection model; no binary editing or arbitrary re-routing allowed.
 */
data class DependentFileInspection(
    val key: String,
    val declaredPath: String,
    val resolvedFile: File?,
    val exists: Boolean,
    val sizeBytes: Long
) {
    val formattedSize: String
        get() {
            if (!exists || sizeBytes <= 0) return "0 B"
            return when {
                sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
                sizeBytes >= 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
                else -> "$sizeBytes B"
            }
        }
}

/**
 * Editor for MUGEN / IKEMEN-GO character definition (.def) files.
 *
 * Guarantees:
 * 1. Preserves comments (lines starting with ';' or inline comments '; ...')
 * 2. Preserves unknown sections, blank lines, and unsupported properties.
 * 3. Case-insensitive key/section resolution while maintaining casing on untouched lines.
 * 4. Rejects direct saves to immutable Original Sources or Vault files.
 * 5. Uses atomic temporary file writes with rollback backup protection.
 */
class DefEditor(val defFile: File, val context: Context? = null) {
    val lines: MutableList<String> = if (defFile.exists()) defFile.readLines().toMutableList() else mutableListOf()
    val properties = mutableMapOf<String, MutableMap<String, DefProperty>>()

    data class DefProperty(
        val section: String,
        val key: String,
        var value: String,
        var rawComment: String = "",
        var lineIndex: Int
    )

    init {
        parseLines()
    }

    fun reparseLines() {
        if (defFile.exists() && defFile.isFile) {
            lines.clear()
            lines.addAll(defFile.readLines())
        }
        parseLines()
    }

    private fun parseLines() {
        properties.clear()
        var currentSection = ""
        for (i in lines.indices) {
            val raw = lines[i]
            val clean = raw.substringBefore(";").trim()
            if (clean.startsWith("[") && clean.endsWith("]")) {
                currentSection = clean.substring(1, clean.length - 1).trim().lowercase()
            } else if (currentSection.isNotEmpty() && clean.contains("=")) {
                val key = clean.substringBefore("=").trim().lowercase()
                val afterEqual = raw.substringAfter("=")
                val valPart = afterEqual.substringBefore(";").trim()
                val commentPart = if (afterEqual.contains(";")) " ;" + afterEqual.substringAfter(";") else ""
                val cleanVal = valPart.removeSurrounding("\"").trim()

                if (key.isNotEmpty()) {
                    val secMap = properties.getOrPut(currentSection) { mutableMapOf() }
                    if (!secMap.containsKey(key)) {
                        secMap[key] = DefProperty(
                            section = currentSection,
                            key = key,
                            value = cleanVal,
                            rawComment = commentPart,
                            lineIndex = i
                        )
                    }
                }
            }
        }
    }

    /**
     * Retrieves the string value of a property in a given section.
     */
    fun getProperty(section: String, key: String): String? {
        val secLower = section.lowercase().removePrefix("[").removeSuffix("]")
        val keyLower = key.lowercase()
        return properties[secLower]?.get(keyLower)?.value
    }

    /**
     * Sets or updates the string value of a property.
     * If the property already exists, its value is updated while preserving comments.
     * If the property does not exist, it is cleanly inserted under the section.
     */
    fun setProperty(section: String, key: String, newValue: String) {
        val secLower = section.lowercase().removePrefix("[").removeSuffix("]")
        val keyLower = key.lowercase()
        val secMap = properties.getOrPut(secLower) { mutableMapOf() }
        val existing = secMap[keyLower]

        if (existing != null) {
            existing.value = newValue
        } else {
            // Find section header line index
            var sectionHeaderIndex = -1
            for (i in lines.indices) {
                val clean = lines[i].substringBefore(";").trim().lowercase()
                if (clean == "[$secLower]") {
                    sectionHeaderIndex = i
                    break
                }
            }

            val isQuotedString = keyLower in listOf("name", "displayname", "author", "versiondate", "mugenversion")
            val formattedVal = if (isQuotedString && !newValue.startsWith("\"")) "\"$newValue\"" else newValue

            if (sectionHeaderIndex != -1) {
                val insertIdx = sectionHeaderIndex + 1
                lines.add(insertIdx, "$keyLower = $formattedVal")
            } else {
                // Section does not exist, append section header and property
                lines.add("[$secLower]")
                lines.add("$keyLower = $formattedVal")
            }
            // Re-index all properties after insertion
            parseLines()
        }
    }

    /**
     * Inspects all dependent files declared under the [Files] section.
     * Resolves paths relative to the character directory and checks their physical existence.
     */
    fun getDependentFiles(): List<DependentFileInspection> {
        val parentFolder = defFile.parentFile ?: File(".")
        val filesSec = properties["files"] ?: return emptyList()
        val results = mutableListOf<DependentFileInspection>()

        val fileKeys = listOf("cmd", "cns", "anim", "sprite", "sound", "st", "stcommon",
            "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9")

        for (key in fileKeys) {
            val prop = filesSec[key]
            if (prop != null && prop.value.isNotBlank()) {
                val rel = prop.value.replace('\\', '/')
                val resolved = File(parentFolder, rel)
                val exists = resolved.exists() && resolved.isFile
                val size = if (exists) resolved.length() else 0L
                results.add(
                    DependentFileInspection(
                        key = key,
                        declaredPath = prop.value,
                        resolvedFile = if (exists) resolved else null,
                        exists = exists,
                        sizeBytes = size
                    )
                )
            }
        }

        // Also check any extra keys under [Files] not in standard list
        for ((k, prop) in filesSec) {
            if (k !in fileKeys && prop.value.isNotBlank()) {
                val rel = prop.value.replace('\\', '/')
                val resolved = File(parentFolder, rel)
                val exists = resolved.exists() && resolved.isFile
                val size = if (exists) resolved.length() else 0L
                results.add(
                    DependentFileInspection(
                        key = k,
                        declaredPath = prop.value,
                        resolvedFile = if (exists) resolved else null,
                        exists = exists,
                        sizeBytes = size
                    )
                )
            }
        }

        return results
    }

    /**
     * Compatibility save entry point. All mutable writes are routed through the workspace manager;
     * direct file replacement is deliberately disabled so vault checks, metadata, backups and rollback cannot be bypassed.
     */
    fun save() {
        if (CharacterWorkspaceManager.isProtectedImmutableFile(defFile, context)) {
            throw SecurityException("Direct modification of immutable protected file '${defFile.absolutePath}' is strictly prohibited.")
        }
        val canonical = try { defFile.canonicalFile } catch (_: Exception) { defFile }
        if (CharacterWorkspaceManager.isProtectedImmutableFile(canonical, context)) {
            throw SecurityException("Direct modification of immutable protected file target '${canonical.absolutePath}' is strictly prohibited.")
        }
        val ctx = context ?: throw SecurityException("Direct editor writes are disabled. Supply an app Context and use the workspace-safe save path.")
        val charsRoot = try { MugenMobileStorage.getCharsDir(ctx).canonicalFile } catch (_: Exception) {
            throw SecurityException("Cannot resolve the managed character workspace")
        }
        val characterDir = defFile.parentFile?.let { try { it.canonicalFile } catch (_: Exception) { it } }
            ?: throw SecurityException("Definition file has no parent directory")
        if (characterDir.parentFile?.canonicalFile?.path != charsRoot.path) {
            throw SecurityException("Direct editor writes are only supported for files directly inside a managed character workspace")
        }
        val result = saveSafe(characterDir.name, ctx)
        if (!result.success) throw java.io.IOException(result.errorMessage ?: "Safe .def save failed")
    }

    /**
     * Safe save via CharacterWorkspaceManager ensuring Vault snapshot, Change Metadata, and atomic rename.
     */
    fun saveSafe(characterId: String, context: Context): SafeSaveResult {
        return CharacterWorkspaceManager.saveDefChanges(this, characterId, context)
    }
}

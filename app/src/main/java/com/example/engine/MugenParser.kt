package com.example.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.engine.workspace.CharacterWorkspaceManager
import com.example.engine.workspace.CharacterWorkspaceStatus
import com.example.engine.workspace.SafeSaveResult
import com.example.storage.MugenMobileStorage
import java.io.File

data class CharacterData(
    val folderName: String,
    val displayName: String,
    val imageUri: Uri?,
    val cnsFile: File?,
    val defFile: File? = null,
    val defFileName: String = "",
    val selectEntry: String = folderName,
    val isValid: Boolean = true,
    val validationStatus: CharacterValidationStatus = CharacterValidationStatus.VALID,
    val hasSmallPortrait: Boolean = true,
    val hasBigPortrait: Boolean = true,
    val validationMessage: String = "جاهزة للقتال",
    val workspaceStatus: CharacterWorkspaceStatus = CharacterWorkspaceStatus.PRISTINE_ORIGINAL
)

enum class StageValidationStatus {
    READY,
    MISSING_SFF,
    CORRUPTED_DEF
}

data class StageData(
    val defFileName: String,
    val displayName: String,
    val imageUri: Uri? = null,
    val relativePath: String = "stages/$defFileName",
    val author: String? = null,
    val sffFileName: String? = null,
    val bgmusic: String? = null,
    val status: StageValidationStatus = StageValidationStatus.READY,
    val validationMessage: String = "جاهزة للقتال",
    val isRegisteredInSelectDef: Boolean = false
)

class CnsEditor(val cnsFile: File, val context: Context? = null) {
    val lines = cnsFile.readLines().toMutableList()
    val properties = mutableMapOf<String, MutableMap<String, CnsProperty>>()
    
    data class CnsProperty(val section: String, val key: String, var value: String, var lineIndex: Int)
    
    init {
        reparseLines()
    }

    fun reparseLines() {
        if (cnsFile.exists() && cnsFile.isFile) {
            lines.clear()
            lines.addAll(cnsFile.readLines())
        }
        properties.clear()
        val allowedSections = listOf("[data]", "[size]", "[velocity]", "[movement]")
        var currentSection = ""
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.lowercase()
            } else if (currentSection in allowedSections && line.contains("=")) {
                val key = line.substringBefore("=").trim().lowercase()
                val valueStr = line.substringAfter("=").substringBefore(";").trim()
                if (key.isNotEmpty()) {
                    if (properties[currentSection] == null) properties[currentSection] = mutableMapOf()
                    if (!properties[currentSection]!!.containsKey(key)) {
                        properties[currentSection]!![key] = CnsProperty(currentSection, key, valueStr, i)
                    }
                }
            }
        }
    }

    /**
     * Gets property value from section (e.g. "[data]", "life").
     */
    fun getProperty(section: String, key: String): String? {
        val sec = if (section.startsWith("[")) section.lowercase() else "[$section]".lowercase()
        return properties[sec]?.get(key.lowercase())?.value
    }

    /**
     * Sets or updates property value in section.
     */
    fun setProperty(section: String, key: String, newValue: String) {
        val sec = if (section.startsWith("[")) section.lowercase() else "[$section]".lowercase()
        val keyLower = key.lowercase()
        val secMap = properties.getOrPut(sec) { mutableMapOf() }
        val prop = secMap[keyLower]
        if (prop != null) {
            prop.value = newValue
        } else {
            var sectionHeaderIndex = -1
            for (i in lines.indices) {
                val clean = lines[i].substringBefore(";").trim().lowercase()
                if (clean == sec) {
                    sectionHeaderIndex = i
                    break
                }
            }
            if (sectionHeaderIndex != -1) {
                lines.add(sectionHeaderIndex + 1, "$keyLower = $newValue")
            } else {
                lines.add(sec)
                lines.add("$keyLower = $newValue")
            }
            reparseLines()
        }
    }
    
    /**
     * Compatibility save entry point. All mutable writes are routed through the workspace manager;
     * direct file replacement is deliberately disabled so vault checks, metadata, backups and rollback cannot be bypassed.
     */
    fun save() {
        if (CharacterWorkspaceManager.isProtectedImmutableFile(cnsFile, context)) {
            throw SecurityException("Direct modification of immutable protected file '${cnsFile.absolutePath}' is strictly prohibited.")
        }
        val canonical = try { cnsFile.canonicalFile } catch (_: Exception) { cnsFile }
        if (CharacterWorkspaceManager.isProtectedImmutableFile(canonical, context)) {
            throw SecurityException("Direct modification of immutable protected file target '${canonical.absolutePath}' is strictly prohibited.")
        }
        val ctx = context ?: throw SecurityException("Direct editor writes are disabled. Supply an app Context and use the workspace-safe save path.")
        val charsRoot = try { MugenMobileStorage.getCharsDir(ctx).canonicalFile } catch (_: Exception) {
            throw SecurityException("Cannot resolve the managed character workspace")
        }
        val characterDir = cnsFile.parentFile?.let { try { it.canonicalFile } catch (_: Exception) { it } }
            ?: throw SecurityException("CNS file has no parent directory")
        if (characterDir.parentFile?.canonicalFile?.path != charsRoot.path) {
            throw SecurityException("Direct editor writes are only supported for files directly inside a managed character workspace")
        }
        val result = saveSafe(characterDir.name, ctx)
        if (!result.success) throw java.io.IOException(result.errorMessage ?: "Safe .cns save failed")
    }

    /**
     * Safe save via CharacterWorkspaceManager ensuring Vault snapshot and Change Metadata.
     */
    fun saveSafe(characterId: String, context: Context): SafeSaveResult {
        return CharacterWorkspaceManager.saveCnsChanges(this, characterId, context)
    }
}

object MugenParser {
    private const val TAG = "MugenParser"

    private fun getCacheDir(context: Context? = null): File {
        val dir = if (context != null) File(context.cacheDir, "mugen_sff_cache") else File(MugenMobileStorage.baseDir, ".cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Scans, validates, and synchronizes characters from the authoritative Game Directory.
     * Uses direct File scanning and CharacterValidator to verify .def and essential files,
     * then executes idempotent synchronization with data/select.def.
     */
    fun getCharacters(context: Context? = null): List<CharacterData> {
        val charsDir = MugenMobileStorage.getCharsDir(context)
        Log.i(TAG, "Scanning characters in authoritative path: ${charsDir.absolutePath}")

        // 1. Direct File scanning & validation
        if (charsDir.exists() && charsDir.canRead()) {
            val validatedList = mutableListOf<ValidatedCharacter>()
            val folders = charsDir.listFiles { file -> file.isDirectory } ?: emptyArray()

            for (folder in folders) {
                val validated = CharacterValidator.validateFolder(folder, context)
                validatedList.add(validated)
            }

            if (validatedList.isNotEmpty()) {
                // Filter characters that are valid for registration (VALID or VALID_MISSING_PORTRAIT)
                val validForRegistration = validatedList.filter {
                    it.status == CharacterValidationStatus.VALID ||
                            it.status == CharacterValidationStatus.VALID_MISSING_PORTRAIT
                }

                // Automatically synchronize valid characters into data/select.def
                val syncResult = SelectDefManager.syncCharacters(context, validForRegistration)
                Log.i(TAG, "select.def sync complete: added=${syncResult.addedCharacters.size}, retained=${syncResult.retainedCharacters.size}, total=${syncResult.totalRegistered}")

                // Map to CharacterData
                val resultList = validatedList.map { valChar ->
                    val isCharValid = (valChar.status == CharacterValidationStatus.VALID ||
                            valChar.status == CharacterValidationStatus.VALID_MISSING_PORTRAIT)
                    val wStatus = if (context != null) {
                        CharacterWorkspaceManager.getCharacterStatus(valChar.folderName, context)
                    } else {
                        CharacterWorkspaceStatus.PRISTINE_ORIGINAL
                    }
                    CharacterData(
                        folderName = valChar.folderName,
                        displayName = valChar.displayName,
                        imageUri = valChar.previewUri,
                        cnsFile = valChar.cnsFile,
                        defFile = valChar.defFile,
                        defFileName = valChar.defFileName ?: "",
                        selectEntry = valChar.selectEntry,
                        isValid = isCharValid,
                        validationStatus = valChar.status,
                        hasSmallPortrait = valChar.hasSmallPortrait,
                        hasBigPortrait = valChar.hasBigPortrait,
                        validationMessage = valChar.validationMessage,
                        workspaceStatus = wStatus
                    )
                }

                Log.i(TAG, "Discovered ${resultList.size} characters via Direct File (${validForRegistration.size} valid for select.def)")
                return resultList
            }
        }

        // 2. Storage Access Framework (SAF) DocumentFile fallback
        if (context != null) {
            val treeUri = MugenMobileStorage.getTreeUri(context)
            if (treeUri != null) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    val charsDoc = rootDoc?.findFile("chars")
                    if (charsDoc != null && charsDoc.isDirectory) {
                        val safList = mutableListOf<CharacterData>()
                        charsDoc.listFiles().filter { it.isDirectory }.forEach { charFolderDoc ->
                            val folderName = charFolderDoc.name ?: "Unknown"
                            var displayName = folderName
                            var previewUri: Uri? = null
                            var defName = ""
                            var hasDef = false

                            charFolderDoc.listFiles().forEach { fileDoc ->
                                val name = fileDoc.name ?: ""
                                if (name.endsWith(".def", ignoreCase = true) && !name.startsWith("intro", ignoreCase = true)) {
                                    hasDef = true
                                    defName = name
                                    try {
                                        context.contentResolver.openInputStream(fileDoc.uri)?.use { stream ->
                                            stream.bufferedReader().useLines { lines ->
                                                lines.forEach { line ->
                                                    val clean = line.substringBefore(";").trim()
                                                    if (clean.lowercase().startsWith("displayname")) {
                                                        displayName = clean.substringAfter("=").replace("\"", "").trim()
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                if (previewUri == null && (name.endsWith(".png", ignoreCase = true) || name.endsWith(".jpg", ignoreCase = true))) {
                                    previewUri = fileDoc.uri
                                }
                            }

                            val selectEntry = if (defName.removeSuffix(".def").equals(folderName, ignoreCase = true)) {
                                folderName
                            } else if (defName.isNotEmpty()) {
                                "$folderName/$defName"
                            } else {
                                folderName
                            }

                            val status = if (hasDef) CharacterValidationStatus.VALID else CharacterValidationStatus.INVALID_NO_DEF
                            val message = if (hasDef) "شخصية مكتشفة عبر SAF" else "لا يوجد ملف .def"

                            val wStatus = if (context != null) {
                                CharacterWorkspaceManager.getCharacterStatus(folderName, context)
                            } else {
                                CharacterWorkspaceStatus.PRISTINE_ORIGINAL
                            }

                            safList.add(
                                CharacterData(
                                    folderName = folderName,
                                    displayName = displayName,
                                    imageUri = previewUri,
                                    cnsFile = null,
                                    defFileName = defName,
                                    selectEntry = selectEntry,
                                    isValid = hasDef,
                                    validationStatus = status,
                                    hasSmallPortrait = previewUri != null,
                                    hasBigPortrait = previewUri != null,
                                    validationMessage = message,
                                    workspaceStatus = wStatus
                                )
                            )
                        }
                        if (safList.isNotEmpty()) {
                            Log.i(TAG, "Discovered ${safList.size} characters via SAF DocumentFile")
                            return safList
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SAF character scan notice: ${e.message}")
                }
            }
        }

        Log.i(TAG, "No characters found in ${charsDir.absolutePath}")
        return emptyList()
    }

    /**
     * Recursively collects all .def files within a directory up to maxDepth.
     */
    private fun findDefFilesRecursively(dir: File, depth: Int = 0, maxDepth: Int = 3): List<File> {
        if (depth > maxDepth || !dir.exists() || !dir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        val files = dir.listFiles() ?: return emptyList()
        for (f in files) {
            if (f.isFile && f.extension.equals("def", ignoreCase = true)) {
                result.add(f)
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                result.addAll(findDefFilesRecursively(f, depth + 1, maxDepth))
            }
        }
        return result
    }

    /**
     * Scans and returns stages from the authoritative Game Directory.
     * Uses recursive direct File scanning with asset validation and select.def status tracking,
     * with DocumentFile (SAF) fallback.
     */
    fun getStages(context: Context? = null): List<StageData> {
        val stagesDir = MugenMobileStorage.getStagesDir(context)
        Log.i(TAG, "Scanning stages recursively in authoritative path: ${stagesDir.absolutePath}")
        com.example.utils.LiveLogger.d(com.example.utils.LiveLogger.Category.STAGE, "STAGE_SCAN_STARTED", "Scanning: ${stagesDir.absolutePath}")

        // 1. Direct File scanning (recursive)
        if (stagesDir.exists() && stagesDir.canRead()) {
            val list = mutableListOf<StageData>()
            val defFiles = findDefFilesRecursively(stagesDir)
            val cache = getCacheDir(context)

            for (defFile in defFiles) {
                var displayName = defFile.nameWithoutExtension
                var author: String? = null
                var sffFileName: String? = null
                var bgMusic: String? = null
                var hasEssentialHeaders = false

                val relativePath = "stages/" + defFile.relativeTo(stagesDir).path.replace('\\', '/')

                try {
                    val lines = defFile.readLines()
                    var inInfo = false
                    var inBgDef = false
                    var inMusic = false

                    for (rawLine in lines) {
                        val cleanLine = rawLine.substringBefore(";").trim()
                        if (cleanLine.startsWith("[") && cleanLine.endsWith("]")) {
                            val section = cleanLine.substring(1, cleanLine.length - 1).trim().lowercase()
                            inInfo = (section == "info")
                            inBgDef = (section == "bgdef")
                            inMusic = (section == "music")
                            if (inInfo || inBgDef) hasEssentialHeaders = true
                            continue
                        }

                        if (inInfo && cleanLine.contains("=")) {
                            val key = cleanLine.substringBefore("=").trim().lowercase()
                            val value = cleanLine.substringAfter("=").replace("\"", "").trim()
                            if (key == "displayname" && value.isNotEmpty()) {
                                displayName = value
                            } else if (key == "name" && displayName == defFile.nameWithoutExtension && value.isNotEmpty()) {
                                displayName = value
                            } else if (key == "author" && value.isNotEmpty()) {
                                author = value
                            }
                        }

                        if (inBgDef && cleanLine.contains("=")) {
                            val key = cleanLine.substringBefore("=").trim().lowercase()
                            val value = cleanLine.substringAfter("=").replace("\"", "").trim()
                            if (key == "spr" || key == "sprite") {
                                sffFileName = value
                            }
                        }

                        if (inMusic && cleanLine.contains("=")) {
                            val key = cleanLine.substringBefore("=").trim().lowercase()
                            val value = cleanLine.substringAfter("=").replace("\"", "").trim()
                            if (key == "bgmusic") {
                                bgMusic = value
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stage def ${defFile.name}: ${e.message}")
                }

                // Check SFF resolution
                val baseName = defFile.nameWithoutExtension
                var resolvedSff: File? = null

                if (!sffFileName.isNullOrBlank()) {
                    val cleanSffName = sffFileName!!.removePrefix("stages/").removePrefix("./")
                    val candidates = listOf(
                        File(defFile.parentFile, cleanSffName),
                        File(defFile.parentFile, sffFileName!!),
                        File(stagesDir, cleanSffName),
                        File(stagesDir, sffFileName!!),
                        File(defFile.parentFile, "$baseName.sff"),
                        File(stagesDir, "$baseName.sff")
                    )
                    resolvedSff = candidates.firstOrNull { it.exists() && it.isFile }
                } else {
                    resolvedSff = File(defFile.parentFile, "$baseName.sff").takeIf { it.exists() }
                        ?: File(stagesDir, "$baseName.sff").takeIf { it.exists() }
                }

                // Check image preview
                var imgUri: Uri? = File(defFile.parentFile, "$baseName.png").takeIf { it.exists() }?.let { Uri.fromFile(it) }
                    ?: File(defFile.parentFile, "$baseName.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it) }
                    ?: File(stagesDir, "$baseName.png").takeIf { it.exists() }?.let { Uri.fromFile(it) }
                    ?: File(stagesDir, "$baseName.jpg").takeIf { it.exists() }?.let { Uri.fromFile(it) }

                if (imgUri == null && resolvedSff != null) {
                    imgUri = SffParser.extractPortrait(resolvedSff, cache, "stage_${baseName.replace(' ', '_')}")
                }

                // Determine validation status
                val isCorrupted = defFile.length() == 0L || !hasEssentialHeaders
                val isMissingSff = !isCorrupted && !sffFileName.isNullOrBlank() && resolvedSff == null

                val status = when {
                    isCorrupted -> StageValidationStatus.CORRUPTED_DEF
                    isMissingSff -> StageValidationStatus.MISSING_SFF
                    else -> StageValidationStatus.READY
                }

                val message = when (status) {
                    StageValidationStatus.READY -> "جاهزة للقتال"
                    StageValidationStatus.MISSING_SFF -> "ملف الرسومات SFF مفقود"
                    StageValidationStatus.CORRUPTED_DEF -> "ملف .def تالف أو فارغ"
                }

                val isRegistered = SelectDefManager.isExtraStageRegistered(context, relativePath)

                list.add(
                    StageData(
                        defFileName = defFile.name,
                        displayName = displayName,
                        imageUri = imgUri,
                        relativePath = relativePath,
                        author = author,
                        sffFileName = resolvedSff?.name ?: sffFileName,
                        bgmusic = bgMusic,
                        status = status,
                        validationMessage = message,
                        isRegisteredInSelectDef = isRegistered
                    )
                )
            }

            if (list.isNotEmpty()) {
                val readyCount = list.count { it.status == StageValidationStatus.READY }
                val regCount = list.count { it.isRegisteredInSelectDef }
                Log.i(TAG, "Discovered ${list.size} stages recursively (${readyCount} ready, ${regCount} in select.def)")
                com.example.utils.LiveLogger.i(
                    com.example.utils.LiveLogger.Category.STAGE,
                    "STAGES_DISCOVERED",
                    "Total=${list.size} | Ready=$readyCount | Registered=$regCount"
                )
                return list
            }
        }

        // 2. Storage Access Framework (SAF) DocumentFile fallback
        if (context != null) {
            val treeUri = MugenMobileStorage.getTreeUri(context)
            if (treeUri != null) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    val stagesDoc = rootDoc?.findFile("stages")
                    if (stagesDoc != null && stagesDoc.isDirectory) {
                        val safStages = mutableListOf<StageData>()
                        
                        fun scanDocDir(parentDoc: DocumentFile, parentRel: String) {
                            for (doc in parentDoc.listFiles()) {
                                if (doc.isFile && doc.name?.endsWith(".def", ignoreCase = true) == true) {
                                    val defName = doc.name ?: "stage.def"
                                    val baseName = defName.removeSuffix(".def")
                                    var displayName = baseName
                                    var previewUri: Uri? = null

                                    try {
                                        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                                            stream.bufferedReader().useLines { lines ->
                                                lines.forEach { line ->
                                                    val clean = line.substringBefore(";").trim()
                                                    if (clean.lowercase().startsWith("displayname")) {
                                                        displayName = clean.substringAfter("=").replace("\"", "").trim()
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}

                                    val imgDoc = parentDoc.findFile("$baseName.png") ?: parentDoc.findFile("$baseName.jpg")
                                    if (imgDoc != null) {
                                        previewUri = imgDoc.uri
                                    }

                                    val relPath = if (parentRel.isEmpty()) "stages/$defName" else "stages/$parentRel/$defName"
                                    val isReg = SelectDefManager.isExtraStageRegistered(context, relPath)

                                    safStages.add(
                                        StageData(
                                            defFileName = defName,
                                            displayName = displayName,
                                            imageUri = previewUri,
                                            relativePath = relPath,
                                            status = StageValidationStatus.READY,
                                            validationMessage = "مكتشفة عبر SAF",
                                            isRegisteredInSelectDef = isReg
                                        )
                                    )
                                } else if (doc.isDirectory && !doc.name.isNullOrEmpty()) {
                                    val subRel = if (parentRel.isEmpty()) doc.name!! else "$parentRel/${doc.name}"
                                    scanDocDir(doc, subRel)
                                }
                            }
                        }

                        scanDocDir(stagesDoc, "")

                        if (safStages.isNotEmpty()) {
                            Log.i(TAG, "Discovered ${safStages.size} stages via SAF DocumentFile")
                            return safStages
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SAF stage scan notice: ${e.message}")
                }
            }
        }

        Log.i(TAG, "No stages found in ${stagesDir.absolutePath}")
        return emptyList()
    }
}

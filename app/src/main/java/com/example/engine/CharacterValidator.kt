package com.example.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.storage.MugenMobileStorage
import java.io.File
import java.io.RandomAccessFile

enum class CharacterValidationStatus {
    VALID,                          // Fully valid, all essential files present and valid
    VALID_MISSING_PORTRAIT,        // Playable, but missing 9000,0 icon or 9000,1 portrait in SFF
    INVALID_NO_DEF,                // No .def file found in character folder
    INVALID_DEF_SYNTAX,            // .def file missing [Info] or [Files] section
    INVALID_MISSING_ESSENTIAL_FILES // Missing referenced .sff, .air, or .cns files on disk
}

data class ValidatedCharacter(
    val folder: File,
    val folderName: String,
    val defFile: File?,
    val defFileName: String?,
    val selectEntry: String,        // The exact formatted string to put in select.def
    val displayName: String,
    val author: String?,
    val status: CharacterValidationStatus,
    val sffFile: File?,
    val airFile: File?,
    val cnsFile: File?,
    val cmdFile: File?,
    val missingFiles: List<String>,
    val hasSmallPortrait: Boolean,
    val hasBigPortrait: Boolean,
    val previewUri: Uri?,
    val validationMessage: String
)

object CharacterValidator {
    private const val TAG = "CharacterValidator"

    /**
     * Validates a character folder inside chars/.
     * Inspects the folder, finds its primary .def file, validates essential file references
     * (sprite, anim, cns/zss, cmd), checks for SFF portrait sprites (group 9000),
     * and constructs the canonical select.def entry.
     */
    fun validateFolder(folder: File, context: Context? = null): ValidatedCharacter {
        val folderName = folder.name
        if (!folder.exists() || !folder.isDirectory) {
            return ValidatedCharacter(
                folder = folder,
                folderName = folderName,
                defFile = null,
                defFileName = null,
                selectEntry = folderName,
                displayName = folderName,
                author = null,
                status = CharacterValidationStatus.INVALID_NO_DEF,
                sffFile = null,
                airFile = null,
                cnsFile = null,
                cmdFile = null,
                missingFiles = listOf("Folder does not exist or is not a directory"),
                hasSmallPortrait = false,
                hasBigPortrait = false,
                previewUri = null,
                validationMessage = "مجلد الشخصية غير موجود أو ليس مجلداً صالحاً"
            )
        }

        // 1. Locate primary .def file
        val allFiles = try {
            folder.walkTopDown().maxDepth(3).toList()
        } catch (_: Exception) {
            folder.listFiles()?.toList() ?: emptyList()
        }

        val primaryDef = findPrimaryDefFile(folder, allFiles)
        if (primaryDef == null || !primaryDef.exists()) {
            return ValidatedCharacter(
                folder = folder,
                folderName = folderName,
                defFile = null,
                defFileName = null,
                selectEntry = folderName,
                displayName = folderName,
                author = null,
                status = CharacterValidationStatus.INVALID_NO_DEF,
                sffFile = null,
                airFile = null,
                cnsFile = null,
                cmdFile = null,
                missingFiles = listOf("No .def file found in folder"),
                hasSmallPortrait = false,
                hasBigPortrait = false,
                previewUri = findPreviewImage(folder, allFiles, context),
                validationMessage = "لم يتم العثور على ملف تعريف (.def) داخل مجلد الشخصية"
            )
        }

        // 2. Parse the .def file
        val parsedDef = parseDefFile(primaryDef)
        if (!parsedDef.hasFilesSection && !parsedDef.hasInfoSection) {
            return ValidatedCharacter(
                folder = folder,
                folderName = folderName,
                defFile = primaryDef,
                defFileName = primaryDef.name,
                selectEntry = buildSelectEntry(folderName, primaryDef.name),
                displayName = folderName,
                author = null,
                status = CharacterValidationStatus.INVALID_DEF_SYNTAX,
                sffFile = null,
                airFile = null,
                cnsFile = null,
                cmdFile = null,
                missingFiles = listOf("Missing [Info] or [Files] section in ${primaryDef.name}"),
                hasSmallPortrait = false,
                hasBigPortrait = false,
                previewUri = findPreviewImage(folder, allFiles, context),
                validationMessage = "ملف .def غير متوافق مع صيغة MUGEN / IKEMEN-GO (أقسام ناقصة)"
            )
        }

        // 3. Resolve and verify referenced files
        val missingList = mutableListOf<String>()

        val sffFile = resolveFile(folder, parsedDef.sffRelPath, allFiles, ".sff")
        if (sffFile == null || !sffFile.exists()) {
            missingList.add("Sprite file (.sff): ${parsedDef.sffRelPath ?: "${primaryDef.nameWithoutExtension}.sff"}")
        }

        val airFile = resolveFile(folder, parsedDef.airRelPath, allFiles, ".air")
        if (airFile == null || !airFile.exists()) {
            missingList.add("Animation file (.air): ${parsedDef.airRelPath ?: "${primaryDef.nameWithoutExtension}.air"}")
        }

        val cnsFile = resolveFile(folder, parsedDef.cnsRelPath, allFiles, ".cns", ".zss")
        if (cnsFile == null && parsedDef.stFiles.isEmpty()) {
            missingList.add("Constants/State file (.cns/.zss): ${parsedDef.cnsRelPath ?: "${primaryDef.nameWithoutExtension}.cns"}")
        }

        val cmdFile = resolveFile(folder, parsedDef.cmdRelPath, allFiles, ".cmd")

        val selectEntry = buildSelectEntry(folderName, primaryDef.name)
        val displayName = parsedDef.displayName.ifBlank { parsedDef.name.ifBlank { folderName } }

        // If essential files are missing, character is invalid
        if (missingList.isNotEmpty()) {
            return ValidatedCharacter(
                folder = folder,
                folderName = folderName,
                defFile = primaryDef,
                defFileName = primaryDef.name,
                selectEntry = selectEntry,
                displayName = displayName,
                author = parsedDef.author,
                status = CharacterValidationStatus.INVALID_MISSING_ESSENTIAL_FILES,
                sffFile = sffFile,
                airFile = airFile,
                cnsFile = cnsFile,
                cmdFile = cmdFile,
                missingFiles = missingList,
                hasSmallPortrait = false,
                hasBigPortrait = false,
                previewUri = findPreviewImage(folder, allFiles, context),
                validationMessage = "ملفات أساسية مفقودة: ${missingList.joinToString(", ")}"
            )
        }

        // 4. Check for portraits in SFF (group 9000,0 and 9000,1)
        var hasSmall = false
        var hasBig = false
        if (sffFile != null && sffFile.exists()) {
            val portraitCheck = checkSffPortraits(sffFile)
            hasSmall = portraitCheck.first
            hasBig = portraitCheck.second
        }

        // Check if manual image exists
        val preview = findPreviewImage(folder, allFiles, context, sffFile)
        if (preview != null && !hasSmall) {
            // Manual image can stand in for preview
            hasSmall = true
        }

        val (status, message) = if (hasSmall && hasBig) {
            Pair(CharacterValidationStatus.VALID, "الشخصية صالحة وجاهزة للقتال بالكامل")
        } else if (hasSmall || hasBig) {
            Pair(CharacterValidationStatus.VALID_MISSING_PORTRAIT, "الشخصية قابلة للعب ولكن تنقصها بعض صور شاشة الاختيار (Portrait 9000)")
        } else {
            Pair(CharacterValidationStatus.VALID_MISSING_PORTRAIT, "الشخصية قابلة للعب ولكن لا تحتوي على صور تعريفية 9000 في ملف SFF")
        }

        return ValidatedCharacter(
            folder = folder,
            folderName = folderName,
            defFile = primaryDef,
            defFileName = primaryDef.name,
            selectEntry = selectEntry,
            displayName = displayName,
            author = parsedDef.author,
            status = status,
            sffFile = sffFile,
            airFile = airFile,
            cnsFile = cnsFile,
            cmdFile = cmdFile,
            missingFiles = emptyList(),
            hasSmallPortrait = hasSmall,
            hasBigPortrait = hasBig,
            previewUri = preview,
            validationMessage = message
        )
    }

    /**
     * Builds the exact string to register in select.def under [Characters].
     * In IKEMEN-GO / MUGEN:
     * - If folderName matches defFileName (without .def), return just `folderName`
     * - Otherwise return `folderName/defFileName`
     */
    fun buildSelectEntry(folderName: String, defFileName: String): String {
        val defBase = defFileName.removeSuffix(".def").removeSuffix(".DEF")
        return if (folderName.equals(defBase, ignoreCase = true)) {
            folderName
        } else {
            "$folderName/$defFileName"
        }
    }

    private fun findPrimaryDefFile(folder: File, allFiles: List<File>): File? {
        // 1. Direct match: folderName.def in folder root
        val direct = File(folder, "${folder.name}.def")
        if (direct.exists()) return direct

        // 2. Case-insensitive folderName.def in folder
        val caseMatch = allFiles.find { 
            it.parentFile == folder && it.name.equals("${folder.name}.def", ignoreCase = true) 
        }
        if (caseMatch != null) return caseMatch

        // 3. Any .def file in root of folder that is not intro/ending/sound/stage
        val rootDefs = allFiles.filter {
            it.parentFile == folder && it.extension.equals("def", ignoreCase = true) &&
                    !it.name.startsWith("intro", ignoreCase = true) &&
                    !it.name.startsWith("ending", ignoreCase = true) &&
                    !it.name.startsWith("sound", ignoreCase = true)
        }
        if (rootDefs.isNotEmpty()) return rootDefs.first()

        // 4. Any .def file in subdirectories
        return allFiles.find {
            it.extension.equals("def", ignoreCase = true) &&
                    !it.name.startsWith("intro", ignoreCase = true) &&
                    !it.name.startsWith("ending", ignoreCase = true)
        }
    }

    private data class ParsedDef(
        var name: String = "",
        var displayName: String = "",
        var author: String = "",
        var sffRelPath: String? = null,
        var airRelPath: String? = null,
        var cnsRelPath: String? = null,
        var cmdRelPath: String? = null,
        val stFiles: MutableList<String> = mutableListOf(),
        var hasInfoSection: Boolean = false,
        var hasFilesSection: Boolean = false
    )

    private fun parseDefFile(defFile: File): ParsedDef {
        val parsed = ParsedDef()
        var currentSection = ""

        try {
            defFile.forEachLine { rawLine ->
                val line = rawLine.substringBefore(";").trim()
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length - 1).trim().lowercase()
                    if (currentSection == "info") parsed.hasInfoSection = true
                    if (currentSection == "files") parsed.hasFilesSection = true
                } else if (line.contains("=")) {
                    val key = line.substringBefore("=").trim().lowercase()
                    val value = line.substringAfter("=").trim().replace("\"", "")

                    when (currentSection) {
                        "info" -> {
                            when (key) {
                                "name" -> parsed.name = value
                                "displayname" -> parsed.displayName = value
                                "author" -> parsed.author = value
                            }
                        }
                        "files" -> {
                            when (key) {
                                "sprite", "spr" -> if (parsed.sffRelPath == null) parsed.sffRelPath = value
                                "anim" -> if (parsed.airRelPath == null) parsed.airRelPath = value
                                "cns" -> if (parsed.cnsRelPath == null) parsed.cnsRelPath = value
                                "cmd" -> if (parsed.cmdRelPath == null) parsed.cmdRelPath = value
                                "st", "stcommon", "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9" -> {
                                    if (value.isNotBlank()) parsed.stFiles.add(value)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing def file ${defFile.name}: ${e.message}")
        }

        return parsed
    }

    private fun resolveFile(folder: File, relPath: String?, allFiles: List<File>, vararg allowedExtensions: String): File? {
        if (!relPath.isNullOrBlank()) {
            val direct = File(folder, relPath)
            if (direct.exists()) return direct

            val cleanRel = relPath.replace('\\', '/')
            val subDirect = File(folder, cleanRel)
            if (subDirect.exists()) return subDirect

            val nameOnly = File(cleanRel).name
            val byName = allFiles.find { it.name.equals(nameOnly, ignoreCase = true) }
            if (byName != null) return byName
        }

        // Fallback: check by extension matching folder name
        for (ext in allowedExtensions) {
            val cleanExt = ext.removePrefix(".")
            val candidate = allFiles.find {
                it.name.equals("${folder.name}.$cleanExt", ignoreCase = true) ||
                        it.extension.equals(cleanExt, ignoreCase = true)
            }
            if (candidate != null) return candidate
        }

        return null
    }

    /**
     * Inspects an SFF file to detect if sprite Group 9000,0 (icon) and 9000,1 (portrait) exist.
     * Returns Pair(hasGroup9000Index0, hasGroup9000Index1).
     */
    fun checkSffPortraits(sffFile: File): Pair<Boolean, Boolean> {
        if (!sffFile.exists() || sffFile.length() < 32) return Pair(false, false)

        var hasSmall = false
        var hasBig = false

        try {
            RandomAccessFile(sffFile, "r").use { raf ->
                val sig = ByteArray(12)
                raf.read(sig)
                val sigStr = String(sig)
                if (!sigStr.startsWith("ElecbyteSpr")) return Pair(false, false)

                val v1 = raf.read()
                val v2 = raf.read()
                val v3 = raf.read()
                val v4 = raf.read()

                if (v4 < 2) {
                    // SFF v1: iterate subheaders
                    raf.seek(16)
                    val numGroups = readIntLE(raf)
                    val numImages = readIntLE(raf)
                    var offset = readIntLE(raf)

                    var count = 0
                    while (offset > 0 && count < numImages && (!hasSmall || !hasBig)) {
                        raf.seek(offset.toLong())
                        val next = readIntLE(raf)
                        val length = readIntLE(raf)
                        val xAxis = readShortLE(raf)
                        val yAxis = readShortLE(raf)
                        val group = readShortLE(raf)
                        val image = readShortLE(raf)

                        if (group == 9000) {
                            if (image == 0) hasSmall = true
                            if (image == 1) hasBig = true
                        }

                        if (next <= offset || next <= 0) break
                        offset = next
                        count++
                    }
                } else {
                    // SFF v2: check sprite table
                    // SFF v2 header structure:
                    // 28: subheader_offset (8 bytes)
                    // 36: total_subheaders (4 bytes)
                    // Each sprite node has group (2 bytes) and item (2 bytes)
                    try {
                        raf.seek(28)
                        val subheaderOffset = readLongLE(raf)
                        val totalSubheaders = readIntLE(raf)

                        if (subheaderOffset > 0 && totalSubheaders in 1..200000) {
                            var curOff = subheaderOffset
                            var count = 0
                            while (count < totalSubheaders && (!hasSmall || !hasBig)) {
                                raf.seek(curOff)
                                val group = readShortLE(raf)
                                val item = readShortLE(raf)

                                if (group == 9000) {
                                    if (item == 0) hasSmall = true
                                    if (item == 1) hasBig = true
                                }
                                curOff += 28 // SFFv2 sprite subheader size is 28 bytes
                                count++
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SFF portrait check warning on ${sffFile.name}: ${e.message}")
        }

        return Pair(hasSmall, hasBig)
    }

    private fun findPreviewImage(folder: File, allFiles: List<File>, context: Context?, sffFile: File? = null): Uri? {
        // 1. Manual PNG/JPG inside character folder
        val manual = allFiles.find {
            val name = it.nameWithoutExtension.lowercase()
            (name == "portrait" || name == "face" || name == folder.name.lowercase()) &&
                    (it.extension.equals("png", ignoreCase = true) || it.extension.equals("jpg", ignoreCase = true))
        }
        if (manual != null) return Uri.fromFile(manual)

        // 2. Try extract from SFF if available
        if (sffFile != null && sffFile.exists()) {
            val cacheDir = if (context != null) File(context.cacheDir, "mugen_sff_cache") else File(folder.parentFile?.parentFile ?: folder, ".cache")
            val extracted = SffParser.extractPortrait(sffFile, cacheDir, "char_${folder.name}")
            if (extracted != null) return extracted
        }

        // 3. Any PNG/JPG in folder
        val anyImg = allFiles.find { it.extension.equals("png", ignoreCase = true) || it.extension.equals("jpg", ignoreCase = true) }
        return anyImg?.let { Uri.fromFile(it) }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b1 = raf.read(); val b2 = raf.read(); val b3 = raf.read(); val b4 = raf.read()
        return (b1 and 0xFF) or ((b2 and 0xFF) shl 8) or ((b3 and 0xFF) shl 16) or ((b4 and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b1 = raf.read(); val b2 = raf.read()
        return (b1 and 0xFF) or ((b2 and 0xFF) shl 8)
    }

    private fun readLongLE(raf: RandomAccessFile): Long {
        var res = 0L
        for (i in 0 until 8) {
            val b = raf.read().toLong()
            res = res or ((b and 0xFFL) shl (i * 8))
        }
        return res
    }
}

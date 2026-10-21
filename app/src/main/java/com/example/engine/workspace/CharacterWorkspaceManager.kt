package com.example.engine.workspace

import android.content.Context
import android.util.Log
import com.example.engine.CnsEditor
import com.example.engine.DefEditor
import com.example.storage.MugenMobileStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class CharacterWorkspaceStatus {
    PRISTINE_ORIGINAL,
    MODIFIED,
    RESTORED,
    CORRUPTED
}

data class CharacterAuditStatus(
    val characterId: String,
    val workspaceStatus: CharacterWorkspaceStatus,
    val isVaultHealthy: Boolean,
    val vaultError: String?,
    val isVerifiedOriginal: Boolean,
    val isAdoptedLegacy: Boolean,
    val canResetToPristine: Boolean,
    val statusBadgeTitle: String,
    val statusBadgeSubtitle: String
)

data class VaultFileFingerprint(
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val lastModified: Long
)

data class VaultManifest(
    val characterId: String,
    val characterName: String,
    val originalSourcePath: String,
    val createdAt: Long,
    val files: Map<String, VaultFileFingerprint>,
    val essentialFiles: List<String>,
    val sourceOrigin: String = "VERIFIED_SOURCE"
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("characterId", characterId)
        root.put("characterName", characterName)
        root.put("originalSourcePath", originalSourcePath)
        root.put("createdAt", createdAt)
        root.put("sourceOrigin", sourceOrigin)

        val filesObj = JSONObject()
        for ((path, fp) in files) {
            val fpObj = JSONObject()
            fpObj.put("relativePath", fp.relativePath)
            fpObj.put("sha256", fp.sha256)
            fpObj.put("sizeBytes", fp.sizeBytes)
            fpObj.put("lastModified", fp.lastModified)
            filesObj.put(path, fpObj)
        }
        root.put("files", filesObj)

        val essentialArr = JSONArray()
        essentialFiles.forEach { essentialArr.put(it) }
        root.put("essentialFiles", essentialArr)

        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): VaultManifest {
            val root = JSONObject(jsonStr)
            val characterId = root.getString("characterId")
            val characterName = root.optString("characterName", characterId)
            val originalSourcePath = root.optString("originalSourcePath", "")
            val createdAt = root.optLong("createdAt", System.currentTimeMillis())
            val sourceOrigin = root.optString("sourceOrigin", "VERIFIED_SOURCE")

            val filesMap = mutableMapOf<String, VaultFileFingerprint>()
            val filesObj = root.optJSONObject("files")
            if (filesObj != null) {
                val keys = filesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fpObj = filesObj.getJSONObject(key)
                    filesMap[key] = VaultFileFingerprint(
                        relativePath = fpObj.getString("relativePath"),
                        sha256 = fpObj.getString("sha256"),
                        sizeBytes = fpObj.getLong("sizeBytes"),
                        lastModified = fpObj.optLong("lastModified", 0L)
                    )
                }
            }

            val essentialList = mutableListOf<String>()
            val essentialArr = root.optJSONArray("essentialFiles")
            if (essentialArr != null) {
                for (i in 0 until essentialArr.length()) {
                    essentialList.add(essentialArr.getString(i))
                }
            }

            return VaultManifest(
                characterId = characterId,
                characterName = characterName,
                originalSourcePath = originalSourcePath,
                createdAt = createdAt,
                files = filesMap,
                essentialFiles = essentialList,
                sourceOrigin = sourceOrigin
            )
        }
    }
}

data class ChangeRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val relativePath: String,
    val section: String,
    val key: String,
    val oldValue: String,
    val newValue: String,
    val source: String = "MANUAL_EDITOR"
)

data class CharacterMetadata(
    val characterId: String,
    val displayName: String,
    val originalSourcePath: String,
    val vaultPath: String,
    val workspacePath: String,
    var status: CharacterWorkspaceStatus,
    var version: Int,
    var lastModified: Long,
    val changeHistory: MutableList<ChangeRecord> = mutableListOf()
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("characterId", characterId)
        root.put("displayName", displayName)
        root.put("originalSourcePath", originalSourcePath)
        root.put("vaultPath", vaultPath)
        root.put("workspacePath", workspacePath)
        root.put("status", status.name)
        root.put("version", version)
        root.put("lastModified", lastModified)

        val historyArr = JSONArray()
        for (cr in changeHistory) {
            val crObj = JSONObject()
            crObj.put("timestamp", cr.timestamp)
            crObj.put("relativePath", cr.relativePath)
            crObj.put("section", cr.section)
            crObj.put("key", cr.key)
            crObj.put("oldValue", cr.oldValue)
            crObj.put("newValue", cr.newValue)
            crObj.put("source", cr.source)
            historyArr.put(crObj)
        }
        root.put("changeHistory", historyArr)

        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): CharacterMetadata {
            val root = JSONObject(jsonStr)
            val characterId = root.getString("characterId")
            val displayName = root.optString("displayName", characterId)
            val originalSourcePath = root.optString("originalSourcePath", "")
            val vaultPath = root.optString("vaultPath", "")
            val workspacePath = root.optString("workspacePath", "")
            val statusStr = root.optString("status", CharacterWorkspaceStatus.PRISTINE_ORIGINAL.name)
            val status = try {
                CharacterWorkspaceStatus.valueOf(statusStr)
            } catch (_: Exception) {
                CharacterWorkspaceStatus.PRISTINE_ORIGINAL
            }
            val version = root.optInt("version", 1)
            val lastModified = root.optLong("lastModified", System.currentTimeMillis())

            val history = mutableListOf<ChangeRecord>()
            val historyArr = root.optJSONArray("changeHistory")
            if (historyArr != null) {
                for (i in 0 until historyArr.length()) {
                    val crObj = historyArr.getJSONObject(i)
                    history.add(
                        ChangeRecord(
                            timestamp = crObj.optLong("timestamp", 0L),
                            relativePath = crObj.getString("relativePath"),
                            section = crObj.getString("section"),
                            key = crObj.getString("key"),
                            oldValue = crObj.getString("oldValue"),
                            newValue = crObj.getString("newValue"),
                            source = crObj.optString("source", "MANUAL_EDITOR")
                        )
                    )
                }
            }

            return CharacterMetadata(
                characterId = characterId,
                displayName = displayName,
                originalSourcePath = originalSourcePath,
                vaultPath = vaultPath,
                workspacePath = workspacePath,
                status = status,
                version = version,
                lastModified = lastModified,
                changeHistory = history
            )
        }
    }
}

data class VaultSnapshotResult(
    val success: Boolean,
    val characterId: String,
    val fingerprints: Map<String, String>,
    val errorMessage: String? = null
)

data class CharacterImportResult(
    val success: Boolean,
    val characterId: String,
    val workspaceFolder: File?,
    val copiedFilesCount: Int,
    val vaultManifest: VaultManifest?,
    val errorMessage: String? = null
)

data class SafeSaveResult(
    val success: Boolean,
    val savedFile: File?,
    val newSha256: String?,
    val errorMessage: String? = null
)

data class RestoreResult(
    val success: Boolean,
    val characterId: String,
    val restoredFilesCount: Int,
    val errorMessage: String? = null
)

data class BatchRestorePlan(
    val totalCharacters: Int,
    val modifiedCharacters: List<String>,
    val pristineCharacters: List<String>,
    val corruptedCharacters: List<String>
)

data class BatchRestoreReport(
    val success: Boolean,
    val restoredCharacters: List<String>,
    val failedCharacters: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Authoritative Character Workspace & Immutable Vault Manager for MUGEN Mobile.
 *
 * Enforces the strict Phase 2 4-layer architecture:
 * 1. Original Source: Immutable files imported or chosen by user (READ-ONLY).
 * 2. Original Vault: App-private reference repository with SHA-256 fingerprints.
 * 3. Character Workspace: Active runtime directory where edits are executed and loaded by the engine.
 * 4. Change Metadata: Complete revision and delta tracking per character.
 */
object CharacterWorkspaceManager {
    private const val TAG = "WorkspaceManager"

    /** Raised when writing metadata failed and its previous bytes could not be restored. */
    private class AtomicWriteRecoveryException(message: String, cause: Throwable) : java.io.IOException(message, cause)

    // Set of registered original source paths to strictly guard against write attempts
    private val registeredOriginalSources = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var sourcesLoaded = false
    @Volatile
    private var cachedAppContext: Context? = null
    @Volatile
    var simulateRollbackFailureForTesting = false

    /** Test-only failure injection. Null disables the commit failure. */
    @Volatile
    var simulateCommitFailureAfterReplacementsForTesting: Int? = null

    /** Test-only failure injection for the metadata commit stage. */
    @Volatile
    var simulateMetadataWriteFailureForTesting = false

    /** Test-only failure injection after the previous workspace has been secured for restore. */
    @Volatile
    var simulateRestoreCommitFailureForTesting = false

    /** Test-only failure injection after metadata replacement, before its final verification. */
    @Volatile
    var simulateMetadataAtomicFailureAfterReplaceForTesting = false

    /** Test-only failure injection for the metadata helper's own recovery path. */
    @Volatile
    var simulateMetadataRollbackFailureForTesting = false

    /**
     * Root directory for the Original Vault inside app-private internal storage.
     * Protected by Android sandbox permissions.
     */
    fun getVaultDir(context: Context): File {
        cachedAppContext = context.applicationContext
        val dir = File(context.filesDir, "vault/characters")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Root directory for Change Metadata JSON files inside app-private internal storage.
     */
    fun getMetadataDir(context: Context): File {
        cachedAppContext = context.applicationContext
        val dir = File(context.filesDir, "metadata/characters")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * File where registered original sources are persisted across app/process lifecycles.
     */
    fun getSourcesRegistryFile(context: Context): File {
        return File(getMetadataDir(context), "registered_sources.json")
    }

    /**
     * Loads persisted original source paths from disk into memory.
     */
    @Synchronized
    fun loadRegisteredSources(context: Context) {
        cachedAppContext = context.applicationContext
        try {
            val file = getSourcesRegistryFile(context)
            if (file.exists() && file.canRead()) {
                val jsonArr = JSONArray(file.readText())
                for (i in 0 until jsonArr.length()) {
                    val path = jsonArr.optString(i, "")
                    if (path.isNotBlank()) {
                        registeredOriginalSources.add(File(path).canonicalPath)
                    }
                }
                sourcesLoaded = true
                Log.i(TAG, "Loaded ${registeredOriginalSources.size} persisted original sources from disk")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice: could not load persisted registered sources: ${e.message}")
        }
    }

    /**
     * Persists registered original source paths to disk atomically.
     */
    @Synchronized
    fun persistRegisteredSources(context: Context) {
        cachedAppContext = context.applicationContext
        try {
            val file = getSourcesRegistryFile(context)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val jsonArr = JSONArray()
            registeredOriginalSources.forEach { jsonArr.put(it) }

            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(jsonArr.toString(2))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting registered original sources: ${e.message}")
        }
    }

    /**
     * Clears registered sources in memory (for isolated unit testing).
     */
    fun clearRegisteredSourcesForTesting() {
        registeredOriginalSources.clear()
        sourcesLoaded = false
        simulateRollbackFailureForTesting = false
        simulateCommitFailureAfterReplacementsForTesting = null
        simulateMetadataWriteFailureForTesting = false
        simulateRestoreCommitFailureForTesting = false
        simulateMetadataAtomicFailureAfterReplaceForTesting = false
        simulateMetadataRollbackFailureForTesting = false
    }

    /**
     * Directory for the active Character Workspace where the game engine loads fighters.
     */
    fun getWorkspaceCharsDir(context: Context? = null): File {
        context?.let { cachedAppContext = it.applicationContext }
        return MugenMobileStorage.getCharsDir(context)
    }

    /**
     * Directory for immutable Original Sources if placed in the game directory.
     */
    fun getSourcesDir(context: Context? = null): File {
        context?.let { cachedAppContext = it.applicationContext }
        val base = MugenMobileStorage.getBaseDir(context)
        val dir = File(base, "sources/chars")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Registers a directory path as an immutable Original Source.
     * Persists the registration to disk so protection survives app restarts.
     * Any write attempt directed into this path or its children will be immediately rejected.
     */
    fun registerOriginalSource(sourcePath: String, context: Context? = null) {
        context?.let { cachedAppContext = it.applicationContext }
        val effectiveCtx = context ?: cachedAppContext
        val normalized = File(sourcePath).canonicalPath

        // Critical safety guard: Never register an active workspace directory as an immutable Original Source
        if (effectiveCtx != null) {
            val workspaceCanonical = try { getWorkspaceCharsDir(effectiveCtx).canonicalPath } catch (_: Exception) { "" }
            if (workspaceCanonical.isNotEmpty() &&
                (normalized == workspaceCanonical || normalized.startsWith("$workspaceCanonical${File.separator}"))) {
                Log.w(TAG, "Skipping registration: path '$normalized' resides in active workspace and cannot be an immutable Original Source.")
                return
            }
        }

        registeredOriginalSources.add(normalized)
        Log.i(TAG, "Registered immutable Original Source path: $normalized")
        if (effectiveCtx != null) {
            persistRegisteredSources(effectiveCtx)
        }
    }

    /**
     * Checks if a target file or folder resides within a registered Original Source
     * or within the default game sources directory.
     */
    fun isOriginalSource(target: File, context: Context? = null): Boolean {
        val effectiveCtx = context ?: cachedAppContext
        return try {
            if (effectiveCtx != null && !sourcesLoaded) {
                loadRegisteredSources(effectiveCtx)
            }
            val targetCanonical = target.canonicalPath

            // 1. Any file inside the game's sources/ directory is an Original Source by default
            val sourcesDir = getSourcesDir(effectiveCtx)
            val sourcesCanonical = sourcesDir.canonicalPath
            if (targetCanonical == sourcesCanonical || targetCanonical.startsWith("$sourcesCanonical${File.separator}")) {
                return true
            }

            // 2. Check registered paths in memory and disk
            registeredOriginalSources.any { sourcePath ->
                targetCanonical == sourcePath || targetCanonical.startsWith("$sourcePath${File.separator}")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if a target file or folder resides within the Original Vault.
     * Structural path verification guarantees detection even if context is absent.
     */
    fun isVaultFile(target: File, context: Context? = null): Boolean {
        val effectiveCtx = context ?: cachedAppContext
        val targetCanonical = try { target.canonicalPath } catch (_: Exception) { target.absolutePath }

        // 1. Check canonical path from known context if available
        if (effectiveCtx != null) {
            try {
                val vaultCanonical = getVaultDir(effectiveCtx).canonicalPath
                if (targetCanonical == vaultCanonical || targetCanonical.startsWith("$vaultCanonical${File.separator}")) {
                    return true
                }
            } catch (_: Exception) {}
        }

        // 2. Structural sandbox check: any file in vault/characters path is part of the vault
        val normalized = targetCanonical.replace('\\', '/')
        if (normalized.contains("/vault/characters/") || normalized.endsWith("/vault/characters")) {
            return true
        }

        return false
    }

    /**
     * Checks if a target file is either an Original Source or an Original Vault file.
     * Any write to either is strictly prohibited.
     */
    fun isProtectedImmutableFile(target: File, context: Context? = null): Boolean {
        return isOriginalSource(target, context) || isVaultFile(target, context)
    }

    /**
     * Computes the SHA-256 hash of a file.
     */
    private fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun calculateSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies the SHA-256 fingerprint of a file against an expected hash.
     */
    fun verifyFileIntegrity(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || expectedSha256.isBlank()) return false
        val actual = calculateSha256(file)
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    /**
     * Creates an immutable, pristine snapshot of a character folder inside the Vault.
     * Computes SHA-256 hashes for all copied files and writes manifest.json.
     */
    @Synchronized
    fun capturePristineSnapshot(
        characterId: String,
        sourceFolder: File,
        context: Context,
        characterName: String = characterId,
        sourceOriginOverride: String? = null
    ): VaultSnapshotResult {
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            return VaultSnapshotResult(
                success = false,
                characterId = characterId,
                fingerprints = emptyMap(),
                errorMessage = "Source character folder does not exist or is not a directory: ${sourceFolder.absolutePath}"
            )
        }

        if (!isSafeCharacterId(characterId)) {
            return VaultSnapshotResult(false, characterId, emptyMap(), "Unsafe character ID '$characterId'")
        }

        val charVaultDir = File(getVaultDir(context), characterId)
        val manifestFileAlreadyPresent = File(charVaultDir, "manifest.json")
        if (manifestFileAlreadyPresent.exists()) {
            val (healthy, error) = verifyVaultIntegrity(characterId, context)
            if (!healthy) {
                return VaultSnapshotResult(false, characterId, emptyMap(), "Refusing to overwrite immutable existing Vault: ${error ?: "integrity check failed"}")
            }
            val existingManifest = getVaultManifest(characterId, context)
                ?: return VaultSnapshotResult(false, characterId, emptyMap(), "Existing Vault manifest could not be read")
            return VaultSnapshotResult(
                success = true,
                characterId = characterId,
                fingerprints = existingManifest.files.mapValues { it.value.sha256 }
            )
        }

        val filesVaultDir = File(charVaultDir, "files")
        if (!manifestFileAlreadyPresent.exists() && filesVaultDir.exists() && (filesVaultDir.listFiles()?.isNotEmpty() == true)) {
            return VaultSnapshotResult(
                false,
                characterId,
                emptyMap(),
                "Refusing to overwrite orphaned Vault payload because its manifest is missing; preserve it for forensic recovery"
            )
        }
        if (!filesVaultDir.exists() && !filesVaultDir.mkdirs()) {
            return VaultSnapshotResult(false, characterId, emptyMap(), "Cannot create Vault files directory")
        }

        val fingerprints = mutableMapOf<String, VaultFileFingerprint>()
        val essentialFiles = mutableListOf<String>()

        try {
            val allFiles = sourceFolder.walkTopDown().filter { it.isFile }.toList()
            if (allFiles.isEmpty()) {
                return VaultSnapshotResult(
                    success = false,
                    characterId = characterId,
                    fingerprints = emptyMap(),
                    errorMessage = "Cannot capture snapshot: folder contains no files (${sourceFolder.absolutePath})"
                )
            }
            val defFiles = allFiles.filter { it.extension.equals("def", ignoreCase = true) }
            if (defFiles.isEmpty()) {
                return VaultSnapshotResult(
                    success = false,
                    characterId = characterId,
                    fingerprints = emptyMap(),
                    errorMessage = "Cannot capture snapshot: folder contains no .def character definition file (${sourceFolder.absolutePath})"
                )
            }

            val workspaceCharsCanonical = try { getWorkspaceCharsDir(context).canonicalPath } catch (_: Exception) { "" }
            val sourceFolderCanonical = try { sourceFolder.canonicalPath } catch (_: Exception) { sourceFolder.absolutePath }
            val isFromWorkspace = workspaceCharsCanonical.isNotEmpty() &&
                    (sourceFolderCanonical == workspaceCharsCanonical || sourceFolderCanonical.startsWith("$workspaceCharsCanonical${File.separator}"))

            val origin = if (sourceOriginOverride != null) {
                sourceOriginOverride
            } else if (isFromWorkspace) {
                Log.w(TAG, "NOTICE: Vault snapshot for '$characterId' captured from pre-existing workspace folder rather than external archive.")
                "PRE_EXISTING_WORKSPACE_ADOPTED"
            } else {
                "VERIFIED_ORIGINAL_SOURCE"
            }

            for (file in allFiles) {
                val relPath = file.relativeTo(sourceFolder).path.replace('\\', '/')
                val targetVaultFile = File(filesVaultDir, relPath)
                targetVaultFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                // Atomic copy to vault
                if (targetVaultFile.exists() && !targetVaultFile.canWrite()) {
                    targetVaultFile.setWritable(true)
                }
                val tempVaultFile = File(targetVaultFile.parentFile, "${targetVaultFile.name}.tmp")
                file.copyTo(tempVaultFile, overwrite = true)
                if (!tempVaultFile.renameTo(targetVaultFile)) {
                    tempVaultFile.copyTo(targetVaultFile, overwrite = true)
                    tempVaultFile.delete()
                }
                targetVaultFile.setReadOnly()

                val hash = calculateSha256(targetVaultFile)
                val fp = VaultFileFingerprint(
                    relativePath = relPath,
                    sha256 = hash,
                    sizeBytes = targetVaultFile.length(),
                    lastModified = file.lastModified()
                )
                fingerprints[relPath] = fp

                val ext = file.extension.lowercase()
                if (ext in listOf("def", "cns", "cmd", "air", "st", "zss")) {
                    essentialFiles.add(relPath)
                }
            }

            val manifest = VaultManifest(
                characterId = characterId,
                characterName = characterName,
                originalSourcePath = sourceFolder.canonicalPath,
                createdAt = System.currentTimeMillis(),
                files = fingerprints,
                essentialFiles = essentialFiles,
                sourceOrigin = origin
            )

            // Commit the manifest only after every payload file is copied and fingerprinted.
            val manifestFile = File(charVaultDir, "manifest.json")
            writeBytesAtomically(manifestFile, manifest.toJson().toByteArray(Charsets.UTF_8))

            // Also initialize CharacterMetadata if missing
            val metaFile = File(getMetadataDir(context), "$characterId.json")
            if (!metaFile.exists()) {
                val meta = CharacterMetadata(
                    characterId = characterId,
                    displayName = characterName,
                    originalSourcePath = sourceFolder.canonicalPath,
                    vaultPath = charVaultDir.canonicalPath,
                    workspacePath = File(getWorkspaceCharsDir(context), characterId).canonicalPath,
                    status = CharacterWorkspaceStatus.PRISTINE_ORIGINAL,
                    version = 1,
                    lastModified = System.currentTimeMillis(),
                    changeHistory = mutableListOf()
                )
                writeBytesAtomically(metaFile, meta.toJson().toByteArray(Charsets.UTF_8))
            }

            Log.i(TAG, "Successfully captured Vault snapshot for '$characterId' (${fingerprints.size} files)")
            return VaultSnapshotResult(
                success = true,
                characterId = characterId,
                fingerprints = fingerprints.mapValues { it.value.sha256 }
            )
        } catch (e: Exception) {
            if (e is AtomicWriteRecoveryException) {
                writeMetadataRecoveryMarker(characterId, context, e.message ?: "Metadata recovery failed during initial vault capture")
            }
            Log.e(TAG, "Error capturing Vault snapshot for '$characterId': ${e.message}", e)
            return VaultSnapshotResult(
                success = false,
                characterId = characterId,
                fingerprints = emptyMap(),
                errorMessage = e.message
            )
        }
    }

    /**
     * Retrieves the stored VaultManifest for a character.
     */
    fun getVaultManifest(characterId: String, context: Context): VaultManifest? {
        val manifestFile = File(File(getVaultDir(context), characterId), "manifest.json")
        if (!manifestFile.exists() || !manifestFile.canRead()) return null
        return try {
            VaultManifest.fromJson(manifestFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Error reading manifest for '$characterId': ${e.message}")
            null
        }
    }

    /**
     * Verifies the cryptographic integrity of files stored inside the Vault against manifest.json.
     */
    fun verifyVaultIntegrity(characterId: String, context: Context): Pair<Boolean, String?> {
        val manifest = getVaultManifest(characterId, context)
            ?: return Pair(false, "No vault manifest found for character '$characterId'")
        if (manifest.characterId != characterId) return Pair(false, "Vault manifest character ID mismatch")
        if (manifest.files.isEmpty()) return Pair(false, "Vault manifest contains no files")

        val filesVaultDir = File(File(getVaultDir(context), characterId), "files")
        if (!filesVaultDir.isDirectory) return Pair(false, "Vault files directory missing for '$characterId'")

        for ((relPath, fp) in manifest.files) {
            val file = resolveSafeRelativeFile(filesVaultDir, relPath)
                ?: return Pair(false, "Unsafe path in vault manifest: $relPath")
            if (fp.relativePath != relPath) return Pair(false, "Vault manifest relative path mismatch for $relPath")
            if (fp.sizeBytes < 0L || !fp.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                return Pair(false, "Invalid vault fingerprint metadata for $relPath")
            }
            if (!file.isFile) {
                return Pair(false, "Vault file missing or not regular: $relPath")
            }
            if (file.length() != fp.sizeBytes) {
                return Pair(false, "Vault file size mismatch for $relPath: expected ${fp.sizeBytes}, found ${file.length()}")
            }
            val actualHash = calculateSha256(file)
            if (!actualHash.equals(fp.sha256, ignoreCase = true)) {
                return Pair(false, "Vault file SHA-256 corrupted for $relPath: expected ${fp.sha256}, calculated $actualHash")
            }
        }

        val vaultHashes = try { fileHashMap(filesVaultDir) } catch (e: Exception) {
            return Pair(false, "Cannot safely enumerate Vault files: ${e.message}")
        }
        if (vaultHashes.keys != manifest.files.keys) {
            return Pair(false, "Vault file set differs from manifest (missing or untracked files detected)")
        }
        for (essentialPath in manifest.essentialFiles) {
            if (!manifest.files.containsKey(essentialPath)) {
                return Pair(false, "Vault manifest essential file is not fingerprinted: $essentialPath")
            }
        }
        return Pair(true, null)
    }

    /**
     * Imports a character into the system following the strict 4-layer protocol:
     * 1. Validates source path.
     * 2. Registers source path as an immutable Original Source.
     * 3. Creates a pristine Vault snapshot in app private space with SHA-256.
     * 4. Deploys an independent working copy to Character Workspace for the engine.
     * 5. Initializes Change Metadata.
     */
    @Synchronized
    fun importCharacter(
        sourceFolder: File,
        characterId: String? = null,
        context: Context
    ): CharacterImportResult {
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            return CharacterImportResult(
                success = false,
                characterId = characterId ?: sourceFolder.name,
                workspaceFolder = null,
                copiedFilesCount = 0,
                vaultManifest = null,
                errorMessage = "Source folder does not exist: ${sourceFolder.absolutePath}"
            )
        }

        val charId = if (!characterId.isNullOrBlank()) characterId.trim() else sourceFolder.name
        registerOriginalSource(sourceFolder.canonicalPath, context)

        // 1. Vault snapshot first
        val snapshotResult = capturePristineSnapshot(charId, sourceFolder, context)
        if (!snapshotResult.success) {
            return CharacterImportResult(
                success = false,
                characterId = charId,
                workspaceFolder = null,
                copiedFilesCount = 0,
                vaultManifest = null,
                errorMessage = "Failed creating Vault snapshot: ${snapshotResult.errorMessage}"
            )
        }

        val manifest = getVaultManifest(charId, context)

        // 2. Deploy to Character Workspace
        val workspaceCharsDir = getWorkspaceCharsDir(context)
        val targetWorkspaceFolder = File(workspaceCharsDir, charId)

        // If source is already in workspace, ensure it's backed by vault and marked safe
        if (targetWorkspaceFolder.canonicalPath == sourceFolder.canonicalPath) {
            Log.i(TAG, "Source folder is already at workspace path; secured with Vault snapshot: ${targetWorkspaceFolder.canonicalPath}")
            return CharacterImportResult(
                success = true,
                characterId = charId,
                workspaceFolder = targetWorkspaceFolder,
                copiedFilesCount = manifest?.files?.size ?: 0,
                vaultManifest = manifest
            )
        }

        if (!targetWorkspaceFolder.exists()) {
            targetWorkspaceFolder.mkdirs()
        }

        val filesVaultDir = File(File(getVaultDir(context), charId), "files")
        var copiedCount = 0

        try {
            val vaultFiles = filesVaultDir.walkTopDown().filter { it.isFile }.toList()
            for (vFile in vaultFiles) {
                val rel = vFile.relativeTo(filesVaultDir).path.replace('\\', '/')
                val destFile = File(targetWorkspaceFolder, rel)
                destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                // Atomic copy
                val tmpDest = File(destFile.parentFile, "${destFile.name}.tmp")
                vFile.copyTo(tmpDest, overwrite = true)
                if (!tmpDest.renameTo(destFile)) {
                    tmpDest.copyTo(destFile, overwrite = true)
                    tmpDest.delete()
                }
                copiedCount++
            }

            Log.i(TAG, "Successfully deployed character '$charId' to workspace: ${targetWorkspaceFolder.absolutePath} ($copiedCount files)")
            return CharacterImportResult(
                success = true,
                characterId = charId,
                workspaceFolder = targetWorkspaceFolder,
                copiedFilesCount = copiedCount,
                vaultManifest = manifest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error deploying character to workspace: ${e.message}", e)
            // Rollback workspace copy to prevent partial/corrupted character
            targetWorkspaceFolder.deleteRecursively()
            return CharacterImportResult(
                success = false,
                characterId = charId,
                workspaceFolder = null,
                copiedFilesCount = 0,
                vaultManifest = manifest,
                errorMessage = "Failed deploying to workspace: ${e.message}"
            )
        }
    }

    /**
     * Separates a pre-existing/legacy character from the active workspace (chars/<charId>)
     * into an immutable Original Source in sources/chars/<charId>.
     *
     * Invariants guaranteed:
     * 1. Validates source character folder exists and contains valid .def file.
     * 2. Atomic copy of files to sources/chars/<charId>.
     * 3. Sets read-only permissions on all files in sources/chars/<charId>.
     * 4. Registers sources/chars/<charId> as an immutable Original Source.
     * 5. Returns the separated File directory or null on failure.
     */
    @Synchronized
    fun separateLegacyCharacterSource(characterFolder: File, context: Context): File? {
        context.let { cachedAppContext = it.applicationContext }
        if (!characterFolder.exists() || !characterFolder.isDirectory) {
            Log.e(TAG, "Cannot separate legacy character: folder does not exist or is not a directory: ${characterFolder.absolutePath}")
            return null
        }
        val defFiles = characterFolder.listFiles()?.filter { it.extension.equals("def", ignoreCase = true) }
        if (defFiles.isNullOrEmpty()) {
            Log.e(TAG, "Cannot separate legacy character: folder contains no .def character definition file: ${characterFolder.absolutePath}")
            return null
        }

        val charId = characterFolder.name
        val sourcesDir = getSourcesDir(context)
        val targetSourceDir = File(sourcesDir, charId)

        if (!targetSourceDir.exists()) {
            targetSourceDir.mkdirs()
        }

        try {
            val allFiles = characterFolder.walkTopDown().filter { it.isFile }.toList()
            for (file in allFiles) {
                val relPath = file.relativeTo(characterFolder).path.replace('\\', '/')
                val destFile = File(targetSourceDir, relPath)
                destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                if (destFile.exists() && !destFile.canWrite()) {
                    destFile.setWritable(true)
                }
                val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
                file.copyTo(tmpFile, overwrite = true)
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                destFile.setReadOnly()
            }

            registerOriginalSource(targetSourceDir.canonicalPath, context)
            Log.i(TAG, "Successfully separated legacy character '$charId' into immutable source: ${targetSourceDir.canonicalPath}")
            return targetSourceDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to separate legacy character '$charId': ${e.message}", e)
            return null
        }
    }

    /**
     * Reconstructs an immutable Original Source in sources/chars/<charId> from pristine
     * Vault files. Used when a legacy character in workspace was already modified prior
     * to separation, ensuring the separated source contains true pristine data rather than
     * modified workspace files.
     */
    @Synchronized
    fun separateLegacySourceFromVault(characterId: String, context: Context): File? {
        context.let { cachedAppContext = it.applicationContext }
        val charVaultDir = File(getVaultDir(context), characterId)
        val filesVaultDir = File(charVaultDir, "files")
        if (!filesVaultDir.exists() || !filesVaultDir.isDirectory) {
            Log.w(TAG, "Cannot separate source from vault: vault files directory missing for '$characterId'")
            return null
        }

        val sourcesDir = getSourcesDir(context)
        val targetSourceDir = File(sourcesDir, characterId)
        if (!targetSourceDir.exists()) {
            targetSourceDir.mkdirs()
        }

        try {
            val vaultFiles = filesVaultDir.walkTopDown().filter { it.isFile }.toList()
            for (vFile in vaultFiles) {
                val relPath = vFile.relativeTo(filesVaultDir).path.replace('\\', '/')
                val destFile = File(targetSourceDir, relPath)
                destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                if (destFile.exists() && !destFile.canWrite()) {
                    destFile.setWritable(true)
                }
                val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
                vFile.copyTo(tmpFile, overwrite = true)
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                destFile.setReadOnly()
            }

            registerOriginalSource(targetSourceDir.canonicalPath, context)
            Log.i(TAG, "Successfully reconstructed pristine immutable source for '$characterId' from Vault in: ${targetSourceDir.canonicalPath}")
            return targetSourceDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed reconstructing source from vault for '$characterId': ${e.message}", e)
            return null
        }
    }

    /**
     * Batch separates all pre-existing legacy characters in the workspace into immutable Original Sources.
     */
    @Synchronized
    fun separateAllLegacyCharacterSources(context: Context): List<String> {
        val charsWorkspace = getWorkspaceCharsDir(context)
        if (!charsWorkspace.exists() || !charsWorkspace.isDirectory) return emptyList()

        val separated = mutableListOf<String>()
        val folders = charsWorkspace.listFiles { file -> file.isDirectory } ?: return emptyList()
        for (folder in folders) {
            val hasDef = folder.listFiles()?.any { it.extension.equals("def", ignoreCase = true) } == true
            if (hasDef) {
                val sourcesDir = getSourcesDir(context)
                val targetSourceDir = File(sourcesDir, folder.name)
                if (!targetSourceDir.exists() || targetSourceDir.listFiles().isNullOrEmpty()) {
                    val result = separateLegacyCharacterSource(folder, context)
                    if (result != null) {
                        ensureVaultSnapshot(folder, context)
                        separated.add(folder.name)
                    }
                }
            }
        }
        return separated
    }

    /**
     * Ensures that any character currently discovered in the game directory has an immutable
     * Vault snapshot on record before any editing action is permitted.
     * Also guarantees that pre-existing legacy characters are separated into immutable
     * Original Sources under sources/chars/<charId>.
     */
    @Synchronized
    fun ensureVaultSnapshot(characterFolder: File, context: Context): Boolean {
        val charId = characterFolder.name
        val manifestFile = File(File(getVaultDir(context), charId), "manifest.json")
        val sourcesDir = getSourcesDir(context)
        val sourceInSources = File(sourcesDir, charId)

        if (manifestFile.exists()) {
            // Existing snapshots are immutable: a present manifest is not enough; verify it before use.
            val (healthy, vaultError) = verifyVaultIntegrity(charId, context)
            if (!healthy) {
                Log.e(TAG, "Refusing to trust existing Vault for '$charId': ${vaultError ?: "integrity check failed"}")
                return false
            }
            // Reconstruct immutable source in sources/chars if missing only after the vault itself is verified.
            if (!sourceInSources.exists() || sourceInSources.listFiles().isNullOrEmpty()) {
                separateLegacySourceFromVault(charId, context)
            }
            return true
        }

        // 1. Authoritative Original Source priority:
        // Check if an authentic untouched copy exists in sources/chars/<charId>
        var isSeparatedLegacy = false
        val snapshotFolder = if (sourceInSources.exists() && sourceInSources.isDirectory &&
            (sourceInSources.listFiles()?.any { it.extension.equals("def", ignoreCase = true) } == true)) {
            Log.i(TAG, "Using authoritative immutable source in sources/chars/$charId for initial vault snapshot")
            registerOriginalSource(sourceInSources.canonicalPath, context)
            sourceInSources
        } else {
            // Check registered sources registry
            val registeredMatch = registeredOriginalSources.find {
                it.endsWith("${File.separator}$charId") && File(it).exists() &&
                        (File(it).listFiles()?.any { f -> f.extension.equals("def", ignoreCase = true) } == true)
            }
            if (registeredMatch != null) {
                File(registeredMatch)
            } else {
                // Pre-existing legacy character in workspace:
                // Separate into sources/chars/<charId> FIRST so immutable original source is physically isolated!
                val separated = separateLegacyCharacterSource(characterFolder, context)
                if (separated != null) {
                    isSeparatedLegacy = true
                    separated
                } else {
                    characterFolder
                }
            }
        }

        val originOverride = if (isSeparatedLegacy) "LEGACY_WORKSPACE_SEPARATED" else null
        val result = capturePristineSnapshot(charId, snapshotFolder, context, sourceOriginOverride = originOverride)
        return result.success
    }

    /**
     * Safely saves modifications made to a character file (e.g. .cns or .def).
     *
     * Invariants guaranteed:
     * 1. REJECTS any write attempt targeted at an Original Source file or Vault file.
     * 2. REJECTS any path traversal attempts escaping the character workspace.
     * 3. Ensures an immutable Vault pristine snapshot exists before writing changes.
     * 4. Writes atomically to a temporary file before renaming with backup rollback.
     * 5. Updates Change Metadata with revision history, timestamps, and MODIFIED status.
     */
    @Synchronized
    fun saveCharacterFileSafe(
        characterId: String,
        relativePath: String,
        updatedContent: String,
        changes: List<ChangeRecord>,
        context: Context
    ): SafeSaveResult {
        if (!isSafeCharacterId(characterId)) return SafeSaveResult(false, null, null, "SECURITY VIOLATION: Unsafe character ID '$characterId'")
        val workspaceRoot = getWorkspaceCharsDir(context)
        val workspaceDir = File(workspaceRoot, characterId)
        val rootCanonical = try { workspaceRoot.canonicalPath } catch (_: Exception) { workspaceRoot.absolutePath }
        val workspaceCanonical = try { workspaceDir.canonicalPath } catch (_: Exception) { workspaceDir.absolutePath }
        if (!workspaceDir.isDirectory || !workspaceCanonical.startsWith(rootCanonical + File.separator)) {
            return SafeSaveResult(false, null, null, "Cannot save: character workspace is missing or outside the workspace root")
        }
        val targetFile = resolveSafeRelativeFile(workspaceDir, relativePath)
            ?: return SafeSaveResult(false, null, null, "SECURITY VIOLATION: Path traversal detected: Unsafe relative path '$relativePath'")
        if (!targetFile.isFile) return SafeSaveResult(false, null, null, "Save blocked: target is missing or not a regular file")

        if (getCharacterStatus(characterId, context) == CharacterWorkspaceStatus.CORRUPTED) {
            return SafeSaveResult(false, null, null, "Save blocked: character workspace is CORRUPTED. Restore from a healthy vault before editing again.")
        }
        val manifestFile = File(File(getVaultDir(context), characterId), "manifest.json")
        if (manifestFile.exists()) {
            val (healthy, vaultError) = verifyVaultIntegrity(characterId, context)
            if (!healthy) return SafeSaveResult(false, null, null, "Save blocked: vault integrity check failed: ${vaultError ?: "unknown vault error"}")
        } else {
            if (!ensureVaultSnapshot(workspaceDir, context)) {
                return SafeSaveResult(false, null, null, "Cannot save: Failed securing pristine Vault snapshot for character '$characterId'")
            }
            val (healthy, vaultError) = verifyVaultIntegrity(characterId, context)
            if (!healthy) return SafeSaveResult(false, null, null, "Save blocked: vault failed integrity validation: $vaultError")
        }

        if (isProtectedImmutableFile(targetFile, context) || isProtectedImmutableFile(targetFile.canonicalFile, context)) {
            return SafeSaveResult(false, null, null, "SECURITY VIOLATION: Attempted direct write to an immutable source or vault file")
        }

        val backupFile = File(targetFile.parentFile, "${targetFile.name}.pre_atomic_bak")
        if (backupFile.exists()) {
            return SafeSaveResult(false, null, null, "Save blocked: unresolved emergency backup exists for ${targetFile.name}")
        }
        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.atomic_tmp_${System.nanoTime()}")
        val originalHash = calculateSha256(targetFile)
        var backupVerified = false
        try {
            targetFile.copyTo(backupFile, overwrite = false)
            if (calculateSha256(backupFile) != originalHash || backupFile.length() != targetFile.length()) {
                throw java.io.IOException("Pre-save backup verification failed")
            }
            backupVerified = true
            FileOutputStream(tempFile).use { out ->
                out.write(updatedContent.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            replaceWithStagedFile(tempFile, targetFile)
            val newHash = calculateSha256(targetFile)
            if (newHash != sha256Bytes(updatedContent.toByteArray(Charsets.UTF_8))) throw java.io.IOException("Saved content verification failed")

            val metaFile = File(getMetadataDir(context), "$characterId.json")
            val meta = if (metaFile.isFile) CharacterMetadata.fromJson(metaFile.readText()) else CharacterMetadata(
                characterId = characterId,
                displayName = characterId,
                originalSourcePath = "",
                vaultPath = File(getVaultDir(context), characterId).canonicalPath,
                workspacePath = workspaceDir.canonicalPath,
                status = CharacterWorkspaceStatus.PRISTINE_ORIGINAL,
                version = 1,
                lastModified = System.currentTimeMillis()
            )
            meta.status = CharacterWorkspaceStatus.MODIFIED
            meta.version += 1
            meta.lastModified = System.currentTimeMillis()
            meta.changeHistory.addAll(changes)
            if (simulateMetadataWriteFailureForTesting) throw java.io.IOException("Simulated metadata write failure before atomic metadata commit")
            writeBytesAtomically(metaFile, meta.toJson().toByteArray(Charsets.UTF_8))

            if (!backupFile.delete()) Log.w(TAG, "Could not remove completed save backup ${backupFile.absolutePath}")
            return SafeSaveResult(true, targetFile, newHash)
        } catch (e: Exception) {
            Log.e(TAG, "Safe save failed for '$characterId/$relativePath': ${e.message}; restoring original file", e)
            if (!backupVerified) {
                // No live target replacement begins before a verified backup exists.
                if (backupFile.exists() && !backupFile.delete()) {
                    markCharacterCorrupted(characterId, context, workspaceDir)
                    return SafeSaveResult(false, null, null, "CRITICAL: Save stopped before commit, but an incomplete backup could not be removed: ${backupFile.absolutePath}")
                }
                if (tempFile.exists()) tempFile.delete()
                return SafeSaveResult(false, null, null, "Save failed before replacing the active file; target was not changed: ${e.message}")
            }
            try {
                if (backupFile.isFile) {
                    val restoreTemp = File(targetFile.parentFile, ".${targetFile.name}.rollback_tmp_${System.nanoTime()}")
                    backupFile.copyTo(restoreTemp, overwrite = false)
                    replaceWithStagedFile(restoreTemp, targetFile)
                    if (calculateSha256(targetFile) != originalHash) throw java.io.IOException("Original file rollback verification failed")
                    if (!backupFile.delete()) Log.w(TAG, "Preserving verified backup at ${backupFile.absolutePath}")
                }
                if (tempFile.exists()) tempFile.delete()
                if (e is AtomicWriteRecoveryException) {
                    writeMetadataRecoveryMarker(characterId, context, e.message ?: "Metadata recovery failed during single-file save")
                    return SafeSaveResult(false, null, null, "CRITICAL: The character file was restored, but metadata recovery could not be verified. Previous metadata was preserved if available; restore the character before editing again. ${e.message}")
                }
                return SafeSaveResult(false, null, null, "Safe save failed and original file was restored: ${e.message}")
            } catch (rollbackEx: Exception) {
                markCharacterCorrupted(characterId, context, workspaceDir)
                Log.e(TAG, "CRITICAL: Safe save rollback failed for '$characterId/$relativePath'; backup preserved at ${backupFile.absolutePath}", rollbackEx)
                return SafeSaveResult(false, null, null, "CRITICAL: Safe save and rollback failed. Emergency backup preserved at ${backupFile.name}: ${rollbackEx.message}")
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Reject path-like IDs before using a character ID as a directory or metadata name. */
    private fun isSafeCharacterId(characterId: String): Boolean {
        if (characterId.isBlank() || characterId != characterId.trim()) return false
        if (characterId == "." || characterId == "..") return false
        if (characterId.contains('/') || characterId.contains('\\') || characterId.contains('\u0000')) return false
        return !File(characterId).isAbsolute
    }

    /** Validate an untrusted manifest path and ensure it resolves beneath root. */
    private fun resolveSafeRelativeFile(root: File, relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.startsWith('/') || relativePath.startsWith('\\')) return null
        val normalized = relativePath.replace('\\', '/')
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        val target = File(root, normalized)
        val rootCanonical = try { root.canonicalFile } catch (_: Exception) { return null }
        val targetCanonical = try { target.canonicalFile } catch (_: Exception) { return null }
        return if (targetCanonical.path.startsWith(rootCanonical.path + File.separator)) targetCanonical else null
    }

    private fun fileHashMap(root: File): Map<String, String> {
        if (!root.exists()) return emptyMap()
        require(root.isDirectory) { "Expected directory: ${root.absolutePath}" }
        val result = linkedMapOf<String, String>()
        fun visit(dir: File) {
            val children = dir.listFiles() ?: throw java.io.IOException("Cannot list directory: ${dir.absolutePath}")
            for (child in children) {
                val safe = resolveSafeRelativeFile(root, child.relativeTo(root).path)
                    ?: throw java.io.IOException("Unsafe path encountered: ${child.absolutePath}")
                if (safe.isDirectory) visit(safe)
                else if (safe.isFile) result[safe.relativeTo(root.canonicalFile).path] = calculateSha256(safe)
                else throw java.io.IOException("Unsupported filesystem entry: ${safe.absolutePath}")
            }
        }
        visit(root.canonicalFile)
        return result
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        if (!source.exists() || !source.isDirectory) throw java.io.IOException("Source directory missing: ${source.absolutePath}")
        if (!destination.exists() && !destination.mkdirs()) throw java.io.IOException("Cannot create directory: ${destination.absolutePath}")
        val sourceCanonical = source.canonicalFile
        val children = sourceCanonical.listFiles() ?: throw java.io.IOException("Cannot list directory: ${source.absolutePath}")
        for (child in children) {
            val safeSource = resolveSafeRelativeFile(sourceCanonical, child.relativeTo(sourceCanonical).path)
                ?: throw java.io.IOException("Unsafe source path: ${child.absolutePath}")
            val target = File(destination, safeSource.relativeTo(sourceCanonical).path)
            if (safeSource.isDirectory) {
                copyDirectoryContents(safeSource, target)
            } else if (safeSource.isFile) {
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw java.io.IOException("Cannot create ${it.absolutePath}") }
                safeSource.copyTo(target, overwrite = false)
                target.setLastModified(safeSource.lastModified())
            } else {
                throw java.io.IOException("Unsupported filesystem entry: ${safeSource.absolutePath}")
            }
        }
    }

    /** Replace a staged file without ever copying bytes directly over the live destination. */
    private fun replaceWithStagedFile(staged: File, target: File) {
        if (staged.renameTo(target)) return

        val parent = target.parentFile ?: throw java.io.IOException("Target has no parent: ${target.absolutePath}")
        val displaced = File(parent, ".${target.name}.replace_hold_${System.nanoTime()}")
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(displaced)) {
            throw java.io.IOException("Cannot safely move existing target aside: ${target.absolutePath}")
        }
        try {
            if (!staged.renameTo(target)) {
                throw java.io.IOException("Cannot rename staged file into place: ${target.absolutePath}")
            }
        } catch (e: Exception) {
            if (hadTarget && displaced.exists() && !displaced.renameTo(target)) {
                throw java.io.IOException("Replacement failed; prior file preserved at ${displaced.absolutePath}", e)
            }
            throw e
        }
        if (displaced.exists() && !displaced.delete()) {
            Log.w(TAG, "Could not remove displaced file ${displaced.absolutePath}; the verified pre-transaction backup remains available")
        }
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw java.io.IOException("Target has no parent: ${target.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) throw java.io.IOException("Cannot create parent directory: ${parent.absolutePath}")
        if (target.exists() && !target.isFile) throw java.io.IOException("Metadata target is not a regular file: ${target.absolutePath}")

        val token = System.nanoTime()
        val temp = File(parent, ".${target.name}.tmp_$token")
        val previous = if (target.isFile) File(parent, ".${target.name}.previous_$token") else null
        val oldHash = if (target.isFile) calculateSha256(target) else null
        var previousSaved = false
        try {
            if (target.isFile && previous != null) {
                target.copyTo(previous, overwrite = false)
                if (previous.length() != target.length() || calculateSha256(previous) != oldHash) {
                    throw java.io.IOException("Could not verify previous metadata backup for ${target.name}")
                }
                previousSaved = true
            }
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            replaceWithStagedFile(temp, target)
            if (simulateMetadataAtomicFailureAfterReplaceForTesting) {
                throw java.io.IOException("Simulated metadata failure after replacing target")
            }
            if (!target.isFile || target.length() != bytes.size.toLong() || calculateSha256(target) != sha256Bytes(bytes)) {
                throw java.io.IOException("Atomic write verification failed for ${target.absolutePath}")
            }
            if (previous != null && previous.exists() && !previous.delete()) {
                Log.w(TAG, "Could not remove verified previous metadata copy ${previous.absolutePath}")
            }
        } catch (e: Exception) {
            try {
                if (simulateMetadataRollbackFailureForTesting) {
                    throw java.io.IOException("Simulated metadata rollback failure")
                }
                if (previousSaved && previous != null && previous.isFile) {
                    val restoreTemp = File(parent, ".${target.name}.metadata_rollback_$token")
                    previous.copyTo(restoreTemp, overwrite = false)
                    replaceWithStagedFile(restoreTemp, target)
                    if (!target.isFile || calculateSha256(target) != oldHash) {
                        throw java.io.IOException("Prior metadata restore verification failed")
                    }
                    if (!previous.delete()) Log.w(TAG, "Prior metadata retained at ${previous.absolutePath}")
                } else if (oldHash == null && target.exists() && !target.delete()) {
                    throw java.io.IOException("Could not remove incomplete new metadata file ${target.absolutePath}")
                }
            } catch (rollback: Exception) {
                Log.e(TAG, "CRITICAL: Metadata write and metadata rollback both failed; prior copy may be preserved at ${previous?.absolutePath}", rollback)
                throw AtomicWriteRecoveryException("Metadata write failed and prior metadata recovery failed; preserved copy: ${previous?.absolutePath}. Cause: ${e.message}; rollback: ${rollback.message}", rollback)
            }
            if (!previousSaved && previous != null && previous.exists() && !previous.delete()) {
                Log.w(TAG, "Could not remove incomplete metadata backup ${previous.absolutePath}")
            }
            throw e
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Persists a sidecar alarm if metadata itself cannot be recovered. This avoids trying to
     * overwrite potentially valuable previous metadata merely to record CORRUPTED status.
     */
    private fun writeMetadataRecoveryMarker(characterId: String, context: Context, reason: String) {
        try {
            if (!isSafeCharacterId(characterId)) return
            val marker = File(getMetadataDir(context), ".${characterId}.recovery_required")
            marker.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw java.io.IOException("Cannot create metadata directory") }
            FileOutputStream(marker).use { stream ->
                stream.write(("RECOVERY_REQUIRED\n$reason\n").toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
        } catch (markerError: Exception) {
            Log.e(TAG, "CRITICAL: Could not persist metadata recovery marker for '$characterId': ${markerError.message}", markerError)
        }
    }

    private fun markCharacterCorrupted(characterId: String, context: Context, workspaceFolder: File) {
        try {
            val metaFile = File(getMetadataDir(context), "$characterId.json")
            val meta = if (metaFile.isFile) {
                CharacterMetadata.fromJson(metaFile.readText())
            } else {
                CharacterMetadata(
                    characterId = characterId,
                    displayName = characterId,
                    originalSourcePath = "",
                    vaultPath = File(getVaultDir(context), characterId).canonicalPath,
                    workspacePath = workspaceFolder.canonicalPath,
                    status = CharacterWorkspaceStatus.CORRUPTED,
                    version = 1,
                    lastModified = System.currentTimeMillis()
                )
            }
            meta.status = CharacterWorkspaceStatus.CORRUPTED
            meta.lastModified = System.currentTimeMillis()
            writeBytesAtomically(metaFile, meta.toJson().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            writeMetadataRecoveryMarker(characterId, context, "Could not persist CORRUPTED status: ${e.message}")
            Log.e(TAG, "Could not persist CORRUPTED status for '$characterId': ${e.message}", e)
        }
    }

    /**
     * Executes single character restoration from the Vault to the Character Workspace.
     *
     * Invariants guaranteed:
     * 1. Verifies cryptographic integrity of the Vault files before performing any restore.
     * 2. NEVER touches the Original Source.
     * 3. Atomically restores all pristine files to the Character Workspace.
     * 4. Updates Change Metadata status to RESTORED.
     */
    @Synchronized
    fun restoreSingleCharacter(characterId: String, context: Context): RestoreResult {
        if (!isSafeCharacterId(characterId)) {
            return RestoreResult(false, characterId, 0, "Unsafe character ID: '$characterId'")
        }

        val (isVaultValid, vaultError) = verifyVaultIntegrity(characterId, context)
        if (!isVaultValid) {
            val err = "Cannot restore '$characterId': Vault integrity check failed: $vaultError"
            Log.e(TAG, err)
            return RestoreResult(false, characterId, 0, err)
        }
        val manifest = getVaultManifest(characterId, context)
            ?: return RestoreResult(false, characterId, 0, "Vault manifest became unavailable for '$characterId'")

        val workspaceChars = getWorkspaceCharsDir(context)
        if (!workspaceChars.exists() && !workspaceChars.mkdirs()) {
            return RestoreResult(false, characterId, 0, "Cannot create character workspace root")
        }
        if (!workspaceChars.isDirectory) {
            return RestoreResult(false, characterId, 0, "Character workspace root is not a directory")
        }
        val workspaceFolder = File(workspaceChars, characterId)
        val workspaceRootCanonical = try { workspaceChars.canonicalPath } catch (e: Exception) {
            return RestoreResult(false, characterId, 0, "Cannot resolve workspace root: ${e.message}")
        }
        val workspaceCanonical = try { workspaceFolder.canonicalPath } catch (e: Exception) {
            return RestoreResult(false, characterId, 0, "Cannot resolve workspace path: ${e.message}")
        }
        if (!workspaceCanonical.startsWith(workspaceRootCanonical + File.separator)) {
            return RestoreResult(false, characterId, 0, "SECURITY VIOLATION: workspace path escaped root")
        }
        if (workspaceFolder.exists() && !workspaceFolder.isDirectory) {
            return RestoreResult(false, characterId, 0, "Workspace character path is not a directory")
        }

        val timestamp = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val stagingDir = File(workspaceChars, ".restore_staging_${characterId}_$timestamp")
        val rollbackDir = File(workspaceChars, ".restore_backup_${characterId}_$timestamp")
        val filesVaultDir = File(File(getVaultDir(context), characterId), "files")
        val hadWorkspace = workspaceFolder.exists()
        var originalWorkspaceMoved = false
        var rollbackCopyComplete = false
        var restoredCount = 0
        val originalWorkspaceHashes = try {
            if (hadWorkspace) fileHashMap(workspaceFolder) else emptyMap()
        } catch (e: Exception) {
            return RestoreResult(false, characterId, 0, "Cannot safely inspect current workspace: ${e.message}")
        }

        try {
            if (stagingDir.exists() && !stagingDir.deleteRecursively()) throw java.io.IOException("Cannot clear restore staging directory")
            if (!stagingDir.mkdirs()) throw java.io.IOException("Cannot create restore staging directory")

            // Build a complete replacement from the verified manifest. Unsafe paths are rejected.
            for ((relPath, fp) in manifest.files) {
                val vaultFile = resolveSafeRelativeFile(filesVaultDir, relPath)
                    ?: throw java.io.IOException("Unsafe path in vault manifest: $relPath")
                if (!vaultFile.isFile) throw java.io.IOException("Vault file missing or not regular: $relPath")
                val stagedFile = resolveSafeRelativeFile(stagingDir, relPath)
                    ?: throw java.io.IOException("Unsafe staging path: $relPath")
                stagedFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw java.io.IOException("Cannot create ${it.absolutePath}") }
                vaultFile.copyTo(stagedFile, overwrite = false)
                stagedFile.setWritable(true)
                if (stagedFile.length() != fp.sizeBytes || !calculateSha256(stagedFile).equals(fp.sha256, ignoreCase = true)) {
                    throw java.io.IOException("Staged restore integrity mismatch for $relPath")
                }
                restoredCount++
            }
            val stagedHashes = fileHashMap(stagingDir)
            val expectedHashes = manifest.files.mapValues { it.value.sha256 }
            if (stagedHashes.keys != expectedHashes.keys || stagedHashes.any { (path, hash) -> !hash.equals(expectedHashes[path], ignoreCase = true) }) {
                throw java.io.IOException("Staged restore does not exactly match the vault manifest")
            }

            // Secure the entire current character directory, including files that were not in the vault.
            if (hadWorkspace) {
                if (!workspaceFolder.renameTo(rollbackDir)) {
                    if (rollbackDir.exists() && !rollbackDir.deleteRecursively()) throw java.io.IOException("Cannot clear rollback directory")
                    if (!rollbackDir.mkdirs()) throw java.io.IOException("Cannot create rollback directory")
                    copyDirectoryContents(workspaceFolder, rollbackDir)
                    val copiedHashes = fileHashMap(rollbackDir)
                    if (copiedHashes != originalWorkspaceHashes) throw java.io.IOException("Current workspace backup verification failed")
                    rollbackCopyComplete = true
                    if (!workspaceFolder.deleteRecursively() || workspaceFolder.exists()) throw java.io.IOException("Cannot move current workspace out of the way")
                } else {
                    originalWorkspaceMoved = true
                }
            }

            if (simulateRestoreCommitFailureForTesting) {
                throw java.io.IOException("Simulated restore commit failure")
            }

            if (!stagingDir.renameTo(workspaceFolder)) {
                if (workspaceFolder.exists() && !workspaceFolder.deleteRecursively()) throw java.io.IOException("Cannot clear failed workspace destination")
                copyDirectoryContents(stagingDir, workspaceFolder)
                val copiedHashes = fileHashMap(workspaceFolder)
                if (copiedHashes.keys != expectedHashes.keys || copiedHashes.any { (path, hash) -> !hash.equals(expectedHashes[path], ignoreCase = true) }) {
                    throw java.io.IOException("Committed workspace verification failed after copy fallback")
                }
                if (!stagingDir.deleteRecursively()) Log.w(TAG, "Could not remove staging directory ${stagingDir.absolutePath}")
            }

            val restoredHashes = fileHashMap(workspaceFolder)
            if (restoredHashes.keys != expectedHashes.keys || restoredHashes.any { (path, hash) -> !hash.equals(expectedHashes[path], ignoreCase = true) }) {
                throw java.io.IOException("Final workspace does not exactly match the vault; extra or missing files detected")
            }

            // Commit metadata only after the entire workspace has been replaced and verified.
            val metaFile = File(getMetadataDir(context), "$characterId.json")
            val meta = (if (metaFile.isFile) runCatching { CharacterMetadata.fromJson(metaFile.readText()) }.getOrNull() else null)
                ?: CharacterMetadata(
                    characterId = characterId,
                    displayName = manifest.characterName.ifBlank { characterId },
                    originalSourcePath = manifest.originalSourcePath,
                    vaultPath = File(getVaultDir(context), characterId).canonicalPath,
                    workspacePath = workspaceFolder.canonicalPath,
                    status = CharacterWorkspaceStatus.PRISTINE_ORIGINAL,
                    version = 1,
                    lastModified = System.currentTimeMillis()
                )
            meta.status = CharacterWorkspaceStatus.RESTORED
            meta.lastModified = System.currentTimeMillis()
            meta.changeHistory.add(
                ChangeRecord(
                    timestamp = System.currentTimeMillis(),
                    relativePath = "*",
                    section = "[VAULT]",
                    key = "RESTORE",
                    oldValue = "MODIFIED",
                    newValue = "PRISTINE_ORIGINAL",
                    source = "RESTORE_ACTION"
                )
            )
            if (simulateMetadataWriteFailureForTesting) throw java.io.IOException("Simulated metadata write failure during restore")
            writeBytesAtomically(metaFile, meta.toJson().toByteArray(Charsets.UTF_8))
            val metadataDir = getMetadataDir(context)
            val recoveryMarker = File(metadataDir, ".${characterId}.recovery_required")
            if (recoveryMarker.exists() && !recoveryMarker.delete()) {
                throw java.io.IOException("Restore metadata is written, but unresolved recovery marker cannot be removed: ${recoveryMarker.absolutePath}")
            }
            // A verified restore establishes fresh metadata from the trusted vault; stale recovery
            // snapshots from a previously failed metadata transaction are now no longer authoritative.
            metadataDir.listFiles { f ->
                f.name.startsWith(".${characterId}.json.previous_") ||
                    f.name.startsWith(".${characterId}.json.metadata_rollback_")
            }?.forEach { stale ->
                if (stale.exists() && !stale.delete()) {
                    Log.w(TAG, "Could not remove superseded metadata recovery file ${stale.absolutePath}")
                }
            }

            // A successful verified restore supersedes previous emergency restore backups for this ID.
            stagingDir.deleteRecursively()
            if (rollbackDir.exists() && !rollbackDir.deleteRecursively()) {
                Log.w(TAG, "Restored character is verified, but old rollback backup could not be removed: ${rollbackDir.absolutePath}")
            }
            val olderRestoreBackups = workspaceChars.listFiles { f -> f.isDirectory && f.name.startsWith(".restore_backup_${characterId}_") }
            olderRestoreBackups?.filter { it != rollbackDir }?.forEach { backup ->
                if (!backup.deleteRecursively()) Log.w(TAG, "Could not clean superseded emergency restore backup ${backup.absolutePath}")
            }
            Log.i(TAG, "Successfully restored '$characterId' from Vault ($restoredCount files); workspace file set exactly matches manifest")
            return RestoreResult(true, characterId, restoredCount)
        } catch (e: Exception) {
            if (e is AtomicWriteRecoveryException) {
                writeMetadataRecoveryMarker(characterId, context, e.message ?: "Metadata recovery failed during restore")
            }
            Log.e(TAG, "Failed restoring '$characterId': ${e.message}; rolling back directory transaction", e)
            var rollbackFailed = false
            var rollbackError: String? = null
            try {
                if (workspaceFolder.exists() && !workspaceFolder.deleteRecursively()) {
                    throw java.io.IOException("Cannot remove incomplete restored workspace")
                }
                if (hadWorkspace) {
                    if (!rollbackDir.exists() || !rollbackDir.isDirectory) {
                        throw java.io.IOException("Emergency rollback directory is missing")
                    }
                    if (simulateRollbackFailureForTesting) {
                        throw java.io.IOException("Simulated I/O failure during restore rollback")
                    }
                    if (originalWorkspaceMoved) {
                        if (!rollbackDir.renameTo(workspaceFolder)) {
                            // Keep the original backup until the copied replacement is verified.
                            copyDirectoryContents(rollbackDir, workspaceFolder)
                        }
                    } else if (rollbackCopyComplete) {
                        copyDirectoryContents(rollbackDir, workspaceFolder)
                    } else {
                        throw java.io.IOException("No complete workspace rollback snapshot exists")
                    }
                    val rolledBackHashes = fileHashMap(workspaceFolder)
                    if (rolledBackHashes != originalWorkspaceHashes) throw java.io.IOException("Restored rollback workspace does not match its pre-operation snapshot")
                    if (rollbackDir.exists() && !rollbackDir.deleteRecursively()) Log.w(TAG, "Could not delete verified rollback directory ${rollbackDir.absolutePath}")
                } else if (workspaceFolder.exists() && !workspaceFolder.deleteRecursively()) {
                    throw java.io.IOException("Cannot remove incomplete new workspace")
                }
            } catch (rollbackEx: Exception) {
                rollbackFailed = true
                rollbackError = rollbackEx.message
                Log.e(TAG, "CRITICAL: Restore rollback failed for '$characterId': ${rollbackEx.message}", rollbackEx)
            } finally {
                stagingDir.deleteRecursively()
            }

            if (rollbackFailed) {
                // Preserve rollbackDir: it contains the pre-restore workspace and any unvaulted files.
                markCharacterCorrupted(characterId, context, workspaceFolder)
                val backupHint = if (rollbackDir.exists()) rollbackDir.name else "backup may be incomplete"
                return RestoreResult(
                    false,
                    characterId,
                    0,
                    "CRITICAL: Restore failed and rollback failed for '$characterId': ${e.message}; rollback error: $rollbackError. Emergency backup preserved as $backupHint"
                )
            }
            return RestoreResult(false, characterId, 0, "Restore failed; previous workspace was recovered: ${e.message}")
        }
    }

    /**
     * Inspects all characters and generates an inspection plan for batch restoration.
     */
    fun planBatchRestore(context: Context): BatchRestorePlan {
        val vaultDir = getVaultDir(context)
        val charDirs = vaultDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        val modifiedList = mutableListOf<String>()
        val pristineList = mutableListOf<String>()
        val corruptedList = mutableListOf<String>()

        for (cDir in charDirs) {
            val charId = cDir.name
            val (isHealthy, _) = verifyVaultIntegrity(charId, context)
            if (!isHealthy) {
                corruptedList.add(charId)
                continue
            }

            val meta = getCharacterMetadata(charId, context)
            if (meta?.status == CharacterWorkspaceStatus.MODIFIED) {
                modifiedList.add(charId)
            } else {
                pristineList.add(charId)
            }
        }

        return BatchRestorePlan(
            totalCharacters = charDirs.size,
            modifiedCharacters = modifiedList,
            pristineCharacters = pristineList,
            corruptedCharacters = corruptedList
        )
    }

    /**
     * Executes batch restoration of specified characters (or all modified characters).
     */
    @Synchronized
    fun executeBatchRestore(characterIds: List<String>?, context: Context): BatchRestoreReport {
        val targets = if (characterIds != null) {
            characterIds
        } else {
            val plan = planBatchRestore(context)
            val list = mutableListOf<String>()
            list.addAll(plan.modifiedCharacters)
            for (corruptedId in plan.corruptedCharacters) {
                val meta = getCharacterMetadata(corruptedId, context)
                if (meta?.status == CharacterWorkspaceStatus.MODIFIED || isCharacterModified(corruptedId, context)) {
                    list.add(corruptedId)
                }
            }
            list
        }

        val restored = mutableListOf<String>()
        val failed = mutableMapOf<String, String>()

        for (id in targets) {
            val result = restoreSingleCharacter(id, context)
            if (result.success) {
                restored.add(id)
            } else {
                failed[id] = result.errorMessage ?: "Unknown error"
            }
        }

        return BatchRestoreReport(
            success = failed.isEmpty(),
            restoredCharacters = restored,
            failedCharacters = failed
        )
    }

    /**
     * Helper to retrieve metadata for a character.
     */
    fun getCharacterMetadata(characterId: String, context: Context): CharacterMetadata? {
        val metaFile = File(getMetadataDir(context), "$characterId.json")
        if (!metaFile.exists()) return null
        return try {
            CharacterMetadata.fromJson(metaFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Helper to get current workspace status for a character.
     */
    fun getCharacterStatus(characterId: String, context: Context): CharacterWorkspaceStatus {
        if (!isSafeCharacterId(characterId)) return CharacterWorkspaceStatus.CORRUPTED
        val metadataDir = getMetadataDir(context)
        val recoveryMarker = File(metadataDir, ".${characterId}.recovery_required")
        if (recoveryMarker.isFile) return CharacterWorkspaceStatus.CORRUPTED
        val metaFile = File(metadataDir, "$characterId.json")
        val meta = getCharacterMetadata(characterId, context)
        if (meta?.status == CharacterWorkspaceStatus.CORRUPTED) return CharacterWorkspaceStatus.CORRUPTED
        if (metaFile.exists() && meta == null) return CharacterWorkspaceStatus.CORRUPTED

        val workspaceRoot = getWorkspaceCharsDir(context)
        val workspaceFolder = File(workspaceRoot, characterId)
        // Defensive check: an emergency save backup inside the live character indicates unresolved recovery.
        if (workspaceFolder.exists() && workspaceFolder.isDirectory) {
            val hasEmergencyBackup = try {
                workspaceFolder.walkTopDown().any { file -> file.isFile && file.name.endsWith(".pre_atomic_bak") }
            } catch (_: Exception) {
                // If the workspace cannot be fully inspected, fail closed rather than allow writes.
                true
            }
            if (hasEmergencyBackup) return CharacterWorkspaceStatus.CORRUPTED
        }
        // A failed directory restore can leave the only complete pre-restore snapshot beside chars/<id>.
        val emergencyRestoreDirs = workspaceRoot.listFiles { f ->
            f.isDirectory && f.name.startsWith(".restore_backup_${characterId}_")
        }
        if (!emergencyRestoreDirs.isNullOrEmpty()) return CharacterWorkspaceStatus.CORRUPTED

        return meta?.status ?: CharacterWorkspaceStatus.PRISTINE_ORIGINAL
    }

    /**
     * Cryptographically verifies whether a character's active workspace files
     * have been modified relative to the original pristine snapshot in the Vault.
     */
    fun isCharacterModified(characterId: String, context: Context): Boolean {
        val manifest = getVaultManifest(characterId, context) ?: return true
        if (!verifyVaultIntegrity(characterId, context).first) return true
        val workspaceFolder = File(getWorkspaceCharsDir(context), characterId)
        if (!workspaceFolder.exists() || !workspaceFolder.isDirectory) return true

        val activeHashes = try { fileHashMap(workspaceFolder) } catch (_: Exception) { return true }
        val expectedHashes = manifest.files.mapValues { it.value.sha256 }
        if (activeHashes.keys != expectedHashes.keys) return true
        return activeHashes.any { (relativePath, actualHash) ->
            !actualHash.equals(expectedHashes[relativePath], ignoreCase = true)
        }
    }

    /**
     * Checks if a character has a sealed Vault snapshot.
     */
    fun isCharacterVaulted(characterId: String, context: Context): Boolean {
        val manifestFile = File(File(getVaultDir(context), characterId), "manifest.json")
        return manifestFile.exists() && manifestFile.canRead()
    }

    /**
     * Saves changes to a DefEditor safely via the workspace manager.
     */
    fun saveDefChanges(
        defEditor: DefEditor,
        characterId: String,
        context: Context,
        changes: List<ChangeRecord> = emptyList()
    ): SafeSaveResult {
        return saveCompleteCharacterEdits(characterId, defEditor = defEditor, cnsEditor = null, context = context)
    }

    /**
     * Saves changes to a CnsEditor safely via the workspace manager.
     */
    fun saveCnsChanges(
        cnsEditor: CnsEditor,
        characterId: String,
        context: Context,
        changes: List<ChangeRecord> = emptyList()
    ): SafeSaveResult {
        return saveCompleteCharacterEdits(characterId, defEditor = null, cnsEditor = cnsEditor, context = context)
    }

    /**
     * Saves all character modifications (.def and/or .cns) in a true atomic transaction across both files.
     *
     * Invariants guaranteed:
     * 1. Pre-validates path traversal and immutability for ALL target files before writing any file.
     * 2. Secures pristine Vault snapshot BEFORE any disk modification.
     * 3. Prepares backups of all target files in workspace.
     * 4. Writes new content to isolated temporary files with disk sync.
     * 5. Atomically replaces all files.
     * 6. Attempts verified rollback on any commit or metadata failure; if rollback itself fails, marks the workspace
     *    CORRUPTED and preserves the pre-atomic backup rather than claiming recovery succeeded.
     * 7. Metadata is updated exactly ONCE (version += 1) after every target has been written and verified.
     */
    @Synchronized
    fun saveCompleteCharacterEdits(
        characterId: String,
        defEditor: DefEditor?,
        cnsEditor: CnsEditor?,
        context: Context
    ): SafeSaveResult {
        if (defEditor == null && cnsEditor == null) {
            return SafeSaveResult(success = false, savedFile = null, newSha256 = null, errorMessage = "No editor provided to save.")
        }
        if (!isSafeCharacterId(characterId)) {
            return SafeSaveResult(false, null, null, "SECURITY VIOLATION: Unsafe character ID '$characterId'")
        }

        val workspaceRoot = getWorkspaceCharsDir(context)
        val workspaceDir = File(workspaceRoot, characterId)
        val workspaceCanonical = try { workspaceDir.canonicalPath } catch (_: Exception) { workspaceDir.absolutePath }
        val rootCanonical = try { workspaceRoot.canonicalPath } catch (_: Exception) { workspaceRoot.absolutePath }
        if (!workspaceCanonical.startsWith(rootCanonical + File.separator) || !workspaceDir.isDirectory) {
            return SafeSaveResult(false, null, null, "Cannot save: character workspace is missing or outside the workspace root")
        }

        // Never continue editing a workspace previously marked corrupted or with unresolved emergency backups.
        if (getCharacterStatus(characterId, context) == CharacterWorkspaceStatus.CORRUPTED) {
            return SafeSaveResult(false, null, null, "Save blocked: character workspace is CORRUPTED. Restore from a healthy vault before editing again.")
        }

        // Validate an existing vault BEFORE any helper can reconstruct sources or stage writes.
        val manifestFile = File(File(getVaultDir(context), characterId), "manifest.json")
        if (manifestFile.exists()) {
            val (healthy, vaultError) = verifyVaultIntegrity(characterId, context)
            if (!healthy) {
                return SafeSaveResult(false, null, null, "Save blocked: vault integrity check failed: ${vaultError ?: "unknown vault error"}")
            }
        } else {
            if (!ensureVaultSnapshot(workspaceDir, context)) {
                val err = "Cannot save: Failed securing pristine Vault snapshot for character '$characterId'"
                Log.e(TAG, err)
                return SafeSaveResult(false, null, null, err)
            }
            val (healthy, vaultError) = verifyVaultIntegrity(characterId, context)
            if (!healthy) return SafeSaveResult(false, null, null, "Save blocked: newly created vault failed integrity validation: $vaultError")
        }

        class PendingFileWrite(
            val file: File,
            val relPath: String,
            val updatedLines: List<String>,
            val changes: List<ChangeRecord>,
            val editorType: String
        )

        val pendingWrites = mutableListOf<PendingFileWrite>()

        // 2. Pre-validation and preparation for defEditor
        if (defEditor != null) {
            val defFile = defEditor.defFile
            val defRel = defFile.name
            if (!defFile.isFile) {
                return SafeSaveResult(false, null, null, "Save blocked: .def target is missing or not a regular file: ${defFile.absolutePath}")
            }
            val targetCanonical = try { defFile.canonicalPath } catch (_: Exception) { defFile.absolutePath }
            if (!targetCanonical.startsWith("$workspaceCanonical${File.separator}")) {
                val err = "SECURITY VIOLATION: Path traversal detected in .def file: $targetCanonical"
                Log.e(TAG, err)
                return SafeSaveResult(success = false, savedFile = null, newSha256 = null, errorMessage = err)
            }
            if (isProtectedImmutableFile(defFile, context) || isProtectedImmutableFile(try { defFile.canonicalFile } catch (_: Exception) { defFile }, context)) {
                val err = "SECURITY VIOLATION: Attempted write to protected immutable .def file: ${defFile.absolutePath}"
                Log.e(TAG, err)
                return SafeSaveResult(success = false, savedFile = null, newSha256 = null, errorMessage = err)
            }

            val updatedLines = defEditor.lines.toMutableList()
            val collectedChanges = mutableListOf<ChangeRecord>()
            for (sec in defEditor.properties.values) {
                for (prop in sec.values) {
                    if (prop.lineIndex in updatedLines.indices) {
                        val originalLine = updatedLines[prop.lineIndex]
                        val isQuoted = prop.key in listOf("name", "displayname", "author", "versiondate", "mugenversion")
                        val formattedVal = if (isQuoted && !prop.value.startsWith("\"")) "\"${prop.value}\"" else prop.value
                        val newLine = "${prop.key} = $formattedVal${prop.rawComment}"
                        if (originalLine != newLine) {
                            updatedLines[prop.lineIndex] = newLine
                            collectedChanges.add(
                                ChangeRecord(
                                    relativePath = defRel,
                                    section = prop.section,
                                    key = prop.key,
                                    oldValue = originalLine.substringAfter("=").substringBefore(";").trim().removeSurrounding("\""),
                                    newValue = prop.value
                                )
                            )
                        }
                    }
                }
            }
            pendingWrites.add(PendingFileWrite(defFile, defRel, updatedLines, collectedChanges, "DEF"))
        }

        // 3. Pre-validation and preparation for cnsEditor
        if (cnsEditor != null) {
            val cnsFile = cnsEditor.cnsFile
            val cnsRel = cnsFile.name
            if (!cnsFile.isFile) {
                return SafeSaveResult(false, null, null, "Save blocked: .cns target is missing or not a regular file: ${cnsFile.absolutePath}")
            }
            val targetCanonical = try { cnsFile.canonicalPath } catch (_: Exception) { cnsFile.absolutePath }
            if (!targetCanonical.startsWith("$workspaceCanonical${File.separator}")) {
                val err = "SECURITY VIOLATION: Path traversal detected in .cns file: $targetCanonical"
                Log.e(TAG, err)
                return SafeSaveResult(success = false, savedFile = null, newSha256 = null, errorMessage = err)
            }
            if (isProtectedImmutableFile(cnsFile, context) || isProtectedImmutableFile(try { cnsFile.canonicalFile } catch (_: Exception) { cnsFile }, context)) {
                val err = "SECURITY VIOLATION: Attempted write to protected immutable .cns file: ${cnsFile.absolutePath}"
                Log.e(TAG, err)
                return SafeSaveResult(success = false, savedFile = null, newSha256 = null, errorMessage = err)
            }

            val updatedLines = cnsEditor.lines.toMutableList()
            val collectedChanges = mutableListOf<ChangeRecord>()
            for (sec in cnsEditor.properties.values) {
                for (prop in sec.values) {
                    if (prop.lineIndex in updatedLines.indices) {
                        val originalLine = updatedLines[prop.lineIndex]
                        val commentPart = if (originalLine.contains(";")) " ;" + originalLine.substringAfter(";") else ""
                        val newLine = "${prop.key} = ${prop.value}$commentPart"
                        if (originalLine != newLine) {
                            updatedLines[prop.lineIndex] = newLine
                            collectedChanges.add(
                                ChangeRecord(
                                    relativePath = cnsRel,
                                    section = prop.section,
                                    key = prop.key,
                                    oldValue = originalLine.substringAfter("=").substringBefore(";").trim(),
                                    newValue = prop.value
                                )
                            )
                        }
                    }
                }
            }
            pendingWrites.add(PendingFileWrite(cnsFile, cnsRel, updatedLines, collectedChanges, "CNS"))
        }

        // 4. Stage every new file and verify a non-overwritable emergency backup before commit.
        val backups = mutableMapOf<File, File>()
        val tmpFiles = mutableMapOf<File, File>()
        val committedFiles = mutableListOf<File>()

        try {
            for (pw in pendingWrites) {
                val targetFile = pw.file
                if (!targetFile.isFile) throw java.io.IOException("Save target changed or is not a regular file: ${targetFile.absolutePath}")
                val bakFile = File(targetFile.parentFile, "${targetFile.name}.pre_atomic_bak")
                if (bakFile.exists()) throw java.io.IOException("Unresolved emergency backup already exists: ${bakFile.absolutePath}; restore the character before saving")
                backups[targetFile] = bakFile // record before copy so a partial backup is cleaned if no commit occurred
                targetFile.copyTo(bakFile, overwrite = false)
                if (bakFile.length() != targetFile.length() || calculateSha256(bakFile) != calculateSha256(targetFile)) {
                    throw java.io.IOException("Pre-atomic backup verification failed for ${targetFile.name}")
                }

                val tmpFile = File(targetFile.parentFile, ".${targetFile.name}.atomic_tmp_${System.nanoTime()}")
                tmpFiles[targetFile] = tmpFile // record before opening the file so partial stages are always cleaned
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(pw.updatedLines.joinToString("\n").toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
            }

            // 5. Commit through rename-only replacement; never stream new bytes over a live file.
            for (pw in pendingWrites) {
                val targetFile = pw.file
                if (simulateCommitFailureAfterReplacementsForTesting != null &&
                    committedFiles.size >= simulateCommitFailureAfterReplacementsForTesting!!) {
                    throw java.io.IOException("Simulated commit failure after ${committedFiles.size} file replacements")
                }
                val tmpFile = tmpFiles[targetFile] ?: throw IllegalStateException("Missing temp file for ${targetFile.name}")
                committedFiles.add(targetFile) // register before the first operation that may move/delete the live target
                replaceWithStagedFile(tmpFile, targetFile)
                val expectedText = pw.updatedLines.joinToString("\n").toByteArray(Charsets.UTF_8)
                if (!targetFile.isFile || targetFile.length() != expectedText.size.toLong() || calculateSha256(targetFile) != sha256Bytes(expectedText)) {
                    throw java.io.IOException("Committed file verification failed for ${targetFile.name}")
                }
            }

            // 6. Update Change Metadata ONCE for the whole transaction
            val allChanges = pendingWrites.flatMap { it.changes }
            val metaFile = File(getMetadataDir(context), "$characterId.json")
            val meta = if (metaFile.exists()) {
                try {
                    CharacterMetadata.fromJson(metaFile.readText())
                } catch (_: Exception) {
                    CharacterMetadata(
                        characterId = characterId,
                        displayName = characterId,
                        originalSourcePath = "",
                        vaultPath = File(getVaultDir(context), characterId).canonicalPath,
                        workspacePath = workspaceDir.canonicalPath,
                        status = CharacterWorkspaceStatus.PRISTINE_ORIGINAL,
                        version = 1,
                        lastModified = System.currentTimeMillis()
                    )
                }
            } else {
                CharacterMetadata(
                    characterId = characterId,
                    displayName = characterId,
                    originalSourcePath = "",
                    vaultPath = File(getVaultDir(context), characterId).canonicalPath,
                    workspacePath = workspaceDir.canonicalPath,
                    status = CharacterWorkspaceStatus.PRISTINE_ORIGINAL,
                    version = 1,
                    lastModified = System.currentTimeMillis()
                )
            }

            meta.status = CharacterWorkspaceStatus.MODIFIED
            meta.version += 1
            meta.lastModified = System.currentTimeMillis()
            meta.changeHistory.addAll(allChanges)

            if (simulateMetadataWriteFailureForTesting) {
                throw java.io.IOException("Simulated metadata write failure before atomic metadata commit")
            }
            writeBytesAtomically(metaFile, meta.toJson().toByteArray(Charsets.UTF_8))

            // 7. Both files and metadata committed successfully! Now update in-memory editor lines
            for (pw in pendingWrites) {
                if (pw.editorType == "DEF" && defEditor != null) {
                    for (i in pw.updatedLines.indices) {
                        if (i < defEditor.lines.size) defEditor.lines[i] = pw.updatedLines[i]
                    }
                } else if (pw.editorType == "CNS" && cnsEditor != null) {
                    for (i in pw.updatedLines.indices) {
                        if (i < cnsEditor.lines.size) cnsEditor.lines[i] = pw.updatedLines[i]
                    }
                }
            }

            // 8. Clean up temporary backups
            for (bak in backups.values) {
                if (bak.exists()) bak.delete()
            }
            for (tmp in tmpFiles.values) {
                if (tmp.exists()) tmp.delete()
            }

            val lastCommitted = committedFiles.lastOrNull()
            val newHash = lastCommitted?.let { calculateSha256(it) }
            Log.i(TAG, "Multi-file atomic save complete for character '$characterId' (${committedFiles.size} files committed, meta version=${meta.version})")
            return SafeSaveResult(success = true, savedFile = lastCommitted, newSha256 = newHash)

        } catch (e: Exception) {
            Log.e(TAG, "Failure during multi-file atomic save for '$characterId': ${e.message}; initiating full transaction rollback!", e)
            val metadataRecoveryFailed = e is AtomicWriteRecoveryException
            var rollbackFailed = metadataRecoveryFailed
            val rollbackFailedFiles = mutableListOf<String>()
            if (metadataRecoveryFailed) {
                rollbackFailedFiles.add("metadata")
                writeMetadataRecoveryMarker(characterId, context, e.message ?: "Metadata recovery failed during multi-file save")
            }

            // ROLLBACK in reverse order using staged replacement; never overwrite a live file byte-by-byte.
            for (targetFile in committedFiles.asReversed()) {
                val bak = backups[targetFile]
                try {
                    if (simulateRollbackFailureForTesting) {
                        throw java.io.IOException("Simulated I/O failure during rollback of ${targetFile.name}")
                    }
                    if (bak != null && bak.isFile) {
                        val restoreTemp = File(targetFile.parentFile, ".${targetFile.name}.rollback_tmp_${System.nanoTime()}")
                        bak.copyTo(restoreTemp, overwrite = false)
                        if (restoreTemp.length() != bak.length() || calculateSha256(restoreTemp) != calculateSha256(bak)) {
                            restoreTemp.delete()
                            throw java.io.IOException("Rollback copy verification failed for ${targetFile.name}")
                        }
                        replaceWithStagedFile(restoreTemp, targetFile)
                        if (!targetFile.isFile || calculateSha256(targetFile) != calculateSha256(bak)) {
                            throw java.io.IOException("Rollback verification failed for ${targetFile.name}")
                        }
                        Log.i(TAG, "Rolled back ${targetFile.name} to pre-save state")
                    } else if (targetFile.exists() && !targetFile.delete()) {
                        throw java.io.IOException("Unable to remove newly created target ${targetFile.name} during rollback")
                    }
                } catch (rbEx: Exception) {
                    rollbackFailed = true
                    rollbackFailedFiles.add(targetFile.name)
                    Log.e(TAG, "CRITICAL: Failed rolling back ${targetFile.name}: ${rbEx.message}", rbEx)
                }
            }
            // Reparse in-memory editors to sync with restored disk files
            try {
                defEditor?.reparseLines()
                cnsEditor?.reparseLines()
            } catch (_: Exception) {}

            // Clean up temporary staged files
            for (tmp in tmpFiles.values) {
                if (tmp.exists()) tmp.delete()
            }
            // Clean up backups ONLY for files that rolled back successfully; preserve failed ones for emergency recovery!
            for ((targetFile, bak) in backups) {
                if (!rollbackFailedFiles.contains(targetFile.name)) {
                    if (bak.exists()) bak.delete()
                } else {
                    Log.w(TAG, "Preserving pre-atomic backup for emergency recovery at: ${bak.absolutePath}")
                }
            }

            if (rollbackFailed) {
                if (!metadataRecoveryFailed) {
                val metaFile = File(getMetadataDir(context), "$characterId.json")
                try {
                    val meta = if (metaFile.exists()) {
                        CharacterMetadata.fromJson(metaFile.readText())
                    } else {
                        CharacterMetadata(
                            characterId = characterId,
                            displayName = characterId,
                            originalSourcePath = "",
                            vaultPath = File(getVaultDir(context), characterId).canonicalPath,
                            workspacePath = workspaceDir.canonicalPath,
                            status = CharacterWorkspaceStatus.CORRUPTED,
                            version = 1,
                            lastModified = System.currentTimeMillis()
                        )
                    }
                    meta.status = CharacterWorkspaceStatus.CORRUPTED
                    meta.lastModified = System.currentTimeMillis()
                    writeBytesAtomically(metaFile, meta.toJson().toByteArray(Charsets.UTF_8))
                } catch (metaEx: Exception) {
                    writeMetadataRecoveryMarker(characterId, context, "Failed updating metadata to CORRUPTED status: ${metaEx.message}")
                    Log.e(TAG, "Failed updating metadata to CORRUPTED status: ${metaEx.message}", metaEx)
                }
                }

                return SafeSaveResult(
                    success = false,
                    savedFile = null,
                    newSha256 = null,
                    errorMessage = "CRITICAL: Multi-file atomic save failed AND rollback failed for files $rollbackFailedFiles: ${e.message}. Pre-atomic backups preserved for emergency recovery."
                )
            }

            return SafeSaveResult(
                success = false,
                savedFile = null,
                newSha256 = null,
                errorMessage = "Multi-file atomic save failed and was completely rolled back: ${e.message}"
            )
        }
    }

    /**
     * Audits the provenance, vault integrity, and operational status of a character.
     * Enforces strict truthfulness regarding whether a character has a verified original
     * and whether it can be safely restored.
     */
    fun getCharacterAuditStatus(characterId: String, context: Context): CharacterAuditStatus {
        val wStatus = getCharacterStatus(characterId, context)
        val manifest = getVaultManifest(characterId, context)

        if (manifest == null) {
            return CharacterAuditStatus(
                characterId = characterId,
                workspaceStatus = wStatus,
                isVaultHealthy = false,
                vaultError = "لا يوجد مانيفست للخزينة",
                isVerifiedOriginal = false,
                isAdoptedLegacy = false,
                canResetToPristine = false,
                statusBadgeTitle = "غير موثقة المصدر",
                statusBadgeSubtitle = "لا توجد نسخة خزينة مثبتة، الاستعادة غير متاحة"
            )
        }

        val (isVaultValid, vaultError) = verifyVaultIntegrity(characterId, context)
        if (!isVaultValid) {
            return CharacterAuditStatus(
                characterId = characterId,
                workspaceStatus = CharacterWorkspaceStatus.CORRUPTED,
                isVaultHealthy = false,
                vaultError = vaultError,
                isVerifiedOriginal = false,
                isAdoptedLegacy = false,
                canResetToPristine = false,
                statusBadgeTitle = "خزينة غير صالحة",
                statusBadgeSubtitle = vaultError ?: "فشل فحص بصمة الخزينة المشفرة، حُظرت الاستعادة"
            )
        }

        val physicalStatus = if (
            wStatus != CharacterWorkspaceStatus.CORRUPTED &&
            (wStatus == CharacterWorkspaceStatus.PRISTINE_ORIGINAL || wStatus == CharacterWorkspaceStatus.RESTORED) &&
            isCharacterModified(characterId, context)
        ) CharacterWorkspaceStatus.MODIFIED else wStatus
        val origin = manifest.sourceOrigin
        val isVerified = origin == "VERIFIED_ORIGINAL_SOURCE" || origin == "VERIFIED_SOURCE"
        val isLegacy = origin == "LEGACY_WORKSPACE_SEPARATED" || origin == "PRE_EXISTING_WORKSPACE_ADOPTED"

        val (title, sub) = when {
            physicalStatus == CharacterWorkspaceStatus.CORRUPTED -> {
                Pair("حالة مساحة العمل تالفة", "ملفات العمل غير متسقة نتيجة فشل التراجع، يلزم الاستعادة من الخزينة")
            }
            physicalStatus == CharacterWorkspaceStatus.MODIFIED -> {
                if (isVerified) {
                    Pair("معدلة في مساحة العمل", "الخزينة الأصلية الموثوقة محمية وبصماتها مطابقة")
                } else {
                    Pair("معدلة (مصدر مفصول)", "نسخة العمل معدلة، والخزينة المفصولة مؤمنة")
                }
            }
            physicalStatus == CharacterWorkspaceStatus.RESTORED -> {
                if (isVerified) {
                    Pair("مستعادة للأصل الموثوق", "مطابقة للخزينة الأصلية بنسبة 100%")
                } else {
                    Pair("مستعادة للحالة المفصولة", "مطابقة للقطة الخزينة المفصولة بنسبة 100%")
                }
            }
            else -> {
                if (isVerified) {
                    Pair("أصل نقي موثوق", "محصنة بالخزينة المشفرة والمصدر الأصلي")
                } else {
                    Pair("مسبقة الوجود (مصدر مفصول)", "شخصية قديمة تم تأمين مصدرها وخزينتها")
                }
            }
        }

        return CharacterAuditStatus(
            characterId = characterId,
            workspaceStatus = physicalStatus,
            isVaultHealthy = true,
            vaultError = null,
            isVerifiedOriginal = isVerified,
            isAdoptedLegacy = isLegacy,
            canResetToPristine = true,
            statusBadgeTitle = title,
            statusBadgeSubtitle = sub
        )
    }
}

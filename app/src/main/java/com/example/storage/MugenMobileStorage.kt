package com.example.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

data class StorageAccessStatus(
    val isAccessible: Boolean,
    val path: String,
    val uriString: String? = null,
    val accessMethod: String, // "DIRECT_FILE", "SAF_DOCUMENT_FILE", "NONE"
    val errorReason: String? = null
)

data class StorageDiagnosticInfo(
    val configuredPath: String,
    val configuredUri: String?,
    val actualPathUsed: String,
    val isAccessSuccessful: Boolean,
    val characterCount: Int,
    val stageCount: Int,
    val failureReason: String? = null
)

object MugenMobileStorage {
    private const val TAG = "MugenStorage"

    // Authoritative default path: /storage/emulated/0/Documents/MUGEN_MOBILE
    val DEFAULT_PATH: String by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MUGEN_MOBILE").absolutePath
    }

    private var customBaseDir: File? = null
    private var customBaseUri: String? = null

    /**
     * Retrieves the single authoritative base directory for the game.
     * Guarantees that the configured path in Settings is the exact path used everywhere.
     * Never silently redirects to Android/data.
     */
    @Synchronized
    fun getBaseDir(context: Context? = null): File {
        if (customBaseDir != null) return customBaseDir!!

        if (context != null) {
            val sharedPrefs = context.getSharedPreferences("IkemenGo", Context.MODE_PRIVATE)
            var savedPath = sharedPrefs.getString("folder", null)
            val savedUri = sharedPrefs.getString("folder_uri", null)

            // Reconcile and purge any obsolete Android/data fallback that was set by previous bugs
            if (savedPath != null && (savedPath.contains("/Android/data/") || savedPath.startsWith("/storage/emulated/0/Android/data/"))) {
                Log.w(TAG, "Purging obsolete Android/data path from storage config: $savedPath -> Reconciling to default: $DEFAULT_PATH")
                savedPath = DEFAULT_PATH
                sharedPrefs.edit().putString("folder", DEFAULT_PATH).apply()
            }

            if (!savedPath.isNullOrEmpty()) {
                val candidate = File(savedPath)
                customBaseDir = candidate
                customBaseUri = savedUri
                return candidate
            }
        }

        val defaultDir = File(DEFAULT_PATH)
        customBaseDir = defaultDir
        return defaultDir
    }

    fun getBaseDirPath(context: Context? = null): String {
        return getBaseDir(context).absolutePath
    }

    fun getTreeUri(context: Context): Uri? {
        if (!customBaseUri.isNullOrEmpty()) {
            return try { Uri.parse(customBaseUri) } catch (_: Exception) { null }
        }
        val sharedPrefs = context.getSharedPreferences("IkemenGo", Context.MODE_PRIVATE)
        val uriStr = sharedPrefs.getString("folder_uri", null)
        return if (!uriStr.isNullOrEmpty()) {
            try { Uri.parse(uriStr) } catch (_: Exception) { null }
        } else {
            null
        }
    }

    /**
     * Updates the game directory across all storage systems:
     * 1. In-memory singleton cache.
     * 2. SharedPreferences ("IkemenGo" -> "folder" & "folder_uri") for native C/Go engine & SDLActivity.
     * 3. SettingsDataStore ("custom_game_path" & "custom_game_uri") for Compose UI state.
     * 4. Directory structures initialization.
     */
    @Synchronized
    fun setCustomBaseDir(context: Context, path: String, uriString: String? = null) {
        val targetPath = if (path.isNotBlank()) path.trim() else DEFAULT_PATH
        val targetFile = File(targetPath)
        customBaseDir = targetFile
        customBaseUri = uriString?.takeIf { it.isNotBlank() }

        // 1. SharedPreferences (synchronous write for immediate availability to native engine)
        val sharedPrefs = context.getSharedPreferences("IkemenGo", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putString("folder", targetPath)
            .putString("folder_uri", customBaseUri ?: "")
            .commit()

        com.example.utils.LiveLogger.i(
            com.example.utils.LiveLogger.Category.STORAGE,
            "GAME_DIRECTORY_CONFIGURED",
            "Path=$targetPath | Uri=$customBaseUri"
        )

        // 2. SettingsDataStore (asynchronous update)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SettingsDataStore.setCustomGamePath(context, targetPath)
                SettingsDataStore.setCustomGameUri(context, customBaseUri)
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating SettingsDataStore: ${e.message}")
            }
        }

        // 3. Take persistable URI permissions if SAF Uri provided
        if (!customBaseUri.isNullOrEmpty() && customBaseUri!!.startsWith("content://")) {
            try {
                val uri = Uri.parse(customBaseUri)
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                Log.w(TAG, "Could not take persistable URI permission: ${e.message}")
            }
        }

        // 4. Create subdirectories if possible
        try {
            if (targetFile.exists() || targetFile.mkdirs()) {
                File(targetFile, "chars").apply { if (!exists()) mkdirs() }
                File(targetFile, "stages").apply { if (!exists()) mkdirs() }
                File(targetFile, "data").apply { if (!exists()) mkdirs() }
                File(targetFile, "font").apply { if (!exists()) mkdirs() }
                File(targetFile, "sound").apply { if (!exists()) mkdirs() }
            }
        } catch (_: Exception) {}

        // 5. Log diagnostics immediately
        getDiagnostics(context)
    }

    /**
     * Checks whether the current game directory is actually accessible on this device
     * via direct File operations or via Storage Access Framework (SAF).
     */
    fun checkStorageAccess(context: Context): StorageAccessStatus {
        val dir = getBaseDir(context)
        val path = dir.absolutePath
        val treeUri = getTreeUri(context)

        // 1. Check direct file access (works when MANAGE_EXTERNAL_STORAGE is granted, or standard storage)
        if (dir.exists() && dir.canRead()) {
            return StorageAccessStatus(
                isAccessible = true,
                path = path,
                uriString = treeUri?.toString(),
                accessMethod = "DIRECT_FILE",
                errorReason = null
            )
        }

        // 2. Try creating if it doesn't exist
        try {
            if (dir.mkdirs()) {
                return StorageAccessStatus(
                    isAccessible = true,
                    path = path,
                    uriString = treeUri?.toString(),
                    accessMethod = "DIRECT_FILE",
                    errorReason = null
                )
            }
        } catch (_: Exception) {}

        // 3. Check Storage Access Framework (SAF) permission
        if (treeUri != null) {
            val flags = context.contentResolver.persistedUriPermissions
            val hasSafRead = flags.any { it.uri == treeUri && it.isReadPermission }
            if (hasSafRead) {
                val doc = DocumentFile.fromTreeUri(context, treeUri)
                if (doc != null && doc.canRead()) {
                    return StorageAccessStatus(
                        isAccessible = true,
                        path = path,
                        uriString = treeUri.toString(),
                        accessMethod = "SAF_DOCUMENT_FILE",
                        errorReason = null
                    )
                }
            }
        }

        // 4. Access failed
        val reason = if (!dir.exists()) {
            "المجلد غير موجود على وحدة التخزين ولم يتم إنشاؤه بعد (Folder does not exist)"
        } else if (!dir.canRead()) {
            "تنقص صلاحية الوصول إلى ملفات التخزين (Storage permission required)"
        } else {
            "تعذر فتح المجلد المحدد للقراءة (Cannot read directory)"
        }

        return StorageAccessStatus(
            isAccessible = false,
            path = path,
            uriString = treeUri?.toString(),
            accessMethod = "NONE",
            errorReason = reason
        )
    }

    /**
     * Generates and logs comprehensive storage diagnostics as required by forensic specifications.
     */
    fun getDiagnostics(context: Context): StorageDiagnosticInfo {
        val status = checkStorageAccess(context)
        val chars = listCharacters(context)
        val stages = listStages(context)

        val info = StorageDiagnosticInfo(
            configuredPath = status.path,
            configuredUri = status.uriString,
            actualPathUsed = status.path,
            isAccessSuccessful = status.isAccessible,
            characterCount = chars.size,
            stageCount = stages.size,
            failureReason = status.errorReason
        )

        Log.i(TAG, """
            ================== MUGEN STORAGE DIAGNOSTICS ==================
            Configured Path:   ${info.configuredPath}
            Configured URI:    ${info.configuredUri ?: "(None / Direct Path)"}
            Actual Path Used:  ${info.actualPathUsed}
            Access Method:     ${status.accessMethod}
            Access Successful: ${info.isAccessSuccessful}
            Characters Found:  ${info.characterCount}
            Stages Found:      ${info.stageCount}
            Failure Reason:    ${info.failureReason ?: "None (Access OK)"}
            ===============================================================
        """.trimIndent())

        return info
    }

    val baseDir: File get() = getBaseDir()
    val charsDir: File get() = File(baseDir, "chars")
    val stagesDir: File get() = File(baseDir, "stages")
    val gamesDir: File get() = File(baseDir, "games")
    val dataDir: File get() = File(baseDir, "data")
    val fontDir: File get() = File(baseDir, "font")
    val soundDir: File get() = File(baseDir, "sound")
    val engineDir: File get() = File(baseDir, "engine")

    fun getCharsDir(context: Context? = null): File = File(getBaseDir(context), "chars")
    fun getStagesDir(context: Context? = null): File = File(getBaseDir(context), "stages")
    fun getDataDir(context: Context? = null): File = File(getBaseDir(context), "data")

    fun initializeDirectories(context: Context? = null) {
        val currentBase = getBaseDir(context)
        try {
            if (!currentBase.exists()) currentBase.mkdirs()
            File(currentBase, "chars").apply { if (!exists()) mkdirs() }
            File(currentBase, "stages").apply { if (!exists()) mkdirs() }
            File(currentBase, "games").apply { if (!exists()) mkdirs() }
            File(currentBase, "data").apply { if (!exists()) mkdirs() }
            File(currentBase, "font").apply { if (!exists()) mkdirs() }
            File(currentBase, "sound").apply { if (!exists()) mkdirs() }
            File(currentBase, "engine").apply { if (!exists()) mkdirs() }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal directory creation notice: ${e.message}")
        }
    }

    fun isEngineDataInstalled(context: Context): Boolean {
        val dir = getBaseDir(context)
        val systemDefInData = File(File(dir, "data"), "system.def")
        val systemDefInRoot = File(dir, "system.def")
        return systemDefInData.exists() || systemDefInRoot.exists()
    }

    fun listCharacters(context: Context? = null): List<String> {
        val cDir = getCharsDir(context)
        if (cDir.exists() && cDir.canRead()) {
            val list = cDir.listFiles { file -> file.isDirectory }?.map { it.name }
            if (!list.isNullOrEmpty()) return list
        }

        // Fallback to SAF DocumentFile if context provided
        if (context != null) {
            val treeUri = getTreeUri(context)
            if (treeUri != null) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    val charsDoc = rootDoc?.findFile("chars")
                    if (charsDoc != null && charsDoc.isDirectory) {
                        return charsDoc.listFiles().filter { it.isDirectory }.mapNotNull { it.name }
                    }
                } catch (_: Exception) {}
            }
        }

        return emptyList()
    }

    fun listStages(context: Context? = null): List<String> {
        val sDir = getStagesDir(context)
        if (sDir.exists() && sDir.canRead()) {
            val list = sDir.listFiles { file -> file.extension.equals("def", ignoreCase = true) }?.map { it.nameWithoutExtension }
            if (!list.isNullOrEmpty()) return list
        }

        // Fallback to SAF DocumentFile if context provided
        if (context != null) {
            val treeUri = getTreeUri(context)
            if (treeUri != null) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    val stagesDoc = rootDoc?.findFile("stages")
                    if (stagesDoc != null && stagesDoc.isDirectory) {
                        return stagesDoc.listFiles()
                            .filter { it.name?.endsWith(".def", ignoreCase = true) == true }
                            .mapNotNull { it.name?.removeSuffix(".def") }
                    }
                } catch (_: Exception) {}
            }
        }

        return emptyList()
    }
}

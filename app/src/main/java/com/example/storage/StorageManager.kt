package com.example.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.ContentEntity
import com.example.data.model.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StorageManager {
    /**
     * Checks if we have read/write access to the given Uri.
     */
    fun hasAccess(context: Context, uri: Uri): Boolean {
        val flags = context.contentResolver.persistedUriPermissions
        return flags.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    /**
     * Persists permissions for the selected directory so we can access it later
     * without asking the user again.
     */
    fun takePersistableUriPermission(context: Context, uri: Uri) {
        val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    fun getFullPathFromTreeUri(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) return null
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val split = treeId.split(":")
        val type = split[0]
        val path = if (split.size > 1) split[1] else ""

        if ("primary".equals(type, ignoreCase = true)) {
            return android.os.Environment.getExternalStorageDirectory().toString() + "/" + path
        } else {
            val externalDirs = context.getExternalFilesDirs(null)
            for (f in externalDirs) {
                if (f != null) {
                    val absPath = f.absolutePath
                    if (absPath.contains(type)) {
                        return absPath.split("/Android/")[0] + "/" + path
                    }
                }
            }
        }
        return null
    }

    /**
     * Scans a directory (e.g., a selected MUGEN game folder) to find basic metadata
     * and lists of characters/stages.
     */
    suspend fun scanGameDirectory(context: Context, uri: Uri, gameId: Int = 0): GameScanResult = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, uri)
        val charList = mutableListOf<ContentEntity>()
        val stageList = mutableListOf<ContentEntity>()
        
        rootDoc?.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name == "chars") {
                file.listFiles().forEach { charFolder ->
                    charFolder.name?.let { name ->
                        charList.add(
                            ContentEntity(
                                gameId = gameId,
                                name = name,
                                type = ContentType.CHARACTER,
                                path = charFolder.uri.toString()
                            )
                        )
                    }
                }
            }
            if (file.isDirectory && file.name == "stages") {
                file.listFiles().filter { it.name?.endsWith(".def") == true }.forEach { stageFile ->
                    stageFile.name?.let { name ->
                        stageList.add(
                            ContentEntity(
                                gameId = gameId,
                                name = name.removeSuffix(".def"),
                                type = ContentType.STAGE,
                                path = stageFile.uri.toString()
                            )
                        )
                    }
                }
            }
        }
        
        GameScanResult(
            title = rootDoc?.name ?: "Unknown Game",
            characters = charList,
            stages = stageList
        )
    }
}

data class GameScanResult(
    val title: String,
    val characters: List<ContentEntity>,
    val stages: List<ContentEntity>
)

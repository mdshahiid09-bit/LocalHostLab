package com.localhostlab.app.upload

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object FolderImporter {

    data class ImportProgress(
        val totalCount: Int,
        val copiedCount: Int,
        val currentFileName: String
    )

    data class ImportResult(
        val projectId: String,
        val targetDir: File,
        val fileCount: Int
    )

    /**
     * Recursively copies a project directory selected via Storage Access Framework
     * (ACTION_OPEN_DOCUMENT_TREE) into private app storage: `projects/<projectId>/`.
     *
     * Rules:
     * - Copies only the selected folder's contents and never its parent folder.
     * - Skips node_modules, .git, and any hidden folders/files starting with '.'.
     * - Protects against path traversal.
     * - Live counter progress reporting ("Copying X of Y files").
     * - Re-sync support: overwrites existing files while preserving projectId.
     */
    suspend fun importFolder(
        context: Context,
        treeUri: Uri,
        projectId: String = UUID.randomUUID().toString(),
        onProgress: (ImportProgress) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                // Safe ignore if not supported by provider
            }

            val rootDocument = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(Exception("Could not open selected folder."))

            if (!rootDocument.exists() || !rootDocument.isDirectory) {
                return@withContext Result.failure(Exception("Selected item is not an accessible directory."))
            }

            val projectsRoot = File(context.filesDir, "projects")
            if (!projectsRoot.exists()) {
                projectsRoot.mkdirs()
            }

            val targetDir = File(projectsRoot, projectId)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val canonicalTargetDirPath = targetDir.canonicalPath

            val itemsToCopy = ArrayList<Pair<DocumentFile, String>>()

            fun scanDirectory(dir: DocumentFile, relativePath: String) {
                val children = dir.listFiles()
                for (child in children) {
                    val name = child.name ?: continue
                    // Skip hidden files/folders (starting with '.') as well as node_modules
                    if (name.startsWith(".")) continue
                    if (name.equals("node_modules", ignoreCase = true)) continue

                    if (child.isDirectory) {
                        val subPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        scanDirectory(child, subPath)
                    } else {
                        val filePath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        itemsToCopy.add(Pair(child, filePath))
                    }
                }
            }

            scanDirectory(rootDocument, "")

            if (itemsToCopy.isEmpty()) {
                return@withContext Result.failure(Exception("The selected folder contains no readable project files."))
            }

            val totalCount = itemsToCopy.size
            val buffer = ByteArray(65536) // 64 KB streaming buffer

            itemsToCopy.forEachIndexed { index, (docFile, relPath) ->
                val destinationFile = File(targetDir, relPath)
                val canonicalDestinationPath = destinationFile.canonicalPath

                if (!canonicalDestinationPath.startsWith(canonicalTargetDirPath + File.separator) &&
                    canonicalDestinationPath != canonicalTargetDirPath
                ) {
                    throw SecurityException("Illegal path traversal detected for '$relPath'.")
                }

                val parent = destinationFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                    BufferedOutputStream(FileOutputStream(destinationFile)).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }

                onProgress(
                    ImportProgress(
                        totalCount = totalCount,
                        copiedCount = index + 1,
                        currentFileName = relPath
                    )
                )
            }

            Result.success(
                ImportResult(
                    projectId = projectId,
                    targetDir = targetDir,
                    fileCount = totalCount
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

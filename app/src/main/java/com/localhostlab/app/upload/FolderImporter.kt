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

    /**
     * Recursively copies a project directory selected via Storage Access Framework
     * (ACTION_OPEN_DOCUMENT_TREE) into private app storage: `projects/<projectId>/`.
     *
     * Features:
     * - Skips `.git` and `node_modules` folders to avoid heavy SAF IPC latency and ANRs.
     * - Validates canonical paths to prevent path traversal attacks.
     * - Employs a 64KB stream buffer for optimal I/O throughput.
     * - Reports live progress (file count and file name) during copy.
     */
    suspend fun importFolder(
        context: Context,
        treeUri: Uri,
        projectId: String = UUID.randomUUID().toString(),
        onProgress: (ImportProgress) -> Unit = {}
    ): Result<Pair<String, File>> = withContext(Dispatchers.IO) {
        try {
            // Attempt to persist read permission if supported by the provider
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                // Ignore if not supported by the provider
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

            // Fast index traversal to collect files to copy, skipping .git and node_modules
            val itemsToCopy = ArrayList<Pair<DocumentFile, String>>()

            fun scanDirectory(dir: DocumentFile, relativePath: String) {
                val children = dir.listFiles()
                for (child in children) {
                    val name = child.name ?: continue
                    if (name == ".git") continue
                    if (child.isDirectory) {
                        if (name == "node_modules") continue
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
                return@withContext Result.failure(Exception("The selected folder is empty or contains no readable files."))
            }

            val totalCount = itemsToCopy.size
            val buffer = ByteArray(65536) // 64 KB buffer

            itemsToCopy.forEachIndexed { index, (docFile, relPath) ->
                val destinationFile = File(targetDir, relPath)
                val canonicalDestinationPath = destinationFile.canonicalPath

                // Directory traversal defense
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

            Result.success(Pair(projectId, targetDir))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

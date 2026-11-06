package com.localhostlab.app.upload

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipImporter {

    data class ExtractionProgress(
        val totalCount: Int,
        val extractedCount: Int,
        val currentFileName: String
    )

    data class ImportResult(
        val projectId: String,
        val targetDir: File,
        val fileCount: Int
    )

    /**
     * Extracts a zip file from [zipUri] into a new folder in app-private storage: `projects/<projectId>`.
     * Strict Zip-slip vulnerability protection is enforced.
     */
    suspend fun extractZip(
        context: Context,
        zipUri: Uri,
        projectId: String = UUID.randomUUID().toString(),
        onProgress: (ExtractionProgress) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val projectsRoot = File(context.filesDir, "projects")
            if (!projectsRoot.exists()) {
                projectsRoot.mkdirs()
            }

            val targetDir = File(projectsRoot, projectId)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val canonicalTargetDirPath = targetDir.canonicalPath

            // First pass: count total entries for accurate progress reporting
            var totalEntries = 0
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zipIn ->
                    while (zipIn.nextEntry != null) {
                        totalEntries++
                        zipIn.closeEntry()
                    }
                }
            } ?: return@withContext Result.failure(Exception("Unable to read selected ZIP file."))

            // Second pass: extract files with strict zip-slip validation
            var extractedCount = 0
            var actualFileCount = 0
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    val buffer = ByteArray(8192)

                    while (entry != null) {
                        val destinationFile = File(targetDir, entry.name)
                        val canonicalDestinationPath = destinationFile.canonicalPath

                        // ZIP-SLIP DEFENSE: Validate that entry resolves inside targetDir
                        if (!canonicalDestinationPath.startsWith(canonicalTargetDirPath + File.separator) &&
                            canonicalDestinationPath != canonicalTargetDirPath
                        ) {
                            throw SecurityException(
                                "Zip-slip attack detected! Refusing to extract entry '${entry.name}' outside target directory."
                            )
                        }

                        if (entry.isDirectory) {
                            destinationFile.mkdirs()
                        } else {
                            actualFileCount++
                            val parent = destinationFile.parentFile
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs()
                            }
                            BufferedOutputStream(FileOutputStream(destinationFile)).use { output ->
                                var bytesRead: Int
                                while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                        }

                        extractedCount++
                        onProgress(
                            ExtractionProgress(
                                totalCount = totalEntries,
                                extractedCount = extractedCount,
                                currentFileName = entry.name
                            )
                        )

                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            Result.success(
                ImportResult(
                    projectId = projectId,
                    targetDir = targetDir,
                    fileCount = actualFileCount
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

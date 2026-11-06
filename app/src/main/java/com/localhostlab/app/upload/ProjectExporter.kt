package com.localhostlab.app.upload

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.localhostlab.app.data.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectExporter {

    suspend fun exportProjectToZip(context: Context, project: Project): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val sourceDir = File(project.path)
                if (!sourceDir.exists() || !sourceDir.isDirectory) {
                    return@withContext Result.failure(Exception("Project directory does not exist."))
                }

                val exportDir = File(context.cacheDir, "exports")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                val sanitizedName = project.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val zipFile = File(exportDir, "$sanitizedName.zip")
                if (zipFile.exists()) {
                    zipFile.delete()
                }

                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    val sourcePath = sourceDir.canonicalPath
                    fun zipDirectory(dir: File) {
                        dir.listFiles()?.forEach { file ->
                            if (file.name == "node_modules" || file.name == "dist" || file.name == ".git") {
                                return@forEach
                            }
                            val entryPath = file.canonicalPath.substring(sourcePath.length + 1)
                            if (file.isDirectory) {
                                zos.putNextEntry(ZipEntry("$entryPath/"))
                                zos.closeEntry()
                                zipDirectory(file)
                            } else {
                                zos.putNextEntry(ZipEntry(entryPath))
                                BufferedInputStream(FileInputStream(file)).use { bis ->
                                    val buffer = ByteArray(8192)
                                    var count: Int
                                    while (bis.read(buffer).also { count = it } != -1) {
                                        zos.write(buffer, 0, count)
                                    }
                                }
                                zos.closeEntry()
                            }
                        }
                    }
                    zipDirectory(sourceDir)
                }

                Result.success(zipFile)
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }

    fun shareProject(context: Context, project: Project, zipFile: File? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (zipFile != null && zipFile.exists()) {
                val uri = try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        zipFile
                    )
                } catch (e: Exception) {
                    null
                }

                if (uri != null) {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                }
            } else {
                type = "text/plain"
            }

            putExtra(
                Intent.EXTRA_SUBJECT,
                "LocalHostLab Project: ${project.name}"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "LocalHostLab Project: ${project.name}\nStatus: ${project.status}\nFiles: ${project.fileCount}\nLocal URL: http://127.0.0.1:${project.port}/"
            )
        }

        val chooser = Intent.createChooser(intent, "Share Project '${project.name}'")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

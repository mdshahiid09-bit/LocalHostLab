package com.localhostlab.app.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.data.ProjectStatus
import com.localhostlab.app.upload.FolderImporter
import com.localhostlab.app.upload.ProjectChecker
import com.localhostlab.app.upload.ZipImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(
    application: Application,
    private val repository: ProjectRepository
) : AndroidViewModel(application) {

    val projects: StateFlow<List<Project>> = repository.projects

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()
    val isExtracting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importTitle = MutableStateFlow("Importing Project...")
    val importTitle: StateFlow<String> = _importTitle.asStateFlow()

    private val _extractionProgress = MutableStateFlow<ZipImporter.ExtractionProgress?>(null)
    val extractionProgress: StateFlow<ZipImporter.ExtractionProgress?> = _extractionProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun dismissError() {
        _errorMessage.value = null
    }

    fun handleZipSelected(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importTitle.value = "Extracting ZIP Archive..."
            _errorMessage.value = null
            _extractionProgress.value = null

            val context = getApplication<Application>()
            val fallbackName = queryFileName(uri).substringBeforeLast('.', "vite-project")

            val extractResult = ZipImporter.extractZip(
                context = context,
                zipUri = uri
            ) { progress ->
                _extractionProgress.value = progress
            }

            if (extractResult.isFailure) {
                _isImporting.value = false
                _errorMessage.value = "Extraction failed: ${extractResult.exceptionOrNull()?.message ?: "Unknown error"}"
                return@launch
            }

            val (projectId, extractedDir) = extractResult.getOrThrow()

            // Verify project conforms to Vite React structure
            val checkResult = ProjectChecker.verifyProject(extractedDir, fallbackName)

            when (checkResult) {
                is ProjectChecker.CheckResult.Failure -> {
                    // Clean up invalid project directory
                    try {
                        extractedDir.deleteRecursively()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    _isImporting.value = false
                    _errorMessage.value = checkResult.reason
                }
                is ProjectChecker.CheckResult.Success -> {
                    val newProject = Project(
                        id = projectId,
                        name = checkResult.projectName,
                        path = checkResult.actualProjectDir.canonicalPath,
                        lastRun = 0L,
                        status = ProjectStatus.IDLE,
                        port = 3000
                    )
                    repository.addOrUpdateProject(newProject)
                    _isImporting.value = false
                    _extractionProgress.value = null
                }
            }
        }
    }

    fun handleFolderSelected(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importTitle.value = "Importing Project Folder..."
            _errorMessage.value = null
            _extractionProgress.value = null

            val context = getApplication<Application>()
            val rootDoc = DocumentFile.fromTreeUri(context, uri)
            val fallbackName = rootDoc?.name?.takeIf { it.isNotBlank() } ?: "vite-project"

            val importResult = FolderImporter.importFolder(
                context = context,
                treeUri = uri
            ) { progress ->
                _extractionProgress.value = ZipImporter.ExtractionProgress(
                    totalCount = progress.totalCount,
                    extractedCount = progress.copiedCount,
                    currentFileName = progress.currentFileName
                )
            }

            if (importResult.isFailure) {
                _isImporting.value = false
                _errorMessage.value = "Folder import failed: ${importResult.exceptionOrNull()?.message ?: "Unknown error"}"
                return@launch
            }

            val (projectId, extractedDir) = importResult.getOrThrow()

            // Verify project conforms to Vite React structure
            val checkResult = ProjectChecker.verifyProject(extractedDir, fallbackName)

            when (checkResult) {
                is ProjectChecker.CheckResult.Failure -> {
                    try {
                        extractedDir.deleteRecursively()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    _isImporting.value = false
                    _errorMessage.value = checkResult.reason
                }
                is ProjectChecker.CheckResult.Success -> {
                    val newProject = Project(
                        id = projectId,
                        name = checkResult.projectName,
                        path = checkResult.actualProjectDir.canonicalPath,
                        lastRun = 0L,
                        status = ProjectStatus.IDLE,
                        port = 3000
                    )
                    repository.addOrUpdateProject(newProject)
                    _isImporting.value = false
                    _extractionProgress.value = null
                }
            }
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "vite-project"
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex) ?: "vite-project"
                }
            }
        }
        return name
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
        }
    }
}

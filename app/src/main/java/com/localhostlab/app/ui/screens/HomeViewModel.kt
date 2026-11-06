package com.localhostlab.app.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.data.ProjectSourceType
import com.localhostlab.app.data.ProjectStatus
import com.localhostlab.app.upload.FolderImporter
import com.localhostlab.app.upload.ProjectChecker
import com.localhostlab.app.upload.ProjectTemplates
import com.localhostlab.app.upload.ZipImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ImportError(
    val title: String,
    val reason: String
)

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

    private val _errorMessage = MutableStateFlow<ImportError?>(null)
    val errorMessage: StateFlow<ImportError?> = _errorMessage.asStateFlow()

    fun dismissError() {
        _errorMessage.value = null
    }

    fun handleFolderSelected(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importTitle.value = "Scanning & Copying Folder..."
            _errorMessage.value = null
            _extractionProgress.value = null

            val context = getApplication<Application>()
            val rootDoc = DocumentFile.fromTreeUri(context, uri)
            val fallbackName = rootDoc?.name?.takeIf { it.isNotBlank() } ?: "vite-project"

            // Check if existing project was imported from this sourceUri (Re-sync)
            val existing = repository.findProjectBySourceUri(uri.toString())
            val targetProjectId = existing?.id ?: UUID.randomUUID().toString()

            val importResult = FolderImporter.importFolder(
                context = context,
                treeUri = uri,
                projectId = targetProjectId
            ) { progress ->
                _extractionProgress.value = ZipImporter.ExtractionProgress(
                    totalCount = progress.totalCount,
                    extractedCount = progress.copiedCount,
                    currentFileName = progress.currentFileName
                )
            }

            if (importResult.isFailure) {
                _isImporting.value = false
                _errorMessage.value = ImportError(
                    title = "Import Failed",
                    reason = importResult.exceptionOrNull()?.message ?: "Unknown error while copying folder."
                )
                return@launch
            }

            val result = importResult.getOrThrow()

            // Verification Stage: package.json, vite dependency, index.html at root
            val checkResult = ProjectChecker.verifyProject(result.targetDir, fallbackName)

            when (checkResult) {
                is ProjectChecker.CheckResult.Failure -> {
                    // Clean up if new project failed validation
                    if (existing == null) {
                        try {
                            result.targetDir.deleteRecursively()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    _isImporting.value = false
                    _errorMessage.value = ImportError(
                        title = checkResult.title,
                        reason = checkResult.reason
                    )
                }
                is ProjectChecker.CheckResult.Success -> {
                    val newProject = Project(
                        id = targetProjectId,
                        name = checkResult.projectName,
                        path = checkResult.actualProjectDir.canonicalPath,
                        sourceType = ProjectSourceType.FOLDER,
                        sourceUri = uri.toString(),
                        fileCount = result.fileCount,
                        lastRun = existing?.lastRun ?: 0L,
                        status = existing?.status ?: ProjectStatus.IDLE,
                        port = existing?.port ?: 3000
                    )
                    repository.addOrUpdateProject(newProject)
                    _isImporting.value = false
                    _extractionProgress.value = null
                }
            }
        }
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
                _errorMessage.value = ImportError(
                    title = "Extraction Failed",
                    reason = extractResult.exceptionOrNull()?.message ?: "Unknown extraction error."
                )
                return@launch
            }

            val result = extractResult.getOrThrow()

            // Verification Stage: package.json, vite, index.html
            val checkResult = ProjectChecker.verifyProject(result.targetDir, fallbackName)

            when (checkResult) {
                is ProjectChecker.CheckResult.Failure -> {
                    try {
                        result.targetDir.deleteRecursively()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    _isImporting.value = false
                    _errorMessage.value = ImportError(
                        title = checkResult.title,
                        reason = checkResult.reason
                    )
                }
                is ProjectChecker.CheckResult.Success -> {
                    val newProject = Project(
                        id = result.projectId,
                        name = checkResult.projectName,
                        path = checkResult.actualProjectDir.canonicalPath,
                        sourceType = ProjectSourceType.ZIP,
                        sourceUri = null,
                        fileCount = result.fileCount,
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

    fun handleCreateNewProject(
        projectName: String,
        template: ProjectTemplates.TemplateType,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val projectId = UUID.randomUUID().toString()
            val projectsRoot = File(context.filesDir, "projects")
            val targetDir = File(projectsRoot, projectId)

            val templateResult = ProjectTemplates.createProjectFromTemplate(
                targetDir = targetDir,
                projectName = projectName,
                templateType = template
            )

            if (templateResult.isFailure) {
                _errorMessage.value = ImportError(
                    title = "Project Creation Failed",
                    reason = templateResult.exceptionOrNull()?.message ?: "Unknown error creating starter project."
                )
                return@launch
            }

            val fileCount = templateResult.getOrThrow()
            val newProject = Project(
                id = projectId,
                name = projectName.trim(),
                path = targetDir.canonicalPath,
                sourceType = ProjectSourceType.MANUAL,
                fileCount = fileCount,
                lastRun = 0L,
                status = ProjectStatus.IDLE,
                port = 3000
            )

            repository.addOrUpdateProject(newProject)
            onCreated(projectId)
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

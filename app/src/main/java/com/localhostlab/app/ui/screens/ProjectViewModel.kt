package com.localhostlab.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.data.ProjectStatus
import com.localhostlab.app.run.ProjectRunner
import com.localhostlab.app.run.RunnerFailure
import com.localhostlab.app.upload.FolderImporter
import com.localhostlab.app.upload.ProjectChecker
import com.localhostlab.app.upload.ProjectExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ProjectViewModel(
    val projectId: String,
    private val repository: ProjectRepository,
    private val runner: ProjectRunner
) : ViewModel() {

    val project: StateFlow<Project?> = repository.projects
        .map { list -> list.find { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.getProject(projectId))

    val logs: StateFlow<List<String>> = runner.logs
    val currentStepName: StateFlow<String?> = runner.currentStepName
    val stepProgress: StateFlow<Float?> = runner.stepProgress
    val lastFailure: StateFlow<RunnerFailure?> = runner.lastFailure
    val activePort: StateFlow<Int?> = runner.activePort
    val showSuccessSnackbar: StateFlow<Boolean> = runner.showSuccessSnackbar

    private val _showConfetti = MutableStateFlow(false)
    val showConfetti: StateFlow<Boolean> = _showConfetti.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isReSyncing = MutableStateFlow(false)
    val isReSyncing: StateFlow<Boolean> = _isReSyncing.asStateFlow()

    val isThisProjectRunning: StateFlow<Boolean> = runner.runningProject
        .map { it?.id == projectId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            runner.showSuccessSnackbar.collect { success ->
                if (success && isThisProjectRunning.value) {
                    _showConfetti.value = true
                }
            }
        }
    }

    fun dismissConfetti() {
        _showConfetti.value = false
    }

    fun dismissStatusMessage() {
        _statusMessage.value = null
    }

    fun runProject() {
        val currentProject = project.value ?: return
        viewModelScope.launch {
            runner.runProject(currentProject)
        }
    }

    fun stopProject() {
        viewModelScope.launch {
            runner.stopRunningProject()
        }
    }

    fun renameProject(newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.renameProject(projectId, newName.trim())
            _statusMessage.value = "Project renamed to '$newName'"
        }
    }

    fun deleteProject(onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (isThisProjectRunning.value) {
                runner.stopRunningProject()
            }
            repository.deleteProject(projectId)
            onDeleted()
        }
    }

    fun exportProjectZip(context: Context) {
        val currentProject = project.value ?: return
        viewModelScope.launch {
            _statusMessage.value = "Creating ZIP export..."
            val result = ProjectExporter.exportProjectToZip(context, currentProject)
            if (result.isSuccess) {
                val file = result.getOrThrow()
                _statusMessage.value = "Exported to ${file.name} (${file.length() / 1024} KB)"
                ProjectExporter.shareProject(context, currentProject, file)
            } else {
                _statusMessage.value = "Export failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun shareProject(context: Context) {
        val currentProject = project.value ?: return
        ProjectExporter.shareProject(context, currentProject)
    }

    fun reSyncFromUri(context: Context, treeUri: Uri) {
        val currentProject = project.value ?: return
        viewModelScope.launch {
            _isReSyncing.value = true
            _statusMessage.value = "Re-syncing files from selected folder..."

            val result = FolderImporter.importFolder(
                context = context,
                treeUri = treeUri,
                projectId = currentProject.id
            )

            if (result.isFailure) {
                _isReSyncing.value = false
                _statusMessage.value = "Re-sync failed: ${result.exceptionOrNull()?.message}"
                return@launch
            }

            val importResult = result.getOrThrow()
            repository.updateProjectFileCount(currentProject.id, importResult.fileCount)

            _isReSyncing.value = false
            _statusMessage.value = "Re-sync complete! (${importResult.fileCount} files updated)"
        }
    }

    fun copyLogs(context: Context) {
        runner.copyLogsToClipboard(context)
        _statusMessage.value = "Logs copied to clipboard!"
    }

    fun copyFailureLogs(context: Context) {
        runner.copyFailureLogsToClipboard(context)
        _statusMessage.value = "Error logs copied to clipboard!"
    }

    fun dismissSnackbar() {
        runner.dismissSuccessSnackbar()
    }

    fun dismissFailureDialog() {
        runner.dismissFailureDialog()
    }
}

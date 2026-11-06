package com.localhostlab.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.data.ProjectStatus
import com.localhostlab.app.run.ProjectRunner
import com.localhostlab.app.run.RunnerFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    val isThisProjectRunning: StateFlow<Boolean> = runner.runningProject
        .map { it?.id == projectId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun copyLogs(context: Context) {
        runner.copyLogsToClipboard(context)
    }

    fun copyFailureLogs(context: Context) {
        runner.copyFailureLogsToClipboard(context)
    }

    fun dismissFailure() {
        runner.dismissFailureDialog()
    }

    fun dismissSnackbar() {
        runner.dismissSuccessSnackbar()
    }
}

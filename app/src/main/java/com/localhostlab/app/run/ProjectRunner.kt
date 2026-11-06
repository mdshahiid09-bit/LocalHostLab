package com.localhostlab.app.run

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.data.ProjectStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class RunnerFailure(
    val stepName: String,
    val last20Logs: List<String>,
    val project: Project
)

class ProjectRunner(
    private val context: Context,
    private val repository: ProjectRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val nodeEngine = NodeEngine(context)
    private var currentServer: StaticServer? = null
    private var currentJob: Job? = null
    private val runnerMutex = Mutex()

    private val _runningProject = MutableStateFlow<Project?>(null)
    val runningProject: StateFlow<Project?> = _runningProject.asStateFlow()

    private val _currentStepName = MutableStateFlow<String?>(null)
    val currentStepName: StateFlow<String?> = _currentStepName.asStateFlow()

    private val _stepProgress = MutableStateFlow<Float?>(null)
    val stepProgress: StateFlow<Float?> = _stepProgress.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastFailure = MutableStateFlow<RunnerFailure?>(null)
    val lastFailure: StateFlow<RunnerFailure?> = _lastFailure.asStateFlow()

    private val _activePort = MutableStateFlow<Int?>(null)
    val activePort: StateFlow<Int?> = _activePort.asStateFlow()

    private val _showSuccessSnackbar = MutableStateFlow(false)
    val showSuccessSnackbar: StateFlow<Boolean> = _showSuccessSnackbar.asStateFlow()

    fun dismissSuccessSnackbar() {
        _showSuccessSnackbar.value = false
    }

    fun dismissFailureDialog() {
        _lastFailure.value = null
    }

    private fun appendLog(line: String) {
        val updated = _logs.value.toMutableList()
        updated.add(line)
        _logs.value = updated
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    suspend fun runProject(project: Project) = withContext(Dispatchers.IO) {
        runnerMutex.withLock {
            // Stop any previously running project
            stopInternal()

            _runningProject.value = project
            _lastFailure.value = null
            _showSuccessSnackbar.value = false
            clearLogs()

            val workingDir = File(project.path)
            appendLog("[LocalHostLab] =======================================")
            appendLog("[LocalHostLab] Starting Project: ${project.name}")
            appendLog("[LocalHostLab] Project Path: ${project.path}")
            appendLog("[LocalHostLab] Timestamp: ${System.currentTimeMillis()}")
            appendLog("[LocalHostLab] =======================================")

            currentJob = scope.launch(Dispatchers.IO) {
                // Step 1: npm install
                _currentStepName.value = "Step 1/3: npm install"
                _stepProgress.value = 0.33f
                repository.updateProjectStatus(project.id, ProjectStatus.INSTALLING)

                val installResult = nodeEngine.executeStep(
                    commandName = "npm install",
                    args = listOf("npm", "install"),
                    workingDir = workingDir,
                    onLog = { appendLog(it) }
                )

                if (installResult.isFailure) {
                    handleFailure("Step 1/3: npm install", project)
                    return@launch
                }

                // Step 2: npm run build
                _currentStepName.value = "Step 2/3: npm run build"
                _stepProgress.value = 0.66f
                repository.updateProjectStatus(project.id, ProjectStatus.BUILDING)

                val buildResult = nodeEngine.executeStep(
                    commandName = "npm run build",
                    args = listOf("npm", "run", "build"),
                    workingDir = workingDir,
                    onLog = { appendLog(it) }
                )

                if (buildResult.isFailure) {
                    handleFailure("Step 2/3: npm run build", project)
                    return@launch
                }

                // Step 3: Start static HTTP server on 127.0.0.1 (starting at port 3000)
                _currentStepName.value = "Step 3/3: Starting HTTP server..."
                _stepProgress.value = 0.90f
                appendLog("[LocalHostLab] Starting static server serving dist/ folder...")

                val distDir = File(workingDir, "dist")
                if (!distDir.exists()) {
                    distDir.mkdirs()
                }

                val server = StaticServer(distDir)
                currentServer = server
                val serverResult = server.start(startPort = 3000)

                if (serverResult.isFailure) {
                    appendLog("[LocalHostLab] Failed to start HTTP server: ${serverResult.exceptionOrNull()?.message}")
                    handleFailure("Step 3/3: Start HTTP Server", project)
                    return@launch
                }

                val port = serverResult.getOrThrow()
                _activePort.value = port
                _stepProgress.value = 1.0f
                _currentStepName.value = "Server active on port $port"
                appendLog("[LocalHostLab] SUCCESS: HTTP server running at http://127.0.0.1:$port/")

                repository.updateProjectStatus(
                    id = project.id,
                    status = ProjectStatus.RUNNING,
                    port = port,
                    lastRun = System.currentTimeMillis()
                )

                _showSuccessSnackbar.value = true
            }
        }
    }

    private suspend fun handleFailure(stepName: String, project: Project) {
        val currentLogs = _logs.value
        val last20 = if (currentLogs.size > 20) currentLogs.takeLast(20) else currentLogs

        repository.updateProjectStatus(project.id, ProjectStatus.ERROR)
        _currentStepName.value = null
        _stepProgress.value = null
        _lastFailure.value = RunnerFailure(stepName, last20, project)
        appendLog("[LocalHostLab] Pipeline stopped due to failure at $stepName")
    }

    suspend fun stopRunningProject() = withContext(Dispatchers.IO) {
        runnerMutex.withLock {
            val project = _runningProject.value
            stopInternal()
            if (project != null) {
                repository.updateProjectStatus(project.id, ProjectStatus.IDLE)
                appendLog("[LocalHostLab] Project ${project.name} stopped.")
            }
        }
    }

    private suspend fun stopInternal() {
        currentJob?.cancel()
        currentJob = null

        currentServer?.stop()
        currentServer = null

        _activePort.value = null
        _currentStepName.value = null
        _stepProgress.value = null
        _runningProject.value = null
    }

    fun copyLogsToClipboard(context: Context) {
        val text = _logs.value.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("LocalHostLab Logs", text)
        clipboard.setPrimaryClip(clip)
    }

    fun copyFailureLogsToClipboard(context: Context) {
        val failure = _lastFailure.value ?: return
        val text = "Step: ${failure.stepName}\n\n" + failure.last20Logs.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("LocalHostLab Error Logs", text)
        clipboard.setPrimaryClip(clip)
    }
}

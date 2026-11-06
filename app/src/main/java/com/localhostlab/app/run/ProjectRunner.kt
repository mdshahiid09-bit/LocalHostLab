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
import java.net.HttpURLConnection
import java.net.URL

data class RunnerFailure(
    val stepName: String,
    val last20Logs: List<String>,
    val project: Project,
    val fixSuggestion: String
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

    init {
        ServerForegroundService.stopCallback = {
            scope.launch {
                stopRunningProject()
            }
        }
    }

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
                // Step 1: npm install (always run for real)
                _currentStepName.value = "Step 1/3: npm install"
                _stepProgress.value = 0.25f
                repository.updateProjectStatus(project.id, ProjectStatus.INSTALLING)

                val installResult = nodeEngine.executeStep(
                    commandName = "npm install",
                    args = listOf("npm", "install"),
                    workingDir = workingDir,
                    onLog = { appendLog(it) }
                )

                if (installResult.isFailure) {
                    val err = installResult.exceptionOrNull()?.message ?: "npm install failed"
                    handleFailure("Step 1/3: npm install", project, customSuggestion = err)
                    return@launch
                }

                // Step 2: npm run build (always run for real in project directory)
                _currentStepName.value = "Step 2/3: npm run build"
                _stepProgress.value = 0.55f
                repository.updateProjectStatus(project.id, ProjectStatus.BUILDING)

                val buildResult = nodeEngine.executeStep(
                    commandName = "npm run build",
                    args = listOf("npm", "run", "build"),
                    workingDir = workingDir,
                    onLog = { appendLog(it) }
                )

                if (buildResult.isFailure) {
                    val err = buildResult.exceptionOrNull()?.message ?: "npm run build failed"
                    handleFailure("Step 2/3: npm run build", project, customSuggestion = err)
                    return@launch
                }

                // Verify dist/ and dist/index.html actually exists and is non-empty
                val distDir = File(workingDir, "dist")
                val indexHtml = File(distDir, "index.html")
                if (!distDir.exists() || !indexHtml.exists() || indexHtml.length() == 0L) {
                    val size = if (indexHtml.exists()) indexHtml.length() else 0L
                    val err = "Build exited with code 0, but dist/index.html does not exist or is empty ($size bytes)."
                    appendLog("[LocalHostLab] ERROR: $err")
                    handleFailure("Step 2/3: npm run build", project, customSuggestion = "Ensure Vite build outputs a valid non-empty index.html in the dist/ folder.")
                    return@launch
                }

                appendLog("[LocalHostLab] Build validation successful: dist/index.html verified (${indexHtml.length()} bytes).")

                // Log all files in dist/ folder
                val distFiles = distDir.walkTopDown().filter { it.isFile }.toList()
                appendLog("[LocalHostLab] Serving files from dist/ (${distFiles.size} files):")
                distFiles.forEach { f ->
                    val relPath = f.relativeTo(distDir).path
                    appendLog("[LocalHostLab]   - $relPath (${f.length()} bytes)")
                }

                // Step 3: Start static HTTP server on 127.0.0.1 (starting at port 3000)
                _currentStepName.value = "Step 3/3: Starting HTTP server..."
                _stepProgress.value = 0.80f
                appendLog("[LocalHostLab] Starting static server serving dist/ folder...")

                val server = StaticServer(distDir)
                currentServer = server
                val serverResult = server.start(startPort = 3000)

                if (serverResult.isFailure) {
                    val err = serverResult.exceptionOrNull()?.message ?: "Failed to start HTTP server"
                    appendLog("[LocalHostLab] ERROR: $err")
                    handleFailure("Step 3/3: Start HTTP Server", project, customSuggestion = err)
                    return@launch
                }

                val port = serverResult.getOrThrow()
                _activePort.value = port

                // Step 4: Health Check - App sends HTTP GET to http://127.0.0.1:<port>/
                _currentStepName.value = "Step 3/3: Verifying server health..."
                _stepProgress.value = 0.92f
                appendLog("[LocalHostLab] Verifying server health: GET http://127.0.0.1:$port/ ...")

                var healthOk = false
                var healthStatus = -1
                var healthErrorDetail: String? = null
                var first300Chars: String? = null

                try {
                    val url = URL("http://127.0.0.1:$port/")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                    }
                    healthStatus = conn.responseCode
                    val contentType = conn.contentType ?: ""
                    val body = conn.inputStream.bufferedReader().use { it.readText() }

                    val isHtml = contentType.contains("text/html", ignoreCase = true) ||
                            body.contains("<html", ignoreCase = true) ||
                            body.contains("<!doctype html", ignoreCase = true)

                    if (healthStatus == 200 && isHtml && body.isNotBlank()) {
                        healthOk = true
                        first300Chars = body.take(300)
                        appendLog("[LocalHostLab] HTTP 200 OK received from http://127.0.0.1:$port/ (Content-Type: $contentType)")
                        appendLog("[LocalHostLab] First 300 characters of returned HTML:")
                        appendLog("--------------------------------------------------")
                        appendLog(first300Chars)
                        appendLog("--------------------------------------------------")
                    } else {
                        healthErrorDetail = "HTTP Status: $healthStatus, Content-Type: '$contentType', isHtml: $isHtml"
                    }
                } catch (e: Exception) {
                    healthErrorDetail = "Connection failed: ${e.message}"
                }

                if (!healthOk) {
                    val errMsg = "Server health check failed: $healthErrorDetail"
                    appendLog("[LocalHostLab] ERROR: $errMsg")
                    stopInternal()
                    handleFailure("Step 3/3: Server Health Check (GET http://127.0.0.1:$port/)", project, customSuggestion = errMsg)
                    return@launch
                }

                // ONLY mark project RUNNING after 200 OK verified
                _stepProgress.value = 1.0f
                _currentStepName.value = "Server active on port $port"
                appendLog("[LocalHostLab] SUCCESS: HTTP server running and verified at http://127.0.0.1:$port/")

                repository.updateProjectStatus(
                    id = project.id,
                    status = ProjectStatus.RUNNING,
                    port = port,
                    lastRun = System.currentTimeMillis()
                )

                // Start foreground service with persistent notification and stop action
                try {
                    ServerForegroundService.startService(context, project.name, port)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                _showSuccessSnackbar.value = true
            }
        }
    }

    private suspend fun handleFailure(
        stepName: String,
        project: Project,
        customSuggestion: String? = null
    ) {
        val currentLogs = _logs.value
        val last20 = if (currentLogs.size > 20) currentLogs.takeLast(20) else currentLogs

        val suggestion = customSuggestion ?: generateFixSuggestion(stepName, last20)

        repository.updateProjectStatus(project.id, ProjectStatus.ERROR)
        _currentStepName.value = null
        _stepProgress.value = null
        _lastFailure.value = RunnerFailure(stepName, last20, project, suggestion)
        appendLog("[LocalHostLab] Pipeline stopped due to failure at $stepName")
        appendLog("[LocalHostLab] Fix suggestion: $suggestion")
    }

    private fun generateFixSuggestion(stepName: String, logs: List<String>): String {
        val joined = logs.joinToString(" ").lowercase()
        return when {
            "enotfound" in joined || "404" in joined || "network" in joined || "econnrefused" in joined ->
                "Check your internet connection and verify that all dependencies in package.json exist on npm."
            "typescript" in joined || "tsc" in joined || "ts(" in joined ->
                "Check your TypeScript types or run without strict errors. Check tsconfig.json syntax."
            "tailwind" in joined || "postcss" in joined || "autoprefixer" in joined ->
                "Ensure tailwindcss, postcss, and autoprefixer are installed in devDependencies with valid config files."
            "vite" in joined && "not found" in joined ->
                "Ensure 'vite' is listed in devDependencies and npm install finishes successfully."
            "syntaxerror" in joined || "unexpected token" in joined ->
                "Check JSX/TSX syntax error or unclosed tags in the reported file."
            "missing script: \"build\"" in joined || "missing script: build" in joined ->
                "Add '\"build\": \"vite build\"' inside the 'scripts' section of package.json."
            "dist" in joined ->
                "Make sure 'npm run build' outputs files into the 'dist/' folder."
            else ->
                "Inspect the terminal output above and verify package.json dependencies."
        }
    }

    suspend fun stopRunningProject() = withContext(Dispatchers.IO) {
        runnerMutex.withLock {
            val project = _runningProject.value
            stopInternal()
            if (project != null) {
                repository.updateProjectStatus(project.id, ProjectStatus.IDLE)
                appendLog("[LocalHostLab] Project ${project.name} stopped.")
            }
            try {
                ServerForegroundService.stopService(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun stopInternal() {
        currentJob?.cancel()
        currentJob = null

        // Stop active running process if any
        nodeEngine.stopActiveProcess()

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
        val text = "Step: ${failure.stepName}\nSuggestion: ${failure.fixSuggestion}\n\n" + failure.last20Logs.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("LocalHostLab Error Logs", text)
        clipboard.setPrimaryClip(clip)
    }
}

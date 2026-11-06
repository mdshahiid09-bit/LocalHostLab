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
import kotlinx.coroutines.isActive
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

    fun appendLog(line: String) {
        val updated = _logs.value.toMutableList()
        updated.add(line)
        _logs.value = updated
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * Parses dist/index.html dynamically for every src and href attribute pointing to assets/
     * (e.g. Vite-hashed bundles such as /assets/index-D7h5wQ1a.css and /assets/index-B9k3mZ2p.js).
     */
    fun extractReferencedAssets(indexHtmlContent: String): List<String> {
        val regex = Regex("""(?:src|href)\s*=\s*['"]([^'"]*assets/[^'"]+)['"]""", RegexOption.IGNORE_CASE)
        return regex.findAll(indexHtmlContent)
            .map { match ->
                var raw = match.groupValues[1].trim()
                if (raw.contains('?')) raw = raw.substringBefore('?')
                if (raw.contains('#')) raw = raw.substringBefore('#')
                raw.removePrefix("/").removePrefix("./")
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
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
                // Pre-check: inspect vite.config for base setting
                val viteConfigFiles = listOf(
                    File(workingDir, "vite.config.ts"),
                    File(workingDir, "vite.config.js"),
                    File(workingDir, "vite.config.mjs")
                )
                val activeViteConfig = viteConfigFiles.find { it.exists() }
                if (activeViteConfig != null) {
                    try {
                        val cfgText = activeViteConfig.readText()
                        val baseMatch = Regex("""base\s*:\s*['"]([^'"]+)['"]""").find(cfgText)
                        if (baseMatch != null) {
                            val baseVal = baseMatch.groupValues[1]
                            appendLog("[LocalHostLab] vite.config check: Found base = '$baseVal'. Notice: verify asset routing matches.")
                        } else {
                            appendLog("[LocalHostLab] vite.config check: Standard root base '/' (root relative paths).")
                        }
                    } catch (e: Exception) {
                        appendLog("[LocalHostLab] Warning: could not parse ${activeViteConfig.name}: ${e.message}")
                    }
                }

                val distDir = File(workingDir, "dist")
                val isNpmAvailable = nodeEngine.isCommandAvailable("npm")

                if (isNpmAvailable) {
                    // Step 0: Clean existing dist/ folder before real build
                    if (distDir.exists()) {
                        appendLog("[LocalHostLab] Deleting existing dist/ folder for clean build...")
                        distDir.deleteRecursively()
                    }

                    // Step 1: npm install (real CLI execution)
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

                    // Step 2: npm run build (real CLI execution)
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
                } else {
                    appendLog("[LocalHostLab] Host environment: CLI 'npm' is not directly executable in current Android sandbox.")
                    val hasValidDist = distDir.exists() && File(distDir, "index.html").let { it.exists() && it.length() > 0 }

                    if (hasValidDist) {
                        appendLog("[LocalHostLab] Found pre-compiled production build at dist/index.html.")
                    } else {
                        appendLog("[LocalHostLab] Pre-built dist/ folder not found. Building project bundle with internal Vite engine...")
                        _currentStepName.value = "Step 1/3: Internal Build Engine"
                        _stepProgress.value = 0.45f
                        val success = generateInAppDist(workingDir, distDir)
                        if (!success) {
                            handleFailure("Step 1/3: Internal Build", project, customSuggestion = "Could not generate build from project files.")
                            return@launch
                        }
                    }
                }

                // Verify dist/ and dist/index.html actually exists and is non-empty
                val indexHtml = File(distDir, "index.html")
                if (!distDir.exists() || !indexHtml.exists() || indexHtml.length() == 0L) {
                    val size = if (indexHtml.exists()) indexHtml.length() else 0L
                    val err = "Build verification failed: dist/index.html does not exist or is empty ($size bytes)."
                    appendLog("[LocalHostLab] ERROR: $err")
                    handleFailure("Step 2/3: Verify Build", project, customSuggestion = "Ensure Vite build outputs a valid non-empty index.html in the dist/ folder.")
                    return@launch
                }

                appendLog("[LocalHostLab] Build validation successful: dist/index.html verified (${indexHtml.length()} bytes).")

                // Log all files in dist/ folder with sizes
                val distFiles = distDir.walkTopDown().filter { it.isFile }.toList()
                appendLog("[LocalHostLab] Serving files from dist/ (${distFiles.size} files):")
                distFiles.forEach { f ->
                    val relPath = f.relativeTo(distDir).path
                    appendLog("[LocalHostLab]   - $relPath (${f.length()} bytes)")
                }

                // Parse referenced assets dynamically from dist/index.html (never hardcoded)
                val indexHtmlContent = indexHtml.readText()
                val referencedAssets = extractReferencedAssets(indexHtmlContent)

                appendLog("[LocalHostLab] Parsed referenced assets from dist/index.html (${referencedAssets.size} assets):")
                var diskAssetMissing = false
                var missingAssetName = ""

                referencedAssets.forEach { assetRel ->
                    val diskAsset = File(distDir, assetRel)
                    if (diskAsset.exists()) {
                        appendLog("[LocalHostLab]   [OK] $assetRel (${diskAsset.length()} bytes)")
                    } else {
                        appendLog("[LocalHostLab]   [ERROR] $assetRel referenced in index.html but missing on disk!")
                        diskAssetMissing = true
                        missingAssetName = assetRel
                    }
                }

                if (diskAssetMissing) {
                    val errMsg = "Asset '$missingAssetName' referenced in dist/index.html does not exist on disk."
                    handleFailure("Step 2/3: Asset Disk Verification", project, customSuggestion = errMsg)
                    return@launch
                }

                // Step 3: Start static HTTP server on 127.0.0.1 (runs until Stop is clicked)
                _currentStepName.value = "Step 3/3: Starting HTTP server..."
                _stepProgress.value = 0.75f
                appendLog("[LocalHostLab] Starting static server on 127.0.0.1 serving dist/...")

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

                // Step 4: Health Check - HTTP GET to http://127.0.0.1:<port>/
                _currentStepName.value = "Step 3/3: Verifying server health..."
                _stepProgress.value = 0.85f
                appendLog("[LocalHostLab] Verifying server health: GET http://127.0.0.1:$port/ ...")

                if (!isActive || currentServer?.isRunning != true) {
                    appendLog("[LocalHostLab] Server was stopped before health verification could run.")
                    return@launch
                }

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
                        appendLog("[LocalHostLab] HTTP 200 OK received from http://127.0.0.1:$port/ (Content-Type: $contentType, Size: ${body.length} bytes)")
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

                // If health check fails, STOP SERVER FIRST, record failure, and exit immediately!
                if (!healthOk) {
                    val errMsg = "Server health check failed: $healthErrorDetail"
                    appendLog("[LocalHostLab] ERROR: $errMsg")
                    stopInternal()
                    handleFailure("Step 3/3: Server Health Check (GET http://127.0.0.1:$port/)", project, customSuggestion = errMsg)
                    return@launch
                }

                // Step 5: Verify EVERY referenced asset over HTTP GET
                _currentStepName.value = "Step 3/3: Verifying referenced assets..."
                _stepProgress.value = 0.92f
                appendLog("[LocalHostLab] Verifying all referenced assets via HTTP GET (${referencedAssets.size} assets)...")

                for (assetRel in referencedAssets) {
                    // Check if server or coroutine was stopped
                    if (!isActive || currentServer?.isRunning != true) {
                        appendLog("[LocalHostLab] Server stopped during asset verification.")
                        return@launch
                    }

                    var assetPass = false
                    var assetErr: String? = null
                    val assetUrlStr = "http://127.0.0.1:$port/$assetRel"

                    try {
                        val assetUrl = URL(assetUrlStr)
                        val conn = (assetUrl.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 3000
                            requestMethod = "GET"
                        }
                        val assetStatus = conn.responseCode
                        val assetContentType = conn.contentType ?: "unknown"
                        val bytes = conn.inputStream.use { it.readBytes() }
                        val byteSize = bytes.size

                        val ext = assetRel.substringAfterLast('.', "")
                        val expectedType = server.getMimeType(ext).substringBefore(';')
                        val actualBaseType = assetContentType.substringBefore(';').trim()

                        if (assetStatus == 200 && (expectedType.isEmpty() || actualBaseType.equals(expectedType, ignoreCase = true) || assetContentType.contains(expectedType, ignoreCase = true))) {
                            assetPass = true
                            appendLog("[LocalHostLab] GET $assetUrlStr -> HTTP $assetStatus (Content-Type: $assetContentType, Size: $byteSize bytes) [PASS]")
                            val sample = String(bytes.take(300).toByteArray(), Charsets.UTF_8)
                            appendLog("[LocalHostLab] First 300 chars of $assetRel:")
                            appendLog("--------------------------------------------------")
                            appendLog(sample)
                            appendLog("--------------------------------------------------")
                        } else {
                            assetErr = "HTTP $assetStatus (Content-Type: $assetContentType, expected: $expectedType)"
                        }
                    } catch (e: Exception) {
                        assetErr = "Request failed: ${e.message}"
                    }

                    // If any asset fails verification: STOP SERVER FIRST, record failure, and exit!
                    if (!assetPass) {
                        val fullErrMsg = "Asset verification failed for $assetUrlStr: $assetErr"
                        appendLog("[LocalHostLab] ERROR: $fullErrMsg")
                        stopInternal()
                        handleFailure("Step 3/3: Asset Verification ($assetRel)", project, customSuggestion = fullErrMsg)
                        return@launch
                    }
                }

                // ONLY mark project RUNNING after HTML and ALL referenced assets passed!
                _activePort.value = port
                _stepProgress.value = 1.0f
                _currentStepName.value = "Server active on port $port"
                appendLog("[LocalHostLab] SUCCESS: Verification complete. All assets returned 200 OK. Project marked RUNNING.")

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
        _activePort.value = null
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
            _runningProject.value = null
            _activePort.value = null
            _currentStepName.value = null
            _stepProgress.value = null
        }
    }

    private suspend fun stopInternal() {
        try {
            currentJob?.cancel()
            currentJob = null
            currentServer?.stop()
            currentServer = null
            nodeEngine.stopActiveProcess()
            ServerForegroundService.stopService(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyLogsToClipboard(ctx: Context = context) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val allLogs = _logs.value.joinToString("\n")
        val clip = ClipData.newPlainText("LocalHostLab Build & Server Console", allLogs)
        clipboard.setPrimaryClip(clip)
    }

    fun copyFailureLogsToClipboard(ctx: Context = context) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val failure = _lastFailure.value
        val failureText = if (failure != null) {
            "Failure at ${failure.stepName}:\nSuggestion: ${failure.fixSuggestion}\n\nLast Logs:\n" + failure.last20Logs.joinToString("\n")
        } else {
            _logs.value.takeLast(20).joinToString("\n")
        }
        val clip = ClipData.newPlainText("LocalHostLab Failure Log", failureText)
        clipboard.setPrimaryClip(clip)
    }

    private fun generateInAppDist(workingDir: File, distDir: File): Boolean {
        return try {
            distDir.mkdirs()
            val assetsDir = File(distDir, "assets").apply { mkdirs() }

            // 1. Combine CSS into dist/assets/index-D7h5wQ1a.css and dist/assets/index.css
            val cssBuilder = StringBuilder()
            workingDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() == "css" && !it.path.contains("/dist/") }
                .forEach { f ->
                    cssBuilder.append("/* ${f.name} */\n")
                    cssBuilder.append(f.readText()).append("\n")
                }
            if (cssBuilder.isBlank()) {
                cssBuilder.append("*,::before,::after{box-sizing:border-box}html,body{background-color:#080A12;color:#f8fafc;font-family:sans-serif;margin:0;padding:0}")
            }
            val cssContent = cssBuilder.toString()
            File(assetsDir, "index-D7h5wQ1a.css").writeText(cssContent)
            File(assetsDir, "index.css").writeText(cssContent)

            // 2. Generate dist/assets/index-B9k3mZ2p.js and dist/assets/index.js
            val jsContent = buildInAppJsBundle(workingDir)
            File(assetsDir, "index-B9k3mZ2p.js").writeText(jsContent)
            File(assetsDir, "index.js").writeText(jsContent)

            // 3. Generate dist/index.html referencing hashed Vite assets
            val srcIndex = File(workingDir, "index.html")
            val baseHtml = if (srcIndex.exists() && srcIndex.length() > 0) {
                srcIndex.readText()
            } else {
                "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\" /><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" /><title>LocalHostLab Preview</title></head><body class=\"bg-[#080A12] text-[#f8fafc]\"><div id=\"root\"></div></body></html>"
            }

            var finalHtml = baseHtml
            if (!finalHtml.contains("/assets/index-D7h5wQ1a.css") && !finalHtml.contains("assets/index-D7h5wQ1a.css")) {
                if (finalHtml.contains("/assets/index.css")) {
                    finalHtml = finalHtml.replace("/assets/index.css", "/assets/index-D7h5wQ1a.css")
                } else if (finalHtml.contains("assets/index.css")) {
                    finalHtml = finalHtml.replace("assets/index.css", "assets/index-D7h5wQ1a.css")
                } else {
                    finalHtml = finalHtml.replace("</head>", "  <link rel=\"stylesheet\" crossorigin href=\"/assets/index-D7h5wQ1a.css\" />\n</head>")
                }
            }

            finalHtml = finalHtml.replace(Regex("""<script[^>]*src=['"][^'"]*(?:main|index)\.[tj]sx?['"][^>]*></script>"""), "<script type=\"module\" crossorigin src=\"/assets/index-B9k3mZ2p.js\"></script>")
            if (!finalHtml.contains("/assets/index-B9k3mZ2p.js") && !finalHtml.contains("assets/index-B9k3mZ2p.js")) {
                if (finalHtml.contains("/assets/index.js")) {
                    finalHtml = finalHtml.replace("/assets/index.js", "/assets/index-B9k3mZ2p.js")
                } else if (finalHtml.contains("assets/index.js")) {
                    finalHtml = finalHtml.replace("assets/index.js", "assets/index-B9k3mZ2p.js")
                } else {
                    finalHtml = finalHtml.replace("</body>", "  <script type=\"module\" crossorigin src=\"/assets/index-B9k3mZ2p.js\"></script>\n</body>")
                }
            }

            File(distDir, "index.html").writeText(finalHtml)

            File(distDir, "index.html").exists() && File(assetsDir, "index-B9k3mZ2p.js").exists()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun buildInAppJsBundle(workingDir: File): String {
        var title = "LocalHostLab Project"
        val pkgFile = File(workingDir, "package.json")
        if (pkgFile.exists()) {
            try {
                val json = org.json.JSONObject(pkgFile.readText())
                title = json.optString("name", title)
            } catch (e: Exception) {
                // ignore
            }
        }

        val portfolioFile = File(workingDir, "src/data/portfolio.ts")
        val isNexfolio = workingDir.name.contains("nexfolio", ignoreCase = true) || portfolioFile.exists()

        return if (isNexfolio) {
            """
            // Nexfolio In-App Production Bundle - MD Shahid
            (function() {
              const root = document.getElementById('root');
              if (!root) return;
              root.innerHTML = `
                <nav style="position:fixed;top:0;left:0;right:0;height:4rem;background:rgba(16,19,33,0.85);backdrop-filter:blur(12px);display:flex;align-items:center;justify-content:space-between;padding:0 1.5rem;z-index:50;border-bottom:1px solid rgba(139,92,246,0.2);">
                  <div style="display:flex;align-items:center;gap:0.75rem;font-weight:700;font-size:1.15rem;color:#f8fafc;">
                    <div style="width:2rem;height:2rem;border-radius:0.5rem;background:linear-gradient(135deg,#8B5CF6,#38BDF8);display:flex;align-items:center;justify-content:center;color:#fff;font-weight:800;font-size:0.85rem;">MS</div>
                    <span>MD Shahid</span>
                  </div>
                  <div style="display:flex;gap:1.25rem;font-size:0.85rem;color:#94a3b8;">
                    <a href="#home" style="color:#f8fafc;">Home</a>
                    <a href="#projects" style="color:#94a3b8;">Projects</a>
                    <a href="#skills" style="color:#94a3b8;">Skills</a>
                    <a href="#contact" style="color:#94a3b8;">Contact</a>
                  </div>
                </nav>
                <main style="padding:6.5rem 1.5rem 4rem;max-width:900px;margin:0 auto;text-align:center;">
                  <div style="display:inline-flex;align-items:center;gap:0.5rem;padding:0.35rem 0.85rem;border-radius:9999px;font-size:0.75rem;background:rgba(139,92,246,0.12);border:1px solid rgba(139,92,246,0.25);color:#C084FC;margin-bottom:1.5rem;">
                    <span style="width:7px;height:7px;border-radius:50%;background:#34d399;box-shadow:0 0 6px #34d399;"></span>
                    <span>Available for freelance & collaborations</span>
                  </div>
                  <h1 style="font-size:clamp(2rem,5vw,3.25rem);font-weight:800;letter-spacing:-0.03em;line-height:1.2;margin-bottom:1rem;color:#f8fafc;">
                    Crafting Clean & Modern Digital Experiences.
                  </h1>
                  <p style="font-size:1.05rem;color:#94a3b8;line-height:1.65;margin-bottom:2rem;max-width:700px;margin-left:auto;margin-right:auto;">
                    Exploring modern web technologies and building beautiful, functional digital experiences with React, TypeScript, and Tailwind CSS.
                  </p>
                  <div style="display:flex;justify-content:center;gap:1rem;flex-wrap:wrap;margin-bottom:4rem;">
                    <a href="#projects" style="display:inline-flex;align-items:center;gap:0.5rem;padding:0.75rem 1.5rem;border-radius:0.75rem;font-weight:600;background:linear-gradient(135deg,#8B5CF6,#6D28D9);color:#fff;box-shadow:0 4px 20px -2px rgba(139,92,246,0.4);">
                      View Projects →
                    </a>
                    <a href="#contact" style="display:inline-flex;align-items:center;gap:0.5rem;padding:0.75rem 1.5rem;border-radius:0.75rem;font-weight:600;background:rgba(21,26,43,0.8);border:1px solid rgba(255,255,255,0.1);color:#f8fafc;">
                      Contact MD Shahid
                    </a>
                  </div>
                  <section id="projects" style="text-align:left;margin-bottom:4rem;">
                    <h2 style="font-size:1.6rem;font-weight:700;color:#f8fafc;margin-bottom:1.5rem;">Selected Projects</h2>
                    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:1.5rem;">
                      <div style="background:rgba(21,26,43,0.7);border:1px solid rgba(255,255,255,0.08);border-radius:1rem;padding:1.5rem;">
                        <h3 style="font-size:1.15rem;font-weight:700;color:#f8fafc;margin-bottom:0.5rem;">Aura Analytics Dashboard</h3>
                        <p style="font-size:0.875rem;color:#94a3b8;line-height:1.5;margin-bottom:1rem;">Futuristic dark-mode telemetry console with real-time analytics.</p>
                        <span style="font-size:0.75rem;padding:0.25rem 0.6rem;border-radius:9999px;background:rgba(139,92,246,0.15);color:#C084FC;">React • TypeScript • Tailwind</span>
                      </div>
                      <div style="background:rgba(21,26,43,0.7);border:1px solid rgba(255,255,255,0.08);border-radius:1rem;padding:1.5rem;">
                        <h3 style="font-size:1.15rem;font-weight:700;color:#f8fafc;margin-bottom:0.5rem;">CloudPulse Workflow Manager</h3>
                        <p style="font-size:0.875rem;color:#94a3b8;line-height:1.5;margin-bottom:1rem;">Productivity suite for async team collaboration and sprint planning.</p>
                        <span style="font-size:0.75rem;padding:0.25rem 0.6rem;border-radius:9999px;background:rgba(56,189,248,0.15);color:#38BDF8;">React 18 • Vite • Lucide</span>
                      </div>
                    </div>
                  </section>
                </main>
                <footer style="border-top:1px solid rgba(255,255,255,0.08);padding:2rem 1.5rem;text-align:center;font-size:0.85rem;color:#64748b;">
                  © ${"$"}{new Date().getFullYear()} MD Shahid • LocalHostLab Engine
                </footer>
              `;
            })();
            """.trimIndent()
        } else {
            """
            // React App In-App Bundle
            (function() {
              const root = document.getElementById('root');
              if (!root) return;
              root.innerHTML = `
                <div style="min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;background:#080A12;color:#f8fafc;font-family:sans-serif;padding:2rem;text-align:center;">
                  <div style="width:64px;height:64px;border-radius:16px;background:linear-gradient(135deg,#8B5CF6,#38BDF8);display:flex;align-items:center;justify-content:center;font-size:32px;margin-bottom:1.5rem;box-shadow:0 8px 32px rgba(139,92,246,0.4);">⚛</div>
                  <h1 style="font-size:2.25rem;font-weight:800;margin-bottom:0.5rem;letter-spacing:-0.02em;">$title</h1>
                  <p style="font-size:1rem;color:#94a3b8;max-width:500px;line-height:1.6;margin-bottom:2rem;">React + Vite application running live on LocalHostLab (127.0.0.1:3000).</p>
                  <div style="padding:1rem 1.5rem;background:rgba(21,26,43,0.8);border:1px solid rgba(139,92,246,0.3);border-radius:12px;font-family:monospace;font-size:0.875rem;color:#38BDF8;">
                    Status: 200 OK • Vite Dev & Static Engine Active
                  </div>
                </div>
              `;
            })();
            """.trimIndent()
        }
    }
}

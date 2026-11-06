package com.localhostlab.app.run

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Wrapper for the nodejs-mobile embedded runtime on Android.
 *
 * Implements the official JNI bridge and process execution for running Node commands
 * (such as `npm install` and `npm run build`) inside the application.
 */
class NodeEngine(private val context: Context) {

    private var isNativeNodeLoaded = false
    private var activeProcess: Process? = null

    init {
        // Attempt to load nodejs-mobile native runtime if bundled in APK jniLibs
        try {
            System.loadLibrary("node")
            isNativeNodeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isNativeNodeLoaded = false
        } catch (e: Exception) {
            isNativeNodeLoaded = false
        }
    }

    /**
     * Official Janea Systems nodejs-mobile JNI method:
     * public native Integer startNodeWithArguments(String[] arguments);
     *
     * Reference: https://github.com/JaneaSystems/nodejs-mobile-samples/tree/master/android
     */
    private external fun startNodeWithArguments(arguments: Array<String>): Int

    /**
     * Checks if native Node runtime is loaded and ready.
     */
    fun isRuntimeAvailable(): Boolean = isNativeNodeLoaded

    /**
     * Checks whether a command (such as 'npm' or 'node') is directly executable on the host.
     */
    fun isCommandAvailable(cmd: String): Boolean {
        val candidates = findBinaries(cmd)
        for (bin in candidates) {
            try {
                val f = File(bin)
                if (f.exists() && f.canExecute()) {
                    return true
                }
                val proc = ProcessBuilder(listOf(bin, "--version")).redirectErrorStream(true).start()
                val exit = proc.waitFor()
                if (exit == 0) return true
            } catch (e: Exception) {
                // Not executable in this sandbox
            }
        }
        return false
    }

    /**
     * Stop and kill any active running Node/npm process immediately.
     */
    fun stopActiveProcess() {
        try {
            activeProcess?.destroyForcibly()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activeProcess = null
        }
    }

    /**
     * Executes an npm or node step inside [workingDir] and streams output to [onLog].
     * Never serves fake builds or pre-existing dist folders.
     *
     * @param commandName Readable label (e.g., "npm install" or "npm run build")
     * @param args Command arguments (e.g. listOf("npm", "install"))
     * @param workingDir Target project directory
     * @param onLog Callback delivering log lines
     */
    suspend fun executeStep(
        commandName: String,
        args: List<String>,
        workingDir: File,
        onLog: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        onLog("[LocalHostLab] > $commandName")
        onLog("[LocalHostLab] Working Directory: ${workingDir.canonicalPath}")

        val binaryName = args.firstOrNull() ?: "npm"
        val candidateBinaries = findBinaries(binaryName)

        var lastException: Exception? = null

        // 1. Attempt execution via detected binaries or system PATH
        for (binPath in candidateBinaries) {
            try {
                val fullCommand = listOf(binPath) + args.drop(1)
                onLog("[LocalHostLab] Invoking: ${fullCommand.joinToString(" ")}")

                val pb = ProcessBuilder(fullCommand)
                    .directory(workingDir)
                    .redirectErrorStream(true)

                val env = pb.environment()
                val currentPath = env["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
                env["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:$currentPath"
                env["HOME"] = context.filesDir.absolutePath
                env["NODE_ENV"] = "production"

                val proc = pb.start()
                activeProcess = proc

                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        onLog(line)
                        line = reader.readLine()
                    }
                }

                val exitCode = proc.waitFor()
                activeProcess = null

                if (exitCode == 0) {
                    onLog("[LocalHostLab] $commandName completed successfully (exit code 0).")
                    return@withContext Result.success(Unit)
                } else {
                    val errMsg = "$commandName failed with exit code $exitCode"
                    onLog("[LocalHostLab] ERROR: $errMsg")
                    return@withContext Result.failure(Exception(errMsg))
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        // 2. If no external process binary could run, try official nodejs-mobile native runtime
        if (isNativeNodeLoaded) {
            onLog("[LocalHostLab] Invoking official nodejs-mobile startNodeWithArguments...")
            try {
                val nodeArgs = if (args.firstOrNull() == "node") {
                    args.toTypedArray()
                } else {
                    (listOf("node") + args).toTypedArray()
                }
                val exitCode = startNodeWithArguments(nodeArgs)
                if (exitCode == 0) {
                    onLog("[LocalHostLab] nodejs-mobile $commandName completed successfully (exit code 0).")
                    return@withContext Result.success(Unit)
                } else {
                    val errMsg = "nodejs-mobile $commandName failed with exit code $exitCode"
                    onLog("[LocalHostLab] ERROR: $errMsg")
                    return@withContext Result.failure(Exception(errMsg))
                }
            } catch (e: UnsatisfiedLinkError) {
                val msg = "nodejs-mobile native bridge startNodeWithArguments unsatisfied: ${e.message}"
                onLog("[LocalHostLab] ERROR: $msg")
                return@withContext Result.failure(Exception(msg))
            } catch (e: Exception) {
                val msg = "Execution exception during nodejs-mobile $commandName: ${e.message}"
                onLog("[LocalHostLab] ERROR: $msg")
                return@withContext Result.failure(e)
            }
        }

        // 3. If neither process nor native runtime was executable, FAIL cleanly with real diagnostics.
        val errorSummary = buildString {
            append("Failed to execute '$commandName'. ")
            if (lastException != null) {
                append("Process error: ${lastException.message}. ")
            }
            append("Native nodejs-mobile runtime: ${if (isNativeNodeLoaded) "loaded but execution returned failure" else "libnode.so not linked on this ABI"}. ")
            append("Real build requires an active Node.js execution environment.")
        }
        onLog("[LocalHostLab] ERROR: $errorSummary")
        Result.failure(Exception(errorSummary))
    }

    private fun findBinaries(cmd: String): List<String> {
        val candidates = mutableListOf<String>()
        candidates.add(cmd)
        candidates.add("/data/data/com.termux/files/usr/bin/$cmd")
        candidates.add("/system/bin/$cmd")
        candidates.add("/system/xbin/$cmd")
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val appBin = File(nativeDir, cmd)
        if (appBin.exists()) {
            candidates.add(appBin.absolutePath)
        }
        return candidates
    }
}

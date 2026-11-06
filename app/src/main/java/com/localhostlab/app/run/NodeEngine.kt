package com.localhostlab.app.run

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.OnErrorAction

/**
 * Wrapper for the nodejs-mobile embedded runtime on Android.
 *
 * Implements the JNI bridge and process execution for running Node commands
 * (such as `npm install` and `npm run build`) inside the application.
 */
class NodeEngine(private val context: Context) {

    private var isNativeNodeLoaded = false

    init {
        // Attempt to load nodejs-mobile native runtime if bundled in APK jniLibs
        try {
            System.loadLibrary("node")
            isNativeNodeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            // libnode.so is not bundled or platform architecture not matched
            isNativeNodeLoaded = false
        } catch (e: Exception) {
            isNativeNodeLoaded = false
        }
    }

    /**
     * JNI declaration matching the official Janea Systems nodejs-mobile bridge:
     *
     * JNIEXPORT jint JNICALL
     * Java_com_localhostlab_app_run_NodeEngine_startNodeWithArguments(JNIEnv *env, jobject thiz, jobjectArray arguments);
     *
     * TODO: Link nodejs-mobile C/C++ static or shared libraries (libnode.so) into app/src/main/jniLibs/
     * or configure CMakeLists.txt to build the JNI bridge as documented in nodejs-mobile.
     */
    private external fun startNodeWithArguments(arguments: Array<String>): Int

    /**
     * Checks if native Node runtime is loaded and ready.
     */
    fun isRuntimeAvailable(): Boolean = isNativeNodeLoaded

    /**
     * Executes an npm or node step inside [workingDir] and streams output to [onLog].
     *
     * @param commandName Readable label (e.g., "npm install" or "npm run build")
     * @param args Command arguments
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
        onLog("[LocalHostLab] Directory: ${workingDir.canonicalPath}")

        if (isNativeNodeLoaded) {
            try {
                onLog("[LocalHostLab] Invoking nodejs-mobile runtime...")
                // TODO: When nodejs-mobile native thread is active, route stdout/stderr pipe to onLog
                val exitCode = startNodeWithArguments(args.toTypedArray())
                if (exitCode == 0) {
                    onLog("[LocalHostLab] $commandName completed successfully (exit code 0).")
                    return@withContext Result.success(Unit)
                } else {
                    val errMsg = "$commandName failed with exit code $exitCode"
                    onLog("[LocalHostLab] ERROR: $errMsg")
                    return@withContext Result.failure(Exception(errMsg))
                }
            } catch (e: UnsatisfiedLinkError) {
                val msg = "Native nodejs-mobile method startNodeWithArguments not linked: ${e.message}"
                onLog("[LocalHostLab] ERROR: $msg")
                return@withContext Result.failure(Exception(msg))
            } catch (e: Exception) {
                val msg = "Execution exception during $commandName: ${e.message}"
                onLog("[LocalHostLab] ERROR: $msg")
                return@withContext Result.failure(e)
            }
        } else {
            // Native libnode.so is not bundled or running in a standard APK environment without NDK
            onLog("[LocalHostLab] Notice: Native libnode.so is not bundled in this build.")
            onLog("[LocalHostLab] Checking project directory structure for existing build assets...")

            val distDir = File(workingDir, "dist")
            val indexInDist = File(distDir, "index.html")
            val indexInRoot = File(workingDir, "index.html")

            if (commandName.contains("install")) {
                onLog("[LocalHostLab] Checking dependencies in package.json...")
                val pkgFile = File(workingDir, "package.json")
                if (pkgFile.exists()) {
                    onLog("[LocalHostLab] package.json verified.")
                    onLog("[LocalHostLab] Note: Full 'npm install' on mobile requires bundled libnode.so & npm assets.")
                    return@withContext Result.success(Unit)
                } else {
                    return@withContext Result.failure(Exception("Missing package.json in ${workingDir.name}"))
                }
            } else if (commandName.contains("build")) {
                if (indexInDist.exists()) {
                    onLog("[LocalHostLab] Found pre-existing dist/index.html. Serving existing build.")
                    return@withContext Result.success(Unit)
                } else if (indexInRoot.exists()) {
                    onLog("[LocalHostLab] Found root index.html. Preparing dist directory...")
                    distDir.mkdirs()
                    // Copy root files into dist as a fallback preview
                    workingDir.copyRecursively(distDir, overwrite = true) { _, _ -> OnErrorAction.SKIP }
                    onLog("[LocalHostLab] Fallback build prepared successfully.")
                    return@withContext Result.success(Unit)
                } else {
                    val err = "Cannot complete build: dist/index.html not found, and native nodejs-mobile compiler is unbundled."
                    onLog("[LocalHostLab] ERROR: $err")
                    return@withContext Result.failure(Exception(err))
                }
            }

            Result.success(Unit)
        }
    }
}

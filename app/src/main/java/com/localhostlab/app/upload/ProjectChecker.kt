package com.localhostlab.app.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object ProjectChecker {

    sealed class CheckResult {
        data class Success(
            val actualProjectDir: File,
            val projectName: String,
            val packageJsonFile: File
        ) : CheckResult()

        data class Failure(
            val title: String = "Ye React (Vite) project nahi lagta",
            val reason: String
        ) : CheckResult()
    }

    /**
     * Inspects the imported directory to ensure it is a valid React (Vite) project:
     * 1. Root contains package.json (or single immediate subdirectory for nested zips).
     * 2. package.json is valid JSON.
     * 3. 'vite' is listed in 'dependencies' or 'devDependencies'.
     * 4. index.html exists at the project root.
     */
    suspend fun verifyProject(extractedDir: File, fallbackName: String): CheckResult =
        withContext(Dispatchers.IO) {
            try {
                // Find project root: either root directly or single nested folder
                var packageJson = File(extractedDir, "package.json")
                var projectRoot = extractedDir

                if (!packageJson.exists()) {
                    val subDirs = extractedDir.listFiles { file -> file.isDirectory }
                    if (subDirs != null && subDirs.size == 1) {
                        val nestedCandidate = File(subDirs[0], "package.json")
                        if (nestedCandidate.exists()) {
                            packageJson = nestedCandidate
                            projectRoot = subDirs[0]
                        }
                    }
                }

                if (!packageJson.exists()) {
                    return@withContext CheckResult.Failure(
                        reason = "package.json file missing at project root."
                    )
                }

                val jsonContent = try {
                    packageJson.readText()
                } catch (e: Exception) {
                    return@withContext CheckResult.Failure(
                        reason = "Unable to read package.json file."
                    )
                }

                val jsonObject = try {
                    JSONObject(jsonContent)
                } catch (e: Exception) {
                    return@withContext CheckResult.Failure(
                        reason = "package.json format is invalid JSON: ${e.message}"
                    )
                }

                // Check for 'vite' in dependencies or devDependencies
                val dependencies = jsonObject.optJSONObject("dependencies")
                val devDependencies = jsonObject.optJSONObject("devDependencies")

                val hasVite = (dependencies != null && dependencies.has("vite")) ||
                        (devDependencies != null && devDependencies.has("vite"))

                if (!hasVite) {
                    return@withContext CheckResult.Failure(
                        reason = "'vite' is not listed in dependencies or devDependencies inside package.json."
                    )
                }

                // Check for index.html at project root
                val indexHtml = File(projectRoot, "index.html")
                if (!indexHtml.exists()) {
                    return@withContext CheckResult.Failure(
                        reason = "index.html file missing at project root."
                    )
                }

                // Project name comes from package.json "name" or fallback
                val rawName = jsonObject.optString("name", "").trim()
                val finalName = if (rawName.isNotEmpty()) rawName else fallbackName

                CheckResult.Success(
                    actualProjectDir = projectRoot,
                    projectName = finalName,
                    packageJsonFile = packageJson
                )
            } catch (e: Exception) {
                CheckResult.Failure(
                    reason = "Error inspecting project: ${e.message ?: "Unknown error"}"
                )
            }
        }
}

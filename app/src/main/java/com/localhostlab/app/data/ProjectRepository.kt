package com.localhostlab.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProjectRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val indexFile = File(context.filesDir, "projects.json")
    private val mutex = Mutex()
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    init {
        scope.launch {
            loadProjects()
        }
    }

    private suspend fun loadProjects() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!indexFile.exists()) {
                _projects.value = emptyList()
                return@withContext
            }

            try {
                val jsonString = indexFile.readText()
                val jsonArray = JSONArray(jsonString)
                val list = mutableListOf<Project>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val project = Project(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        path = obj.getString("path"),
                        sourceType = try {
                            ProjectSourceType.valueOf(obj.optString("sourceType", ProjectSourceType.FOLDER.name))
                        } catch (e: Exception) {
                            ProjectSourceType.FOLDER
                        },
                        sourceUri = obj.optString("sourceUri", "").takeIf { it.isNotEmpty() },
                        fileCount = obj.optInt("fileCount", 0),
                        lastRun = obj.optLong("lastRun", 0L),
                        status = try {
                            ProjectStatus.valueOf(obj.optString("status", ProjectStatus.IDLE.name))
                        } catch (e: Exception) {
                            ProjectStatus.IDLE
                        },
                        port = obj.optInt("port", 3000)
                    )
                    list.add(project)
                }
                _projects.value = list
            } catch (e: Exception) {
                e.printStackTrace()
                _projects.value = emptyList()
            }
        }
    }

    private suspend fun persistProjects(list: List<Project>) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            for (p in list) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("path", p.path)
                    put("sourceType", p.sourceType.name)
                    p.sourceUri?.let { put("sourceUri", it) }
                    put("fileCount", p.fileCount)
                    put("lastRun", p.lastRun)
                    put("status", p.status.name)
                    put("port", p.port)
                }
                jsonArray.put(obj)
            }
            indexFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun addOrUpdateProject(project: Project) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _projects.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == project.id }
            if (existingIndex >= 0) {
                currentList[existingIndex] = project
            } else {
                currentList.add(0, project)
            }
            _projects.value = currentList
            persistProjects(currentList)
        }
    }

    suspend fun updateProjectStatus(
        id: String,
        status: ProjectStatus,
        port: Int? = null,
        lastRun: Long? = null
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _projects.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == id }
            if (index >= 0) {
                val existing = currentList[index]
                val updated = existing.copy(
                    status = status,
                    port = port ?: existing.port,
                    lastRun = lastRun ?: existing.lastRun
                )
                currentList[index] = updated
                _projects.value = currentList
                persistProjects(currentList)
            }
        }
    }

    suspend fun renameProject(id: String, newName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _projects.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(name = newName)
                _projects.value = currentList
                persistProjects(currentList)
            }
        }
    }

    suspend fun updateProjectFileCount(id: String, count: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _projects.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(fileCount = count)
                _projects.value = currentList
                persistProjects(currentList)
            }
        }
    }

    fun getProject(id: String): Project? {
        return _projects.value.find { it.id == id }
    }

    fun findProjectBySourceUri(uriString: String): Project? {
        return _projects.value.find { it.sourceUri == uriString }
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val project = _projects.value.find { it.id == id }
            if (project != null) {
                try {
                    val projectDir = File(project.path)
                    if (projectDir.exists()) {
                        projectDir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            val updatedList = _projects.value.filter { it.id != id }
            _projects.value = updatedList
            persistProjects(updatedList)
        }
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                freedBytes += file.length()
                file.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        freedBytes
    }

    suspend fun clearNodeModules(): Int = withContext(Dispatchers.IO) {
        var clearedCount = 0
        mutex.withLock {
            for (p in _projects.value) {
                if (p.status != ProjectStatus.RUNNING && p.status != ProjectStatus.BUILDING) {
                    val nm = File(p.path, "node_modules")
                    if (nm.exists() && nm.isDirectory) {
                        try {
                            nm.deleteRecursively()
                            clearedCount++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        clearedCount
    }
}

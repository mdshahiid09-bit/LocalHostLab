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

    fun getProject(id: String): Project? {
        return _projects.value.find { it.id == id }
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
}

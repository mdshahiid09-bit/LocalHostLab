package com.localhostlab.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OpenTab(
    val file: File,
    val relativePath: String,
    val content: String,
    val isModified: Boolean = false,
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList()
)

data class FileItem(
    val file: File,
    val relativePath: String,
    val isDirectory: Boolean,
    val name: String,
    val depth: Int = 0,
    val isExpanded: Boolean = true
)

class EditorViewModel(
    application: Application,
    val projectId: String,
    private val repository: ProjectRepository
) : AndroidViewModel(application) {

    val project: Project? get() = repository.getProject(projectId)

    private val _fileTree = MutableStateFlow<List<FileItem>>(emptyList())
    val fileTree: StateFlow<List<FileItem>> = _fileTree.asStateFlow()

    private val _openTabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val openTabs: StateFlow<List<OpenTab>> = _openTabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val activeTab: OpenTab?
        get() {
            val tabs = _openTabs.value
            val idx = _activeTabIndex.value
            return if (idx in tabs.indices) tabs[idx] else null
        }

    init {
        refreshFileTree()
    }

    fun dismissStatusMessage() {
        _statusMessage.value = null
    }

    fun refreshFileTree() {
        val proj = project ?: return
        val rootDir = File(proj.path)
        if (!rootDir.exists()) return

        viewModelScope.launch(Dispatchers.IO) {
            val items = mutableListOf<FileItem>()
            val rootPath = rootDir.canonicalPath

            fun walk(dir: File, depth: Int) {
                val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: return
                for (f in files) {
                    if (f.name == "node_modules" || f.name == "dist" || f.name == ".git") continue
                    val rel = f.canonicalPath.substring(rootPath.length + 1)
                    items.add(
                        FileItem(
                            file = f,
                            relativePath = rel,
                            isDirectory = f.isDirectory,
                            name = f.name,
                            depth = depth
                        )
                    )
                    if (f.isDirectory) {
                        walk(f, depth + 1)
                    }
                }
            }

            walk(rootDir, 0)
            _fileTree.value = items

            // Open initial file (e.g. App.tsx, App.jsx, index.html, or package.json) if no tabs open
            if (_openTabs.value.isEmpty()) {
                val candidate = items.find { !it.isDirectory && (it.name.startsWith("App.") || it.name == "index.html" || it.name == "package.json") }
                    ?: items.find { !it.isDirectory }
                candidate?.let { openFile(it.file) }
            }
        }
    }

    fun openFile(file: File) {
        if (file.isDirectory) return
        val proj = project ?: return
        val rootPath = File(proj.path).canonicalPath
        val rel = file.canonicalPath.substring(rootPath.length + 1)

        val existingIdx = _openTabs.value.indexOfFirst { it.file.canonicalPath == file.canonicalPath }
        if (existingIdx >= 0) {
            _activeTabIndex.value = existingIdx
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                file.readText()
            } catch (e: Exception) {
                "// Unable to read file: ${e.message}"
            }
            val newTab = OpenTab(
                file = file,
                relativePath = rel,
                content = text,
                isModified = false
            )
            val updated = _openTabs.value.toMutableList()
            updated.add(newTab)
            _openTabs.value = updated
            _activeTabIndex.value = updated.size - 1
        }
    }

    fun selectTab(index: Int) {
        if (index in _openTabs.value.indices) {
            _activeTabIndex.value = index
        }
    }

    fun closeTab(index: Int) {
        val current = _openTabs.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _openTabs.value = current
            if (_activeTabIndex.value >= current.size) {
                _activeTabIndex.value = (current.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun updateContent(newContent: String) {
        val tabs = _openTabs.value.toMutableList()
        val idx = _activeTabIndex.value
        if (idx !in tabs.indices) return

        val current = tabs[idx]
        if (current.content == newContent) return

        val newUndo = current.undoStack.toMutableList()
        newUndo.add(current.content)
        if (newUndo.size > 50) newUndo.removeAt(0)

        tabs[idx] = current.copy(
            content = newContent,
            isModified = true,
            undoStack = newUndo,
            redoStack = emptyList() // clear redo on new edit
        )
        _openTabs.value = tabs
    }

    fun undo() {
        val tabs = _openTabs.value.toMutableList()
        val idx = _activeTabIndex.value
        if (idx !in tabs.indices) return

        val current = tabs[idx]
        if (current.undoStack.isEmpty()) return

        val prevContent = current.undoStack.last()
        val newUndo = current.undoStack.dropLast(1)
        val newRedo = current.redoStack.toMutableList()
        newRedo.add(current.content)

        tabs[idx] = current.copy(
            content = prevContent,
            isModified = true,
            undoStack = newUndo,
            redoStack = newRedo
        )
        _openTabs.value = tabs
    }

    fun redo() {
        val tabs = _openTabs.value.toMutableList()
        val idx = _activeTabIndex.value
        if (idx !in tabs.indices) return

        val current = tabs[idx]
        if (current.redoStack.isEmpty()) return

        val nextContent = current.redoStack.last()
        val newRedo = current.redoStack.dropLast(1)
        val newUndo = current.undoStack.toMutableList()
        newUndo.add(current.content)

        tabs[idx] = current.copy(
            content = nextContent,
            isModified = true,
            undoStack = newUndo,
            redoStack = newRedo
        )
        _openTabs.value = tabs
    }

    fun saveActiveFile() {
        val tab = activeTab ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                tab.file.writeText(tab.content)
                val tabs = _openTabs.value.toMutableList()
                val idx = _activeTabIndex.value
                if (idx in tabs.indices) {
                    tabs[idx] = tabs[idx].copy(isModified = false)
                    _openTabs.value = tabs
                }
                _statusMessage.value = "Saved '${tab.file.name}'"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    fun createFile(relativePath: String) {
        val proj = project ?: return
        val rootDir = File(proj.path)
        val newFile = File(rootDir, relativePath)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (newFile.exists()) {
                    _statusMessage.value = "File already exists!"
                    return@launch
                }
                newFile.parentFile?.mkdirs()
                newFile.createNewFile()
                refreshFileTree()
                openFile(newFile)
                _statusMessage.value = "Created '${newFile.name}'"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to create file: ${e.message}"
            }
        }
    }

    fun createFolder(relativePath: String) {
        val proj = project ?: return
        val rootDir = File(proj.path)
        val newDir = File(rootDir, relativePath)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (newDir.exists()) {
                    _statusMessage.value = "Folder already exists!"
                    return@launch
                }
                newDir.mkdirs()
                refreshFileTree()
                _statusMessage.value = "Created folder '${newDir.name}'"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to create folder: ${e.message}"
            }
        }
    }

    fun deleteFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isDir = file.isDirectory
                file.deleteRecursively()
                // Close any open tab of deleted file
                val tabs = _openTabs.value.filter { !it.file.canonicalPath.startsWith(file.canonicalPath) }
                _openTabs.value = tabs
                _activeTabIndex.value = (tabs.size - 1).coerceAtLeast(0)
                refreshFileTree()
                _statusMessage.value = "Deleted ${if (isDir) "folder" else "file"} '${file.name}'"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to delete: ${e.message}"
            }
        }
    }

    fun renameFile(file: File, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parent = file.parentFile ?: return@launch
                val target = File(parent, newName)
                if (target.exists()) {
                    _statusMessage.value = "A file with that name already exists!"
                    return@launch
                }
                val success = file.renameTo(target)
                if (success) {
                    refreshFileTree()
                    _openTabs.value = emptyList() // reset tabs to re-open safely
                    openFile(target)
                    _statusMessage.value = "Renamed to '$newName'"
                } else {
                    _statusMessage.value = "Rename failed."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to rename: ${e.message}"
            }
        }
    }

    fun hasUnsavedChanges(): Boolean {
        return _openTabs.value.any { it.isModified }
    }
}

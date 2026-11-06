package com.localhostlab.app.data

enum class ProjectStatus {
    IDLE,
    INSTALLING,
    BUILDING,
    RUNNING,
    ERROR;

    fun getDisplayName(): String = when (this) {
        IDLE -> "Idle"
        INSTALLING -> "Installing..."
        BUILDING -> "Building..."
        RUNNING -> "Running"
        ERROR -> "Error"
    }
}

enum class ProjectSourceType {
    FOLDER,
    ZIP,
    MANUAL;

    fun getDisplayName(): String = when (this) {
        FOLDER -> "Folder"
        ZIP -> "ZIP"
        MANUAL -> "Manual"
    }
}

data class Project(
    val id: String,
    val name: String,
    val path: String,
    val sourceType: ProjectSourceType = ProjectSourceType.FOLDER,
    val sourceUri: String? = null,
    val fileCount: Int = 0,
    val lastRun: Long = 0L,
    val status: ProjectStatus = ProjectStatus.IDLE,
    val port: Int = 3000
)

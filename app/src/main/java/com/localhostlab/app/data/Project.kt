package com.localhostlab.app.data

enum class ProjectStatus {
    IDLE,
    INSTALLING,
    BUILDING,
    RUNNING,
    ERROR;

    fun getDisplayName(): String {
        return when (this) {
            IDLE -> "Idle"
            INSTALLING -> "Installing (npm install)..."
            BUILDING -> "Building (npm run build)..."
            RUNNING -> "Running"
            ERROR -> "Error"
        }
    }
}

data class Project(
    val id: String,
    val name: String,
    val path: String,
    val lastRun: Long = 0L,
    val status: ProjectStatus = ProjectStatus.IDLE,
    val port: Int = 3000
)

package com.localhostlab.app.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Home : Screen("home")
    object Settings : Screen("settings")
    object Project : Screen("project/{projectId}") {
        fun createRoute(projectId: String) = "project/$projectId"
    }
    object Preview : Screen("preview/{projectId}") {
        fun createRoute(projectId: String) = "preview/$projectId"
    }
    object Editor : Screen("editor/{projectId}") {
        fun createRoute(projectId: String) = "editor/$projectId"
    }
}

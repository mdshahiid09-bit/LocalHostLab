package com.localhostlab.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.navigation.LocalHostLabNavGraph
import com.localhostlab.app.run.ProjectRunner
import com.localhostlab.app.ui.screens.AppThemeMode
import com.localhostlab.app.ui.theme.LocalHostLabTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: ProjectRepository
    private lateinit var runner: ProjectRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = ProjectRepository(applicationContext)
        runner = ProjectRunner(applicationContext, repository)

        setContent {
            val prefs = remember { getSharedPreferences("localhostlab_settings", Context.MODE_PRIVATE) }
            var themeMode by remember {
                mutableStateOf(
                    try {
                        AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name)
                    } catch (e: Exception) {
                        AppThemeMode.SYSTEM
                    }
                )
            }

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "theme_mode") {
                        themeMode = try {
                            AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name)
                        } catch (e: Exception) {
                            AppThemeMode.SYSTEM
                        }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            val isDark = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            LocalHostLabTheme(darkTheme = isDark) {
                val navController = rememberNavController()
                LocalHostLabNavGraph(
                    navController = navController,
                    repository = repository,
                    runner = runner
                )
            }
        }
    }
}

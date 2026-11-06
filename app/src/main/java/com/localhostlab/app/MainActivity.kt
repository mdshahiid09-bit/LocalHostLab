package com.localhostlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.navigation.LocalHostLabNavGraph
import com.localhostlab.app.run.ProjectRunner
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
            LocalHostLabTheme {
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

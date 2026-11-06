package com.localhostlab.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.localhostlab.app.data.ProjectRepository
import com.localhostlab.app.run.ProjectRunner
import com.localhostlab.app.ui.screens.HomeScreen
import com.localhostlab.app.ui.screens.HomeViewModel
import com.localhostlab.app.ui.screens.PreviewScreen
import com.localhostlab.app.ui.screens.ProjectScreen
import com.localhostlab.app.ui.screens.ProjectViewModel
import com.localhostlab.app.ui.screens.SplashScreen

@Composable
fun LocalHostLabNavGraph(
    navController: NavHostController,
    repository: ProjectRepository,
    runner: ProjectRunner
) {
    val context = LocalContext.current
    val homeViewModel = remember {
        HomeViewModel(
            application = context.applicationContext as android.app.Application,
            repository = repository
        )
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable(
            route = Screen.Splash.route,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            SplashScreen(
                onSplashFinished = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Home.route,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            HomeScreen(
                viewModel = homeViewModel,
                onProjectClick = { projectId ->
                    navController.navigate(Screen.Project.createRoute(projectId))
                }
            )
        }

        composable(
            route = Screen.Project.route,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) }
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val projectViewModel = remember(projectId) {
                ProjectViewModel(
                    projectId = projectId,
                    repository = repository,
                    runner = runner
                )
            }

            ProjectScreen(
                viewModel = projectViewModel,
                onBackClick = { navController.popBackStack() },
                onOpenPreview = { id ->
                    navController.navigate(Screen.Preview.createRoute(id))
                }
            )
        }

        composable(
            route = Screen.Preview.route,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) }
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val project = repository.getProject(projectId)
            val port = project?.port ?: 3000

            PreviewScreen(
                port = port,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

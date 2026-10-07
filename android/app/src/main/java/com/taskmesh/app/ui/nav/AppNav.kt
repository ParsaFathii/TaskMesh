@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskmesh.app.AppViewModel
import com.taskmesh.app.data.SessionState
import com.taskmesh.app.data.UserDto
import com.taskmesh.app.data.isOperator
import com.taskmesh.app.ui.screens.JobDetailScreen
import com.taskmesh.app.ui.screens.JobsScreen
import com.taskmesh.app.ui.screens.LoginScreen
import com.taskmesh.app.ui.screens.NewJobScreen
import com.taskmesh.app.ui.screens.NotificationsScreen
import com.taskmesh.app.ui.screens.ProjectsScreen
import com.taskmesh.app.ui.screens.SettingsScreen
import com.taskmesh.app.ui.screens.WorkersScreen

object Routes {
    const val LOGIN = "login"
    /** Destination pattern with an optional project filter argument. */
    const val JOBS = "jobs?project={project}"
    /** Concrete navigation target (uses the argument default: null). */
    const val JOBS_PLAIN = "jobs"
    const val PROJECTS = "projects"
    const val WORKERS = "workers"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val JOB_DETAIL = "job/{id}"
    const val NEW_JOB = "newjob"

    fun jobDetail(jobId: String) = "job/$jobId"
    fun jobsForProject(projectId: String) = "jobs?project=$projectId"
}

@Composable
fun AppRoot(appViewModel: AppViewModel) {
    val navController = rememberNavController()
    val sessionState by appViewModel.sessionState.collectAsState()
    val unreadCount by appViewModel.notifications.unreadCount.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val loggedIn = sessionState is SessionState.LoggedIn
    val user = (sessionState as? SessionState.LoggedIn)?.user

    val topLevelNavTargets = buildList {
        add(Routes.JOBS)
        add(Routes.PROJECTS)
        if (user?.isOperator == true) add(Routes.WORKERS)
        add(Routes.ALERTS)
        add(Routes.SETTINGS)
    }
    val showBottomBar = loggedIn && currentRoute != null && currentRoute in topLevelNavTargets
    val startDestination = if (loggedIn) Routes.JOBS_PLAIN else Routes.LOGIN

    // Session-driven navigation: forced logout (401) -> login; login -> jobs.
    LaunchedEffect(sessionState) {
        val route = navController.currentDestination?.route
        when (sessionState) {
            is SessionState.LoggedOut -> if (route != Routes.LOGIN) {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
            is SessionState.LoggedIn -> if (route == Routes.LOGIN) {
                navController.navigate(Routes.JOBS_PLAIN) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems(user).forEach { item ->
                        val selected = currentRoute == item.selectedRoute
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.navRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (item.route == Routes.ALERTS && unreadCount > 0) {
                                    BadgedBox(
                                        badge = { Badge { Text(text = unreadCount.coerceAtMost(99).toString()) } },
                                    ) {
                                        Icon(imageVector = item.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(imageVector = item.icon, contentDescription = null)
                                }
                            },
                            label = { Text(text = item.label) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Routes.JOBS_PLAIN) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.JOBS,
                arguments = listOf(
                    navArgument("project") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                JobsScreen(
                    initialProject = entry.arguments?.getString("project"),
                    onJobClick = { jobId -> navController.navigate(Routes.jobDetail(jobId)) },
                    onNewJob = { navController.navigate(Routes.NEW_JOB) },
                )
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(
                    onProjectClick = { projectId ->
                        navController.navigate(Routes.jobsForProject(projectId)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.WORKERS) { WorkersScreen() }
            composable(Routes.ALERTS) { NotificationsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                route = Routes.JOB_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                JobDetailScreen(
                    jobId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.NEW_JOB) {
                NewJobScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { navController.popBackStack() },
                )
            }
        }
    }
}

private data class BottomItem(
    val route: String,
    val navRoute: String,
    val selectedRoute: String,
    val label: String,
    val icon: ImageVector,
)

private fun bottomItems(user: UserDto?): List<BottomItem> = buildList {
    // Jobs navigates to the plain route; selection matches the pattern route.
    add(BottomItem(Routes.JOBS, Routes.JOBS_PLAIN, Routes.JOBS, "Jobs", Icons.Filled.List))
    add(BottomItem(Routes.PROJECTS, Routes.PROJECTS, Routes.PROJECTS, "Projects", Icons.Filled.Home))
    // SPEC §11: UI only hides what the backend already denies — the Workers
    // endpoint requires OPERATOR/ADMIN, so the tab is hidden for plain users.
    if (user?.isOperator == true) {
        add(BottomItem(Routes.WORKERS, Routes.WORKERS, Routes.WORKERS, "Workers", Icons.Filled.Build))
    }
    add(BottomItem(Routes.ALERTS, Routes.ALERTS, Routes.ALERTS, "Alerts", Icons.Filled.Notifications))
    add(BottomItem(Routes.SETTINGS, Routes.SETTINGS, Routes.SETTINGS, "Settings", Icons.Filled.Settings))
}

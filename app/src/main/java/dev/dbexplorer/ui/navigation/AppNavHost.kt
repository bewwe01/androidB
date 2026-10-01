package dev.dbexplorer.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.dbexplorer.ui.connections.ConnectionEditScreen
import dev.dbexplorer.ui.connections.ConnectionListScreen
import dev.dbexplorer.ui.explorer.ExplorerHomeScreen
import dev.dbexplorer.ui.explorer.ObjectListScreen
import dev.dbexplorer.ui.explorer.SchemaListScreen
import dev.dbexplorer.ui.explorer.TableDetailScreen
import dev.dbexplorer.ui.placeholder.ComingSoonScreen

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevels = listOf(
    TopLevel(Routes.TAB_CONNECTIONS, "Connections", Icons.Filled.Home),
    TopLevel(Routes.TAB_EXPLORER, "Explorer", Icons.AutoMirrored.Filled.List),
    TopLevel(Routes.TAB_QUERY, "Query", Icons.Filled.PlayArrow),
    TopLevel(Routes.TAB_HISTORY, "History", Icons.Filled.DateRange),
)

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = destination?.route != Routes.CONNECTION_EDIT

    // The outer Scaffold only reserves room for the bottom bar; each screen's own Scaffold handles
    // the status bar, so no insets are applied (or consumed) here.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevels.forEach { top ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == top.route } == true,
                            onClick = { navController.navigateToTab(top.route) },
                            icon = { Icon(top.icon, contentDescription = null) },
                            label = { Text(top.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TAB_CONNECTIONS,
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(padding),
        ) {
            navigation(startDestination = Routes.CONNECTION_LIST, route = Routes.TAB_CONNECTIONS) {
                composable(Routes.CONNECTION_LIST) {
                    ConnectionListScreen(
                        onAdd = { navController.navigate(Routes.connectionEdit(null)) },
                        onEdit = { id -> navController.navigate(Routes.connectionEdit(id)) },
                        onConnected = { id ->
                            navController.navigateToTab(Routes.TAB_EXPLORER)
                            navController.navigate(Routes.schemas(id))
                        },
                    )
                }
                composable(
                    Routes.CONNECTION_EDIT,
                    arguments = listOf(
                        navArgument("id") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) {
                    ConnectionEditScreen(onDone = { navController.popBackStack() })
                }
            }

            navigation(startDestination = Routes.EXPLORER_HOME, route = Routes.TAB_EXPLORER) {
                composable(Routes.EXPLORER_HOME) {
                    ExplorerHomeScreen(
                        onOpen = { id -> navController.navigate(Routes.schemas(id)) },
                        onGoToConnections = { navController.navigateToTab(Routes.TAB_CONNECTIONS) },
                    )
                }
                composable(Routes.SCHEMAS, arguments = listOf(connIdArg())) {
                    SchemaListScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSchema = { id, ref -> navController.navigate(Routes.objects(id, ref)) },
                    )
                }
                composable(Routes.OBJECTS, arguments = listOf(connIdArg(), optionalString("catalog"), optionalString("schema"))) {
                    ObjectListScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTable = { id, t -> navController.navigate(Routes.table(id, t)) },
                    )
                }
                composable(
                    Routes.TABLE,
                    arguments = listOf(
                        connIdArg(),
                        optionalString("catalog"),
                        optionalString("schema"),
                        optionalString("name"),
                        optionalString("type"),
                    ),
                ) {
                    TableDetailScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTable = { id, t -> navController.navigate(Routes.table(id, t)) },
                    )
                }
            }

            navigation(startDestination = Routes.QUERY, route = Routes.TAB_QUERY) {
                composable(Routes.QUERY) {
                    ComingSoonScreen("Query", "A tabbed SQL editor with streamed, paged results, run/cancel and an extended keyboard row.")
                }
            }
            navigation(startDestination = Routes.HISTORY, route = Routes.TAB_HISTORY) {
                composable(Routes.HISTORY) {
                    ComingSoonScreen("History", "Every query you run, searchable, with saved scripts.")
                }
            }
        }
    }
}

private fun connIdArg() = navArgument("connId") { type = NavType.LongType }

private fun optionalString(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

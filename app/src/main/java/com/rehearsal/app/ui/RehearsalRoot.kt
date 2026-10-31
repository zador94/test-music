package com.rehearsal.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rehearsal.app.ui.library.LibraryScreen
import com.rehearsal.app.ui.player.PlayerScreen
import com.rehearsal.app.ui.processing.ProcessingScreen

@Composable
fun RehearsalRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenProcessing = { id -> nav.navigate("processing/$id") },
                onOpenPlayer = { id -> nav.navigate("player/$id") },
            )
        }
        composable(
            route = "processing/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.StringType }),
        ) {
            ProcessingScreen(
                onBack = { nav.popBackStack() },
                onOpenPlayer = { id ->
                    nav.navigate("player/$id") {
                        popUpTo("library")
                    }
                },
            )
        }
        composable(
            route = "player/{trackId}",
            arguments = listOf(navArgument("trackId") { type = NavType.StringType }),
        ) {
            PlayerScreen(onBack = { nav.popBackStack() })
        }
    }
}

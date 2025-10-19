package com.example.composeble.blelab.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val nav = rememberNavController()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ComposeBLE") },
                actions = {
                    TextButton(onClick = { nav.navigate("settings") }) { Text("Settings") }
                }
            )
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen(navToDetail = { addr -> nav.navigate("detail/$addr") }) }
            composable(
                route = "detail/{address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType })
            ) { backStackEntry ->
                val address = backStackEntry.arguments?.getString("address") ?: ""
                DeviceDetailScreen(address = address)
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

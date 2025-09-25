package com.example.composeble.blelab.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val nav = rememberNavController()
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("ComposeBLE") }) }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(navToDetail = { addr -> nav.navigate("detail/$addr") })
            }
            composable(
                route = "detail/{address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType })
            ) { backStackEntry ->
                val address = backStackEntry.arguments?.getString("address") ?: ""
                DeviceDetailScreen(address = address)
            }
        }
    }
}

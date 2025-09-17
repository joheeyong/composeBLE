package com.example.composeble.blelab.ui
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.luxrobo.blelab.ui.HomeScreen

@ExperimentalMaterial3Api
@Composable
fun App() {
    val nav = rememberNavController()
    Scaffold(
        topBar = { TopAppBar(title = { Text("BLE Lab") }) }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen() }
        }
    }
}
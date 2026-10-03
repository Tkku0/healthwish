package com.healthwish.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.healthwish.app.ui.components.AccentPink
import com.healthwish.app.ui.components.CardBg
import com.healthwish.app.ui.screens.ExportScreen
import com.healthwish.app.ui.screens.HistoryScreen
import com.healthwish.app.ui.screens.SettingsScreen
import com.healthwish.app.ui.screens.TodayScreen
import com.healthwish.app.viewmodel.DayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthWishApp(onLogout: () -> Unit = {}) {
    var selectedScreen by remember { mutableIntStateOf(0) }
    val dayVm: DayViewModel = viewModel()
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    Scaffold(
        containerColor = Color(0xFF0D0D12),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row {
                        Text("Health", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp)
                        Text("Wish", fontWeight = FontWeight.Bold, color = AccentPink, fontSize = 20.sp)
                        Text(" 💜", fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0D0D12)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF14141E),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedScreen == 0,
                    onClick = { selectedScreen = 0 },
                    icon = { Icon(Icons.Rounded.Today, contentDescription = "Today") },
                    label = { Text("Today") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPink,
                        selectedTextColor = AccentPink,
                        indicatorColor = AccentPink.copy(alpha = 0.2f),
                        unselectedIconColor = Color(0xFF8E8EA0),
                        unselectedTextColor = Color(0xFF8E8EA0)
                    )
                )
                NavigationBarItem(
                    selected = selectedScreen == 1,
                    onClick = { selectedScreen = 1 },
                    icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = "History") },
                    label = { Text("History") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPink,
                        selectedTextColor = AccentPink,
                        indicatorColor = AccentPink.copy(alpha = 0.2f),
                        unselectedIconColor = Color(0xFF8E8EA0),
                        unselectedTextColor = Color(0xFF8E8EA0)
                    )
                )
                NavigationBarItem(
                    selected = selectedScreen == 2,
                    onClick = { selectedScreen = 2 },
                    icon = { Icon(Icons.Rounded.FileDownload, contentDescription = "Export") },
                    label = { Text("Export") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPink,
                        selectedTextColor = AccentPink,
                        indicatorColor = AccentPink.copy(alpha = 0.2f),
                        unselectedIconColor = Color(0xFF8E8EA0),
                        unselectedTextColor = Color(0xFF8E8EA0)
                    )
                )
                NavigationBarItem(
                    selected = selectedScreen == 3,
                    onClick = { selectedScreen = 3 },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPink,
                        selectedTextColor = AccentPink,
                        indicatorColor = AccentPink.copy(alpha = 0.2f),
                        unselectedIconColor = Color(0xFF8E8EA0),
                        unselectedTextColor = Color(0xFF8E8EA0)
                    )
                )
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .background(Color(0xFF0D0D12))
        ) {
            when (selectedScreen) {
                0 -> TodayScreen(vm = dayVm)
                1 -> HistoryScreen(currentUid = uid)
                2 -> ExportScreen(currentUid = uid)
                3 -> SettingsScreen(currentUid = uid, onLogout = onLogout)
            }
        }
    }
}

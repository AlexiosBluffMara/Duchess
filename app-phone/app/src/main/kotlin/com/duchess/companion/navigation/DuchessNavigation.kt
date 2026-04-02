package com.duchess.companion.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.duchess.companion.R

sealed class Screen(val route: String, val titleResId: Int, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Filled.Dashboard)
    data object Stream : Screen("stream", R.string.nav_stream, Icons.Filled.Videocam)
    data object Alerts : Screen("alerts", R.string.nav_alerts, Icons.Filled.NotificationsActive)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Filled.Settings)
    data object AlertDetail : Screen("alert_detail/{alertId}", R.string.nav_alerts, Icons.Filled.NotificationsActive) {
        fun createRoute(alertId: String) = "alert_detail/$alertId"
    }
    data object HudSimulator : Screen("hud_simulator", R.string.hud_sim_title, Icons.Filled.Videocam)
}

val bottomNavScreens = listOf(
    Screen.Dashboard,
    Screen.Stream,
    Screen.Alerts,
    Screen.Settings,
)

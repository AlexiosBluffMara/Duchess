package com.duchess.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.duchess.companion.alerts.AlertDetailScreen
import com.duchess.companion.alerts.AlertListScreen
import com.duchess.companion.dashboard.DashboardScreen
import com.duchess.companion.navigation.Screen
import com.duchess.companion.navigation.bottomNavScreens
import com.duchess.companion.settings.SettingsScreen
import com.duchess.companion.splash.SplashScreen
import com.duchess.companion.stream.StreamScreen
import com.duchess.companion.ui.theme.DuchessTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.registration.RegistrationState
import com.meta.wearable.dat.core.permissions.Permission
import com.meta.wearable.dat.core.permissions.PermissionStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Set to true to bypass DAT SDK registration and show the full app with demo data.
         * Flip to false when testing with real Meta glasses hardware.
         */
        const val DEMO_MODE = true
    }

    private val _registrationState = MutableStateFlow<RegistrationState?>(null)
    private val registrationState: StateFlow<RegistrationState?> = _registrationState.asStateFlow()

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()

    private val permissionsLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            permissionContinuation?.resume(result)
            permissionContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!DEMO_MODE) {
            observeRegistrationState()
        }

        setContent {
            DuchessTheme(dynamicColor = false) {
                if (DEMO_MODE) {
                    DuchessMainApp()
                } else {
                    val regState by registrationState.collectAsState()
                    MainContent(
                        registrationState = regState,
                        onRegisterClick = { startRegistration() },
                    )
                }
            }
        }
    }

    private fun observeRegistrationState() {
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                _registrationState.value = state
            }
        }
    }

    private fun startRegistration() {
        Wearables.startRegistration(this)
    }

    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsLauncher.launch(permission)
            }
        }
    }
}

// region Main App with Navigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuchessMainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBars = currentRoute in bottomNavScreens.map { it.route }
            || currentRoute == Screen.AlertDetail.route

    Scaffold(
        topBar = { if (showBars) DuchessTopBar() },
        bottomBar = { if (showBars) DuchessBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(
                    onSplashComplete = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToHudSim = {
                        navController.navigate(Screen.HudSimulator.route)
                    },
                    onZoneClick = {
                        navController.navigate(Screen.Alerts.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.Stream.route) { StreamScreen() }
            composable(Screen.Alerts.route) {
                AlertListScreen(
                    onAlertClick = { alertId ->
                        navController.navigate(Screen.AlertDetail.createRoute(alertId))
                    },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToHudSim = {
                        navController.navigate(Screen.HudSimulator.route)
                    },
                )
            }
            composable(Screen.HudSimulator.route) {
                com.duchess.companion.hud.HudSimulatorScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Screen.AlertDetail.route,
                arguments = listOf(navArgument("alertId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val alertId = backStackEntry.arguments?.getString("alertId") ?: return@composable
                AlertDetailScreen(
                    alertId = alertId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuchessTopBar() {
    val demoYellow = Color(0xFFFFD600)
    CenterAlignedTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "\uD83D\uDEE1\uFE0F",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        actions = {
            // Connection status dot — yellow for demo mode
            Box(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (MainActivity.DEMO_MODE) demoYellow else Color(0xFF4CAF50)),
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun DuchessBottomBar(navController: androidx.navigation.NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        bottomNavScreens.forEach { screen ->
            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = stringResource(screen.titleResId),
                    )
                },
                label = { Text(stringResource(screen.titleResId)) },
                selected = selected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

// endregion

// region Registration Flow (non-demo)

@Composable
private fun MainContent(
    registrationState: RegistrationState?,
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (registrationState) {
        is RegistrationState.Registered -> DuchessMainApp()
        is RegistrationState.Unregistered -> {
            RegistrationPrompt(onRegisterClick = onRegisterClick, modifier = modifier)
        }
        null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        else -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.registration_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RegistrationPrompt(
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.registration_prompt),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRegisterClick) {
                Text(text = stringResource(R.string.register_button))
            }
        }
    }
}

// endregion

package com.duchess.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.duchess.companion.stream.StreamScreen
import com.duchess.companion.ui.theme.DuchessTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.registration.RegistrationState
// Alex: RequestPermissionContract is the Activity Result API contract for DAT permissions.
// It launches into the Meta AI companion app where the user approves camera access.
// This is NOT the same as Android runtime permissions — it's DAT SDK-specific.
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

// Alex: @AndroidEntryPoint is required for Hilt injection into Activities.
// Without it, any @Inject fields or hiltViewModel() calls in Compose will crash
// with a cryptic "Hilt component missing" error. Ask me how I know.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val _registrationState = MutableStateFlow<RegistrationState?>(null)
    private val registrationState: StateFlow<RegistrationState?> = _registrationState.asStateFlow()

    // Alex: Permission flow using the DAT SDK's Activity Result contract.
    // The RequestPermissionContract launches the Meta AI app where the user
    // can choose "Allow once" or "Allow always" for camera access.
    // We wrap this in a CancellableContinuation so we can use it with suspend fns.
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()

    private val permissionsLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            // Alex: This callback fires when the user returns from the Meta AI app.
            // result is a PermissionStatus — either Granted or Denied.
            permissionContinuation?.resume(result)
            permissionContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        observeRegistrationState()

        setContent {
            DuchessTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val regState by registrationState.collectAsState()
                    MainContent(
                        registrationState = regState,
                        onRegisterClick = { startRegistration() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    /**
     * Observe the DAT SDK registration state.
     *
     * Alex: Wearables.registrationState is a StateFlow<RegistrationState> that emits
     * Registered or Unregistered. We collect it in lifecycleScope so it auto-cancels
     * when the Activity is destroyed. No manual cleanup needed — structured concurrency
     * handles it. This is why we love coroutines.
     */
    private fun observeRegistrationState() {
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                _registrationState.value = state
            }
        }
    }

    /**
     * Start the DAT SDK registration flow.
     *
     * Alex: The OLD code used Wearables.requestPermission() which DOES NOT EXIST.
     * The correct API is Wearables.startRegistration(context) — this launches the
     * Meta AI companion app where the user approves your app as a permitted integration.
     * Registration is a ONE-TIME thing per app install. After registration, you use
     * RequestPermissionContract for camera/sensor permissions.
     */
    private fun startRegistration() {
        Wearables.startRegistration(this)
    }

    /**
     * Request a specific DAT SDK permission (e.g., CAMERA) using the Activity Result API.
     *
     * Alex: This is NOT Android's requestPermissions(). The DAT SDK has its own permission
     * system that goes through the Meta AI app. The user sees a dialog in Meta AI asking
     * "Allow Duchess to access camera?" with "Allow once" / "Allow always" options.
     * We use a Mutex here because only one permission request can be in-flight at a time —
     * launching a second one while the first is pending would corrupt the continuation.
     */
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

// Alex: Composable functions below are private to this file — they're layout-only helpers.
// The real state management lives in StreamViewModel. These just react to RegistrationState.

@Composable
private fun MainContent(
    registrationState: RegistrationState?,
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (registrationState) {
        is RegistrationState.Registered -> {
            // Alex: Once registered, show the stream screen. The StreamScreen
            // uses hiltViewModel() internally to get a StreamViewModel.
            StreamScreen(modifier = modifier)
        }
        // Alex: The SDK uses "Unregistered" not "NotRegistered".
        // Subtle naming difference that caused a compile error in the original code.
        is RegistrationState.Unregistered -> {
            RegistrationPrompt(
                onRegisterClick = onRegisterClick,
                modifier = modifier
            )
        }
        null -> {
            // Alex: null means we haven't received the first emission yet.
            // Show a loading state — it resolves within milliseconds usually.
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        else -> {
            // Alex: Catch-all for any future RegistrationState variants the SDK adds.
            // Show an error and let the user try registering again.
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.registration_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RegistrationPrompt(
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.registration_prompt),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRegisterClick) {
                Text(text = stringResource(R.string.register_button))
            }
        }
    }
}

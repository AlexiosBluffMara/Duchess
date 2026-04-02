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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val _registrationState = MutableStateFlow<RegistrationState?>(null)
    private val registrationState: StateFlow<RegistrationState?> = _registrationState.asStateFlow()

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
                        onRegisterClick = { requestRegistration() },
                        modifier = Modifier.padding(innerPadding)
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

    private fun requestRegistration() {
        lifecycleScope.launch {
            Wearables.requestPermission(this@MainActivity)
        }
    }
}

@Composable
private fun MainContent(
    registrationState: RegistrationState?,
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (registrationState) {
        is RegistrationState.Registered -> {
            StreamScreen(modifier = modifier)
        }
        is RegistrationState.NotRegistered -> {
            RegistrationPrompt(
                onRegisterClick = onRegisterClick,
                modifier = modifier
            )
        }
        null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        else -> {
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

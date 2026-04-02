package com.meta.wearable.dat.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.StubStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.permissions.Permission
import com.meta.wearable.dat.core.permissions.PermissionStatus
import com.meta.wearable.dat.core.registration.RegistrationState
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stub for the DAT SDK's Wearables entry point.
 * In DEMO_MODE, all calls are no-ops or return stub data.
 */
object Wearables {
    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Unregistered)
    val registrationState: StateFlow<RegistrationState> = _registrationState

    fun initialize(context: Context) {
        // No-op stub — real SDK registers BLE receivers here
    }

    fun startRegistration(context: Context) {
        _registrationState.value = RegistrationState.Registered
    }

    fun startUnregistration(context: Context) {
        _registrationState.value = RegistrationState.Unregistered
    }

    fun startStreamSession(
        context: Context,
        deviceSelector: AutoDeviceSelector,
        streamConfiguration: StreamConfiguration,
    ): DatResult<StreamSession, WearablesError> {
        return DatResult.Failure(WearablesError.NOT_INITIALIZED)
    }

    fun checkPermissionStatus(permission: Permission): PermissionStatus {
        return PermissionStatus.Denied
    }

    fun RequestPermissionContract(): ActivityResultContract<Permission, PermissionStatus> {
        return object : ActivityResultContract<Permission, PermissionStatus>() {
            override fun createIntent(context: Context, input: Permission): Intent {
                return Intent()
            }

            override fun parseResult(resultCode: Int, intent: Intent?): PermissionStatus {
                return if (resultCode == Activity.RESULT_OK) PermissionStatus.Granted
                else PermissionStatus.Denied
            }
        }
    }
}

enum class WearablesError {
    NOT_INITIALIZED,
    DEVICE_NOT_FOUND,
    PERMISSION_DENIED,
}

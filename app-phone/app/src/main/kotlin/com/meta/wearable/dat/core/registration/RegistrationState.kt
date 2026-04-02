package com.meta.wearable.dat.core.registration

sealed interface RegistrationState {
    data object Registered : RegistrationState
    data object Unregistered : RegistrationState
}

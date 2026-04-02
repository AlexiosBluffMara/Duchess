package com.meta.wearable.dat.core

/**
 * Stub for the DAT SDK's DatResult type.
 * Provides fold(), onSuccess(), onFailure() for type-safe error handling.
 */
sealed class DatResult<out T, out E> {
    data class Success<T>(val value: T) : DatResult<T, Nothing>()
    data class Failure<E>(val error: E) : DatResult<Nothing, E>()

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (E) -> R,
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }

    inline fun onSuccess(action: (T) -> Unit): DatResult<T, E> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (E) -> Unit): DatResult<T, E> {
        if (this is Failure) action(error)
        return this
    }
}

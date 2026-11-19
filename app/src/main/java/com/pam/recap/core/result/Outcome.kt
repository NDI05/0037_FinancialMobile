package com.pam.recap.core.result

/**
 * Explicit success/failure envelope for every suspend port call.
 *
 * A failed call never returns a zero amount or an empty list that could be mistaken for real
 * data (NFR-11: zero != unavailable != partial != empty).
 */
sealed interface Outcome<out T> {

    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    val isFailure: Boolean get() = this is Failure

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)

        fun <T> failure(error: AppError): Outcome<T> = Failure(error)

        fun <T> failure(kind: ErrorKind, detail: String? = null): Outcome<T> =
            Failure(
                when (kind) {
                    ErrorKind.NETWORK -> AppError.Network(detail)
                    ErrorKind.UNAUTHORIZED -> AppError.Unauthorized(detail)
                    ErrorKind.PERMISSION_REQUIRED -> AppError.PermissionRequired(detail)
                    ErrorKind.CONFIGURATION -> AppError.Configuration(detail)
                    ErrorKind.PARSING -> AppError.Parsing(detail)
                    ErrorKind.NOT_FOUND -> AppError.NotFound(detail)
                    ErrorKind.CONFLICT -> AppError.Conflict(detail)
                    ErrorKind.VALIDATION -> AppError.Validation(detail ?: "unspecified")
                    ErrorKind.RATE_LIMITED -> AppError.RateLimited(detail)
                    ErrorKind.STORAGE -> AppError.Storage(detail)
                    ErrorKind.UNKNOWN -> AppError.Unknown(detail)
                },
            )
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error

inline fun <T, R> Outcome<T>.fold(onSuccess: (T) -> R, onFailure: (AppError) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

/**
 * Runs [block] and converts any thrown exception into [AppError.Unknown] without leaking the
 * exception message into user copy or logs (NFR-18). Storage/crypto failures are mapped to
 * [ErrorKind.STORAGE] so the UI can offer a retry instead of a silent zero.
 */
inline fun <T> outcomeOf(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    Outcome.Failure(AppError.Storage(error::class.simpleName))
}

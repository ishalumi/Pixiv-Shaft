package ceui.pixiv.chat.core

import ceui.pixiv.chat.core.AppError

sealed interface AppResult<out T> {

    data class Success<T>(val data: T) : AppResult<T>

    data class Error(val error: AppError) : AppResult<Nothing>
}

// ── Chaining ──────────────────────────────────────────────────────────────────

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(error)
    return this
}

/** Transform the success value, propagating errors unchanged. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}

/** Chain a result-returning operation on success, propagating errors unchanged. */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(data)
    is AppResult.Error -> this
}

// ── Extraction ────────────────────────────────────────────────────────────────

/** Returns the success value, or null on error. */
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data

/** Returns the success value, or [default] on error. */
fun <T> AppResult<T>.getOrElse(default: T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Error -> default
}

/** Collapses both branches into a single value. */
inline fun <T, R> AppResult<T>.fold(
    onSuccess: (T) -> R,
    onError: (AppError) -> R
): R = when (this) {
    is AppResult.Success -> onSuccess(data)
    is AppResult.Error -> onError(error)
}

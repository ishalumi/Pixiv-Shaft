package ceui.pixiv.chat.core

/**
 * Domain-level error hierarchy for the application.
 *
 * [debugMessage] is intended for **logging and diagnostics only**. To obtain a
 * user-facing, localisable string, use the `AppError.toUserMessage(Context)`
 * extension defined in the `:base` module.
 *
 * @param httpCode HTTP status code for HTTP-originated errors, or `null` for
 *                 non-HTTP errors (network, timeout, serialization, etc.).
 */
sealed class AppError(
    open val debugMessage: String,
    open val cause: Throwable? = null,
    val httpCode: Int? = null
) {
    /**
     * Whether the user can meaningfully retry this operation.
     * Auth errors (401/403) require re-login, not a retry.
     */
    open val isRetryable: Boolean get() = true

    // ── Network ─────────────────────────────────────────────────────────────

    data class NetworkUnavailable(override val cause: Throwable? = null) : AppError(
        debugMessage = "Network unavailable. Please check your connection."
    )

    data class RequestTimeout(override val cause: Throwable? = null) : AppError(
        debugMessage = "Request timed out. Please try again."
    )

    /** SSL/TLS failure — certificate expired, pinning mismatch, or handshake error. */
    data class SecurityError(override val cause: Throwable? = null) : AppError(
        debugMessage = "Secure connection failed."
    ) {
        override val isRetryable get() = false
    }

    // ── Client errors (4xx) ─────────────────────────────────────────────────

    /** 400 — Malformed request. */
    data class BadRequest(
        override val debugMessage: String = "The request was invalid.",
        override val cause: Throwable? = null
    ) : AppError(debugMessage = debugMessage, httpCode = 400) {
        override val isRetryable get() = false
    }

    /** 401 — Token expired or missing. */
    data class Unauthorized(override val cause: Throwable? = null) : AppError(
        debugMessage = "Session expired. Please sign in again.",
        httpCode = 401
    ) {
        override val isRetryable get() = false
    }

    /** 403 — Insufficient permissions. */
    data class Forbidden(override val cause: Throwable? = null) : AppError(
        debugMessage = "You do not have permission to perform this action.",
        httpCode = 403
    ) {
        override val isRetryable get() = false
    }

    /** 404 — Resource does not exist. */
    data class NotFound(override val cause: Throwable? = null) : AppError(
        debugMessage = "Requested resource was not found.",
        httpCode = 404
    ) {
        override val isRetryable get() = false
    }

    /** 409 — Resource conflict (e.g. duplicate creation, optimistic lock failure). */
    data class Conflict(
        override val debugMessage: String = "The request conflicts with the current state.",
        override val cause: Throwable? = null
    ) : AppError(debugMessage = debugMessage, httpCode = 409) {
        override val isRetryable get() = false
    }

    /** 410 — Resource permanently removed. */
    data class Gone(override val cause: Throwable? = null) : AppError(
        debugMessage = "This resource is no longer available.",
        httpCode = 410
    ) {
        override val isRetryable get() = false
    }

    /** 422 — Semantic validation failed (fields present but values invalid). */
    data class Unprocessable(
        override val debugMessage: String = "The submitted data could not be processed.",
        override val cause: Throwable? = null
    ) : AppError(debugMessage = debugMessage, httpCode = 422) {
        override val isRetryable get() = false
    }

    /** 429 — Rate limit exceeded. [retryAfterSeconds] comes from the Retry-After header when available. */
    data class RateLimited(
        val retryAfterSeconds: Int? = null,
        override val cause: Throwable? = null
    ) : AppError(
        debugMessage = if (retryAfterSeconds != null) "Too many requests. Please try again in ${retryAfterSeconds}s."
        else "Too many requests. Please try again later.",
        httpCode = 429
    )

    // ── Server errors (5xx) ─────────────────────────────────────────────────

    /** Generic server error with the original HTTP status code. */
    data class Server(
        val code: Int,
        override val debugMessage: String,
        override val cause: Throwable? = null
    ) : AppError(debugMessage = debugMessage, httpCode = code)

    /** 503 — Service temporarily unavailable (maintenance, overload). */
    data class ServiceUnavailable(override val cause: Throwable? = null) : AppError(
        debugMessage = "Service is temporarily unavailable. Please try again later.",
        httpCode = 503
    )

    // ── Data / Unknown ──────────────────────────────────────────────────────

    data class Serialization(override val cause: Throwable? = null) : AppError(
        debugMessage = "Received invalid data from server."
    ) {
        override val isRetryable get() = false
    }

    data class Unknown(
        override val debugMessage: String,
        override val cause: Throwable? = null
    ) : AppError(debugMessage = debugMessage) {
        override val isRetryable get() = false
    }
}

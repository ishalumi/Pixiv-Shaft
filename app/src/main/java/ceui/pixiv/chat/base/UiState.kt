package ceui.pixiv.chat.base

import ceui.pixiv.chat.core.AppError

/**
 * Page-level state for any screen. Every variant is exhaustive — the UI layer
 * uses a `when` block and handles each case once.
 *
 * Pagination concerns ([PagingState]) are deliberately excluded so that
 * non-list screens never carry dead code.
 *
 * @param T The type of data presented in the [Content] state.
 */
sealed class PageState<out T> {

    data class Loading(val reason: LoadReason = LoadReason.Initial) : PageState<Nothing>()

    data class Content<T>(val data: T) : PageState<T>()

    /** Full-screen error. Carries the original [AppError] for type-safe handling. */
    data class Error(val error: AppError) : PageState<Nothing>()

    data class Empty(val message: String = "") : PageState<Nothing>()
}

/**
 * Pagination state, exposed as a separate [StateFlow] by [PagedListViewModel].
 *
 * The adapter reads this to show/hide its loading or error footer.
 * Completely independent of [PageState] — a screen can be in
 * [PageState.Content] while pagination is [PagingState.LoadingMore].
 */
sealed class PagingState {

    /** No pagination activity — either idle or not a paginated screen. */
    data object Idle : PagingState()

    /** A next-page request is in flight. */
    data object LoadingMore : PagingState()

    /** The last next-page request failed. */
    data class Error(val error: AppError) : PagingState()

    /** All pages have been loaded. */
    data object EndReached : PagingState()
}

enum class LoadReason {
    /** Initial or programmatic load. Shows full-screen [StateLayout] spinner. */
    Initial,

    /** Triggered by swipe-to-refresh. Shows [SwipeRefreshLayout] spinner, not full-screen. */
    Swipe,

    /** Triggered by the error-state retry button. Shows full-screen [StateLayout] spinner. */
    Retry
}

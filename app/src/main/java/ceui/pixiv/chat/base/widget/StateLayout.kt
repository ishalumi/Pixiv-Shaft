package ceui.pixiv.chat.base.widget

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.google.android.material.button.MaterialButton
import ceui.lisa.R
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.chat.base.PageState

/**
 * A container that manages four visual states: loading, content, error, and empty.
 */
class StateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var loadingView: View? = null
    private var errorView: View? = null
    private var emptyView: EmptyStateView? = null

    /** All children added via XML or addView (excluding internal state views). */
    private val contentViews = mutableListOf<View>()

    private var onRetryClick: (() -> Unit)? = null
    private var onEmptyActionClick: (() -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Capture all initial children as content.
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (isNotInternalView(child)) {
                contentViews.add(child)
            }
        }
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        super.addView(child, index, params)
        if (child != null && isNotInternalView(child)) {
            if (!contentViews.contains(child)) {
                contentViews.add(child)
            }
        }
    }

    private fun isNotInternalView(view: View): Boolean {
        return view != loadingView && view != errorView && view != emptyView
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun setState(state: PageState<*>) {
        when (state) {
            is PageState.Loading -> showLoading()
            is PageState.Content -> showContent()
            is PageState.Error -> showError(
                message = state.error.toUserMessage(context),
                showRetry = state.error.isRetryable,
                httpCode = state.error.httpCode
            )
            is PageState.Empty -> showEmpty(state.message)
        }
    }

    fun setOnRetryClickListener(listener: () -> Unit) {
        onRetryClick = listener
    }

    fun setOnEmptyActionClickListener(listener: () -> Unit) {
        onEmptyActionClick = listener
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun showLoading() {
        val view = ensureLoadingView()
        hideAll()
        view.visibility = View.VISIBLE
        
        // Start the spinner animation
        val imageView = view.findViewById<ImageView>(R.id.iv_loading_spinner)
        (imageView.drawable as? CircularProgressDrawable)?.start()
    }

    private fun showContent() {
        stopLoadingAnimation()
        loadingView?.visibility = View.GONE
        errorView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        setContentVisible(true)
    }

    private fun showError(message: String, showRetry: Boolean = true, httpCode: Int? = null) {
        stopLoadingAnimation()
        val view = ensureErrorView()
        hideAll()

        val codeView = view.findViewById<TextView>(R.id.tv_error_code)
        val iconView = view.findViewById<ImageView>(R.id.iv_error_icon)
        if (httpCode != null) {
            codeView.text = httpCode.toString()
            codeView.visibility = View.VISIBLE
            iconView.visibility = View.GONE
        } else {
            codeView.visibility = View.GONE
            iconView.visibility = View.VISIBLE
        }

        view.findViewById<TextView>(R.id.tv_error_message).text =
            message.ifEmpty { context.getString(R.string.chat_state_error_default) }
        view.findViewById<MaterialButton>(R.id.btn_retry).visibility =
            if (showRetry) View.VISIBLE else View.GONE
        view.visibility = View.VISIBLE
    }

    private fun showEmpty(message: String) {
        stopLoadingAnimation()
        val view = ensureEmptyView()
        hideAll()
        if (message.isNotEmpty()) {
            view.setTitle(message)
        }
        view.visibility = View.VISIBLE
    }

    private fun stopLoadingAnimation() {
        loadingView?.findViewById<ImageView>(R.id.iv_loading_spinner)?.let {
            (it.drawable as? CircularProgressDrawable)?.stop()
        }
    }

    private fun hideAll() {
        loadingView?.visibility = View.GONE
        errorView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        setContentVisible(false)
    }

    private fun setContentVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        contentViews.forEach { it.visibility = vis }
    }

    // ------------------------------------------------------------------
    // Lazy inflation
    // ------------------------------------------------------------------

    private fun ensureLoadingView(): View {
        if (loadingView == null) {
            loadingView = LayoutInflater.from(context).inflate(R.layout.chat_view_state_loading, this, false)
            
            val imageView = loadingView!!.findViewById<ImageView>(R.id.iv_loading_spinner)
            val progressDrawable = CircularProgressDrawable(context).apply {
                val color = com.google.android.material.color.MaterialColors.getColor(
                    this@StateLayout, 
                    com.google.android.material.R.attr.colorPrimary
                )
                setColorSchemeColors(color)
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 5f * resources.displayMetrics.density
                centerRadius = (14 * resources.displayMetrics.density) // centered within 40dp container
            }
            imageView.setImageDrawable(progressDrawable)
            
            addView(loadingView)
            loadingView?.visibility = View.GONE
        }
        return loadingView!!
    }

    private fun ensureErrorView(): View {
        if (errorView == null) {
            errorView = LayoutInflater.from(context).inflate(R.layout.chat_view_state_error, this, false)
            errorView?.findViewById<MaterialButton>(R.id.btn_retry)?.setOnClickListener {
                onRetryClick?.invoke()
            }
            addView(errorView)
            errorView?.visibility = View.GONE
        }
        return errorView!!
    }

    private fun ensureEmptyView(): EmptyStateView {
        if (emptyView == null) {
            emptyView = EmptyStateView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setTitle(context.getString(R.string.chat_state_empty_default))
                setOnActionClickListener { onEmptyActionClick?.invoke() }
            }
            addView(emptyView)
            emptyView?.visibility = View.GONE
        }
        return emptyView!!
    }
}

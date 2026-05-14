package ceui.pixiv.chat.base.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import ceui.lisa.R

/**
 * A reusable empty-state placeholder with icon, title, subtitle, and action button.
 *
 * XML attrs (from R.styleable.EmptyStateView):
 *   - es_icon: drawable resource for the icon
 *   - es_title: title text
 *   - es_subtitle: subtitle text
 *   - es_actionText: action button label (hidden when empty)
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val actionButton: MaterialButton

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        iconView = ImageView(context).apply {
            val size = (64 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
            alpha = 0.5f
        }
        addView(iconView)

        titleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            textSize = 18f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        addView(titleView)

        subtitleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (24 * resources.displayMetrics.density).toInt()
            }
            textSize = 14f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textAlignment = TEXT_ALIGNMENT_CENTER
        }
        addView(subtitleView)

        actionButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
        }
        addView(actionButton)

        context.obtainStyledAttributes(attrs, R.styleable.EmptyStateView).apply {
            getDrawable(R.styleable.EmptyStateView_es_icon)?.let { iconView.setImageDrawable(it) }
            getString(R.styleable.EmptyStateView_es_title)?.let { titleView.text = it }
            getString(R.styleable.EmptyStateView_es_subtitle)?.let { subtitleView.text = it }
            getString(R.styleable.EmptyStateView_es_actionText)?.let {
                actionButton.text = it
                actionButton.visibility = if (it.isNotEmpty()) View.VISIBLE else View.GONE
            }
            recycle()
        }
    }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setSubtitle(text: CharSequence) {
        subtitleView.text = text
    }

    fun setActionText(text: CharSequence) {
        actionButton.text = text
        actionButton.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun setOnActionClickListener(listener: OnClickListener?) {
        actionButton.setOnClickListener(listener)
    }
}

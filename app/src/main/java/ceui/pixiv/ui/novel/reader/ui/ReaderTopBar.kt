package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.LayoutReaderTopBarBinding

class ReaderTopBar(private val binding: LayoutReaderTopBarBinding) {

    val view: View get() = binding.root

    var onBackClick: (() -> Unit)? = null
    var onAnnotationsClick: (() -> Unit)? = null
    var onLikeClick: (() -> Unit)? = null
    var onLikeLongClick: (() -> Unit)? = null
    var onMarkerClick: (() -> Unit)? = null
    var onMoreClick: (() -> Unit)? = null

    init {
        binding.btnBack.setOnClickListener { onBackClick?.invoke() }
        binding.btnAnnotations.setOnClickListener { onAnnotationsClick?.invoke() }
        binding.btnLike.setOnClickListener { onLikeClick?.invoke() }
        binding.btnLike.setOnLongClickListener {
            val cb = onLikeLongClick ?: return@setOnLongClickListener false
            cb.invoke()
            true
        }
        binding.btnMarker.setOnClickListener { onMarkerClick?.invoke() }
        binding.btnMore.setOnClickListener { onMoreClick?.invoke() }
    }

    fun setTitle(title: String?) {
        binding.txtTitle.text = title.orEmpty()
    }

    /** pixiv 收藏（ブックマーク）状态 → 心形实心/描边。 */
    fun setLiked(liked: Boolean) {
        binding.btnLike.setImageResource(
            if (liked) R.drawable.ic_reader_heart_filled else R.drawable.ic_reader_heart_border,
        )
    }

    /** pixiv 原版书签（しおり/marker）状态 → 丝带实心/描边。issue #935。 */
    fun setMarked(marked: Boolean) {
        binding.btnMarker.setImageResource(
            if (marked) R.drawable.ic_reader_bookmark_filled else R.drawable.ic_reader_bookmark_border,
        )
    }

    /** 本地 txt 没有 pixiv 收藏/书签概念，两颗按钮直接隐藏。 */
    fun setPixivActionsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.btnLike.visibility = v
        binding.btnMarker.visibility = v
    }
}

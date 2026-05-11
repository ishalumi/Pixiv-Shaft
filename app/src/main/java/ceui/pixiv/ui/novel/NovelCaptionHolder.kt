package ceui.pixiv.ui.novel

import android.content.Intent
import android.net.Uri
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.utils.Common
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.extractPixivId
import ceui.pixiv.utils.setOnClick
import timber.log.Timber


class NovelCaptionHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelCaptionHolder::class)
class NovelCaptionViewHolder(bd: CellNovelCaptionBinding) : ListItemViewHolder<CellNovelCaptionBinding, NovelCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        binding.novel = liveNovel
        liveNovel.observe(lifecycleOwner) { novel ->
            val rawCaption = novel.caption.orEmpty()
            val hasCaption = rawCaption.isNotEmpty()
            // Pixiv 的 caption 里 `\n` 和 `<br>` 经常混用，HtmlCompat 只认后者，
            // 不转就会把几十段挤成一段（issue 里系列详情的投诉，普通详情页同源同修）。
            val normalizedCaption = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            if (hasCaption) {
                binding.caption.isVisible = true
                val linkHandler = CustomLinkMovementMethod { link ->
                    val info = extractPixivId(link)
                    when (info.type) {
                        "novels" -> info.value.toLongOrNull()?.let { id ->
                            binding.caption.findActionReceiverOrNull<NovelActionReceiver>()?.visitNovelById(id)
                        }
                        "illusts" -> info.value.toLongOrNull()?.let { id ->
                            binding.caption.findActionReceiverOrNull<IllustCardActionReceiver>()?.visitIllustById(id)
                        }
                        "users" -> info.value.toLongOrNull()?.let { id ->
                            binding.caption.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(id)
                        }
                        else -> {
                            // caption 来自用户输入，URLSpan 里可能藏 javascript: / intent:// / file:// 等
                            // 危险 scheme。这里限制为 http/https 走系统浏览器。
                            val uri = runCatching { Uri.parse(link) }.getOrNull()
                            val scheme = uri?.scheme?.lowercase()
                            if (uri != null && (scheme == "http" || scheme == "https")) {
                                runCatching {
                                    binding.caption.context.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri)
                                    )
                                }
                            }
                        }
                    }
                    Timber.d("caption link clicked: $info")
                }
                binding.caption.movementMethod = linkHandler
                binding.caption.text = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                // 任务 #5：点击简介正文复制纯文本。但如果点的是链接，要让链接自己处理，不能反过来复制。
                binding.caption.setOnClick {
                    if (linkHandler.wasLinkClicked) return@setOnClick
                    val plain = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                        .toString().trim()
                    Common.copy(context, plain)
                }
            } else {
                binding.caption.isVisible = false
            }
        }
    }
}

package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellPvisionCardBinding
import ceui.loxia.Article
import ceui.loxia.findActionReceiverOrNull

class PvisionCardHolder(val article: Article) : ListItemHolder() {

    override fun getItemId(): Long {
        return article.id
    }
}

@ItemHolder(PvisionCardHolder::class)
class PvisionCardViewHolder(bd: CellPvisionCardBinding) : ListItemViewHolder<CellPvisionCardBinding, PvisionCardHolder>(bd) {

    override fun onBindViewHolder(holder: PvisionCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<ArticleActionReceiver>()?.onClickArticle(holder.article)
        }
    }
}

interface ArticleActionReceiver {
    fun onClickArticle(article: Article)
}
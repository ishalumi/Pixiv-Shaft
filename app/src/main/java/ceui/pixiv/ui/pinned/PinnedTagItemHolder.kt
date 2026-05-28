package ceui.pixiv.ui.pinned

import android.content.Intent
import android.view.View
import ceui.lisa.activities.SearchActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.database.SearchEntity
import ceui.lisa.databinding.CellItemPinnedTagBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ImageUrls
import ceui.loxia.Tag
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import com.google.gson.Gson
import timber.log.Timber

/**
 * 「侧边栏 → 置顶标签」列表里一条 item 的 holder。
 *
 * 数据来源：[ceui.lisa.utils.PixivOperate.insertPinnedSearchHistory] 写入的 `search_table`
 * 行，里面 `previewIllustsJson` 是 `{tag, resp:{illusts:[…]}}` 这种 shape 的 JSON
 * （镜像 [ceui.pixiv.ui.prime.PrimeTagResult]，由 [ceui.pixiv.utils.buildPinnedTagPreviewJson]
 * 写入）。
 *
 * 字段命名 / 暴露形式刻意对齐 [ceui.pixiv.ui.prime.PrimeTagItemHolder] —— 这样 cell xml 就能
 * 一比一抄 `cell_item_prime_tag.xml` 的 binding 表达式。
 */
class PinnedTagItemHolder(val entity: SearchEntity) : ListItemHolder() {

    private val parsed: PinnedTagPreviewParsed? = parsePreview(entity.previewIllustsJson)

    /** 标签元信息 —— 优先从 `previewIllustsJson` 里那份完整 TagsBean 取（翻译名齐全），
     *  缺时退回到 `keyword` 字符串本身（旧数据 / 3 参 insertPinnedSearchHistory 路径）。*/
    val tag: Tag = Tag(
        name = parsed?.tagName ?: entity.keyword.orEmpty(),
        translated_name = parsed?.tagTranslated,
    )

    val illust0: Illust? = parsed?.previewUrls?.getOrNull(0)?.toPreviewIllust()
    val illust1: Illust? = parsed?.previewUrls?.getOrNull(1)?.toPreviewIllust()
    val illust2: Illust? = parsed?.previewUrls?.getOrNull(2)?.toPreviewIllust()

    val hasPreview: Boolean = listOfNotNull(illust0, illust1, illust2).isNotEmpty()

    /** translated_name 有内容时，name 才显示在副标题；否则上面那行已经是 name，副标题隐藏避免重复。*/
    val showSubtitle: Boolean = !parsed?.tagTranslated.isNullOrBlank() &&
        parsed?.tagTranslated != tag.name

    override fun getItemId(): Long = entity.id.toLong()
}

@ItemHolder(PinnedTagItemHolder::class)
class PinnedTagItemViewHolder(private val bd: CellItemPinnedTagBinding) :
    ListItemViewHolder<CellItemPinnedTagBinding, PinnedTagItemHolder>(bd) {

    override fun onBindViewHolder(holder: PinnedTagItemHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            // 标准跳法（见 V3TagFlowView.kt:179-184）：开 SearchActivity，
            // illust tab（index=0），keyword 就是 tag.name。
            // Tag.name 类型是 String?；运行时 VM 已经把空 keyword 的 entity 过滤掉，但编译期
            // 不知道这事，加一道防御兜底——免得未来 Tag/SearchEntity 字段改 nullability 时这里
            // 静悄悄传 null keyword 给 SearchActivity。
            val keyword = holder.tag.name?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            val ctx = it.context
            val intent = Intent(ctx, SearchActivity::class.java).apply {
                putExtra(Params.KEY_WORD, keyword)
                putExtra(Params.INDEX, 0)
            }
            ctx.startActivity(intent)
        }
    }
}

// ── previewIllustsJson 解析 ──

private data class PinnedTagPreviewParsed(
    val tagName: String?,
    val tagTranslated: String?,
    val previewUrls: List<String>,
)

private val gson by lazy { Gson() }

private fun parsePreview(json: String?): PinnedTagPreviewParsed? {
    if (json.isNullOrBlank()) return null
    return try {
        val root = gson.fromJson(json, PreviewRoot::class.java) ?: return null
        val urls = root.resp?.illusts.orEmpty().mapNotNull { it.image_urls?.square_medium }
        PinnedTagPreviewParsed(
            tagName = root.tag?.name,
            tagTranslated = root.tag?.translated_name,
            previewUrls = urls,
        )
    } catch (t: Throwable) {
        Timber.w(t, "Failed to parse pinned tag preview JSON")
        null
    }
}

/**
 * 只解我们关心的子集。直接 `IllustResponse` / `TagsBean` 也行，但那些类字段多、还可能跟 Room
 * 自动迁移产生不必要的耦合，保持本地 POJO 更稳。
 */
private data class PreviewRoot(val tag: PreviewTag?, val resp: PreviewResp?)
private data class PreviewTag(val name: String?, val translated_name: String?)
private data class PreviewResp(val illusts: List<IllustsBean>?)

private fun String.toPreviewIllust(): Illust =
    Illust(id = 0, image_urls = ImageUrls(square_medium = this))

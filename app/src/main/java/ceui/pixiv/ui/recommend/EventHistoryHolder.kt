package ceui.pixiv.ui.recommend

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.CellEventHistoryBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.models.UserBean
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import com.bumptech.glide.Glide
import com.google.gson.JsonObject
import timber.log.Timber

/**
 * 一行操作记录: 缩略图 + "动作 + 标题/用户名" + 副标题(类型 · 时间)。
 *
 * 服务端 item.meta 已经塞了原始 IllustsBean / NovelBean / UserBean,这里 lazy 反序列化
 * 一次,绑定时取 thumb/title/clickTarget。
 */
class EventHistoryHolder(val item: ShaftApiV2.EventHistoryItem) : ListItemHolder() {

    override fun getItemId(): Long = item.id

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        // event 是 append-only, id 相同就内容相同
        return other is EventHistoryHolder && other.item.id == item.id
    }

    // 解析一次缓存在 holder 上,避免每次 onBind 都 parse
    val parsed: ParsedTarget? by lazy { parseTarget(item.target_type, item.meta) }
}

data class ParsedTarget(
    val title: String,
    val thumbUrl: String?,
    val openIntent: (Context) -> Unit,
)

private fun parseTarget(targetType: String, meta: JsonObject?): ParsedTarget? {
    if (meta == null) return null
    val gson = Shaft.sGson
    return try {
        when (targetType) {
            "illust", "manga" -> {
                val bean = gson.fromJson(meta, IllustsBean::class.java) ?: return null
                ParsedTarget(
                    title = bean.title ?: "",
                    thumbUrl = bean.image_urls?.medium ?: bean.image_urls?.square_medium,
                    openIntent = { ctx ->
                        val page = PageData(arrayListOf(bean))
                        Container.get().addPageToMap(page)
                        ctx.startActivity(Intent(ctx, VActivity::class.java).apply {
                            putExtra(Params.POSITION, 0)
                            putExtra(Params.PAGE_UUID, page.getUUID())
                        })
                    }
                )
            }
            "novel" -> {
                val bean = gson.fromJson(meta, NovelBean::class.java) ?: return null
                ParsedTarget(
                    title = bean.title ?: "",
                    thumbUrl = bean.image_urls?.medium ?: bean.image_urls?.square_medium,
                    openIntent = { ctx ->
                        ctx.startActivity(Intent(ctx, TemplateActivity::class.java).apply {
                            putExtra(Params.CONTENT, bean)
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
                            putExtra("hideStatusBar", true)
                        })
                    }
                )
            }
            "user" -> {
                val bean = gson.fromJson(meta, UserBean::class.java) ?: return null
                ParsedTarget(
                    title = bean.name ?: "",
                    thumbUrl = bean.profile_image_urls?.medium,
                    openIntent = { ctx ->
                        ctx.startActivity(Intent(ctx, UActivity::class.java).apply {
                            putExtra(Params.USER_ID, bean.id)
                        })
                    }
                )
            }
            else -> null
        }
    } catch (e: Throwable) {
        Timber.tag("EventHistory").w(e, "parseTarget failed target_type=$targetType")
        null
    }
}

@ItemHolder(EventHistoryHolder::class)
class EventHistoryViewHolder(bd: CellEventHistoryBinding) :
    ListItemViewHolder<CellEventHistoryBinding, EventHistoryHolder>(bd) {

    override fun onBindViewHolder(holder: EventHistoryHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val ev = holder.item
        val parsed = holder.parsed

        val displayTitle = parsed?.title.orEmpty()
        val verb = verbLabel(context, ev.event_type)

        // "收藏了《XXX》" / "关注了 XXX" — 用户类型不加书名号,作品类型加
        binding.title.text = if (displayTitle.isEmpty()) verb
        else if (ev.target_type == "user") "$verb $displayTitle"
        else "$verb 《$displayTitle》"

        binding.subtitle.text = buildString {
            append(typeLabel(context, ev.target_type))
            append(" · ")
            append(formatRelative(ev.ts))
        }

        Glide.with(context)
            .load(GlideUtil.getUrl(parsed?.thumbUrl))
            .placeholder(R.color.v3_surface_2)
            .into(binding.thumb)

        binding.root.setOnClickListener {
            // 没有 meta 或 meta 损坏 → parsed=null,不响应点击 (服务端 retention 已过期把
            // illust_meta orphan 也清了,只剩 events 行)
            parsed?.openIntent?.invoke(context)
        }
        binding.root.isClickable = parsed != null
    }

    private fun verbLabel(ctx: Context, type: String): String = when (type) {
        "bookmark" -> ctx.getString(R.string.event_verb_bookmark)
        "unbookmark" -> ctx.getString(R.string.event_verb_unbookmark)
        "download" -> ctx.getString(R.string.event_verb_download)
        "follow" -> ctx.getString(R.string.event_verb_follow)
        "unfollow" -> ctx.getString(R.string.event_verb_unfollow)
        else -> type
    }

    private fun typeLabel(ctx: Context, type: String): String = when (type) {
        "illust" -> ctx.getString(R.string.type_illust)
        "manga" -> ctx.getString(R.string.type_manga)
        "novel" -> ctx.getString(R.string.type_novel)
        "user" -> ctx.getString(R.string.tab_user)
        else -> type
    }

    private fun formatRelative(ts: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
}

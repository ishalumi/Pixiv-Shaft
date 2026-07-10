package ceui.pixiv.feeds

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding

/**
 * 某一种条目类型的渲染器（delegate adapter 模式）。
 *
 * 数据（[FeedItem]）和展示（Renderer）彻底分离：
 * - 一个页面 = 一组 Renderer 注册进 [FeedAdapter]，异构列表天然支持；
 * - 类型安全靠泛型 + 构造时的 Class token 保证，没有反射、没有注解处理器；
 * - Renderer 应当无状态（所有状态在条目和 [FeedCell] 上），可以安全复用/共享。
 *
 * 简单场景用 [feedRenderer] DSL 一行注册，复杂场景（局部刷新、回收清理）继承本类。
 */
abstract class FeedRenderer<T : FeedItem, VB : ViewBinding>(
    internal val itemClass: Class<T>,
    internal val inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
) {

    /** 在 Grid / StaggeredGrid 中占满整行（section 头、footer 等）。 */
    open val fullSpan: Boolean
        get() = false

    /** GridLayoutManager 的跨度；默认整行或 1 格，需要 2/6 之类的自行覆盖。 */
    open fun spanSize(spanCount: Int): Int = if (fullSpan) spanCount else 1

    /** ViewHolder 创建时调用一次：注册点击监听（用 `cell.item` 取当下条目）、做一次性 View 配置。 */
    open fun onCreate(cell: FeedCell<T, VB>) {}

    /** 全量绑定。 */
    abstract fun onBind(cell: FeedCell<T, VB>)

    /**
     * 带 payload 的增量绑定，配合 [changePayload] 做局部刷新（如只更新点赞数、不动图片）。
     * 收到不认识的 payload 必须退回全量绑定——默认实现就是这么做的。
     */
    open fun onBind(cell: FeedCell<T, VB>, payloads: List<Any>) {
        onBind(cell)
    }

    /**
     * 同一条目内容变化时给出增量 payload；返回 null 走全量重绑
     * （框架保证全量重绑也复用同一个 ViewHolder，不会有 crossfade 闪烁）。
     */
    open fun changePayload(oldItem: T, newItem: T): Any? = null

    /** ViewHolder 被回收时调用：释放图片请求、动画等。 */
    open fun onRecycled(cell: FeedCell<T, VB>) {}
}

/**
 * 快速声明一个 Renderer：
 *
 * ```
 * feedRenderer<IllustFeedItem, CellXxxBinding>(
 *     inflate = CellXxxBinding::inflate,
 *     create = { cell -> cell.binding.root.setOnClickListener { open(cell.item) } },
 * ) { cell ->
 *     cell.binding.title.text = cell.item.illust.title
 * }
 * ```
 */
inline fun <reified T : FeedItem, VB : ViewBinding> feedRenderer(
    noinline inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
    fullSpan: Boolean = false,
    noinline create: ((FeedCell<T, VB>) -> Unit)? = null,
    noinline recycle: ((FeedCell<T, VB>) -> Unit)? = null,
    noinline bind: (FeedCell<T, VB>) -> Unit,
): FeedRenderer<T, VB> {
    val isFullSpan = fullSpan
    return object : FeedRenderer<T, VB>(T::class.java, inflate) {
        override val fullSpan: Boolean get() = isFullSpan

        override fun onCreate(cell: FeedCell<T, VB>) {
            create?.invoke(cell)
        }

        override fun onBind(cell: FeedCell<T, VB>) {
            bind(cell)
        }

        override fun onRecycled(cell: FeedCell<T, VB>) {
            recycle?.invoke(cell)
        }
    }
}

package ceui.pixiv.feeds

/**
 * 列表条目的最小数据契约。
 *
 * 实现方推荐用 immutable data class：
 * - [feedKey] 提供身份（identity），同一种条目内必须稳定且唯一，驱动 DiffUtil 的移动/增删判定；
 * - 内容比较（是否需要重绑）直接依赖 data class 的 equals()。
 *
 * 条目身份 = (具体类型, feedKey)，所以不同类型的条目允许使用相同的 key，互不干扰。
 */
interface FeedItem {

    /** 同类条目内全局唯一且稳定的身份，例如作品 id。 */
    val feedKey: Any
}

/** 条目完整身份：类型 + key，跨类型不会互相碰撞。 */
internal val FeedItem.identity: Any
    get() = Pair(javaClass, feedKey)

/** 按身份去重，保留首个出现的条目；DiffUtil 依赖身份唯一，重复 key 会导致 diff 结果错乱。 */
internal fun List<FeedItem>.dedupByIdentity(): List<FeedItem> {
    val seen = HashSet<Any>(size)
    return filter { seen.add(it.identity) }
}

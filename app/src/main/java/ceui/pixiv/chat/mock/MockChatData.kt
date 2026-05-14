package ceui.pixiv.chat.mock

/**
 * Realistic e-commerce chat mock data with time-aware separators.
 *
 * Two participants:
 *  - Buyer  (uid [BUYER_UID])
 *  - Seller (uid [SELLER_UID])
 *
 * ## Time separators
 *
 * A system message (type 11) with a formatted timestamp is
 * auto-inserted whenever two consecutive messages are **≥ 5 minutes**
 * apart, mirroring the standard rule used by WeChat / Telegram / etc.
 *
 * Call [getPage] with `null` for the first (newest) page, then pass
 * the returned `nextPageToken` for subsequent older pages. The final
 * page returns `nextPageToken = null`.
 *
 * Message ordering within each page is **newest-first** (descending
 * `createdTime`), matching the real API contract.
 */
object MockChatData {

    const val THREAD_ID = 2037166271773492509L
    const val BUYER_UID = 2041755844184444929L
    const val SELLER_UID = 2041755898123194368L

    private const val PAGE_SIZE = 30

    // Base snowflake-ish ID; increments per message.
    private const val BASE_ID = 2042420000000000000L

    // Start timestamp: 2026-04-08 10:00:00 UTC+8 (unix seconds).
    private const val BASE_TIME = 1775584800L

    // ── raw conversation (oldest → newest) ─────────────────────────────

    private val conversation: List<Raw> = ChatBuilder().apply {

        // ── Arc 1: Phone case inquiry & purchase (4月8日 10:00) ───────

        b("在吗")
        b("想看一下手机壳")
        s("在的亲！请问您用的是什么型号？")
        b("iPhone 16 Pro Max")
        s("好的，我们目前有这几款：\n1. 极光幻彩透明壳 ¥29.9\n2. 磨砂防指纹壳 ¥39.9\n3. 液态硅胶壳 ¥49.9\n都包邮哦")
        b("第一个有实物图吗")
        s("有的，稍等")
        s("刚拍的实物图，您看一下\n颜色会根据光线角度变化\n很好看的")
        b("确实挺好看")
        b("会不会发黄")
        s("不会的，我们用的是日本进口TPU材料\n抗黄变指数很高\n正常使用一年以上没问题")
        b("多少钱来着")
        s("29.9包邮")
        b("有点贵 别家才19.9")
        s("亲我们的和别家不一样的\n您可以看看评价区\n好评率99.2%\n而且我们是双层气囊防摔的")
        b("能便宜点吗")
        s("这个已经是活动价了\n利润很薄")
        b("25行不行")
        s("亲真的不行\n29.9已经是最低了\n不过我可以额外送您一张钢化膜")
        b("真的？")
        s("真的！下单备注「送膜」就行")
        b("那好吧")
        b("拍了")
        s("好的！收到订单了 马上安排")
        b("发什么快递")
        s("默认中通 您那边能到吗？")
        b("能")
        s("好嘞")

        // ── Arc 2: Shipping & receipt (4月10日 14:00) ─────────────────

        gap(hours = 52)
        b("几天能到")
        s("正常3-5天 江浙沪1-2天\n亲您在哪个城市？")
        b("杭州")
        s("那很快的 基本后天就到")
        b("ok")

        gap(hours = 20) // 次日
        b("到了 刚拆")
        s("感觉怎么样？")
        b("还行 不过好像没图片上透")
        s("亲 壳上有一层出厂保护膜\n撕掉就好了")
        b("卧槽真的有")
        b("撕了 好看多了")
        s("哈哈 满意的话帮忙五星好评呗")
        b("行")
        s("谢谢亲！")

        gap(minutes = 8)
        b("嗯嗯")
        b("贴膜在哪")
        s("在盒子里的夹层\n一个透明袋装着的\n您翻翻看")
        b("找到了")
        s("好的 需要其他的随时找我")
        b("好")

        // ── Arc 3: Charging cable purchase (4月12日 19:00) ────────────

        gap(hours = 45)
        b("你们卖充电线吗")
        s("有的！Type-C和Lightning都有")
        b("Type-C iPhone 16用的那种")
        s("好的 有1米和2米两种：\n1米 ¥19.9\n2米 ¥29.9\n都支持PD快充 最高100W")
        b("1米就行")
        b("是编织线还是普通的")
        s("编织线的 很耐用\n接头也是金属的不容易断")
        b("行 拍了")
        s("收到！")
        b("这个也送贴膜吗哈哈")
        s("亲你可真会占便宜")
        b("开玩笑的")
        s("哈哈 不过我可以给您优惠2块\n改价17.9")
        b("可以可以")
        s("好的 已改价 您刷新看一下")
        b("看到了 付了")
        s("收到 今天一起发出")

        // ── Arc 4: Earbuds inquiry (4月14日 11:00) ────────────────────

        gap(hours = 40)
        b("你们有蓝牙耳机吗")
        s("有的 请问您要入耳式还是半入耳的？")
        b("半入耳 不喜欢塞耳朵里")
        s("推荐这款「云感半入耳蓝牙5.4」\n降噪 / 长续航 / 低延迟\n原价199现在店庆价149")
        b("续航多久")
        s("耳机单次6小时\n加充电仓一共30小时\n日常通勤完全够用")
        b("有白色的吗")
        s("有白色和黑色两个颜色")
        b("音质怎么样 打游戏有延迟吗")
        s("游戏模式下延迟低于60ms\n音质的话13mm大动圈\n低音很饱满\n吃鸡听脚步声很清楚")
        b("我考虑一下")
        s("好的亲 不着急\n有问题随时来问")
        b("嗯")

        // ── Arc 5: Problem with order (4月16日 09:00) ─────────────────

        gap(hours = 46)
        b("在吗 充电线收到了")
        s("好的亲 用着怎么样？")
        b("线没问题 但是快递盒压扁了")
        s("啊 这样\n线本身有没有损坏？")
        b("线看着没事")
        b("充了一下能用")
        s("那就好\n快递暴力分拣有时候没办法\n如果后续有质量问题随时找我\n一年内免费换新")
        b("行 一年保修是吧")
        s("对的 凭订单号就行")
        b("好")
        b("对了 手机壳摔了一下")
        s("怎么摔的？壳没事吧？")
        b("从桌上掉地上\n壳没裂 手机也没事\n气囊确实有用")
        s("那就好！\n双层气囊就是为了日常防摔设计的\n1.5米跌落测试通过的")
        b("牛")
        s("哈哈 继续用")

        // ── Arc 5b: Small talk (4月16日 15:00) ───────────────────────

        gap(hours = 6)
        b("今天天气好热")
        s("是的 杭州这几天都30多度了")
        b("你们是哪里发货的")
        s("义乌仓库")
        b("义乌啊 难怪快递快")
        s("是的 江浙沪基本次日达")
        b("你们店开了多久了")
        s("快三年了 2023年开的")
        b("评分挺高的")
        s("谢谢 我们一直很注重品质和服务\n所以回购率很高")
        b("看得出来")
        b("你一个人客服吗")
        s("白天两个人 晚上就我一个")
        b("辛苦了")
        s("习惯了 谢谢关心")

        // ── Arc 6: Buying for friend (4月17日 20:00) ──────────────────

        gap(hours = 29)
        b("我朋友也想买手机壳")
        b("她的是iPhone 15 有吗")
        s("有的 同款也有15/15 Pro/15 Pro Max的")
        b("好 她要磨砂黑的")
        s("磨砂黑好评很多\n手感特别好 不沾指纹")
        b("可以寄到另一个地址吗")
        s("可以的 下单时写收货人和地址就行")
        b("好 她自己拍还是我帮她拍")
        s("都可以 您帮她拍也行\n写她的收货信息就好")
        b("行 帮她拍了")
        s("好的 收到！发哪个地址呢？")
        b("上海浦东新区张江高科技园区\n碧波路889号\n15812345678 王小花")
        s("好的 已备注\n明天发出")
        b("谢谢")
        s("不客气 有问题随时找我")

        // ── Arc 7: Return visit & earbuds purchase (4月18日 10:00) ────

        gap(hours = 14)
        b("想了一下 耳机也要了")
        s("好的！白色的对吧？")
        b("对 白色")
        s("149包邮 您直接拍就行")
        b("有没有优惠券")
        s("我看看\n有一张满100减10的店铺券\n领了直接用就行")
        b("领了 139是吧")
        s("对的！")
        b("买了")
        s("收到 跟朋友的手机壳一起发哈")
        b("不是 耳机寄我这里")
        s("哦好 那分开发\n手机壳发上海\n耳机发杭州对吧？")
        b("对")
        s("OK 都安排上了")
        b("大概什么时候到")
        s("杭州的明天到\n上海的后天")
        b("好")

        // ── Arc 8: Earbuds follow-up (4月20日 16:00) ─────────────────

        gap(hours = 54)
        b("耳机收到了")
        s("怎么样？")
        b("包装很精致 比我预期好")
        b("音质确实不错")
        s("开心！打游戏试了吗？")
        b("试了 确实听得清脚步")
        b("不过有个问题")
        s("怎么了？")
        b("右耳偶尔断连一下\n大概用了十几分钟会断一次")
        s("这个可能需要重新配对一下\n您试试这个方法：\n1. 两只耳机放回充电仓\n2. 长按充电仓按钮10秒\n3. 指示灯闪烁后重新连接")
        b("好 试试")

        gap(minutes = 6)
        b("重新配对了 目前正常")
        s("好的 如果后续还出现的话可能是固件问题\n我帮您联系厂家升级固件")
        b("行 先用着看看")
        s("好的")

        // ── Arc 9: Screen protector & bundle (4月21日 13:00) ──────────

        gap(hours = 21)
        b("你们有卖钢化膜吗 上次送的那个贴歪了")
        s("有的 单卖15一张 两张25")
        b("带贴膜神器吗")
        s("带的 有定位贴膜器\n对着卡位直接放上去就行\n手残党也能贴")
        b("哈哈好")
        b("那来两张")
        s("好的 两张25包邮")
        b("顺便问一下\n你们有那种手机支架吗\n就是放桌上看视频的那种")
        s("有的！铝合金可折叠款\n适配4-12寸设备\n39.9一个")
        b("感觉也不便宜")
        s("这个是全铝的亲\n塑料的确实便宜但是不稳\n一碰就倒")
        b("算了先不买这个 就贴膜吧")
        s("好的 只要贴膜两张对吧？")
        b("嗯")
        b("付了")
        s("收到！")

        // ── Arc 10: General chat (4月22日 21:00) ─────────────────────

        gap(hours = 32)
        b("我朋友说手机壳收到了\n她很喜欢")
        s("太好了！")
        b("她问有没有同款的AirPods保护壳")
        s("目前还没有\n不过下个月会上新\n到时候我通知您？")
        b("好的 麻烦了")
        s("不麻烦 我备注一下到时候给您发消息")
        b("你们店东西还挺靠谱的")
        b("以后手机配件就在你这买了")
        s("谢谢亲的信任！\n老顾客以后每次下单都给您优惠")
        b("好嘞")
        b("对了问一下\n你们有抖音号吗\n我想看看产品视频")
        s("有的 搜「好物严选数码」就能找到\n每周二四六晚上八点直播")
        b("关注了")
        s("谢谢 直播间偶尔会放专属优惠券")
        b("那我到时候蹲一下")
        s("欢迎来蹲！")

        gap(minutes = 7)
        b("你们双十一有活动吗")
        s("有的 到时候全店满减加上平台券\n基本所有商品都是历史最低价\n而且会出几款限量礼盒")
        b("离双十一还有半年呢哈哈")
        s("哈哈提前预告一下\n到时候我私聊提醒您")
        b("好")
        b("突然想起来\n我之前买的手机壳\n现在装了MagSafe磁吸环之后还能用无线充吗")
        s("可以的 TPU壳不影响无线充电\n只是充电速度可能比裸机慢一丢丢\n基本感觉不到差别")
        b("那就好")
        s("您用的15W那款的话完全没问题")
        b("好 放心了")
        b("下次再聊")
        s("好的 随时来")
        b("88")
        s("88~")

        // ── Arc 10c: Reviews (4月23日 18:00) ─────────────────────────

        gap(hours = 21)
        s("亲 方便的话帮几个订单都好评一下吧\n截图给我返2块红包")
        b("行 等下评")
        s("谢谢！")

        gap(hours = 2)
        b("贴膜到了 贴膜器好用")
        s("一次就贴好了吧？")
        b("对 这次没歪")
        b("完美")
        s("哈哈 以后都不用去店里贴了")
        b("确实 省了30块")

        // ── Arc 11: Earbuds firmware issue (4月25日 09:00) ────────────

        gap(hours = 37)
        b("耳机又断连了")
        s("啊 那确实可能是固件问题\n我帮您申请换新吧")
        b("不用换新吧 能升级固件就行")
        s("好的 我联系一下厂家\n他们有个APP可以OTA升级\n叫「SoundLink」")
        b("下了 然后呢")
        s("打开蓝牙连接耳机\n进APP点设备管理\n应该会提示固件更新")
        b("找到了 在升级")

        gap(minutes = 6)
        b("升级好了")
        s("试一下还会不会断")

        gap(minutes = 25)
        b("播了二十分钟 暂时没断")
        s("那应该是修复了\n后续有问题再找我")
        b("好")
        b("话说你们什么时候上新啊")
        s("下周会上一批新品\n有无线充电器和MagSafe配件")
        b("MagSafe卡包有吗")
        s("有的！下周上架\n预计59.9")
        b("不错 到时候通知我")
        s("一定！")

        // ── Arc 12: Wireless charger (4月27日 14:00) ─────────────────

        gap(hours = 53)
        b("新品上了吗")
        s("上了！无线充电器和MagSafe卡包都有了")
        b("发个链接看看")
        s("无线充电器两款：\n1. 15W桌面款 ¥79\n2. 3合1折叠款（手机+耳机+手表）¥199")
        b("3合1那个好贵")
        s("这个确实成本高\n里面有三组线圈\n支持苹果和安卓通用\n折叠起来出差很方便")
        b("就15W那个吧")
        s("好的 79包邮")
        b("买了")
        s("收到 明天发")
        b("MagSafe卡包多少钱")
        s("59.9 有黑色棕色海军蓝三个色")
        b("海军蓝好看")
        b("但我先不买了 这个月花太多了哈哈")
        s("哈哈没关系 随时来")
        b("你们以后会出iPad配件吗")
        s("有计划的 下季度应该会上iPad保护壳和触控笔")
        b("好 到时候通知我")
        s("没问题 记小本本了")
        b("哈哈好")

        // ── Arc 13: Delivery follow-up (4月29日 11:00) ───────────────

        gap(hours = 45)
        b("无线充到了")
        s("这么快！怎么样？")
        b("充电速度还行")
        b("就是充的时候手机不能偏太多\n偏了就断充")
        s("是的 这是无线充电的通病\n建议放上去之后看一眼是不是对准了\n有充电提示音就是OK的")
        b("明白了")
        s("如果经常偏的话\n可以贴一个定位贴\n我送您一个")
        b("有这种东西？")
        s("有的 就是一个小磁铁圈\n贴在手机背面\n放上去自动吸附对准")
        b("那给我来一个")
        s("好 下次下单一起寄 免费的")
        b("你们服务真好")
        s("应该的")

        // ── Arc 14: Random follow-ups (4月29日 17:00) ────────────────

        gap(hours = 6)
        b("你推荐的那个耳机APP升级之后\n续航好像变短了一点")
        s("升级后第一次充放电可能不准\n用两三次循环就正常了")
        b("哦好 那我再观察一下")
        s("嗯 如果用了几天还是明显短的话告诉我")
        b("好")
        b("话说你们有售后群吗")
        s("有的 我把二维码发您\n进群之后有新品优惠和抽奖")
        b("好 扫了")
        s("欢迎欢迎")
        b("群里好多人")
        s("老顾客都在里面\n每周五晚上会发红包")
        b("不错不错")
        b("行 先聊到这 我去忙了")
        s("好的亲 有事随时找我")
        b("嗯嗯")
        s("祝您工作顺利")
        b("谢谢 你也是")

        // ── Arc 15: Reviews & red packet (4月30日 10:00) ─────────────

        gap(hours = 17)
        b("我那三个好评都写了\n截图发你")
        s("收到！谢谢亲\n红包马上转给您")
        b("不急")
        s("已转 麻烦查收")
        b("收到 谢谢")
        s("应该谢您才对\n感谢信任和支持")
        b("客气了")

        gap(minutes = 12)
        b("对了 你们有没有数据线收纳包")
        s("暂时没有\n不过我可以帮您找找供应商\n有货了跟您说")
        b("好")
        b("那先这样")
        s("好的亲 有需要随时来")
        b("嗯 拜拜")
        s("拜拜 祝您生活愉快")

        gap(minutes = 8)
        b("等等 还有个事")
        s("请说")
        b("磁吸环哪天能寄")
        s("这两天就寄 您下次随便买个什么我塞一起")
        b("好的")

        // ── Arc 16: MagSafe card holder (5月2日 15:00) ───────────────

        gap(hours = 53)
        b("卡包还有海军蓝吗")
        s("有的 还有货")
        b("好 拍了")
        s("收到！今天给您发出\n磁吸环也一起塞里面")
        b("ok 用了优惠券 49.9")
        s("好的")

        gap(hours = 40) // 两天后到货
        b("卡包到了")
        s("怎么样？")
        b("颜色比图片上好看 磁吸力很强")
        b("放了三张卡 稳稳的")
        s("三张正好 太厚的话可能会翘起来")
        b("两三张刚刚好")
        s("磁吸环贴了吗")
        b("贴了 无线充再也不会偏了")
        s("完美！")

        // ── Arc 17: Recommend to colleague (5月5日 11:00) ────────────

        gap(hours = 20)
        b("又有个同事想买手机壳")
        b("他的是三星 S24 Ultra")
        s("三星的我们也有！")
        s("S24 Ultra 磨砂黑和极光透明都有")
        b("他要透明的")
        s("好的 29.9 跟iPhone的同价")
        b("发个链接给他 他自己拍")
        s("好 链接发您了")

        gap(hours = 3)
        b("他拍了 订单号 20260505xxxxx")
        s("收到 明天发出")
        b("ok 谢啦")
        s("不客气")

        // ── Arc 18: 618 preview (5月7日 20:00) ───────────────────────

        gap(hours = 30)
        b("618快到了 有活动吗")
        s("有的！活动力度很大")
        s("全店满99减15 满199减40")
        s("另外有5款特价商品直降50%")
        b("什么时候开始")
        s("6月1号零点开始 持续到6月18号")
        b("还有不到一个月")
        s("对 不过可以先加购物车\n活动开始自动算优惠")
        b("行 到时候一起囤")
        s("好的 我列个清单给您参考")
        b("行")
        s("1. 液态硅胶壳（49.9→24.9）\n2. 3合1无线充（199→149）\n3. 编织数据线2米（29.9→14.9）\n4. 蓝牙耳机（149→99）\n5. iPad保护壳（69.9→34.9）")
        b("iPad保护壳出了？")
        s("对 下周上架\n618的时候直接半价")
        b("那我618一起买")
        b("先把3合1无线充和iPad壳加购物车")
        s("好的 到时候提醒您")
        b("谢谢")
        s("不客气 等着您来薅羊毛")
        b("哈哈 一定来")
    }.build()

    // ── page generation ─────────────────────────────────────────────────

    /**
     * Return a single page of 30 messages plus pagination metadata.
     *
     * @param pageToken `null` for the first (newest) page; pass the
     *   returned `nextPageToken` for older pages.
     * @return a map matching the wire JSON shape; see top-level KDoc for
     *   structure.
     */
    fun getPage(pageToken: String?): Map<String, Any?> {
        // Pages are numbered 0..N-1 where 0 = newest.
        val pageIndex = pageToken?.toIntOrNull() ?: 0
        val totalPages = (conversation.size + PAGE_SIZE - 1) / PAGE_SIZE

        // Slice: page 0 = last 30 messages, page 1 = previous 30, etc.
        val newestGlobalIndex = conversation.size - 1 - pageIndex * PAGE_SIZE
        val oldestGlobalIndex = (newestGlobalIndex - PAGE_SIZE + 1).coerceAtLeast(0)
        if (newestGlobalIndex < 0) {
            return mapOf(
                "pagination" to mapOf("nextPageToken" to null),
                "list" to emptyList<Any>(),
            )
        }

        val slice = (newestGlobalIndex downTo oldestGlobalIndex).map { i ->
            conversation[i].toMap(globalIndex = i)
        }

        val nextToken = if (oldestGlobalIndex > 0) "${pageIndex + 1}" else null
        return mapOf(
            "pagination" to mapOf("nextPageToken" to nextToken),
            "list" to slice,
        )
    }

    /** All messages as a flat list (newest first). */
    fun allMessagesNewestFirst(): List<Map<String, Any?>> =
        conversation.indices.reversed().map { i -> conversation[i].toMap(i) }

    // ── internals ───────────────────────────────────────────────────────

    private data class Raw(
        val uid: Long,
        val type: Int,
        val content: String?,
        val time: Long,
    )

    private fun Raw.toMap(globalIndex: Int): Map<String, Any?> = buildMap {
        put("threadId", THREAD_ID)
        put("messageId", BASE_ID + globalIndex)
        put("uid", uid)
        put("createdTime", time)
        put("type", type)
        put("content", content)
        if (type != 11) put("asSummary", true)
    }

    // ── builder ──────────────────────────────────────────────────────────

    /**
     * Fluent builder for the conversation list. Tracks a running clock
     * and auto-inserts time-separator system messages when the gap
     * between two consecutive messages is **≥ 5 minutes**.
     */
    private class ChatBuilder {
        private val msgs = mutableListOf<Raw>()
        private var t = BASE_TIME
        private var lastEmitTime = 0L
        private val spacing = intArrayOf(20, 35, 15, 45, 25, 30, 40, 22, 38, 28)
        private var idx = 0

        /** Buyer message. */
        fun b(text: String) = msg(BUYER_UID, text)

        /** Seller message. */
        fun s(text: String) = msg(SELLER_UID, text)

        /** Jump the clock forward to simulate a gap between arcs. */
        fun gap(hours: Int = 0, minutes: Int = 0) {
            t += hours * 3600L + minutes * 60L
        }

        fun build(): List<Raw> = msgs.toList()

        private fun msg(uid: Long, text: String) {
            t += spacing[idx % spacing.size]
            idx++
            maybeTimeSep()
            msgs.add(Raw(uid, 1, text, t))
            lastEmitTime = t
        }

        /**
         * Insert a time separator if the gap since the last message
         * is ≥ 5 minutes, or if this is the first message.
         */
        private fun maybeTimeSep() {
            if (lastEmitTime == 0L || t - lastEmitTime >= 300) {
                // Use t-1 so the separator sorts just before the
                // following message in DESC order (Room query:
                // ORDER BY createdTime DESC, messageId DESC).
                msgs.add(Raw(0L, 11, formatTime(t), t - 1))
                lastEmitTime = t
            }
        }

        /** Format for mock data only. Handles April→May rollover;
         *  breaks if the conversation extends past May 31. */
        private fun formatTime(ts: Long): String {
            val midnight = BASE_TIME - 10 * 3600
            val total = ts - midnight
            val day = (total / 86400).toInt()
            val secInDay = (total % 86400).toInt()
            val h = secInDay / 3600
            val m = (secInDay % 3600) / 60
            val dayOfMonth = 8 + day
            val month = if (dayOfMonth > 30) 5 else 4
            val d = if (dayOfMonth > 30) dayOfMonth - 30 else dayOfMonth
            return "%d月%d日 %02d:%02d".format(month, d, h, m)
        }
    }
}

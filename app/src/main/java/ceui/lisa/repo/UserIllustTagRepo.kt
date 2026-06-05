package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ProfileImageUrlsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.loxia.UserTagIllust
import io.reactivex.Observable

/**
 * issue #569: 按 Tag 筛选某画师的插画作品。走网页 ajax /ajax/user/{id}/illusts/tag(app-api 无此能力),
 * offset 翻页;把精简的网页 work 对象映射成 IllustsBean 以复用现有瀑布流 adapter。
 *
 * 注:列表项点进详情 / 下载时,该精简 bean 缺分页图/原图,由详情页与下载链路的 isFullDetail 守卫
 * 回 v1/illust/detail 补全(见 ceui.loxia.fetchFullIllustDetail)。
 */
class UserIllustTagRepo(
    private val userID: Int,
    private val tag: String,
) : RemoteRepo<ListIllust>() {

    // 已加载条数,即下一页的 offset。
    private var loaded = 0

    override fun initApi(): Observable<ListIllust> {
        loaded = 0
        return fetch(0)
    }

    override fun initNextApi(): Observable<ListIllust> = fetch(loaded)

    private fun fetch(offset: Int): Observable<ListIllust> =
        Retro.getWebApi()
            .getUserIllustsByTag(userID.toLong(), tag, offset, PAGE_SIZE, "userSetting", "zh")
            .map { resp ->
                val body = resp.body
                val works = body?.works ?: emptyList()
                val total = body?.total ?: 0
                loaded = offset + works.size
                ListIllust().apply {
                    illusts = ArrayList(works.map { it.toIllustsBean() })
                    // next_url 仅作「还有下一页」的非空哨兵——实际翻页用 loaded(offset),
                    // NetListFragment 只看它是否为空来决定 footer / 是否触发 loadMore。
                    next_url = if (works.isNotEmpty() && loaded < total) "offset=$loaded" else null
                }
            }

    companion object {
        private const val PAGE_SIZE = 48
    }
}

// 网页方图缩略图路径形如 .../img-master/img/<日期>/<id>_pN_square1200.jpg,或画师自定义封面的
// .../custom-thumb/img/<日期>/<id>_pN_custom1200.jpg。两者底下都有同一张 img-master/_master1200,
// 故从日期路径+作品号重建标准尺寸 URL。编译一次复用(每页 48 项,别在 map 里反复 new Regex)。
private val IMG_PATH_REGEX = Regex("/img/(.+?)_(?:square|custom|master)1200\\.\\w+")

/**
 * 网页 work → IllustsBean。务必 setVisible(true),否则被 {@link ceui.lisa.core.Mapper} 当不可见整条过滤掉。
 * 图片走同一 i.pximg.net CDN:由方图 url 重建无裁切的 master1200(跟 app-api 同形)。
 */
internal fun UserTagIllust.toIllustsBean(): IllustsBean {
    val bean = IllustsBean()
    bean.id = id.toInt()
    bean.title = title ?: ""
    bean.isVisible = true
    bean.width = width
    bean.height = height
    bean.page_count = if (pageCount > 0) pageCount else 1
    bean.x_restrict = xRestrict
    bean.illust_ai_type = aiType
    bean.create_date = createDate
    bean.type = when (illustType) {
        1 -> "manga"
        2 -> "ugoira"
        else -> "illust"
    }

    val square = url ?: ""
    bean.image_urls = ImageUrlsBean().apply {
        val m = IMG_PATH_REGEX.find(square)
        if (m != null) {
            val rel = m.groupValues[1] // 2024/11/11/18/36/26/124200157_p0
            medium = "https://i.pximg.net/c/540x540_70/img-master/img/${rel}_master1200.jpg"
            large = "https://i.pximg.net/c/600x1200_90_webp/img-master/img/${rel}_master1200.jpg"
            square_medium = square.ifEmpty { medium }
        } else if (square.isNotEmpty()) {
            medium = square
            large = square
            square_medium = square
        }
    }

    bean.user = UserBean().apply {
        setId(userId.toInt())
        setName(userName ?: "")
        // 头像:列表已带,先填上,免得点进详情(回 API 补全前)那一下是空头像占位
        profileImageUrl?.takeIf { it.isNotEmpty() }?.let { avatar ->
            profile_image_urls = ProfileImageUrlsBean().apply {
                medium = avatar          // FragmentIllust / ArtistVH 读 profile_image_urls.medium
                setPx_170x170(avatar)
            }
        }
    }

    // tags 兜空,避免 getTagNames()/TagAdapter 等对 null 列表崩
    bean.tags = tags?.map { name -> TagsBean().apply { setName(name) } } ?: emptyList()

    return bean
}

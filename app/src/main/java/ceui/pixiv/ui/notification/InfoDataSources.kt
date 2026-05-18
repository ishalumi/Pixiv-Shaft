package ceui.pixiv.ui.notification

import ceui.loxia.CategorizedInfo
import ceui.loxia.Client
import ceui.loxia.InfoItem
import ceui.loxia.InfoLatestResponse
import ceui.loxia.InfoListResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListItemHolder

/**
 * /v1/info/latest 聚合页:每个 CategorizedInfo 摊成 [header + 4-5 个 entry]。
 * 服务端不分页(next_url 永远 null),DataSource 复用只是为了 refresh/error 流。
 */
class InfoLatestDataSource : DataSource<CategorizedInfo, InfoLatestResponse>(
    dataFetcher = { Client.appApi.getInfoLatest() },
    itemMapper = { category ->
        val holders = mutableListOf<ListItemHolder>()
        holders += InfoCategoryHeaderHolder(category, showMore = true)
        category.info_list.forEach { item ->
            holders += InfoEntryHolder(item)
        }
        holders
    },
)

/**
 * 单分类下钻列表:/v1/info/list?cid=N,真正分页。
 */
class InfoCategoryListDataSource(
    private val categoryId: Int,
) : DataSource<InfoItem, InfoListResponse>(
    dataFetcher = { Client.appApi.getInfoList(categoryId) },
    itemMapper = { item -> listOf(InfoEntryHolder(item)) },
)

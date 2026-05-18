package ceui.pixiv.ui.search

object SortType {
    const val POPULAR_PREVIEW = "popular_preview"
    const val DATE_DESC = "date_desc"
    const val DATE_ASC = "date_asc"
    const val POPULAR_DESC = "popular_desc"
    // 仅 illust/manga + 会员；非会员选了也会被 [shouldUsePopularPreview] 兜底走 popular-preview
    const val POPULAR_MALE_DESC = "popular_male_desc"
    const val POPULAR_FEMALE_DESC = "popular_female_desc"
    const val TRENDING_BUILTIN = "trending_builtin"
}

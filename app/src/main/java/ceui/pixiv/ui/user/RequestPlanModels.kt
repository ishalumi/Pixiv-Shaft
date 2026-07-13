package ceui.pixiv.ui.user

import ceui.loxia.User
import java.io.Serializable

/**
 * GET /v1/user/request-plans 响应。约稿方案是单页返回(无 next_url),
 * 所以不实现 KListShow —— 由 [RequestPlanFeedFragment] 用普通 FeedSource 一次性映射。
 */
data class UserRequestPlansResponse(
    val request_plans: List<RequestPlan>? = null,
    val user: User? = null,
    val user_profile: RequestPlanUserProfile? = null,
) : Serializable

data class RequestPlanUserProfile(
    val background_image_url: String? = null,
) : Serializable

/**
 * 单个约稿方案。价格 [standard_price] 单位为日元(如 40000 = ¥40,000)。
 * [ai_type] 沿用 pixiv 语义:1=非 AI,2=AI 生成。
 */
data class RequestPlan(
    val id: Long = 0L,
    val standard_price: Int = 0,
    val accept_flags: RequestPlanAcceptFlags? = null,
    val ai_type: Int = 0,
    val title: RequestPlanText? = null,
    val description: RequestPlanText? = null,
    val image_urls: RequestPlanImageUrls? = null,
) : Serializable

/** 该方案可接受的约稿类型 / 条件。 */
data class RequestPlanAcceptFlags(
    val adult: Boolean = false,
    val anonymous: Boolean = false,
    val illust: Boolean = false,
    val ugoira: Boolean = false,
    val manga: Boolean = false,
    val novel: Boolean = false,
) : Serializable

/** 服务端把标题 / 说明拆成原文 + 译文,优先展示 [translation]。 */
data class RequestPlanText(
    val original: String? = null,
    val original_lang: String? = null,
    val translation: String? = null,
) : Serializable {

    /** 优先译文,回退原文,都空则空串。 */
    fun display(): String = translation?.takeIf { it.isNotBlank() }
        ?: original?.takeIf { it.isNotBlank() }
        ?: ""
}

data class RequestPlanImageUrls(
    val cover: String? = null,
    val card: String? = null,
) : Serializable {

    /** 卡片列表用 card(800x400),缺失回退 cover。 */
    fun cardOrCover(): String? = card?.takeIf { it.isNotBlank() } ?: cover
}

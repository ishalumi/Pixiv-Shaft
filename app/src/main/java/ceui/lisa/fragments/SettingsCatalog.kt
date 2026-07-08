package ceui.lisa.fragments

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity

/**
 * 两级设置页的目录：分类定义 + 全量设置项索引（搜索用）。
 *
 * idName 是分类布局里那一行的 view id 名字，搜索命中后跳到分类页并滚动高亮该行；
 * 索引只收录用户可见的行（常驻隐藏的 r18/ai 分目录开关、lite 渠道隐藏的邮箱备份不进索引）。
 */
object SettingsCatalog {

    const val PAGE_CATEGORY = "设置分类"
    const val EXTRA_CATEGORY = "settings_category"
    const val EXTRA_HIGHLIGHT = "settings_highlight_id"

    class Category(
        val key: String,
        @StringRes val titleRes: Int,
        @DrawableRes val iconRes: Int,
    )

    class Entry(
        val category: Category,
        val idName: String,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int = 0,
    )

    @JvmField val ACCOUNT = Category("account", R.string.account, R.drawable.ic_setcat_person)
    @JvmField val NETWORK = Category("network", R.string.about_network, R.drawable.ic_setcat_globe)
    @JvmField val APPEARANCE = Category("appearance", R.string.string_376, R.drawable.ic_setcat_palette)
    @JvmField val BROWSING = Category("browsing", R.string.settings_cat_browse, R.drawable.ic_setcat_search)
    @JvmField val VIEWING = Category("viewing", R.string.settings_cat_viewing, R.drawable.ic_setcat_photo)
    @JvmField val BOOKMARKS = Category("bookmarks", R.string.settings_cat_bookmark, R.drawable.ic_setcat_heart)
    @JvmField val DOWNLOAD = Category("download", R.string.string_377, R.drawable.ic_setcat_download)
    @JvmField val AI = Category("ai", R.string.settings_cat_ai, R.drawable.ic_setcat_sparkle)
    @JvmField val DATA = Category("data", R.string.settings_cat_data, R.drawable.ic_setcat_backup)
    @JvmField val EXPERIMENTAL = Category("experimental", R.string.experimental_section, R.drawable.ic_setcat_science)

    @JvmField
    val categories = listOf(
        ACCOUNT, NETWORK, APPEARANCE, BROWSING, VIEWING,
        BOOKMARKS, DOWNLOAD, AI, DATA, EXPERIMENTAL,
    )

    val entries: List<Entry> = buildList {
        // 账号
        add(Entry(ACCOUNT, "user_manage", R.string.account_manage))
        add(Entry(ACCOUNT, "edit_account", R.string.string_91))
        if (!ceui.lisa.BuildConfig.IS_LITE) {
            add(Entry(ACCOUNT, "account_backup", R.string.email_backup_settings_entry))
        }
        add(Entry(ACCOUNT, "edit_file", R.string.string_92))
        add(Entry(ACCOUNT, "work_space", R.string.string_267))
        add(Entry(ACCOUNT, "r18_space", R.string.string_398))
        add(Entry(ACCOUNT, "premium_space", R.string.string_399))
        add(Entry(ACCOUNT, "login_out", R.string.login_out))

        // 网络
        add(Entry(NETWORK, "direct_connect_rela", R.string.open_direct_connection, R.string.see_pixiv_ez))
        add(Entry(NETWORK, "use_secure_dns_rela", R.string.use_secure_dns_title, R.string.use_secure_dns_summary))
        add(Entry(NETWORK, "image_host_rela", R.string.image_host_title))
        add(Entry(NETWORK, "show_large_thumbnail_image_rela", R.string.string_450, R.string.string_334))
        add(Entry(NETWORK, "show_original_preview_image_rela", R.string.string_413, R.string.string_334))

        // 界面
        add(Entry(APPEARANCE, "theme_mode_rela", R.string.theme_mode))
        add(Entry(APPEARANCE, "color_select_rela", R.string.string_324))
        add(Entry(APPEARANCE, "app_language_rela", R.string.language))
        add(Entry(APPEARANCE, "line_count_rela", R.string.string_336))
        add(Entry(APPEARANCE, "layout_mode_rela", R.string.layout_mode))
        add(Entry(APPEARANCE, "show_novel_card_tags_rela", R.string.show_novel_card_tags_setting))
        add(Entry(APPEARANCE, "navigation_init_position_rela", R.string.string_426))
        add(Entry(APPEARANCE, "bottom_bar_order_rela", R.string.string_342))
        add(Entry(APPEARANCE, "main_view_r18_rela", R.string.string_359))

        // 浏览与搜索
        add(Entry(BROWSING, "save_history_rela", R.string.save_view_history))
        add(Entry(BROWSING, "cloud_history_sync_rela", R.string.cloud_history_sync))
        add(Entry(BROWSING, "clear_cloud_history_rela", R.string.clear_cloud_history))
        add(Entry(BROWSING, "filter_comment_rela", R.string.string_379))
        add(Entry(BROWSING, "r18_filter_default_enable_rela", R.string.string_414, R.string.string_415))
        add(Entry(BROWSING, "delete_ai_illust_rela", R.string.delete_ai_illust))
        add(Entry(BROWSING, "filter_rank_bookmarked_rela", R.string.filter_rank_bookmarked))
        add(Entry(BROWSING, "delete_star_illust_rela", R.string.delete_star_illust))
        add(Entry(BROWSING, "filter_invalid_bookmarks_rela", R.string.filter_invalid_bookmarks))
        add(Entry(BROWSING, "search_filter_rela", R.string.search_result_filter))
        add(Entry(BROWSING, "search_default_sort_type_rela", R.string.string_439))
        add(Entry(BROWSING, "synonym_dict_enable_rela", R.string.synonym_dict_enable))
        add(Entry(BROWSING, "synonym_dict_rela", R.string.synonym_dict_title))

        // 看图与详情
        add(Entry(VIEWING, "illust_detail_v3_rela", R.string.illust_detail_v3))
        add(Entry(VIEWING, "artwork_v3_fab_order_rela", R.string.artwork_v3_fab_order_title))
        add(Entry(VIEWING, "transform_type_rela", R.string.string_393))
        add(Entry(VIEWING, "keep_status_bar_when_view_image_rela", R.string.keep_status_bar_when_view_image))
        add(Entry(VIEWING, "illust_detail_keep_screen_on_rela", R.string.string_451))
        add(Entry(VIEWING, "use_custom_double_tap_zoom_rela", R.string.use_custom_double_tap_zoom))
        add(Entry(VIEWING, "custom_zoom_scale_rela", R.string.custom_zoom_scale_title, R.string.custom_zoom_scale_link_text))
        add(Entry(VIEWING, "use_custom_three_level_zoom_rela", R.string.use_three_level_zoom_title, R.string.three_level_zoom_link_text))
        add(Entry(VIEWING, "use_custom_long_press_reset_rela", R.string.use_custom_long_press_reset, R.string.use_custom_long_press_reset_link_text))

        // 收藏与互动
        add(Entry(BOOKMARKS, "show_like_button_rela", R.string.string_335))
        add(Entry(BOOKMARKS, "hide_star_bar_rela", R.string.string_371))
        add(Entry(BOOKMARKS, "select_all_tag_rela", R.string.string_372))
        add(Entry(BOOKMARKS, "show_related_when_star_rela", R.string.string_396))
        add(Entry(BOOKMARKS, "auto_follow_after_star_rela", R.string.string_456))
        add(Entry(BOOKMARKS, "auto_download_after_star_rela", R.string.auto_download_after_star))
        add(Entry(BOOKMARKS, "download_auto_post_like_rela", R.string.string_409))

        // 下载
        add(Entry(DOWNLOAD, "storage_choice_rela", R.string.setting_storage_choice))
        add(Entry(DOWNLOAD, "file_name_rela", R.string.download_path_title, R.string.download_path_entry_desc))
        add(Entry(DOWNLOAD, "overwrite_policy_rela", R.string.setting_overwrite_policy))
        add(Entry(DOWNLOAD, "page_index_rela", R.string.setting_page_index))
        add(Entry(DOWNLOAD, "default_image_resolution_rela", R.string.setting_default_image_resolution))
        add(Entry(DOWNLOAD, "default_novel_format_rela", R.string.setting_default_novel_format))
        add(Entry(DOWNLOAD, "novel_header_rela", R.string.novel_header_settings_title, R.string.novel_header_settings_entry_desc))
        add(Entry(DOWNLOAD, "download_limit_type_rela", R.string.string_452))
        add(Entry(DOWNLOAD, "max_concurrent_downloads_rela", R.string.setting_max_concurrent_downloads))
        add(Entry(DOWNLOAD, "illust_long_press_download_rela", R.string.string_405))
        add(Entry(DOWNLOAD, "toast_download_result_rela", R.string.toast_download_result))
        add(Entry(DOWNLOAD, "aria2_rela", R.string.aria2_settings_title, R.string.aria2_settings_entry_desc))

        // AI 功能
        add(Entry(AI, "default_upscale_model_rela", R.string.string_default_upscale_model))
        add(Entry(AI, "default_rembg_model_rela", R.string.string_default_rembg_model))
        add(Entry(AI, "bubble_detector_model_rela", R.string.string_bubble_detector_model))
        add(Entry(AI, "ocr_model_rela", R.string.string_ocr_model))

        // 备份与缓存
        add(Entry(DATA, "backup_rela", R.string.string_420))
        add(Entry(DATA, "restore_rela", R.string.string_421))
        add(Entry(DATA, "moon_upload_rela", R.string.moon_upload_title))
        add(Entry(DATA, "moon_sync_rela", R.string.moon_manual_sync_title))
        add(Entry(DATA, "clear_image_cache", R.string.string_101))
        add(Entry(DATA, "clear_gif_cache", R.string.string_102))
        add(Entry(DATA, "clear_bulk_download_cache", R.string.clear_bulk_download_cache))

        // 试验性
        add(Entry(EXPERIMENTAL, "show_chat_room_entry_rela", R.string.setting_show_chat_room_entry, R.string.setting_chat_room_entry_warning))
        add(Entry(EXPERIMENTAL, "show_chat_room_push_banner_rela", R.string.setting_show_chat_room_push_banner))
        add(Entry(EXPERIMENTAL, "show_plaza_entry_rela", R.string.setting_show_plaza_entry))
        add(Entry(EXPERIMENTAL, "is_firebase_enable_rela", R.string.string_367))
    }

    fun entriesOf(category: Category): List<Entry> = entries.filter { it.category === category }

    fun search(context: Context, query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return entries.filter { entry ->
            context.getString(entry.titleRes).contains(q, ignoreCase = true) ||
                    (entry.descRes != 0 && context.getString(entry.descRes).contains(q, ignoreCase = true)) ||
                    context.getString(entry.category.titleRes).contains(q, ignoreCase = true)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun open(context: Context, category: Category, highlightIdName: String? = null) {
        val intent = Intent(context, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, PAGE_CATEGORY)
        intent.putExtra(EXTRA_CATEGORY, category.key)
        if (!highlightIdName.isNullOrEmpty()) {
            intent.putExtra(EXTRA_HIGHLIGHT, highlightIdName)
        }
        context.startActivity(intent)
    }

    @JvmStatic
    fun fragmentFor(categoryKey: String?): Fragment {
        return when (categoryKey) {
            ACCOUNT.key -> FragmentSettingsAccount()
            NETWORK.key -> FragmentSettingsNetwork()
            APPEARANCE.key -> FragmentSettingsAppearance()
            BROWSING.key -> FragmentSettingsBrowsing()
            VIEWING.key -> FragmentSettingsViewing()
            BOOKMARKS.key -> FragmentSettingsBookmarks()
            DOWNLOAD.key -> FragmentSettingsDownload()
            AI.key -> FragmentSettingsAi()
            DATA.key -> FragmentSettingsData()
            EXPERIMENTAL.key -> FragmentSettingsExperimental()
            else -> FragmentSettingsHub()
        }
    }

    /**
     * 搜索跳转的落点高亮：分类页打开时若 intent 带 EXTRA_HIGHLIGHT，
     * 滚动到那一行并用主题色 foreground 闪一下。
     */
    @JvmStatic
    fun maybeHighlight(fragment: Fragment, root: View) {
        val idName = fragment.activity?.intent?.getStringExtra(EXTRA_HIGHLIGHT) ?: return
        val viewId = root.resources.getIdentifier(idName, "id", root.context.packageName)
        if (viewId == 0) return
        val target = root.findViewById<View>(viewId) ?: return
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view) ?: return
        root.post {
            // 条件隐藏的行（如 V3 关闭时的 FAB 顺序、词典关闭时的管理入口）此刻是 GONE：
            // 没有布局位置，滚动/闪烁都是错的，直接正常打开页面即可。
            if (!target.isShown || target.height == 0) {
                return@post
            }
            var y = 0
            var v: View = target
            while (v !== scroll) {
                y += v.top
                v = (v.parent as? View) ?: break
            }
            scroll.smoothScrollTo(0, (y - scroll.height / 4).coerceAtLeast(0))
            flash(target)
        }
    }

    private fun flash(target: View) {
        val tv = TypedValue()
        target.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
        val overlay = ColorDrawable(ColorUtils.setAlphaComponent(tv.data, 255))
        overlay.alpha = 0
        target.foreground = overlay
        ValueAnimator.ofInt(0, 60, 60, 0).apply {
            duration = 1800
            startDelay = 250
            addUpdateListener {
                overlay.alpha = it.animatedValue as Int
                target.invalidate()
            }
            start()
        }
    }
}

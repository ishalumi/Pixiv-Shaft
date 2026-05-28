package ceui.pixiv.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings

/**
 * 启动时做三件事：
 * 1. 把旧 [Settings.appLanguage] 字段迁到 [AppLocales.saveTag] + AppCompat per-app locale，清空旧字段；
 * 2. 首次安装或从未选过语言时，按系统 locale 做合理 fallback（[AppLocales.ensureInitialized]）；
 * 3. **关键**：把 MMKV 持久化的 tag 同步给 AppCompat。这一步是支撑「onboarding 原地切语言不 recreate」
 *    那条路径的关键 —— [ceui.lisa.fragments.FragmentLogin.transitionToLogin] 只写 MMKV，不调
 *    `setApplicationLocales`，等到进程下次冷启 Application.onCreate 时再补一发，此时没有 Activity
 *    在前台，AppCompat 的 lifecycle callback 不会触发 recreate。
 */
object AppLocalesBootstrap {

    /** 旧的显示名 → BCP-47 tag。显示名取自已删除的 `Settings.ALL_LANGUAGE`。 */
    private val legacyNameToTag: Map<String, String> = mapOf(
        "简体中文" to "zh-CN",
        "日本語" to "ja",
        "English" to "en",
        "繁體中文" to "zh-TW",
        "русский" to "ru",
        "한국어" to "ko",
    )

    fun bootstrap(settings: Settings) {
        migrateLegacyField(settings)
        AppLocales.ensureInitialized()
        syncAppCompatFromSavedTag()
    }

    @Suppress("DEPRECATION")
    private fun migrateLegacyField(settings: Settings) {
        val legacy = settings.appLanguage
        if (legacy.isNullOrBlank()) return
        val tag = legacyNameToTag[legacy]
        val alreadySet = !AppCompatDelegate.getApplicationLocales().isEmpty
        if (!alreadySet && tag != null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
        // 不管有没有成功迁移，都标记"已配置过"，防止 ensureInitialized 再插手；
        // 同时把 tag 写到 MMKV，让 BaseActivity.attachBaseContext 也能用上。
        if (tag != null) AppLocales.saveTag(tag) else AppLocales.markUserConfigured()
        settings.appLanguage = ""
        Local.setSettings(settings)
    }

    /**
     * 如果用户曾经走 onboarding 选过语言（MMKV 有 tag）但 AppCompat 还没被告知（比如该次 onboarding
     * 走的是「不 recreate」路径），此时 Application.onCreate 期间没有 Activity，set 一下是安全的。
     */
    private fun syncAppCompatFromSavedTag() {
        val tag = AppLocales.savedTag() ?: return
        val expected = LocaleListCompat.forLanguageTags(tag)
        val current = AppCompatDelegate.getApplicationLocales()
        if (expected.toLanguageTags() != current.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(expected)
        }
    }
}

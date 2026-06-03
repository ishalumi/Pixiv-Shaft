package ceui.lisa.helper;

import java.util.Locale;

import ceui.pixiv.i18n.AppLocales;

/**
 * 把当前 app locale 转成 Pixiv API 的 `accept-language` 值。
 * Pixiv 用下划线形式（`zh_CN` / `zh_TW`），其它标准 ISO-639-1 下划线单段。
 */
public final class LanguageHelper {

    private LanguageHelper() {}

    public static String getRequestHeaderAcceptLanguageFromAppLanguage() {
        Locale locale = AppLocales.INSTANCE.currentLocale();
        String language = locale.getLanguage();
        if ("zh".equals(language)) {
            String country = locale.getCountry();
            if (isTraditionalChineseRegion(country)) {
                return "zh_TW";
            }
            return "zh_CN";
        }
        return language.isEmpty() ? "en" : language;
    }

    /**
     * 把当前 app locale 转成 Pixiv API 的 `app-accept-language` 值。
     * iOS 客户端用小写连字符形式（`zh-hans` / `zh-hant`），其它语言 ISO-639-1 单段。
     * 服务端按这个 header 本地化标签翻译等内容。
     */
    public static String getRequestHeaderAppAcceptLanguageFromAppLanguage() {
        Locale locale = AppLocales.INSTANCE.currentLocale();
        String language = locale.getLanguage();
        if ("zh".equals(language)) {
            if (isTraditionalChineseRegion(locale.getCountry())) {
                return "zh-hant";
            }
            return "zh-hans";
        }
        return language.isEmpty() ? "en" : language;
    }

    private static boolean isTraditionalChineseRegion(String country) {
        return "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country);
    }
}

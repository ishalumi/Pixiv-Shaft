package ceui.lisa.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Generates a full V3 dark-theme color palette from the current theme's colorPrimary.
 * All accent-derived colors are computed at runtime so every theme index "just works".
 *
 * Usage:
 *   val p = V3Palette.from(context)
 *   followBtn.background = p.pillPrimary(...)
 *   tagCount.setTextColor(p.textAccent)
 */
class V3Palette(@ColorInt val primary: Int, val isDark: Boolean = true) {

    // ── derived alphas ──────────────────────────────────────────────

    /** 8 % — tag locked background tint */
    @ColorInt val alpha08: Int = withAlpha(primary, 0.08f)

    /** 10 % — very subtle tint (tag count badge, shimmer) */
    @ColorInt val alpha10: Int = withAlpha(primary, 0.10f)

    /** 15 % — tag locked border, slight surfaces */
    @ColorInt val alpha15: Int = withAlpha(primary, 0.15f)

    /** 20 % — secondary button / chip fill */
    @ColorInt val alpha20: Int = withAlpha(primary, 0.20f)

    /** 30 % — secondary button stroke */
    @ColorInt val alpha30: Int = withAlpha(primary, 0.30f)

    /** 50 % — accent line, medium emphasis */
    @ColorInt val alpha50: Int = withAlpha(primary, 0.50f)

    /** 60 % — artist banner overlay */
    @ColorInt val alpha60: Int = withAlpha(primary, 0.60f)

    // ── text colors ─────────────────────────────────────────────────

    /** Primary accent text — adjusted for background readability */
    @ColorInt val textAccent: Int = if (isDark) ensureLightEnough(primary, 0.60f)
        else ensureDarkEnough(primary, 0.40f)

    /** Variant for secondary button label */
    @ColorInt val textSecondary: Int = if (isDark)
        withAlpha(ensureLightEnough(primary, 0.72f), 0.90f)
    else withAlpha(ensureDarkEnough(primary, 0.35f), 0.90f)

    /** Tag locked text */
    @ColorInt val textTag: Int = if (isDark) ensureLightEnough(primary, 0.70f)
        else ensureDarkEnough(primary, 0.38f)

    /** Series label text */
    @ColorInt val textSeries: Int = if (isDark)
        withAlpha(ensureLightEnough(primary, 0.68f), 0.70f)
    else withAlpha(ensureDarkEnough(primary, 0.35f), 0.70f)

    /**
     * Series strip 正文文字(系列名/label/chevron 共用) —— 深色模式白字压在暗靛蓝渐变条上;
     * 浅色模式条底被 [seriesStripBg] tint 成浅粉,白字会糊,改主题色压深(L≤0.30)保证可读。
     * label 靠 XML 里 0.7 view alpha 再降一档灰度,不必单独配色。
     */
    @ColorInt val seriesStripText: Int = if (isDark) 0xFFFFFFFF.toInt()
        else ensureDarkEnough(primary, 0.30f)

    // ── scroll progress gradient ────────────────────────────────────

    /** Scroll progress bar: primary → shifted hue → gold */
    @ColorInt val scrollProgressStart: Int = primary
    @ColorInt val scrollProgressMid: Int = hueShift(primary, 40f)
    @ColorInt val scrollProgressEnd: Int = 0xFFFFC233.toInt()

    // ── drawable factories ──────────────────────────────────────────

    /** Solid pill — follow button */
    fun pillPrimary(radiusPx: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(primary)
        }

    /** Semi-transparent pill with stroke — unfollow / secondary button */
    fun pillSecondary(radiusPx: Float = 999f, strokePx: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(alpha20)
            setStroke(strokePx, alpha30)
        }

    /** Tag count badge background */
    fun tagCountBg(radiusPx: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(alpha10)
        }

    /** Tag locked background (author tags) */
    fun tagLockedBg(radiusPx: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(alpha08)
            setStroke(1, alpha15)
        }

    /** Accent line (horizontal gradient: transparent → accent → accent → transparent) */
    fun accentLine(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00000000, alpha50, hueShift(alpha50, 30f), 0x00000000)
        )

    /** Banner placeholder — ambient gradient matching theme color */
    fun bannerPlaceholder(): GradientDrawable {
        val base = desaturate(primary, 0.85f)
        return GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(
                if (isDark) darken(base, 0.15f) else lighten(base, 0.85f),
                if (isDark) darken(hueShift(base, 25f), 0.12f) else lighten(hueShift(base, 25f), 0.88f),
                if (isDark) darken(hueShift(base, -15f), 0.17f) else lighten(hueShift(base, -15f), 0.83f)
            )
        )
    }

    /** Artist banner overlay gradient */
    fun artistBannerBg(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(alpha60, withAlpha(hueShift(primary, 30f), 0.50f))
        )

    /** Series strip gradient background */
    fun seriesStripBg(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(
                withAlpha(primary, 0.35f),
                withAlpha(hueShift(primary, 25f), 0.30f)
            )
        ).apply {
            cornerRadius = radiusPx
            setStroke(1, alpha15)
        }

    /** Series icon square background */
    fun seriesIconBg(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(primary, hueShift(primary, 40f))
        ).apply {
            cornerRadius = radiusPx
        }

    /** Detail panel / glass card background */
    fun glassCardBg(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                withAlpha(desaturate(primary, 0.4f), 0.45f),
                withAlpha(desaturate(primary, 0.25f), 0.35f)
            )
        ).apply {
            cornerRadius = radiusPx
            setStroke(1, 0x0FFFFFFF)
        }

    /**
     * Settings-card 底色 —— 隐约带一点主题色，专用作背景（绝不用主题色正色）。
     * 深色：把 primary 大幅去饱和后压到接近 sheet 底的暗度，得到一块"带主题色调的暗底"；
     * 浅色：去饱和后提到极浅，得到一块"带主题色调的白底"。外加一条 12% 主题色 hairline，
     * 替代静态 [ceui.lisa.R.drawable.bg_v3_settings_card]（固定中性 v3_menu_bg，切主题色不动）。
     */
    /**
     * Settings-card / 悬浮胶囊的不透明底色 —— 隐约带主题色（日夜双模），见 [settingsCardBg]。
     * tint 强度刻意压得很低（饱和度只保留一小截）：能看出"和主题色有关系"即可，
     * 不能一眼读出主题色本身（樱桃粉夜间此前 42% 饱和度算出 #32151C，太粉，被打回）。
     */
    @ColorInt val cardFill: Int = if (isDark) darken(desaturate(primary, 0.16f), 0.135f)
    else lighten(desaturate(primary, 0.50f), 0.96f)

    /** 与 [cardFill] 配套的 12% 主题色 hairline。 */
    @ColorInt val cardHairline: Int = if (isDark) withAlpha(ensureLightEnough(primary, 0.60f), 0.12f)
    else withAlpha(ensureDarkEnough(primary, 0.40f), 0.12f)

    fun settingsCardBg(radiusPx: Float, strokePx: Int): GradientDrawable {
        val hairline = cardHairline
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(cardFill)
            if (strokePx > 0) setStroke(strokePx, hairline)
        }
    }

    /**
     * 悬浮胶囊底色（fab bar / glass pill）：[cardFill] 同款主题 tint 加透明，悬浮在内容上，
     * 替代固定的 #CC1A1A2E。默认 80% 不透明（原 fab bar 的 0xCC）。
     */
    fun floatingPillBg(radiusPx: Float, alpha: Float = 0.80f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(withAlpha(cardFill, alpha))
        }

    /**
     * [floatingPillBg] 胶囊上的前景（图标/分隔线/进度环）—— 胶囊底是 [cardFill] tint：
     * 深色模式近黑靛蓝，保持纯白；浅色模式底是"带主题色调的白"，白图标会隐形，
     * 压深主题色（同 [seriesStripText] 的日夜策略）。
     */
    @ColorInt val floatingPillContent: Int = if (isDark) 0xFFFFFFFF.toInt()
    else ensureDarkEnough(primary, 0.40f)

    // ── convenience ─────────────────────────────────────────────────

    /** Apply accent-colored follow button drawable */
    fun applyFollowBtn(btn: View) {
        btn.background = pillPrimary(999f * btn.resources.displayMetrics.density)
    }

    /** Apply accent-colored unfollow button drawable + text */
    fun applyUnfollowBtn(btn: TextView) {
        val d = btn.resources.displayMetrics.density
        btn.background = pillSecondary(999f * d, (1 * d).toInt())
        btn.setTextColor(textSecondary)
    }

    // ── companion ───────────────────────────────────────────────────
    companion object {

        /** Resolve the palette from the current theme's colorPrimary */
        @JvmStatic
        fun from(context: Context): V3Palette {
            val primary = Common.resolveThemeAttribute(
                context, androidx.appcompat.R.attr.colorPrimary
            )
            val nightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            return V3Palette(primary, isDark)
        }

        @ColorInt
        fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
            ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

        @ColorInt
        private fun ensureLightEnough(@ColorInt color: Int, minL: Float = 0.60f): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[2] < minL) hsl[2] = minL
            return ColorUtils.HSLToColor(hsl)
        }

        /** For light mode — darken a color so it's readable on white backgrounds */
        @ColorInt
        private fun ensureDarkEnough(@ColorInt color: Int, maxL: Float = 0.40f): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[2] > maxL) hsl[2] = maxL
            return ColorUtils.HSLToColor(hsl)
        }

        /** Shift hue by [degrees] while keeping saturation and lightness */
        @ColorInt
        private fun hueShift(@ColorInt color: Int, degrees: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[0] = (hsl[0] + degrees) % 360f
            val shifted = ColorUtils.HSLToColor(hsl)
            // preserve original alpha
            return ColorUtils.setAlphaComponent(shifted, (color ushr 24) and 0xFF)
        }

        /** Set lightness to a specific value */
        @ColorInt
        private fun darken(@ColorInt color: Int, lightness: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = lightness
            return ColorUtils.HSLToColor(hsl)
        }

        @ColorInt
        private fun lighten(@ColorInt color: Int, lightness: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = lightness
            return ColorUtils.HSLToColor(hsl)
        }

        /** Reduce saturation towards gray */
        @ColorInt
        private fun desaturate(@ColorInt color: Int, factor: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] *= factor
            return ColorUtils.HSLToColor(hsl)
        }
    }
}

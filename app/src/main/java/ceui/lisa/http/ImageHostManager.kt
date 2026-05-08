package ceui.lisa.http

/**
 * Image host abstraction. Foundation for issue #865 (pixivcat 可否加回来).
 *
 * NOT WIRED YET. This object is built and parked: nothing in the existing
 * image-load / download paths calls into it. Wiring plan when the foundation
 * is ready to apply:
 *
 *   1. GlideUrlChild ctor          → wrap incoming url with [rewrite]
 *   2. IllustDownload.getUrl/      → wrap with [rewrite] so downloads follow
 *      getShowUrl                    the same host as on-screen images
 *   3. Shaft.onCreate              → load mode + customHost from Settings into
 *                                     this object once at startup; subsequent
 *                                     setMode/setCustomHost updates from the
 *                                     settings UI write through to here too
 *   4. Shaft.onCreate (OkHttp)     → gate the directConnect SSL/DNS bypass on
 *                                     [requiresStandardClient]; non-PIXIV modes
 *                                     must use system DNS + standard TLS
 *                                     (the hardcoded 210.140.139.x IPs in
 *                                     HttpDns and the SNI-skipping factory
 *                                     would break pixiv.cat / arbitrary proxies)
 *   5. network_security_config.xml → whitelist pixiv.cat and the custom host
 *   6. SettingsFragment / strings  → UI for picking mode + entering custom URL
 *   7. Settings.java               → migrate the dead `usePixivCat` boolean
 *                                     into `imageHostMode` (int) and add
 *                                     `customImageHost` (string)
 *
 * Defaults chosen during foundation (revisit before wiring):
 *
 *   - CUSTOM accepts a full URL prefix: "https://your.proxy[/optional/path]".
 *     Trailing slash is stripped on set. The original "https://i.pximg.net"
 *     prefix is replaced wholesale; the remaining path/query is preserved.
 *     This lets a user point at a proxy mounted under a sub-path.
 *
 *   - PIXIV_CAT maps both i.pximg.net → i.pixiv.cat and s.pximg.net →
 *     s.pixiv.cat. pixiv.cat reverse-proxies both subdomains, so the s.* URLs
 *     used for placeholder/profile images (Params.IMAGE_UNKNOWN,
 *     Params.HEAD_UNKNOWN, GlideUtil.DEFAULT_HEAD_IMAGE,
 *     UserFollowingFragment.NO_PROFILE_IMG) ride along.
 *
 *   - State lives in memory only. Settings persistence is intentionally not
 *     touched at the foundation stage — Shaft.onCreate will hydrate this
 *     object from Settings once wiring begins.
 */
object ImageHostManager {

    enum class Mode { PIXIV, PIXIV_CAT, CUSTOM }

    private const val PXIMG_I = "i.pximg.net"
    private const val PXIMG_S = "s.pximg.net"
    private const val PIXIV_CAT_I = "i.pixiv.cat"
    private const val PIXIV_CAT_S = "s.pixiv.cat"

    @Volatile private var mode: Mode = Mode.PIXIV
    @Volatile private var customHost: String = ""

    fun getMode(): Mode = mode
    fun setMode(value: Mode) { mode = value }

    fun getCustomHost(): String = customHost
    fun setCustomHost(value: String) { customHost = value.trim().trimEnd('/') }

    /**
     * True when the active host requires standard system DNS + TLS-with-SNI.
     * The OkHttp builder in Shaft.onCreate must consult this before installing
     * the directConnect DNS / SNI-skip / cert-bypass overrides — those are
     * pinned to Pixiv's CDN IPs and would break any other host.
     */
    fun requiresStandardClient(): Boolean = mode != Mode.PIXIV

    /**
     * Rewrites a Pixiv image URL to the currently selected host. Returns the
     * input unchanged when:
     *   - the URL is empty or not parseable as scheme://host[:port][/...]
     *   - the host is not a recognized Pixiv image domain
     *   - mode is PIXIV
     *   - mode is CUSTOM but no customHost is configured
     */
    fun rewrite(url: String): String {
        if (url.isEmpty()) return url
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart).let { if (it < 0) url.length else it }
        if (pathStart <= hostStart) return url

        val hostAndPort = url.substring(hostStart, pathStart)
        val host = hostAndPort.substringBefore(':')
        if (host != PXIMG_I && host != PXIMG_S) return url

        return when (mode) {
            Mode.PIXIV -> url
            Mode.PIXIV_CAT -> {
                val mapped = if (host == PXIMG_I) PIXIV_CAT_I else PIXIV_CAT_S
                url.substring(0, hostStart) + mapped + url.substring(pathStart)
            }
            Mode.CUSTOM -> {
                if (customHost.isEmpty()) url
                else customHost + url.substring(pathStart)
            }
        }
    }
}

package ceui.pixiv.ui.slideshow

import android.content.Context
import android.content.Intent
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import timber.log.Timber

object SlideshowLauncher {

    /**
     * Launch the slideshow from a V2 list of [IllustsBean]. Single-page illusts contribute their
     * one image; multi-page illusts contribute every page in order. The slideshow plays at LARGE
     * resolution to balance quality and OOM risk, falling back to the user's preview setting.
     */
    @JvmStatic
    @JvmOverloads
    fun launchFromIllustsBeans(
        context: Context,
        list: List<IllustsBean>,
        startListIndex: Int,
        random: Boolean = true,
    ) {
        val urls = ArrayList<String>(list.size)
        val titles = ArrayList<String>(list.size)
        var startUrlIndex = 0
        var seenStart = false
        list.forEachIndexed { i, illust ->
            if (illust.getPage_count() <= 0) return@forEachIndexed
            val baseTitle = illust.getTitle().orEmpty()
            try {
                if (illust.getPage_count() == 1) {
                    val url = IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_LARGE)
                    if (!url.isNullOrEmpty()) {
                        if (i == startListIndex && !seenStart) {
                            startUrlIndex = urls.size
                            seenStart = true
                        }
                        urls.add(url)
                        titles.add(baseTitle)
                    }
                } else {
                    for (p in 0 until illust.getPage_count()) {
                        val url = IllustDownload.getUrl(illust, p, Params.IMAGE_RESOLUTION_LARGE)
                        if (!url.isNullOrEmpty()) {
                            if (i == startListIndex && !seenStart) {
                                startUrlIndex = urls.size
                                seenStart = true
                            }
                            urls.add(url)
                            titles.add(if (illust.getPage_count() > 1) "$baseTitle (${p + 1})" else baseTitle)
                        }
                    }
                }
            } catch (ex: Exception) {
                Timber.w(ex, "[SlideshowLauncher] skipping illust ${illust.getId()}")
            }
        }
        if (urls.isEmpty()) {
            Common.showToast(context.getString(ceui.lisa.R.string.slideshow_empty))
            return
        }
        startSession(context, urls, titles, startUrlIndex, random)
    }

    /**
     * Launch from V3 [Illust] list. Same expansion rule: each illust's pages become consecutive
     * frames in the slideshow.
     */
    @JvmStatic
    @JvmOverloads
    fun launchFromIllusts(
        context: Context,
        list: List<Illust>,
        startListIndex: Int,
        random: Boolean = true,
    ) {
        val urls = ArrayList<String>(list.size)
        val titles = ArrayList<String>(list.size)
        var startUrlIndex = 0
        var seenStart = false
        list.forEachIndexed { i, illust ->
            val baseTitle = illust.title.orEmpty()
            val pages = pagesOf(illust)
            for ((p, url) in pages.withIndex()) {
                if (url.isEmpty()) continue
                if (i == startListIndex && !seenStart) {
                    startUrlIndex = urls.size
                    seenStart = true
                }
                urls.add(url)
                titles.add(if (pages.size > 1) "$baseTitle (${p + 1})" else baseTitle)
            }
        }
        if (urls.isEmpty()) {
            Common.showToast(context.getString(ceui.lisa.R.string.slideshow_empty))
            return
        }
        startSession(context, urls, titles, startUrlIndex, random)
    }

    /** Both branches prefer LARGE for the same OOM/quality balance the V2 launcher uses;
     *  fall back to original-size variants only if LARGE is missing. */
    private fun pagesOf(illust: Illust): List<String> {
        if (illust.page_count <= 0) return emptyList()
        return if (illust.page_count == 1) {
            listOfNotNull(
                illust.image_urls?.large
                    ?: illust.meta_single_page?.original_image_url
                    ?: illust.image_urls?.original
            )
        } else {
            illust.meta_pages.orEmpty().mapNotNull { mp ->
                mp.image_urls?.large ?: mp.image_urls?.original
            }
        }
    }

    private fun startSession(
        context: Context,
        urls: List<String>,
        titles: List<String>,
        startIndex: Int,
        random: Boolean,
    ) {
        val sessionId = SlideshowStore.put(
            SlideshowStore.Session(
                urls = urls,
                titles = titles,
                startIndex = startIndex,
                random = random,
            )
        )
        val intent = Intent(context, SlideshowActivity::class.java).apply {
            putExtra(SlideshowActivity.EXTRA_SESSION_ID, sessionId)
        }
        // Same activity-launch flag posture as ImageDetailActivity / VActivity uses. We avoid
        // NEW_TASK so the slideshow stays in the host activity's task and back returns naturally.
        context.startActivity(intent)
    }
}

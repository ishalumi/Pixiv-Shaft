package ceui.pixiv.ui.detail

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.ui.bulk.UGOIRA_LOG_TAG
import ceui.pixiv.ui.bulk.UgoiraEngine
import ceui.pixiv.ui.bulk.UgoiraPhase
import ceui.pixiv.ui.bulk.UgoiraProgress
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 详情页内联 ugoira 播放 View —— 预览图打底 + 进度浮层([UgoiraEngine] 出片后
 * Glide `asGif` 原地播放,出错显示「重试」)。
 *
 * 慢阶段有真实进度:zip 下载走字节级 %,GIF 编码走帧级 %,浮层用**确定进度条 + 百分比**
 * 显示;meta/解压很快,转圈即可。文案复用现成本地化串(下载=正在下载 / 其余=加载中…),
 * 不新增字符串。
 *
 * 完全不认识 Fragment;生命周期靠 [bind] 传入的 [LifecycleOwner](详情页传
 * viewLifecycleOwner),协程挂它的 lifecycleScope,页面销毁自动取消。
 */
class UgoiraPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    // 横向进度条:确定态(有 %)/不确定态(转圈)由 renderProgress 切换。
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
    }
    private val captionText = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
    }
    private val overlay = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(20.ppppx, 14.ppppx, 20.ppppx, 14.ppppx)
        background = GradientDrawable().apply {
            cornerRadius = 12.ppppx.toFloat()
            setColor(0xB3000000.toInt()) // ~70% 黑,任何底图上都读得清
        }
        addView(
            progressBar,
            LinearLayout.LayoutParams(160.ppppx, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(
            captionText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.ppppx },
        )
        isVisible = false
    }
    private val retryText = TextView(context).apply {
        setText(R.string.retry)
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0x99000000.toInt())
        setPadding(20.ppppx, 10.ppppx, 20.ppppx, 10.ppppx)
        isVisible = false
    }

    private var job: Job? = null

    init {
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            overlay,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(
            retryText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    /** 绑定一条 ugoira。进入即自动拉数据 → 下载 → 解压 → 编码 → 播放。 */
    fun bind(owner: LifecycleOwner, illust: IllustsBean, maxHeight: Int) {
        // 高度按原图宽高比 * 屏宽,封顶 maxHeight —— 和 IllustAdapter / 老 ugora 页一致。
        val w = illust.width.coerceAtLeast(1)
        val h = illust.height.coerceAtLeast(1)
        val screenW = resources.displayMetrics.widthPixels
        var targetH = (screenW.toLong() * h / w).toInt()
        if (maxHeight > 0 && targetH > maxHeight) targetH = maxHeight
        imageView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, targetH)

        // 预览图打底(拿到 gif 前先显示首帧/大图)。
        Glide.with(this).load(GlideUtil.getLargeImage(illust)).into(imageView)

        Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d bind %dx%d", illust.id, illust.width, illust.height)
        retryText.setOnClickListener { startLoad(owner, illust) }
        startLoad(owner, illust)
    }

    private fun startLoad(owner: LifecycleOwner, illust: IllustsBean) {
        job?.cancel()
        retryText.isVisible = false
        // 秒开:内存已有编好的 gif 就直接播,不起协程、不显示浮层、不碰文件系统(主线程零 IO,不绕远路)。
        UgoiraEngine.peekReadyInMemory(illust.id)?.let { ready ->
            overlay.isVisible = false
            Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 内存秒开 %s", illust.id, ready.name)
            playGif(illust, ready)
            return
        }
        overlay.isVisible = true
        Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d startLoad", illust.id)
        job = owner.lifecycleScope.launch {
            // 观察引擎共享进度:再进来立刻拿到当前阶段。子协程,页面退出/播放完成随之取消。
            val progressJob = launch {
                UgoiraEngine.progressOf(illust.id).collect { renderProgress(it) }
            }
            try {
                // await 被取消(页面退出)不会取消底层任务——它继续在引擎 scope 跑完落缓存。
                val gif = UgoiraEngine.loadPlayableGif(illust)
                progressJob.cancel()
                overlay.isVisible = false
                Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 拿到 gif,开始播放 %s", illust.id, gif.name)
                playGif(illust, gif)
            } catch (c: CancellationException) {
                Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 协程取消(页面退出/重绑),底层任务继续在后台跑", illust.id)
                throw c
            } catch (t: Throwable) {
                progressJob.cancel()
                overlay.isVisible = false
                retryText.isVisible = true
                Timber.tag(UGOIRA_LOG_TAG).w(t, "[player] illust=%d 加载失败,显示重试", illust.id)
            }
        }
    }

    /** Glide asGif 播放 + 失败自愈:文件被系统清了缓存目录 → invalidate 内存记录 + 显示重试
     *  (重试走完整 pipeline 重下重编)。也是 loadPlayableGif 不做主线程 stat 的安全网。 */
    private fun playGif(illust: IllustsBean, file: File) {
        Glide.with(this)
            .asGif()
            .load(file)
            .placeholder(imageView.drawable) // 保留预览图当占位,不闪白
            .listener(object : RequestListener<GifDrawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<GifDrawable>, isFirstResource: Boolean,
                ): Boolean {
                    Timber.tag(UGOIRA_LOG_TAG).w(e, "[player] illust=%d gif 加载失败(疑似缓存被清),invalidate+显示重试", illust.id)
                    UgoiraEngine.invalidate(illust.id)
                    overlay.isVisible = false
                    retryText.isVisible = true
                    return false
                }

                override fun onResourceReady(
                    resource: GifDrawable, model: Any, target: Target<GifDrawable>?,
                    source: DataSource, isFirstResource: Boolean,
                ): Boolean {
                    retryText.isVisible = false
                    return false
                }
            })
            .into(imageView)
    }

    private fun renderProgress(p: UgoiraProgress) {
        val pct = p.percent
        if (pct != null) {
            progressBar.isIndeterminate = false
            progressBar.progress = pct
        } else {
            progressBar.isIndeterminate = true
        }
        // 本地化文案(7 语言均已补):下载=正在下载,编码=压制中(ugoira_encoding),
        // 其余(meta/解压)=加载中…;% 直接拼数字。
        val base = when (p.phase) {
            UgoiraPhase.DOWNLOAD_ZIP -> context.getString(R.string.now_downloading)
            UgoiraPhase.ENCODE -> context.getString(R.string.ugoira_encoding)
            else -> context.getString(R.string.now_loading)
        }
        captionText.text = if (pct != null) "$base  $pct%" else base
    }
}

/**
 * 单条目 adapter,把 [UgoiraPlayerView] 塞进详情页的 RecyclerView 图片位。
 * FragmentIllust(LinearLayoutManager)直接 setAdapter;ArtworkV3(ConcatAdapter +
 * StaggeredGridLayoutManager)addAdapter(0, this),靠 [onViewAttachedToWindow] 占满整行。
 */
class UgoiraPlayerAdapter(
    private val illust: IllustsBean,
    private val owner: LifecycleOwner,
    private val maxHeight: Int,
) : RecyclerView.Adapter<UgoiraPlayerAdapter.VH>() {

    class VH(val player: UgoiraPlayerView) : RecyclerView.ViewHolder(player)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val player = UgoiraPlayerView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return VH(player)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.player.bind(owner, illust, maxHeight)
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
    }

    override fun getItemCount(): Int = 1
}

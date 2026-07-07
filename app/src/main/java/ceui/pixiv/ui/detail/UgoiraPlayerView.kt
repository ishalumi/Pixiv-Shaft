package ceui.pixiv.ui.detail

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import ceui.lisa.utils.V3Palette
import com.google.android.material.progressindicator.LinearProgressIndicator
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

    // 主题色板(日夜双模):进度条填充 + 重试胶囊都取当前主题色,和详情页「关注 / 下载」等 V3 按钮同源。
    private val palette = V3Palette.from(context)

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    // 谷歌 Material 3 Expressive「波浪(wavy)」进度条 —— 官方组件(Material 1.14),不自绘。
    // 轨道 + 已填充段都圆角,活动段画成蛇行正弦波并流动;determinate 显示下载 %,indeterminate 滚动波(起步/解压)。
    // 填充色随主题(日夜双模)。注意:Material 进度条**可见时不能切到 indeterminate**(会抛 IllegalStateException),
    // 故起步态在构造时置好、只在浮层不可见时(startLoad 重置)才回切,见 [startLoad] / [renderProgress]。
    private val progressBar = LinearProgressIndicator(context).apply {
        max = 100
        trackThickness = 4.ppppx
        trackCornerRadius = 2.ppppx
        indicatorTrackGapSize = 2.ppppx
        trackStopIndicatorSize = 0 // 去掉 M3 末端那个小圆点,保持纯波浪线
        setIndicatorColor(palette.primary)
        trackColor = V3Palette.withAlpha(0xFFFFFFFF.toInt(), 0.22f)
        // wavy 形态:振幅 + 波长 + 流动速度
        waveAmplitude = 4.ppppx
        setWavelength(20.ppppx)
        waveSpeed = 24.ppppx
        isIndeterminate = true // 起步先滚动波,拿到 % 后 setProgressCompat 平滑转确定态
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
            // 高度 WRAP_CONTENT:让 Material 自己按 轨道厚 + 波振幅 量高,别把波浪裁掉。
            LinearLayout.LayoutParams(180.ppppx, LinearLayout.LayoutParams.WRAP_CONTENT),
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

    // 「重试」按钮 V3 重设计:主题实心圆角胶囊 + 刷新图标 + 文案。白色前景在任意底图与日夜模式下都读得清,
    // 与详情页「关注 / 下载」主按钮同一套视觉语言,取代原先白字压半透明黑方块的粗糙样式。
    private val retryButton = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.ppppx, 9.ppppx, 20.ppppx, 9.ppppx)
        isClickable = true
        isFocusable = true
        isVisible = false
        background = buildRetryPill()
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_refresh_48)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(18.ppppx, 16.ppppx).apply { marginEnd = 7.ppppx },
        )
        addView(
            TextView(context).apply {
                setText(R.string.retry)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** 主题实心圆角胶囊 + 白色按压波纹(裁到胶囊形状)。 */
    private fun buildRetryPill(): Drawable {
        val radius = 999f
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(
            ColorStateList.valueOf(V3Palette.withAlpha(0xFFFFFFFF.toInt(), 0.28f)),
            palette.pillPrimary(radius),
            mask,
        )
    }

    /** 收浮层。Material 进度条会随聚合可见性自动停/续内部动画,浮层 GONE 即暂停,无需手动停。 */
    private fun hideOverlay() {
        overlay.isVisible = false
    }

    private var job: Job? = null

    init {
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            overlay,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(
            retryButton,
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
        retryButton.setOnClickListener { startLoad(owner, illust) }
        startLoad(owner, illust)
    }

    private fun startLoad(owner: LifecycleOwner, illust: IllustsBean) {
        job?.cancel()
        retryButton.isVisible = false
        // 秒开:内存已有编好的 gif 就直接播,不起协程、不显示浮层、不碰文件系统(主线程零 IO,不绕远路)。
        UgoiraEngine.peekReadyInMemory(illust.id)?.let { ready ->
            hideOverlay()
            Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 内存秒开 %s", illust.id, ready.name)
            playGif(illust, ready)
            return
        }
        // 回到起步的滚动波。Material 进度条只有在**不可见**时才能切到 indeterminate(可见时切会抛
        // IllegalStateException)。浮层此刻仍 GONE(初次 / 上次 hideOverlay 过),安全;极少数「加载中被
        // rebind、浮层已可见」的情况走 isShown 分支,改用平滑重置到确定态 0,避免抛异常。
        if (progressBar.isShown) {
            progressBar.setProgressCompat(0, false)
        } else {
            progressBar.isIndeterminate = true
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
                hideOverlay()
                Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 拿到 gif,开始播放 %s", illust.id, gif.name)
                playGif(illust, gif)
            } catch (c: CancellationException) {
                Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 协程取消(页面退出/重绑),底层任务继续在后台跑", illust.id)
                throw c
            } catch (t: Throwable) {
                progressJob.cancel()
                hideOverlay()
                retryButton.isVisible = true
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
                    hideOverlay()
                    retryButton.isVisible = true
                    return false
                }

                override fun onResourceReady(
                    resource: GifDrawable, model: Any, target: Target<GifDrawable>?,
                    source: DataSource, isFirstResource: Boolean,
                ): Boolean {
                    retryButton.isVisible = false
                    return false
                }
            })
            .into(imageView)
    }

    private fun renderProgress(p: UgoiraProgress) {
        val pct = p.percent
        if (pct != null) {
            // setProgressCompat:若当前是起步的滚动波,会平滑过渡到确定态并动画到 pct。
            progressBar.setProgressCompat(pct, true)
        }
        // 无 % 的阶段(FETCH_META / EXTRACT):不在此切 indeterminate。起步态已在 startLoad 置为滚动波;
        // 之后再出现的无 % 阶段(如 EXTRACT 紧接确定态下载)只保持当前进度,不回切——可见时 Material 会崩。
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

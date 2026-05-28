package ceui.lisa.fragments

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.ImageTranslationViewModel
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentImageDetailBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.pixiv.ui.common.deleteImageById
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.saveImageToGallery
import ceui.pixiv.ui.common.setUpWithTaskStatus
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import ceui.pixiv.utils.setOnClick
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.github.panpf.zoomimage.zoom.GestureType
import com.github.panpf.zoomimage.zoom.ReadMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class FragmentImageDetail : BaseFragment<FragmentImageDetailBinding?>() {
    private var index = 0
    private var url: String? = null
    private var saveName: String? = null
    private val viewModel by viewModels<ToggleToolnarViewModel>(ownerProducer = { requireActivity() })
    private val translationViewModel by viewModels<ImageTranslationViewModel>(ownerProducer = { requireActivity() })

    // 不再放进 arguments / savedInstanceState，避免每个 Fragment 重复持久化 80KB IllustsBean
    // 导致 TransactionTooLargeException。统一向 ImageDetailActivity 取。
    private val mIllustsBean: IllustsBean?
        get() = (activity as? ImageDetailActivity)?.mIllustsBean

    // PR#900：自定义双击「增量放大 + 长按归位」。
    // 仅在 Settings.isUseCustomDoubleTapZoom 开启时才会被访问/初始化；
    // 关闭时整条路径不参与，保持与未引入 PR 前完全一致的体验。
    private val gestureDetector by lazy {
        GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val zoomable = baseBind.image.zoomable
                val contentPoint = zoomable.touchPointToContentPointF(OffsetCompat(e.x, e.y))
                if (viewModel.isFullscreenMode.value == false) {
                    viewModel.toggleFullscreen()
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    zoomable.scaleBy(
                        addScale = CUSTOM_ZOOM_ADD_SCALE,
                        centroidContentPointF = contentPoint,
                        animated = true
                    )
                    val afterScale = zoomable.transformState.value.scaleX
                    val maxScale = zoomable.maxScaleState.value
                    if (afterScale >= maxScale - MAX_SCALE_EPSILON) {
                        Toast.makeText(
                            requireContext(),
                            R.string.double_tap_zoom_max_reached,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val zoomable = baseBind.image.zoomable
                val contentPoint = zoomable.touchPointToContentPointF(OffsetCompat(e.x, e.y))
                if (viewModel.isFullscreenMode.value == true) {
                    viewModel.toggleFullscreen()
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    zoomable.scale(
                        targetScale = zoomable.minScaleState.value,
                        centroidContentPointF = contentPoint,
                        animated = true
                    )
                }
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                viewModel.toggleFullscreen()
                return true
            }
        })
    }

    public override fun initBundle(bundle: Bundle) {
        url = bundle.getString(Params.URL)
        index = bundle.getInt(Params.INDEX)
        saveName = bundle.getString(Params.TITLE)
    }

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_image_detail
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        baseBind.emptyActionButton.setOnClickListener { v: View? -> loadImage() }
        //插画二级详情保持屏幕常亮
        if (Shaft.sSettings.isIllustDetailKeepScreenOn) {
            baseBind.root.keepScreenOn = true
        }
        if (Shaft.sSettings.isUseCustomDoubleTapZoom) {
            // PR#900 路径：禁用 ZoomImage 自带双击缩放，改走自家 GestureDetector
            baseBind.image.zoomable.setDisabledGestureTypes(
                baseBind.image.zoomable.disabledGestureTypesState.value or GestureType.DOUBLE_TAP_SCALE
            )
            // 单指交给 gestureDetector（双击/长按/单击），多指交回 ZoomImage（拖拽/双指缩放）
            baseBind.image.setOnTouchListener { v, event ->
                if (event.pointerCount == 1) {
                    gestureDetector.onTouchEvent(event)
                } else {
                    v.onTouchEvent(event)
                }
            }
        } else {
            // 默认路径：onViewTapListener → setReadMode 的顺序与改前完全一致
            baseBind.image.onViewTapListener = OnViewTapListener { _, _ ->
                viewModel.toggleFullscreen()
            }
        }
        // 长图阅读模式：自动填满宽度、从顶部开始，无需手动双击放大再滑动
        baseBind.image.zoomable.setReadMode(ReadMode.Default)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadImage()
        // 监听"翻译漫画"产出:VM 里出现本页 index 的译图就直接换图
        translationViewModel.translatedPaths.observe(viewLifecycleOwner) { map ->
            val path = map[index] ?: return@observe
            val f = File(path)
            if (f.exists()) {
                baseBind.image.loadImage(f)
            }
        }
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        val isUrlMode = mIllustsBean == null && !TextUtils.isEmpty(url)
        val imageUrl: String? = if (isUrlMode) {
            url
        } else {
            IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL)
        }

        val shortUrl = imageUrl?.substringAfterLast('/') ?: "null"
        Timber.d("[ImageDetail] loadImage index=$index, isUrlMode=$isUrlMode, url=$shortUrl")

        if (imageUrl?.isNotEmpty() == true) {
            // content:// URI（来自下载完成页的 SAF 路径）直接用 Sketch 加载，
            // 不走 TaskPool/Glide，因为 Glide 没有 SAF URI 的访问权限。
            if (imageUrl.startsWith("content://")) {
                baseBind.image.loadImage(Uri.parse(imageUrl))
                return
            }

            val task = TaskPool.getLoadTask(NamedUrl("", imageUrl))
            Timber.d("[ImageDetail] task acquired. taskId=${task.taskId}, status=${task.status.value}, hasResult=${task.result.value != null}, url=$shortUrl")

            // 原图尚未加载完时，若一级详情页的大图已在 Glide 缓存，先用大图占位
            if (mIllustsBean != null && task.result.value == null) {
                val largeUrl = IllustDownload.getUrl(
                    mIllustsBean, index, Params.IMAGE_RESOLUTION_LARGE
                )
                if (!largeUrl.isNullOrEmpty() && largeUrl != imageUrl) {
                    val largeFile = TaskPool.peekCachedFile(largeUrl)
                    if (largeFile != null) {
                        Timber.d("[ImageDetail] placeholder HIT path=${largeFile.absolutePath} size=${largeFile.length()}")
                        baseBind.image.loadImage(largeFile)
                    } else {
                        Timber.d("[ImageDetail] placeholder MISS largeUrl=${largeUrl.substringAfterLast('/')}")
                    }
                }
            }

            task.result.observe(viewLifecycleOwner) { file ->
                Timber.d("[ImageDetail] result callback. file=${file?.absolutePath}, exists=${file?.exists()}, size=${file?.length() ?: -1}, url=$shortUrl")
                baseBind.image.loadImage(file)
                if (isUrlMode) {
                    baseBind.downloadButton.visibility = View.VISIBLE
                    baseBind.downloadButton.setOnClick {
                        val ext = imageUrl.substringAfterLast('.', "jpg")
                        val displayName = if (!saveName.isNullOrEmpty()) {
                            "$saveName.$ext"
                        } else {
                            imageUrl.substringAfterLast('/')
                        }
                        val ctx = requireActivity()
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val imageId = getImageIdInGallery(ctx, displayName)
                                if (imageId != null) {
                                    deleteImageById(ctx, imageId)
                                }
                                saveImageToGallery(ctx, file, displayName)
                            }
                        }
                    }
                }
            }
            baseBind.progressCircular.setUpWithTaskStatus(task.status, viewLifecycleOwner)
        }
    }

    companion object {
        // PR#900 自定义双击放大：每次乘 1.8f；浮点误差判最大倍数容差 0.01f
        private const val CUSTOM_ZOOM_ADD_SCALE = 1.8f
        private const val MAX_SCALE_EPSILON = 0.01f

        // IllustsBean 由 ImageDetailActivity 持有，Fragment 运行时读取，避免放进 Bundle
        @JvmStatic
        fun newInstance(index: Int): FragmentImageDetail {
            val args = Bundle()
            args.putInt(Params.INDEX, index)
            val fragment = FragmentImageDetail()
            fragment.arguments = args
            return fragment
        }

        @JvmStatic
        @JvmOverloads
        fun newInstance(pUrl: String?, pSaveName: String? = null): FragmentImageDetail {
            val args = Bundle()
            args.putString(Params.URL, pUrl)
            if (pSaveName != null) {
                args.putString(Params.TITLE, pSaveName)
            }
            val fragment = FragmentImageDetail()
            fragment.arguments = args
            return fragment
        }
    }
}

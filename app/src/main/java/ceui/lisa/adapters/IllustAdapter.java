package ceui.lisa.adapters;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import timber.log.Timber;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.RecyIllustDetailBinding;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.LargeBitmapScaleTransformer;
import ceui.lisa.transformer.UniformScaleTransformation;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUrlChild;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.utils.SketchPreloader;
import ceui.pixiv.imageloader.ImageLoadState;
import ceui.pixiv.imageloader.ImageLoadTask;
import ceui.pixiv.imageloader.ImageLoaderV3;
import ceui.pixiv.ui.task.TaskStatus;

public class IllustAdapter extends AbstractIllustAdapter<ViewHolder<RecyIllustDetailBinding>> {

    /** Reports per-page LoadTask status changes to the host (used by V3's retry-all banner). */
    public interface PageStatusListener {
        void onStatusChanged(int position, @NonNull TaskStatus status);
    }

    private final int maxHeight;
    private final FragmentActivity mActivity;
    private final Fragment mFragment;
    private static final boolean longPressDownload = Shaft.sSettings.isIllustLongPressDownload();

    /** fast_out_slow_in —— large→original 圆形揭露用的平台标准缓动，懒加载后共享(无状态)。 */
    private static Interpolator sRevealInterpolator;
    /** large→original 圆形揭露时长(ms)。改这一个值即可调快慢。 */
    private static final long REVEAL_DURATION_MS = 1200L;
    /** 圈外黑蒙版颜色 —— 无模糊(API<31 退回)时用，稍浓补偿没有模糊。0x4D≈30% 黑。 */
    private static final int REVEAL_SCRIM_COLOR = 0x4D000000;
    /** 圈外黑蒙版颜色 —— 有模糊(API31+)时叠在模糊之上，稍淡。0x33≈20% 黑。 */
    private static final int REVEAL_BLUR_SCRIM_COLOR = 0x33000000;
    /** 圈外高斯模糊半径(px)。API31+ 才有 RenderEffect；越大越糊。 */
    private static final float REVEAL_BLUR_RADIUS = 2f;
    /** 圈外模糊的渐入时长(ms)：large 从清晰在这段时间内平滑变糊，避免起始「唰一下变糊」。 */
    private static final long REVEAL_BLUR_FADE_MS = 200L;

    @Nullable
    private PageStatusListener pageStatusListener;

    /**
     * 页码 -> 已下载文件 Uri。后台扫一次 illust_download_table，命中的页在绑定时
     * 直接 Glide 读本地文件，免得详情页(尤其多图「展开」后)又回 pixiv 重新下。
     * 见用户反馈：未展开时下载，展开后第二张及之后被重新加载。
     */
    private final Map<Integer, Uri> localPageUris = new ConcurrentHashMap<>();
    private volatile boolean localScanRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public IllustAdapter(FragmentActivity activity, Fragment fragment, IllustsBean illustsBean, int maxHeight, boolean isForceOriginal) {
        Common.showLog("IllustAdapter maxHeight " + maxHeight);
        mActivity = activity;
        mContext = fragment.requireContext();
        allIllust = illustsBean;
        this.maxHeight = maxHeight;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
        this.isForceOriginal = isForceOriginal;
        this.mFragment = fragment;
        scanLocalDownloads();
    }

    public void setPageStatusListener(@Nullable PageStatusListener listener) {
        this.pageStatusListener = listener;
    }

    /**
     * 后台扫描该作品已下载的页 → 本地文件 Uri。命中后回主线程刷新，绑定时优先走本地。
     * 调两次：构造时(覆盖「打开前就下好了」)、展开时(覆盖「未展开时下载，再展开」)。
     * 页码 → 文件名用 {@link FileCreator#customFileName}，与下载时写库的 fileName 同源，
     * 所以是精确的逐页匹配，不依赖文件名字典序，分图缺页也不会错位。
     */
    public void scanLocalDownloads() {
        final IllustsBean illust = allIllust;
        if (illust == null || localScanRunning || illust.isGif()) {
            return;
        }
        final int pageCount = Math.max(illust.getPage_count(), 1);
        final long illustId = illust.getId();
        localScanRunning = true;
        new Thread(() -> {
            final Map<Integer, Uri> found = new HashMap<>();
            try {
                DownloadDao dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao();
                for (int i = 0; i < pageCount; i++) {
                    // 页码 → 下载文件名(与写库时同源)→ 主键精确查。命中就记下本地 Uri。
                    DownloadEntity e = dao.getDownloadByFileName(FileCreator.customFileName(illust, i));
                    if (e != null && e.getFilePath() != null && !e.getFilePath().isEmpty()) {
                        try {
                            found.put(i, Uri.parse(e.getFilePath()));
                        } catch (Exception ignore) {
                            // 坏 URI 跳过，该页照常走网络
                        }
                    }
                }
            } catch (Throwable t) {
                Timber.w(t, "[IllustAdapter] scanLocalDownloads failed, id=%d", illustId);
            }
            mainHandler.post(() -> {
                localScanRunning = false;
                boolean changed = false;
                for (Map.Entry<Integer, Uri> en : found.entrySet()) {
                    if (localPageUris.put(en.getKey(), en.getValue()) == null) {
                        changed = true;
                    }
                }
                if (changed) {
                    notifyDataSetChanged();
                }
            });
        }).start();
    }

    @NonNull
    @Override
    public ViewHolder<RecyIllustDetailBinding> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder<>(DataBindingUtil.inflate(
                LayoutInflater.from(mContext), R.layout.recy_illust_detail, parent, false
        ));
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder<RecyIllustDetailBinding> holder) {
        super.onViewRecycled(holder);
        // Detach this holder's LoadTask observers (see loadIllust) so they don't outlive
        // the bind and pile up on the per-URL task's LiveData.
        detachTaskObservers(holder);
        // 取消顶层原图可能还在跑的揭露，复位模糊/裁剪/蒙版，避免漏到复用的 holder 上。
        cancelReveal(holder);
        // Cancel any in-flight Glide load targeting these ImageViews so a late-arriving
        // bitmap from the previous bind can't leak into the recycled holder.
        Glide.with(mFragment).clear(holder.baseBind.illust);
        Glide.with(mFragment).clear(holder.baseBind.illustHd);
        holder.baseBind.illustHd.setImageDrawable(null);
        holder.baseBind.illustHd.setVisibility(View.GONE);
        holder.baseBind.illust.setTag(R.id.tag_image_url, null);
    }

    /**
     * Remove the status/result observers registered by the previous {@link #loadIllust} on
     * this holder, if any. The observers are attached to the fragment's viewLifecycleOwner
     * (not the holder), so without this they would survive every rebind and accumulate
     * unbounded on the shared, per-URL {@link LoadTask} LiveData — each progress tick then
     * fans out to all of them, and the leaked lambdas pin recycled holders/bitmaps. On a
     * 50–60 page artwork that compounds into severe scroll jank. See issue #912.
     */
    private void detachTaskObservers(@NonNull ViewHolder<RecyIllustDetailBinding> holder) {
        Object prev = holder.itemView.getTag(R.id.tag_task_observers);
        if (prev instanceof Runnable) {
            ((Runnable) prev).run();
            holder.itemView.setTag(R.id.tag_task_observers, null);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder<RecyIllustDetailBinding> holder, int position) {
        super.onBindViewHolder(holder, position);
        if(longPressDownload && mActivity instanceof BaseActivity<?>){
            holder.itemView.setOnLongClickListener(v -> {
                IllustDownload.downloadIllustCertainPage(allIllust, position, (BaseActivity<?>) mActivity);
                if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !allIllust.isIs_bookmarked()){
                    PixivOperate.postLikeDefaultStarType(allIllust);
                }
                return true;
            });
        }

        if (position == 0) {
            // 第一张图：宽 = 屏宽，FIT_CENTER 不裁切。
            // 单 P：高 = max(自然高, maxHeight)，扁图保留 maxHeight 占位。
            // 多 P（≥2P）：高 = 自然高，不施加 maxHeight 约束，消除上下黑边。
            int iw = allIllust.getWidth();
            int ih = allIllust.getHeight();
            boolean hasValidDims = iw > 0 && ih > 0;

            ImageView.ScaleType scaleType;
            int targetHeight;
            boolean changeSize;
            String branchTag;
            if (!hasValidDims) {
                scaleType = ImageView.ScaleType.FIT_CENTER;
                targetHeight = maxHeight > 0 ? maxHeight : holder.baseBind.illust.getLayoutParams().height;
                changeSize = true;
                branchTag = "fallback(noValidDims)";
            } else {
                int naturalHeight = Math.round((float) imageSize * ih / iw);
                scaleType = ImageView.ScaleType.FIT_CENTER;
                if (allIllust.getPage_count() >= 2) {
                    // 多P：第一P 直接用自然高度，不施加 maxHeight 约束，消除上下黑边
                    targetHeight = naturalHeight;
                } else {
                    targetHeight = maxHeight > 0 ? Math.max(naturalHeight, maxHeight) : naturalHeight;
                }
                changeSize = false;
                boolean tall = maxHeight <= 0 || naturalHeight >= maxHeight;
                branchTag = (allIllust.getPage_count() == 1 ? "single_" : "multiP_")
                        + (tall ? "tall_natural" : "flat_padToMax");
            }

            int pageCount = allIllust.getPage_count();
            Timber.tag("V3MultiP").d(
                "[IllustAdapter.bind pos=0] illustId=%d, page_count=%d, iw=%d, ih=%d, " +
                    "imageSize(=screenW)=%d, maxHeight=%d, branch=%s, targetHeight=%d, " +
                    "scaleType=%s, changeSize=%b, adapterClass=%s, getItemCount=%d",
                allIllust.getId(), pageCount, iw, ih, imageSize, maxHeight, branchTag,
                targetHeight, scaleType, changeSize, this.getClass().getSimpleName(), getItemCount()
            );

            holder.baseBind.illust.setScaleType(scaleType);
            ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
            params.width = imageSize;
            params.height = targetHeight;
            holder.baseBind.illust.setLayoutParams(params);
            loadIllust(holder, position, changeSize);
        } else {
            Timber.tag("V3MultiP").d(
                "[IllustAdapter.bind pos=%d] non-first page, illustId=%d",
                position, allIllust.getId()
            );
            holder.baseBind.illust.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadIllust(holder, position, true);
        }
    }

    /**
     * @param holder
     * @param position
     * @param changeSize 是否自动计算宽高
     */
    private void loadIllust(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        // Drop observers left over from a previous bind of this recycled holder before
        // registering new ones. Prevents unbounded observer accumulation across rebinds. #912
        detachTaskObservers(holder);

        // 复用前重置「顶层原图」overlay，避免上一条的原图盖在这次的图上。底层 large 由各渲染路径自行覆盖。
        cancelReveal(holder);
        Glide.with(mFragment).clear(holder.baseBind.illustHd);
        holder.baseBind.illustHd.setImageDrawable(null);
        holder.baseBind.illustHd.setVisibility(View.GONE);

        // 命中已下载的本地文件就直读，跳过网络 LoadTask —— 详情页展开多图复用下载结果。
        Uri localUri = localPageUris.get(position);
        if (localUri != null) {
            loadFromLocalFile(holder, position, changeSize, localUri);
            return;
        }
        loadFromNetwork(holder, position, changeSize);
    }

    private void loadFromNetwork(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        boolean loadOriginal = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        final String largeUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_LARGE);
        final String targetUrl = loadOriginal
                ? IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_ORIGINAL)
                : largeUrl;

        // tag = 本次 bind 想显示的「最终」url;所有回调据此判 stale,复用时旧回调自动变 no-op(#912)。
        holder.baseBind.illust.setTag(R.id.tag_image_url, targetUrl);
        holder.baseBind.reload.setVisibility(View.GONE);
        holder.baseBind.reload.setOnClickListener(v -> loadIllust(holder, position, changeSize));

        // 总是先用 large 秒显 —— 命中外面瀑布流 A 已加载的 Glide 内存/磁盘缓存(同 url 同 key,立即出图)。
        // 「仅 large」模式下它就是最终图;「原图」模式下它是即时占位,原图下好再覆盖上去。
        holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
        holder.baseBind.progressLayout.donutProgress.setProgress(0);
        renderBase(holder, position, changeSize, new GlideUrlChild(largeUrl), targetUrl, /*isFinal=*/!loadOriginal);

        if (!loadOriginal) {
            // 设置没开加载原图 → 停在 large:不建 imageloader 任务、不下原图。
            return;
        }

        // 设置开了 → 在 large 占位之上再加载原图(imageloader 共享任务,与大图页 C 复用同一次下载/进度/结果)。
        String shortUrl = targetUrl.substring(targetUrl.lastIndexOf('/') + 1);
        ImageLoadTask task = ImageLoaderV3.obtain(targetUrl);
        // 已失败的任务在(重新)绑定时强制重来一次(对齐旧 TaskPool「rebind 即重下」+ 让重试横幅生效)。
        if (task.getState().getValue() instanceof ImageLoadState.Error) {
            task.retry();
        }
        Timber.d("[IllustAdapter] loadIllust original pos=%d, state=%s, url=%s",
                position, task.getState().getValue(), shortUrl);

        final Observer<ImageLoadState> stateObserver = state -> {
            if (!targetUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) {
                return;
            }
            if (state instanceof ImageLoadState.Loading) {
                int percent = ((ImageLoadState.Loading) state).getPercent();
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                holder.baseBind.progressLayout.donutProgress.setProgress(percent);
                reportPageStatus(position, new TaskStatus.Executing(percent));
            } else if (state instanceof ImageLoadState.Success) {
                // 原图就绪 → 加载进「顶层」illust_hd，就绪后淡入盖住底层 large(large 从不被清 → 零闪烁)。
                renderOverlay(holder, ((ImageLoadState.Success) state).getFile(), targetUrl, position);
                reportPageStatus(position, TaskStatus.Finished.INSTANCE);
            } else if (state instanceof ImageLoadState.Error) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                holder.baseBind.reload.setVisibility(View.VISIBLE);
                Throwable cause = ((ImageLoadState.Error) state).getCause();
                Timber.w(cause, "[IllustAdapter] original load failed, showing reload. pos=%d, url=%s", position, shortUrl);
                reportPageStatus(position, new TaskStatus.Error(
                        (cause instanceof Exception) ? (Exception) cause : new Exception(cause)));
            }
        };

        task.getStateLiveData().observe(mFragment.getViewLifecycleOwner(), stateObserver);
        holder.itemView.setTag(R.id.tag_task_observers,
                (Runnable) () -> task.getStateLiveData().removeObserver(stateObserver));
    }

    /**
     * large → 底层 {@code illust}(带动态 resize)。{@code isFinal=true}(仅 large 模式,large 即最终图)时,
     * 其成功/失败负责收进度环 / 亮重载按钮;{@code isFinal=false}(large 只是原图的占位)不碰进度环。
     * 回调按 {@code guardUrl}(=tag)判 stale,复用时自动 no-op。
     */
    private void renderBase(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize,
                            Object model, String guardUrl, boolean isFinal) {
        String shortUrl = guardUrl.substring(guardUrl.lastIndexOf('/') + 1);
        RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
        requestManager
                .asBitmap()
                .load(model)
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object m, Target<Bitmap> target, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.w(e, "[IllustAdapter] base(large) FAIL pos=%d, isFinal=%b, url=%s", position, isFinal, shortUrl);
                        if (isFinal) {
                            holder.baseBind.reload.setVisibility(View.VISIBLE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object m, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.d("[IllustAdapter] base(large) OK pos=%d, isFinal=%b, ds=%s, url=%s", position, isFinal, dataSource.name(), shortUrl);
                        holder.baseBind.reload.setVisibility(View.GONE);
                        if (isFinal) {
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        }
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
    }

    /**
     * 原图 → 顶层 {@code illust_hd},就绪后用圆形揭露(circular reveal)从中心把原图揭开、盖住底层 large。
     * 底层 large 全程不被清、不共享/回收 bitmap → 揭露没盖到的地方一直是 large,零闪烁。尺寸随底层 illust
     * (布局四边对齐)。原图失败则保留底层 large、亮重载。见 {@link #revealOriginal}。
     */
    private void renderOverlay(ViewHolder<RecyIllustDetailBinding> holder, File file, String guardUrl, int position) {
        // 仅对首图(pos 0,点进去最常打开的那张)且「详情展示原图」设置开启时,把原图预热进 Sketch 内存缓存,
        // 让二级大图页 C 首次打开秒开不黑屏。只挂 pos 0 是为避免翻多图时每页都多解一遍原图的性能开销。
        if (position == 0 && Shaft.sSettings.isShowOriginalPreviewImage()) {
            SketchPreloader.warm(mContext, file);
        }
        String shortUrl = guardUrl.substring(guardUrl.lastIndexOf('/') + 1);
        // 关键:先置 INVISIBLE(不是 GONE)。GONE 视图不参与 measure/layout,尺寸为 0,Glide 的 ViewTarget
        // 拿不到尺寸会一直等、onResourceReady 永不触发;INVISIBLE 照常布局(拿得到 illust 的尺寸)、只是不绘制,
        // 底层 large 依旧透出来。原图 bitmap 就绪后(仍 INVISIBLE)才启动圆形揭露、在揭露首帧才置 VISIBLE。
        holder.baseBind.illustHd.setVisibility(View.INVISIBLE);
        RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
        requestManager
                .asBitmap()
                .load(file)
                .transform(new LargeBitmapScaleTransformer())
                // 不用 Glide 的 crossfade —— 换原图的过渡完全交给圆形揭露,两者叠加会打架/闪。
                // 且此时 view 是 INVISIBLE,crossfade 也看不到,只会在揭露起步时造成一帧突显。
                .dontAnimate()
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object m, Target<Bitmap> target, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.w(e, "[IllustAdapter] overlay(original) FAIL pos=%d, url=%s", position, shortUrl);
                        holder.baseBind.reload.setVisibility(View.VISIBLE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object m, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.d("[IllustAdapter] overlay(original) OK pos=%d, %dx%d, ds=%s, url=%s",
                                position, resource.getWidth(), resource.getHeight(), dataSource.name(), shortUrl);
                        holder.baseBind.reload.setVisibility(View.GONE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        // 原图 bitmap 已设进(仍 INVISIBLE 的)illust_hd → 用圆形揭露把它从 large 上揭开,零闪。
                        revealOriginal(holder, guardUrl);
                        Shaft.getMMKV().encode(guardUrl, true);
                        return false;
                    }
                })
                .into(holder.baseBind.illustHd);
    }

    /**
     * large → original 的无闪揭露：圈内清晰原图、圈外高斯模糊，圆从中心长大 → 「糊变清」一眼可见。
     *
     * 实现(API 31+)：底层 {@code illust}(large)整张打 {@link RenderEffect} 高斯模糊作「圈外」；顶层 {@code illust_hd}
     * (清晰 original)用 {@link ViewOutlineProvider} 椭圆裁到圆内，{@link ValueAnimator} 把圆半径 0→满。圈内透出清晰
     * original、圈外透出模糊 large。API<31 无 RenderEffect → 退回「带黑色透明度的蒙版」({@link RevealScrimDrawable})。
     *
     * 为什么不用 {@code ViewAnimationUtils.createCircularReveal}：它是 RenderNodeAnimator，从 Glide 回调里对一个刚从
     * INVISIBLE 转 VISIBLE 的 view 同步 start()，RenderNode 还没进显示列表，动画会静默不启动(实测无揭露)。纯 UI 线程
     * ValueAnimator + Outline/前景：确定性启动、不依赖 RenderNode 就绪。
     *
     * 「不闪」：先把裁剪/蒙版设到半径≈0(此时 illust_hd 仍 INVISIBLE)，再转 VISIBLE → 首帧就已裁没/全蒙，不存在原图
     * 整张突显那一帧；底层 large 全程不清。动画挂在 illust_hd 的 {@code tag_reveal_anim}，recycle/rebind 由
     * {@link #cancelReveal} 精准取消并复位(撤模糊/裁剪/蒙版)。
     */
    private void revealOriginal(ViewHolder<RecyIllustDetailBinding> holder, String guardUrl) {
        final ImageView hd = holder.baseBind.illustHd;
        final ImageView base = holder.baseBind.illust;
        cancelReveal(holder); // 清掉上一轮残留 + 复位

        final int w = hd.getWidth(), h = hd.getHeight();
        // illust_hd 之前是 INVISIBLE(参与布局)→ 正常已有尺寸。拿不到尺寸/未 attach 就直接显示(无揭露，
        // 底下仍是 large 也不闪)，不硬等以免 onResourceReady 里卡住。
        if (w <= 0 || h <= 0 || !hd.isAttachedToWindow()) {
            hd.setVisibility(View.VISIBLE);
            return;
        }

        final float maxRadius = (float) Math.hypot(w, h) / 2f;
        final float[] radius = {2f}; // 从 2px 起(避免空 outline 不裁剪)

        // 圈外「未擦除」区的处理，都叠在底层 large 上、圆洞随动画放大：
        //  - 黑蒙版：两条路径都有(前景 RevealScrimDrawable)。有模糊时 20% 黑、无模糊时 30% 黑(补偿没有模糊)。
        //  - 高斯模糊：仅 API31+，额外叠在 large 上，并在 updateListener 里 200ms 渐入(起始不糊，无「唰一下变糊」)。
        final boolean useBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        final RevealScrimDrawable scrim =
                new RevealScrimDrawable(useBlur ? REVEAL_BLUR_SCRIM_COLOR : REVEAL_SCRIM_COLOR);
        scrim.setHoleRadius(2f);
        base.setForeground(scrim);
        final boolean[] blurAtFull = {false};

        // 顶层清晰 original 裁到圆内 → 圈内=original(擦除后)，圈外露出底层 large(未擦除，带黑/带模糊)。
        hd.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int r = (int) radius[0];
                outline.setOval(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r);
            }
        });
        hd.setClipToOutline(true);
        hd.invalidateOutline();          // 仍 INVISIBLE 时先裁到 2px
        hd.setVisibility(View.VISIBLE);  // 首帧即裁到 2px → 无整张突显

        ValueAnimator anim = ValueAnimator.ofFloat(2f, maxRadius);
        anim.setDuration(REVEAL_DURATION_MS);
        anim.setInterpolator(revealInterpolator());
        anim.addUpdateListener(a -> {
            radius[0] = (float) a.getAnimatedValue();
            hd.invalidateOutline();
            scrim.setHoleRadius(radius[0]); // 黑蒙版圆洞与裁剪同步放大
            if (useBlur && !blurAtFull[0]) {
                // 模糊渐入：头 REVEAL_BLUR_FADE_MS 内把底层 large 的模糊半径从 0 升到 REVEAL_BLUR_RADIUS。
                float f = Math.min(1f, a.getCurrentPlayTime() / (float) REVEAL_BLUR_FADE_MS);
                float br = REVEAL_BLUR_RADIUS * f;
                if (br >= 0.5f) {
                    base.setRenderEffect(RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP));
                } else {
                    base.setRenderEffect(null); // 太小当作不糊，避免 createBlurEffect 半径≈0 抛异常
                }
                if (f >= 1f) {
                    blurAtFull[0] = true; // 渐入完成，后续帧不再重建 RenderEffect
                }
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                clearReveal(holder); // 揭完/被取消都收尾：撤模糊/裁剪/蒙版，原图整张清晰
            }
        });
        hd.setTag(R.id.tag_reveal_anim, anim);
        anim.start();
    }

    private Interpolator revealInterpolator() {
        if (sRevealInterpolator == null) {
            sRevealInterpolator = AnimationUtils.loadInterpolator(
                    mContext, android.R.interpolator.fast_out_slow_in);
        }
        return sRevealInterpolator;
    }

    /** 复位揭露的所有临时效果：撤顶层裁剪、撤底层模糊、撤底层黑蒙版前景(全幂等)。 */
    private void clearReveal(@NonNull ViewHolder<RecyIllustDetailBinding> holder) {
        View hd = holder.baseBind.illustHd;
        if (hd.getClipToOutline()) {
            hd.setClipToOutline(false);
            hd.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            hd.invalidateOutline();
        }
        View base = holder.baseBind.illust;
        if (base.getForeground() instanceof RevealScrimDrawable) {
            base.setForeground(null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.setRenderEffect(null); // 无公开 getter，直接置 null(幂等)
        }
    }

    /** 取消正在跑的揭露动画并复位效果(不改可见性；可见性由各复位路径自行处理)。 */
    private void cancelReveal(@NonNull ViewHolder<RecyIllustDetailBinding> holder) {
        View hd = holder.baseBind.illustHd;
        Object prev = hd.getTag(R.id.tag_reveal_anim);
        if (prev instanceof Animator) {
            ((Animator) prev).cancel(); // 触发 onAnimationEnd → clearReveal
        }
        hd.setTag(R.id.tag_reveal_anim, null);
        clearReveal(holder); // 兜底，幂等
    }

    /**
     * 揭露蒙版：整块铺 {@link #color}(带黑色透明度)，中心留一个半径 {@link #holeRadius} 的透明圆洞。
     * 用 EVEN_ODD 填充(矩形 + 圆 → 圆内被挖空)一次 drawPath 完成，不需要 saveLayer/PorterDuff，边缘抗锯齿。
     * 作为 illust_hd 的 foreground 绘制，bounds 自动等于 view 尺寸。
     */
    private static final class RevealScrimDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int color;
        private float holeRadius = 0f;

        RevealScrimDrawable(int color) {
            this.color = color;
            paint.setStyle(Paint.Style.FILL);
        }

        void setHoleRadius(float r) {
            if (r != holeRadius) {
                holeRadius = r;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            path.rewind();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(b.left, b.top, b.right, b.bottom, Path.Direction.CW);
            if (holeRadius > 0f) {
                path.addCircle(b.exactCenterX(), b.exactCenterY(), holeRadius, Path.Direction.CW);
            }
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) { /* 固定色，忽略 */ }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) { /* 不支持 */ }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private void reportPageStatus(int position, @NonNull TaskStatus status) {
        if (pageStatusListener != null) {
            pageStatusListener.onStatusChanged(position, status);
        }
    }

    /**
     * 直接 Glide 读已下载的本地文件（content:// 或 file://），不走 LoadTask 网络下载。
     * 读失败（文件被移动/删除/无权限）就忘掉这页的本地映射、回退网络。observer 由
     * 上层 detachTaskObservers 统一清理，本路径不挂 LiveData observer，不会累积(#912)。
     */
    private void loadFromLocalFile(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize, Uri localUri) {
        boolean isLoadOriginalImage = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        final String imageUrl = IllustDownload.getUrl(allIllust, position,
                isLoadOriginalImage ? Params.IMAGE_RESOLUTION_ORIGINAL : Params.IMAGE_RESOLUTION_LARGE);
        // 与网络路径一致地打 tag，让后续 stale 回调判定、reload 重试逻辑共用一套。
        holder.baseBind.illust.setTag(R.id.tag_image_url, imageUrl);
        holder.baseBind.reload.setVisibility(View.GONE);
        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
        holder.baseBind.reload.setOnClickListener(v -> loadIllust(holder, position, changeSize));

        RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
        requestManager
                .asBitmap()
                .load(localUri)
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.w(e, "[IllustAdapter] local file load FAIL pos=%d, fall back to network", position);
                        localPageUris.remove(position);
                        // Glide forbids starting/clearing a load from inside a Target/RequestListener
                        // callback. loadFromNetwork() observes the per-URL task's *sticky* result
                        // LiveData, which can dispatch synchronously and call Glide.into() — that
                        // clears this very request while it's still in onLoadFailed and throws
                        // "You can't start or clear loads in ... callbacks". Defer to the next main-
                        // loop tick so this callback unwinds first.
                        holder.baseBind.illust.post(() -> {
                            // By the next tick the fragment's view may be gone (user navigated
                            // away). loadFromNetwork() reads mFragment.getViewLifecycleOwner(),
                            // which throws once getView()==null (after onDestroyView). Bail first.
                            if (mFragment == null || mFragment.getView() == null) return;
                            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return;
                            loadIllust(holder, position, changeSize);
                        });
                        return true; // 已接管，网络路径会重新填图
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        holder.baseBind.reload.setVisibility(View.GONE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
    }
}

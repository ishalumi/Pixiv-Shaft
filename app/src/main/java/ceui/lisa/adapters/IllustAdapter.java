package ceui.lisa.adapters;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.ui.task.LoadTask;
import ceui.pixiv.ui.task.NamedUrl;
import ceui.pixiv.ui.task.TaskPool;
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
        // Cancel any in-flight Glide load targeting this ImageView so a late-arriving
        // bitmap from the previous bind can't leak into the recycled holder.
        Glide.with(mFragment).clear(holder.baseBind.illust);
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

        // 命中已下载的本地文件就直读，跳过网络 LoadTask —— 详情页展开多图复用下载结果。
        Uri localUri = localPageUris.get(position);
        if (localUri != null) {
            loadFromLocalFile(holder, position, changeSize, localUri);
            return;
        }
        loadFromNetwork(holder, position, changeSize);
    }

    private void loadFromNetwork(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        final String imageUrl;
        boolean isLoadOriginalImage = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        if (isLoadOriginalImage) {
            imageUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_ORIGINAL);
        } else {
            imageUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_LARGE);
        }

        // Stamp the ImageView with the URL this bind intends to display. Task LiveData
        // observers are attached to the fragment's viewLifecycleOwner and persist across
        // rebinds; a late-arriving callback from a previous position would otherwise
        // push the wrong bitmap into a recycled holder. Every callback below checks the
        // tag before mutating UI so stale callbacks become no-ops.
        holder.baseBind.illust.setTag(R.id.tag_image_url, imageUrl);

        // Reset stale Error-state UI before the new task can fire its first status. Recycled
        // holders and retry-all rebinds both hit this path; without the reset the "重新加载"
        // button stays visible until the new download finishes.
        holder.baseBind.reload.setVisibility(View.GONE);
        holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
        holder.baseBind.progressLayout.donutProgress.setProgress(0);

        holder.baseBind.reload.setOnClickListener(v -> {
            TaskPool.INSTANCE.removeTask(imageUrl);
            loadIllust(holder, position, changeSize);
        });

        LifecycleOwner lifecycleOwner = mFragment.getViewLifecycleOwner();
        String shortUrl = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
        LoadTask task = TaskPool.INSTANCE.getLoadTask(new NamedUrl("", imageUrl), true);
        Timber.d("[IllustAdapter] loadIllust pos=%d, isOriginal=%b, taskId=%d, taskStatus=%s, url=%s",
                position, isLoadOriginalImage, task.getTaskId(), task.getStatus().getValue(), shortUrl);

        final Observer<TaskStatus> statusObserver = status -> {
            boolean tagMatch = imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url));
            if (!tagMatch) {
                Timber.d("[IllustAdapter] status STALE callback ignored. pos=%d, status=%s, url=%s", position, status, shortUrl);
                return;
            }
            Timber.d("[IllustAdapter] status -> %s, pos=%d, url=%s", status, position, shortUrl);
            if (status instanceof TaskStatus.Executing) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                holder.baseBind.progressLayout.donutProgress.setProgress(
                        ((TaskStatus.Executing) status).getPercentage()
                );
            } else if (status instanceof TaskStatus.Finished) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
            } else if (status instanceof TaskStatus.Error) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                holder.baseBind.reload.setVisibility(View.VISIBLE);
                Timber.w("[IllustAdapter] showing reload button. pos=%d, url=%s", position, shortUrl);
            }
            if (pageStatusListener != null) {
                pageStatusListener.onStatusChanged(position, status);
            }
        };

        final Observer<File> resultObserver = file -> {
            if (file == null) {
                Timber.d("[IllustAdapter] result NULL callback. pos=%d, url=%s", position, shortUrl);
                return;
            }
            boolean tagMatch = imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url));
            if (!tagMatch) {
                Timber.d("[IllustAdapter] result STALE callback ignored. pos=%d, url=%s", position, shortUrl);
                return;
            }
            Timber.d("[IllustAdapter] result -> file=%s, exists=%b, size=%d, pos=%d, url=%s",
                    file.getAbsolutePath(), file.exists(), file.length(), position, shortUrl);
            holder.baseBind.reload.setVisibility(View.GONE);
            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);

            RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
            requestManager
                    .asBitmap()
                    .load(file)
                    .transform(new LargeBitmapScaleTransformer())
                    .transition(BitmapTransitionOptions.withCrossFade())
                    .listener(new RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                            Timber.w(e, "[IllustAdapter] Glide bitmap FAIL. pos=%d, model=%s, url=%s", position, model, shortUrl);
                            holder.baseBind.reload.setVisibility(View.VISIBLE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                            Timber.d("[IllustAdapter] Glide bitmap OK. pos=%d, %dx%d, dataSource=%s, url=%s",
                                    position, resource.getWidth(), resource.getHeight(), dataSource.name(), shortUrl);
                            holder.baseBind.reload.setVisibility(View.GONE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                            if (isLoadOriginalImage) {
                                Shaft.getMMKV().encode(imageUrl, true);
                            }
                            return false;
                        }
                    })
                    .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
        };

        task.getStatus().observe(lifecycleOwner, statusObserver);
        task.getResult().observe(lifecycleOwner, resultObserver);

        // Remember how to detach these two observers when this holder is recycled or rebound,
        // so they don't outlive the bind on the per-URL task's (sticky) LiveData. See #912.
        holder.itemView.setTag(R.id.tag_task_observers, (Runnable) () -> {
            task.getStatus().removeObserver(statusObserver);
            task.getResult().removeObserver(resultObserver);
        });
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
                        loadFromNetwork(holder, position, changeSize);
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

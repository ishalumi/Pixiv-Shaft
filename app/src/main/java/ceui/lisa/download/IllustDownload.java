package ceui.lisa.download;

import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;

import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.OutPut;
import ceui.lisa.file.SAFile;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.ImageUrlsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.NovelSeriesItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.download.config.DownloadItems;
import ceui.pixiv.download.model.RelativePath;

public class IllustDownload {

    private static DownloadItem buildDownloadItem(IllustsBean illust, int index) {
        return buildDownloadItem(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    private static DownloadItem buildDownloadItem(IllustsBean illust, int index, String imageResolution) {
        if (illust.isGif()) {
            return null;
        } else if (illust.getPage_count() == 1) {
            DownloadItem item = new DownloadItem(illust, 0);
            item.setUrl(getUrl(illust, 0, imageResolution));
            item.setShowUrl(getShowUrl(illust, 0));
            return item;
        } else {
            DownloadItem item = new DownloadItem(illust, index);
            item.setUrl(getUrl(illust, index, imageResolution));
            item.setShowUrl(getShowUrl(illust, index));
            return item;
        }
    }

    public static void downloadIllustFirstPage(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> downloadIllustFirstPage(illust));
    }

    public static void downloadIllustFirstPageWithResolution(IllustsBean illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                DownloadItem item = buildDownloadItem(illust, 0, imageResolution);
                Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTask(item);
            }
        });
    }

    public static void downloadIllustFirstPage(IllustsBean illust) {
        downloadIllustFirstPageWithResolution(illust, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static void downloadIllustFirstPageWithResolution(IllustsBean illust, String imageResolution) {
        if (illust.getPage_count() == 1) {
            DownloadItem item = buildDownloadItem(illust, 0, imageResolution);
            Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
            Manager.get().addTask(item);
        }
    }

    public static void downloadIllustCertainPage(IllustsBean illust, int index, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                // index!=0 时不合理
                downloadIllustFirstPage(illust);
            } else {
                DownloadItem item = buildDownloadItem(illust, index);
                Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTask(item);
            }
        });
    }

    public static void downloadIllustAllPages(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> downloadIllustAllPages(illust));
    }

    public static void downloadIllustAllPagesWithResolution(IllustsBean illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllustFirstPage(illust, activity);
            } else {
                List<DownloadItem> tempList = new ArrayList<>();
                for (int i = 0; i < illust.getPage_count(); i++) {
                    DownloadItem item = buildDownloadItem(illust, i, imageResolution);
                    tempList.add(item);
                }
                Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTasks(tempList);
            }
        });
    }

    public static void downloadIllustAllPages(IllustsBean illust) {
        if (illust.isGif()){
            downloadGif(illust);
        } else if (illust.getPage_count() == 1) {
            downloadIllustFirstPage(illust);
        } else {
            List<DownloadItem> tempList = new ArrayList<>();
            for (int i = 0; i < illust.getPage_count(); i++) {
                DownloadItem item = buildDownloadItem(illust, i);
                tempList.add(item);
            }
            Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
            Manager.get().addTasks(tempList);
        }
    }


    // downloadCheckedIllustAllPages 已移除：旧的 FragmentMultiDownload 勾选下载入口已废弃，
    // 现在统一通过 download_queue v33 持久化队列（见 ceui.pixiv.ui.bulk.LegacyBatchEnqueue 与 ceui.pixiv.ui.bulk.bulkEnqueueIllusts）。

    public static DownloadItem downloadGif(GifResponse response, IllustsBean illust) {
        return downloadGif(response, illust, false);
    }

    public static DownloadItem downloadGif(GifResponse response, IllustsBean illust, boolean autoSave) {
        DownloadItem item = new DownloadItem(illust, 0);
        item.setAutoSave(autoSave);
        item.setUrl((response.getUgoira_metadata().getZip_urls().getMedium()));
        item.setShowUrl((illust.getImage_urls().getMedium()));
        Manager.get().addTask(item);
        return item;
    }

    public static void downloadGif(IllustsBean illustsBean){
        if(!illustsBean.isGif()){
            return;
        }
        PixivOperate.getGifInfo(illustsBean, new ErrorCtrl<GifResponse>() {
            @Override
            public void next(GifResponse gifResponse) {
                Cache.get().saveModel(Params.ILLUST_ID + "_" + illustsBean.getId(), gifResponse);
                downloadGif(gifResponse, illustsBean, true);
            }
        });
    }

    public static void downloadNovel(BaseActivity<?> activity, NovelSeriesItem novelSeriesItem, String content, Callback<Uri> targetCallback) {
        // 文件名仍按系列合集惯例（NovelSeries_<id>_Chapter_1~N_<title>.txt），
        // 但目录从用户当前的 Novel 命名预设里取——和 Kotlin 端的
        // MergeDownloadNovelSeriesTask、CrossSeriesDownloadTask 走同一规则。
        String mergeName = FileCreator.deleteSpecialWords("NovelSeries_" + novelSeriesItem.getId() + "_Chapter_1~" + novelSeriesItem.getContent_count() + "_" + novelSeriesItem.getTitle() + ".txt");
        RelativePath path = DownloadItems.novelMergeDestinationForSeriesItem(novelSeriesItem, mergeName);
        downloadNovel(activity, path, content, targetCallback);
    }

    public static String truncateTitle(String title, int maxLength) {
        if (title == null)  return " ";
        if (title.length() <= maxLength)  return title;
        if (maxLength < 3) return title.substring(0, maxLength);

        int available = maxLength - 3;
        int front = available / 2;
        int rear = available - front;

        return title.substring(0, front) + "..." + title.substring(title.length() - rear);
    }


    public static String getNovelText( String title , NovelBean novelBean, NovelDetail novelDetail) {
        String content = title +"\n\n"+
                "RawTitle:"+novelBean.getTitle()+"\n"+
                "Date:"+novelBean.getCreate_date().substring(0, 10)+" "+ "Length:"+novelBean.getText_length()+"\n"+
                "Name:"+novelBean.getUser().getName()+"(https://www.pixiv.net/users/"+novelBean.getUser().getId()+ ")\n" +
                "Source:"+"https://www.pixiv.net/novel/show.php?id="+novelBean.getId()+"\n"+
                "Tags:"+Arrays.toString(novelBean.getTagNames())+"\n"+
                "Caption:\n"+novelBean.getCaption().replaceAll("<br />", "\n")+
                "\n>---------------------<\n";
        content=content+ novelDetail.getNovel_text()+"\n\n";
        return content;
    }


    public static void downloadNovel(BaseActivity<?> activity, NovelBean novelBean, NovelDetail novelDetail, Callback<Uri> targetCallback) {
        // 文件路径完全交给用户当前的 Novel 命名预设——和 Kotlin 端的
        // DownloadNovelTask、ReaderV3 导出走同一规则；旧版写死的
        // "Novel_<id>_<title>.txt" 已经废弃。Reader 标题里加系列前缀的视觉
        // 习惯保留在 getNovelText 里（正文头部仍带系列名），文件名不再二次拼接。
        RelativePath path = DownloadItems.novelDestinationFromBean(novelBean);
        String content = getNovelText(buildContentTitle(novelBean), novelBean, novelDetail);
        downloadNovel(activity, path, content, targetCallback);
    }

    /**
     * 仅用于内容头部展示的标题——保留旧版「系列名_章节名」格式；不参与
     * 文件命名（文件命名走预设模板，模板自己有 {title} 占位符）。
     */
    private static String buildContentTitle(NovelBean novelBean) {
        String title = novelBean.getTitle();
        if (novelBean.getSeries() != null && novelBean.getSeries().getTitle() != null) {
            title = novelBean.getSeries().getTitle() + "_" + title;
        }
        return truncateTitle(title, 58);
    }

    /**
     * Inner save: write [content] to a temp file (for FileProvider sharing /
     * "open with" intents) and copy it into MediaStore at [path], which is
     * the user's Novel-bucket path rendered through the active naming
     * preset. The temp filename keeps using [RelativePath.getFilename] —
     * not because MediaStore needs it (MediaStore reads from `path`), but
     * to keep FileProvider URIs human-readable for the share callback.
     */
    public static void downloadNovel(BaseActivity<?> activity, RelativePath path, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, path.getFilename());
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadNovel path " + path.joinTo("/"));
                    OutPut.outPutNovel(activity, textFile, path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadFile displayName " + textFile.getName());
                    OutPut.outPutFile(activity, textFile, textFile.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback){
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadBackupFile displayName " + textFile.getName());
                    OutPut.outPutBackupFile(activity, textFile, textFile.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, Callback<File> fileWriter, Callback<Uri> targetCallback){
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, displayName);
                try {
                    fileWriter.doSomething(textFile);
                    Common.showLog("downloadBackupFile displayName " + textFile.getName());
                    OutPut.outPutBackupFile(activity, textFile, textFile.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static String getUrl(IllustsBean illust, int index) {
        return getUrl(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static String getUrl(IllustsBean illust, int index, String imageResolution) {
        return (getImageUrlByResolution(illust, index, imageResolution));
    }

    private static String getImageUrlByResolution(IllustsBean illust, int index, String imageResolution) {
        ImageUrlsBean imageUrlsBean = getImageUrlsBean(illust, index, imageResolution);
        switch (imageResolution) {
            case Params.IMAGE_RESOLUTION_ORIGINAL:
                return imageUrlsBean.getOriginal();
            case Params.IMAGE_RESOLUTION_LARGE:
                return imageUrlsBean.getLarge();
            case Params.IMAGE_RESOLUTION_MEDIUM:
                return imageUrlsBean.getMedium();
            case Params.IMAGE_RESOLUTION_SQUARE_MEDIUM:
                return imageUrlsBean.getSquare_medium();
            default:
                return imageUrlsBean.getMaxImage();
        }
    }

    private static ImageUrlsBean getImageUrlsBean(IllustsBean illust, int index, String imageResolution) {
        if (illust.getPage_count() == 1) {
            return imageResolution.equals(Params.IMAGE_RESOLUTION_ORIGINAL) ? illust.getMeta_single_page() : illust.getImage_urls();
        } else {
            // Diagnostic only — log if meta_pages looks malformed but still let the
            // original behavior (NPE / IOOB on .get(index)) propagate so we don't
            // mask the bug. Once the root cause is confirmed we can revisit fallback.
            java.util.List<ceui.lisa.models.MetaPagesBean> mp = illust.getMeta_pages();
            if (mp == null || mp.isEmpty() || index < 0 || index >= mp.size()) {
                timber.log.Timber.tag("V3MultiP").w(
                    "[IllustDownload.getImageUrlsBean] SUSPECT: illustId=%d page_count=%d index=%d " +
                        "meta_pages=%s — about to throw on .get(index)",
                    illust.getId(), illust.getPage_count(), index,
                    mp == null ? "null" : ("size=" + mp.size())
                );
            }
            return illust.getMeta_pages().get(index).getImage_urls();
        }
    }

    public static String getShowUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return illust.getImage_urls().getMedium();
        } else {
            return illust.getMeta_pages().get(index).getImage_urls().getMedium();
        }
    }

    public static void check(BaseActivity<?> activity, FeedBack feedBack) {
        if (Shaft.sSettings.getDownloadWay() == 1) {
            if (TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
                activity.setFeedBack(feedBack);
                new QMUIDialog.MessageDialogBuilder(activity)
                        .setTitle(activity.getResources().getString(R.string.string_143))
                        .setMessage(activity.getResources().getString(R.string.string_313))
                        .setSkinManager(QMUISkinManager.defaultInstance(activity))
                        .addAction(0, activity.getResources().getString(R.string.string_142),
                                QMUIDialogAction.ACTION_PROP_NEGATIVE,
                                (dialog, index) -> dialog.dismiss())
                        .addAction(0, activity.getResources().getString(R.string.string_312),
                                (dialog, index) -> {
                                    dialog.dismiss();
                                    BaseActivity.launchSafTreePicker(activity);
                                })
                        .show();
            } else {
                DocumentFile root = SAFile.rootFolder(activity);
                if (root == null || !root.exists() || !root.isDirectory()) {
                    activity.setFeedBack(feedBack);
                    new QMUIDialog.MessageDialogBuilder(activity)
                            .setTitle(activity.getResources().getString(R.string.string_143))
                            .setMessage(activity.getResources().getString(R.string.string_365))
                            .setSkinManager(QMUISkinManager.defaultInstance(activity))
                            .addAction(0, activity.getResources().getString(R.string.string_142),
                                    QMUIDialogAction.ACTION_PROP_NEGATIVE,
                                    (dialog, index) -> dialog.dismiss())
                            .addAction(0, activity.getResources().getString(R.string.string_366),
                                    (dialog, index) -> {
                                        dialog.dismiss();
                                        BaseActivity.launchSafTreePicker(activity);
                                    })
                            .show();
                } else {
                    if (feedBack != null) {
                        try {
                            feedBack.doSomething();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } else {
            if (feedBack != null) {
                try {
                    feedBack.doSomething();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

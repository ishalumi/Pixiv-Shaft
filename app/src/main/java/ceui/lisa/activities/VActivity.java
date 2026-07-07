package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.ViewPager;

import ceui.lisa.R;
import ceui.lisa.core.Container;
import ceui.lisa.core.Mapper;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.ActivityViewPagerBinding;
import ceui.lisa.fragments.FragmentIllust;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.pixiv.ui.detail.ArtworkV3Fragment;
import ceui.lisa.helper.DeduplicateArrayList;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import java.util.Collections;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.loxia.ObjectPool;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class VActivity extends BaseActivity<ActivityViewPagerBinding> {

    private String pageUUID = "";
    private int index = 0;
    private IllustsBean widgetIllust = null;

    @Override
    protected void initBundle(Bundle bundle) {
        pageUUID = bundle.getString(Params.PAGE_UUID);
        index = bundle.getInt(Params.POSITION);
        // widget 点击携带的单张作品：进程被杀后 Container 已空时的兜底数据源
        widgetIllust = (IllustsBean) bundle.getSerializable(Params.WIDGET_ILLUST);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_view_pager;
    }

    @Override
    protected void initView() {
        PageData found = Container.get().getPage(pageUUID);
        // 进程被杀后 Container（内存级 HashMap）已空 → widget 点击会丢数据，之前直接 finish() 闪退回桌面
        //（视频复现：杀掉 app 后点 widget 没反应，刷新一次重新拉活进程才能点开）。
        // widget 的 intent 自带 IllustsBean（Serializable），用它重建单图 PageData，
        // 这样 app 未运行时点击 widget 也能正常打开详情。
        if (found == null && widgetIllust != null) {
            found = new PageData(pageUUID, null, Collections.singletonList(widgetIllust));
            Container.get().addPageToMap(found);
        }
        final PageData pageData = found;
        // [DEBUG-568] PageData 在 Container（进程级单例）或由 widget intent 重建 → 重建 ViewPager；
        // 都没有（进程被杀且非 widget 入口）→ finish()，用户会被多弹回一级
        timber.log.Timber.tag("DEBUG-568").w("VActivity initView pageUUID=%s pageDataFound=%s",
                pageUUID, pageData != null);
        if (pageData != null) {
            baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager(), 0) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    IllustsBean illustsBean = pageData.getList().get(position);
                    if (illustsBean.getId() == 0 || !illustsBean.isVisible()) {
                        return FragmentImageDetail.newInstance(illustsBean.getImage_urls().getMaxImage());
                    } else {
                        // ugoira(动图)不再甩去独立老页 FragmentSingleUgora,和普通插画一样走
                        // V3 / FragmentIllust,由页面内联的 UgoiraPlayerAdapter 自动播放。
                        // 旧的 FragmentSingleIllust 兜底页已删,非 V3 一律走 FragmentIllust。
                        IllustsBean exist = ObjectPool.INSTANCE.getIllust(illustsBean.getId()).getValue();
                        if (exist == null) {
                            ObjectPool.INSTANCE.updateIllust(illustsBean);
                        }
                        if (Shaft.sSettings.isUseArtworkV3()) {
                            return ArtworkV3Fragment.newInstance(illustsBean.getId());
                        } else {
                            return FragmentIllust.newInstance(illustsBean.getId());
                        }
                    }
                }

                @Override
                public int getCount() {
                    return pageData.getList().size();
                }

                @Nullable
                @org.jetbrains.annotations.Nullable
                @Override
                public Parcelable saveState() {
                    Bundle bundle = (Bundle) super.saveState();
                    if (bundle != null) {
                        bundle.putParcelableArray("states", null);
                    }
                    return bundle;
                }
            });
            // offscreenPageLimit=1 keeps 3 fragments attached (prev/current/next) instead of
            // 5. Each ArtworkV3Fragment init fires /v1/illust/detail on ObjectPool miss —
            // 5 parallel fetches reliably trip Pixiv's per-IP 429 rate limit.
            baseBind.viewPager.setOffscreenPageLimit(1);

            ViewPager.OnPageChangeListener listener = new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

                }

                @Override
                public void onPageSelected(int position) {
                    Common.showLog("Container onPageSelected " + position);
                    if (Common.isEmpty(pageData.getList())) {
                        return;
                    }

                    if (position >= pageData.getList().size()) {
                        return;
                    }

                    if (Shaft.sSettings.isSaveViewHistory()) {
                        PixivOperate.insertIllustViewHistory(pageData.getList().get(position));
                    }

                    if (position == (pageData.getList().size() - 1) || position == (pageData.getList().size() - 2)) {
                        String nextUrl = pageData.getNextUrl();
                        if (!TextUtils.isEmpty(nextUrl)) {
                            if (!Container.get().isNetworking()) {
                                Common.showLog("Container 去请求下一页 " + nextUrl);
                                Retro.getAppApi().getNextIllust(nextUrl)
                                        .subscribeOn(Schedulers.newThread())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(new NullCtrl<ListIllust>() {
                                            @Override
                                            public void success(ListIllust listIllust) {
                                                Mapper mapper = new Mapper<ListIllust>();
                                                listIllust = (ListIllust) mapper.apply(listIllust);
                                                Common.showLog("Container 下一页请求成功 ");
                                                Intent intent = new Intent(Params.FRAGMENT_ADD_DATA);
                                                intent.putExtra(Params.PAGE_UUID, pageUUID);
                                                intent.putExtra(Params.CONTENT, listIllust);
                                                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);

                                                // pageData.getList().addAll(listIllust.getList());
                                                DeduplicateArrayList.addAllWithNoRepeat(pageData.getList(), listIllust.getList());
                                                pageData.setNextUrl(listIllust.getNextUrl());
                                                if (baseBind.viewPager.getAdapter() != null) {
                                                    baseBind.viewPager.getAdapter().notifyDataSetChanged();
                                                }
                                            }

                                            @Override
                                            public void must() {
                                                super.must();
                                                Container.get().setNetworking(false);
                                            }

                                            @Override
                                            public void subscribe(Disposable d) {
                                                super.subscribe(d);
                                                Container.get().setNetworking(true);
                                            }
                                        });
                            } else {
                                Common.showLog("Container 不去请求下一页 00");
                            }
                        } else {
                            Common.showLog("Container 不去请求下一页 11");
                        }
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {

                }
            };
            baseBind.viewPager.addOnPageChangeListener(listener);

            if (index < pageData.getList().size()) {
                baseBind.viewPager.setCurrentItem(index);
            }

            if (index == 0) {
                baseBind.viewPager.post(() -> listener.onPageSelected(baseBind.viewPager.getCurrentItem()));
            }
        } else {
            finish();
        }
    }

    @Override
    protected void initData() {

    }

    @Override
    protected void onDestroy() {
        PixivOperate.clearBack();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        //通知外界列表，滚动到正确的位置
        Intent intent = new Intent(Params.FRAGMENT_SCROLL_TO_POSITION);
        intent.putExtra(Params.INDEX, baseBind.viewPager.getCurrentItem());
        intent.putExtra(Params.PAGE_UUID, pageUUID);
        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
        super.onPause();
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}

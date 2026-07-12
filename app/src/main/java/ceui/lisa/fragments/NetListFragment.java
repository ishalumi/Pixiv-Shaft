package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.CallSuper;
import androidx.databinding.ViewDataBinding;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;


import com.scwang.smart.refresh.footer.ClassicsFooter;
import com.scwang.smart.refresh.header.FalsifyFooter;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.adapters.SimpleUserAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.adapters.UserHAdapter;
import ceui.lisa.core.AutoLoadPolicy;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.Starable;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.notification.CommonReceiver;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import timber.log.Timber;

/**
 * 联网获取xx列表，
 *
 * @param <Layout>   这个列表的LayoutBinding.
 * @param <Response> Type: {@link ListIllust}这次请求的Response.
 * @param <Item>     这个列表的单个Item实体类.
 */
public abstract class NetListFragment<Layout extends ViewDataBinding,
        Response extends ListShow<Item>, Item> extends ListFragment<Layout, Item> {

    protected RemoteRepo<Response> mRemoteRepo;
    protected Response mResponse;//ListIllust
    protected BroadcastReceiver mReceiver = null, dataReceiver = null, scrollReceiver = null;
    protected boolean isLoading = false;
    protected final AutoLoadPolicy mAutoLoadPolicy = new AutoLoadPolicy();

    /**
     * Fresh the page.
     * */
    @Override
    public void fresh() {
        setEmptyStateVisible(false);
        if (isLoading) {
            //自动加载下一页进行中(#729)又触发下拉刷新时，结束刷新动画避免转圈卡死
            mRefreshLayout.finishRefresh();
            return;
        }
        isLoading = true;
        mAutoLoadPolicy.reset();//用户主动刷新，重置自动加载预算 (#729)
        //Get first data
        mRemoteRepo.getFirstData(new NullCtrl<Response>()
        {
            /**
             * The method is called when the response is successfully received
             * @param response The response of previous request
             *          <p>
             *                 For example:
             *          </p>
             *                 <p>
             *                 Request for the daily rank list,response is an ArrayList of IllustsBean
             *                 </p>
             * */
            @Override
            public void success(Response response) {
                Common.showLog("trace 000");
                if (!isAdded()) {
                    return;
                }
                Common.showLog("trace 111");
                mResponse = response;
                tryCatchResponse(mResponse);
                List<Item> mResponseList = mResponse.getList();
                //Show the received data
                if (!Common.isEmpty(mResponseList)) {
                    Common.showLog("trace 222 " + mAdapter.getItemCount());
                    beforeFirstLoad(mResponseList);
                    int beforeLoadSize = getStartSize();
                    mModel.load(mResponseList, true);
                    if (mRemoteRepo.hasEffectiveUserFollowStatus()) {
                        mModel.tidyAppViewModel();
                    }
                    allItems = mModel.getContent();//Get all the critical information such as IllustBean list
                    int afterLoadSize = getStartSize();
                    onFirstLoaded(mResponseList);
                    mRecyclerView.setVisibility(View.VISIBLE);
                    setEmptyStateVisible(false);
                    mAdapter.notifyItemRangeInserted(beforeLoadSize, afterLoadSize - beforeLoadSize);
                    Common.showLog("trace 777 " + mAdapter.getItemCount() + " allItems.size():" + allItems.size() + " modelSize:" + mModel.getContent().size());
                } else {
                    Common.showLog("trace 333");
                    showEmptyState();
                }
                Common.showLog("trace 444");
                mRemoteRepo.setNextUrl(mResponse.getNextUrl());
                mAdapter.setNextUrl(mResponse.getNextUrl());
                if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
                    Common.showLog("trace 555");
                    mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                } else {
                    Common.showLog("trace 666");
                    mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                }
            }

            @Override
            public void must(boolean isSuccess) {
                mRefreshLayout.finishRefresh(isSuccess);
                isLoading = false;
                if (isSuccess) {
                    autoLoadIfAllBlocked();
                }
            }

            @Override
            public void error(Throwable e) {
                //不调 super.error()：ErrorCtrl 只处理 HttpException，断网/超时/DNS 失败等
                //IOException 会被静默吞掉；且 errorBody 只能读一次，留给 getHumanReadableMessage 解析
                Timber.e(e, "NetListFragment fresh failed");
                must(false);
                if (!isAdded()) {
                    return;
                }
                //把错误暴露在页面中央，而不是误导性的"这里什么都没有呢"
                showError(e);
            }
        }
        );
    }

    private void tryCatchResponse(Response response) {
        try {
            onResponse(response);
        } catch (Exception e) {
            Timber.e(e, "onResponse failed");
        }
    }

    @Override
    public void loadMore() {
        if (!TextUtils.isEmpty(mRemoteRepo.getNextUrl())) {
            if(isLoading) return;
            isLoading = true;
            mRemoteRepo.getNextData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    if (!isAdded()) {
                        return;
                    }
                    mResponse = response;
                    List<Item> mResponseList = mResponse.getList();
                    if (!Common.isEmpty(mResponseList)) {
                        beforeNextLoad(mResponseList);
                        int beforeLoadSize = getStartSize();
                        mModel.load(mResponseList, false);
                        if (mRemoteRepo.hasEffectiveUserFollowStatus()) {
                            mModel.tidyAppViewModel(mResponseList);
                        }
                        allItems = mModel.getContent();
                        int afterLoadSize = getStartSize();
                        onNextLoaded(mResponseList);
                        //首页被整页屏蔽时 fresh() 会隐藏列表，自动加载找回内容后恢复显示 (#729)
                        if (mRecyclerView.getVisibility() != View.VISIBLE) {
                            mRecyclerView.setVisibility(View.VISIBLE);
                            setEmptyStateVisible(false);
                        }
                        mAdapter.notifyItemRangeInserted(beforeLoadSize, afterLoadSize - beforeLoadSize);
                    }
                    mRemoteRepo.setNextUrl(mResponse.getNextUrl());
                    mAdapter.setNextUrl(mResponse.getNextUrl());
                    if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
                        mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                    } else {
                        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                }

                @Override
                public void must(boolean isSuccess) {
                    mRefreshLayout.finishLoadMore(isSuccess);
                    isLoading = false;
                    if (isSuccess) {
                        autoLoadIfAllBlocked();
                    }
                }

                @Override
                public void error(Throwable e) {
                    //同 fresh()：不走 ErrorCtrl，把错误暴露给用户（列表已有内容时 Toast，为空时显示在页面中央）
                    Timber.e(e, "NetListFragment loadMore failed");
                    must(false);
                    if (!isAdded()) {
                        return;
                    }
                    showError(e);
                }
            });
        } else {
            if (mRemoteRepo.showNoDataHint()) {
                Common.showToast(getString(R.string.string_224));
            }
            mRefreshLayout.finishLoadMore();
        }
    }

    /**
     * 屏蔽过多时整页作品可能被 {@link ceui.lisa.core.Mapper} 全部过滤掉，
     * 列表为空导致无法滑动，也就无法触发加载下一页 (#729)。
     * 这里在每页加载成功后检查：列表仍为空且还有下一页时，自动加载下一页，
     * 直到有内容显示或达到 {@link AutoLoadPolicy#MAX_AUTO_LOAD_TIMES} 次上限。
     */
    private void autoLoadIfAllBlocked() {
        if (mAutoLoadPolicy.shouldAutoLoad(getCount(), mRemoteRepo.getNextUrl())) {
            Common.showLog("autoLoadIfAllBlocked, auto load attempt " + mAutoLoadPolicy.getAutoLoadCount());
            loadMore();
        }
    }

    @Override
    protected void initData() {
        mRemoteRepo = (RemoteRepo<Response>) mModel.getBaseRepo();
        super.initData();
    }

    public void onResponse(Response response) {

    }

    @CallSuper
    @Override
    public void onAdapterPrepared() {
        mAdapter.setUuid(uuid);
        //注册本地广播
        if (mAdapter instanceof IAdapter || mAdapter instanceof EventAdapter) {
            {
                IntentFilter intentFilter = new IntentFilter();
                mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
                intentFilter.addAction(Params.LIKED_ILLUST);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
            }
            if (mAdapter instanceof IAdapter) {
                addPageLoadReceiver();
                addPageScrollReceiver();
            }
        } else if (mAdapter instanceof UAdapter || mAdapter instanceof UserHAdapter || mAdapter instanceof SimpleUserAdapter) {
            IntentFilter intentFilter = new IntentFilter();
            mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
            intentFilter.addAction(Params.LIKED_USER);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
        } else if (mAdapter instanceof NAdapter) {
            IntentFilter intentFilter = new IntentFilter();
            mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
            intentFilter.addAction(Params.LIKED_NOVEL);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
        }

        // 预加载
        if (mAdapter instanceof IAdapter) {
            mAdapter.onPreload = this::loadMore;
        }
    }

    @Override
    public void onDestroy() {
        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
        }
        if (dataReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(dataReceiver);
        }
        if (scrollReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(scrollReceiver);
        }
        super.onDestroy();
    }

    private void addPageLoadReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        dataReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    //接受VActivity传过来的ListIllust 数据
                    final String intentUUID = intent.getStringExtra(Params.PAGE_UUID);
                    PageData pageData = Container.get().getPage(intentUUID);
                    if (pageData != null && TextUtils.equals(pageData.getUUID(), uuid)) {
                        ListIllust listIllust = (ListIllust) bundle.getSerializable(Params.CONTENT);
                        if (listIllust != null && !Common.isEmpty(listIllust.getList())) {
                            if (!isAdded()) {
                                return;
                            }
                            mResponse = (Response) listIllust;
                            List<Item> mResponseList = mResponse.getList();
                            if (!Common.isEmpty(mResponseList)) {
                                beforeNextLoad(mResponseList);
                                int beforeLoadSize = getStartSize();
                                mModel.load(mResponseList, false);
                                if (mRemoteRepo.hasEffectiveUserFollowStatus()) {
                                    mModel.tidyAppViewModel(mResponseList);
                                }
                                allItems = mModel.getContent();
                                int afterLoadSize = getStartSize();
                                onNextLoaded(mResponseList);
                                mAdapter.notifyItemRangeInserted(beforeLoadSize, afterLoadSize - beforeLoadSize);
                            }
                            mRemoteRepo.setNextUrl(mResponse.getNextUrl());
                            mAdapter.setNextUrl(mResponse.getNextUrl());
                            if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
                                mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                            } else {
                                mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                            }
                        }
                    }
                }
            }
        });
        intentFilter.addAction(Params.FRAGMENT_ADD_DATA);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(dataReceiver, intentFilter);
    }

    private void addPageScrollReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        scrollReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    int index = bundle.getInt(Params.INDEX);
                    String pageUUID = bundle.getString(Params.PAGE_UUID);
                    if (TextUtils.equals(pageUUID, uuid)) {
                        try {
                            mRecyclerView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mRecyclerView.smoothScrollToPosition(index + mAdapter.headerSize());
                                }
                            }, 200L);
                        } catch (Exception e) {
                            Timber.e(e, "scrollToPosition failed");
                        }
                    }
                }
            }
        });
        intentFilter.addAction(Params.FRAGMENT_SCROLL_TO_POSITION);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(scrollReceiver, intentFilter);
    }
}

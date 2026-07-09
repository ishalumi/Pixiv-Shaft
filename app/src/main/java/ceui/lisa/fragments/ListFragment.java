package ceui.lisa.fragments;

import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.scwang.smart.refresh.header.FalsifyFooter;
import com.scwang.smart.refresh.header.FalsifyHeader;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnLoadMoreListener;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;

import java.util.List;
import java.util.function.Consumer;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.helper.StaggeredManager;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.view.SpacesItemDecoration;
import ceui.lisa.viewmodel.BaseModel;
import ceui.loxia.ObjectPool;
import ceui.loxia.RefreshStateKt;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;
import timber.log.Timber;

public abstract class ListFragment<Layout extends ViewDataBinding, Item>
        extends BaseLazyFragment<Layout> {

    public static final long animateDuration = 400L;
    public static final int PAGE_SIZE = 20;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected ImageView noData;
    protected RelativeLayout emptyRela;
    protected View emptyScroller;
    protected TextView noDataText, noDataErrorDetail;
    protected BaseAdapter<?, ? extends ViewDataBinding> mAdapter;
    protected List<Item> allItems = null;
    protected BaseModel<Item> mModel;
    protected Toolbar mToolbar;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_base_list;
    }

    public abstract BaseAdapter<?, ? extends ViewDataBinding> adapter();

    public abstract BaseRepo repository();

    public void onAdapterPrepared() {

    }

    @Override
    public void initModel() {
        mModel = (BaseModel<Item>) new ViewModelProvider(this).get(modelClass());
        allItems = mModel.getContent();
        if (mModel.getBaseRepo() == null) {
            mModel.setBaseRepo(repository());
        }
    }

    public Class<? extends BaseModel> modelClass() {
        return BaseModel.class;
    }

    @Override
    public void initView() {

        mToolbar = rootView.findViewById(R.id.toolbar);
        if (mToolbar != null) {
            initToolbar(mToolbar);
        }

        mRecyclerView = rootView.findViewById(R.id.recyclerView);
        initRecyclerView();
        mRecyclerView.setItemAnimator(animation());

        mRefreshLayout = rootView.findViewById(R.id.refreshLayout);
        mRefreshLayout.setDragRate(0.8f); // 阻尼效果太小，会导致滑动距离增大，动画不跟手
        mRefreshLayout.setHeaderTriggerRate(1.0f); // 触发刷新位置，默认为 1.0*header高度
        mRefreshLayout.setHeaderMaxDragRate(1.5f); // 最大下拉位置
        noData = rootView.findViewById(R.id.no_data);
        emptyRela = rootView.findViewById(R.id.no_data_rela);
        emptyScroller = rootView.findViewById(R.id.emptyScroller);
        noDataText = rootView.findViewById(R.id.no_data_text);
        noDataErrorDetail = rootView.findViewById(R.id.no_data_error_detail);
        emptyRela.setOnClickListener(v -> {
            setEmptyStateVisible(false);
            mRefreshLayout.autoRefresh();
        });
        mRefreshLayout.setRefreshHeader(mModel.getBaseRepo().enableRefresh() ?
                mModel.getBaseRepo().getHeader(mContext) : new FalsifyHeader(mContext));
        mRefreshLayout.setRefreshFooter(mModel.getBaseRepo().hasNext() ?
                mModel.getBaseRepo().getFooter(mContext) : new FalsifyFooter(mContext));

        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                try {
                    if (mRecyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager
                            && mRecyclerView.getItemAnimator() == null) {
                        mRecyclerView.setItemAnimator(animation());
                    }
                    clear();
                    fresh();
                } catch (Exception e) {
                    Timber.e(e, "onRefresh failed");
                    mRefreshLayout.finishRefresh(false);
                    showError(e);
                }
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                try {
                    if (mRecyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager
                            && mRecyclerView.getItemAnimator() != null) {
                        mRecyclerView.setItemAnimator(null);
                    }
                    if (mModel.getBaseRepo().hasNext()) {
                        loadMore();
                    } else {
                        mRefreshLayout.finishLoadMore();
                        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                } catch (Exception e) {
                    Timber.e(e, "onLoadMore failed");
                    mRefreshLayout.finishLoadMore(false);
                    showError(e);
                }
            }
        });

        allItems = mModel.getContent();
        mAdapter = adapter();
        if (mAdapter != null) {
            mRecyclerView.setAdapter(mAdapter);
        }

        onAdapterPrepared();

        if (!isLazy()) {
            //进页面主动刷新
            if (autoRefresh() && !mModel.isLoaded()) {
                // [DEBUG-568] 网络重载触发点：mModel(ViewModel) 是空的。
                // 首次进页面这是正常路径；但如果同一个页面（用户已经看过内容）返回时又走到这里，
                // 说明 Activity 被系统销毁过 → ViewModel 被清空 → 这就是 issue #568 用户看到的"重新加载"
                Timber.tag("DEBUG-568").w("RELOAD-TRIGGER %s autoRefresh: ViewModel为空, 发起网络加载", className);
                mRefreshLayout.autoRefresh();
            } else if (autoRefresh() && mModel.isLoaded()) {
                // [DEBUG-568] 对照组：ViewModel 还活着（数据还在），不需要网络重载
                Timber.tag("DEBUG-568").w("NO-RELOAD %s ViewModel数据还在(%s条), 直接复用", className,
                        allItems != null ? allItems.size() : 0);
            }
        }
    }

    public void refresh() {
        mRefreshLayout.autoRefresh();
    }

    @Override
    public void lazyData() {
        //进页面主动刷新
        if (autoRefresh() && !mModel.isLoaded()) {
            // [DEBUG-568] 同 initView 里的 RELOAD-TRIGGER，lazy 路径
            Timber.tag("DEBUG-568").w("RELOAD-TRIGGER(lazy) %s autoRefresh: ViewModel为空, 发起网络加载", className);
            mRefreshLayout.autoRefresh();
        }
    }

    public void forceRefresh() {
        scrollToTop(() -> mRefreshLayout.autoRefresh());
    }

    public void scrollToTop(FeedBack feedBack) {
        try {
            mRecyclerView.smoothScrollToPosition(0);
            mRecyclerView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (feedBack != null) {
                        feedBack.doSomething();
                    }
                }
            }, animateDuration);
        } catch (Exception e) {
            Timber.e(e, "scrollToTop failed");
        }
    }

    public void scrollToTop() {
        scrollToTop(null);
    }

    public abstract void fresh();

    public abstract void loadMore();

    /**
     * 指定是否显示Toolbar
     *
     * @return default true
     */
    public boolean showToolbar() {
        return true;
    }

    /**
     * 指定Toolbar title
     *
     * @return title
     */
    public String getToolbarTitle() {
        return "";
    }


    public void initRecyclerView() {
        verticalRecyclerView();
    }

    public void verticalRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }

    protected void staggerRecyclerView() {
        StaggeredManager manager = new StaggeredManager(
                Shaft.sSettings.getLineCount(), StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(
                DensityUtil.dp2px(8.0f)));
    }

    /**
     * 决定刚进入页面是否直接刷新，一般都是直接刷新，但是FragmentHotTag，不要直接刷新
     *
     * @return default true
     */
    public boolean autoRefresh() {
        return true;
    }

    public void initToolbar(Toolbar toolbar) {
        if (showToolbar()) {
            toolbar.setVisibility(View.VISIBLE);
            TextView title = toolbar.findViewById(R.id.toolbar_title);
            if (title != null) {
                title.setText(getToolbarTitle());
                title.setMovementMethod(ScrollingMovementMethod.getInstance());
                title.setHorizontallyScrolling(true);
            } else {
                toolbar.setTitle(getToolbarTitle());
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        } else {
            toolbar.setVisibility(View.GONE);
        }
    }

    public void beforeFirstLoad(List<Item> items) {

    }

    public void beforeNextLoad(List<Item> items) {

    }

    public void onFirstLoaded(List<Item> items) {
        items.forEach(item -> {
            if (item instanceof IllustsBean) {
                ObjectPool.INSTANCE.updateIllust((IllustsBean) item);
            } else if (item instanceof ListTrendingtag.TrendTagsBean) {
                ObjectPool.INSTANCE.updateIllust(((ListTrendingtag.TrendTagsBean) item).getIllust());
            }
        });
    }

    public void onNextLoaded(List<Item> items) {
        items.forEach(item -> {
            if (item instanceof IllustsBean) {
                ObjectPool.INSTANCE.updateIllust((IllustsBean) item);
            }
        });
    }

    /**
     * mAdapter is not null
     * Clear all items on the page
     */
    public void clear() {
        if (mAdapter != null) {
            mAdapter.clear();
        }
    }

    public void clearAndRefresh() {
        clear();
        if (mRefreshLayout != null) {
            mRefreshLayout.autoRefresh();
        }
    }

    public BaseItemAnimator animation() {
        //设置item动画
        BaseItemAnimator baseItemAnimator = new LandingAnimator();
        baseItemAnimator.setAddDuration(animateDuration);
        baseItemAnimator.setRemoveDuration(animateDuration);
        baseItemAnimator.setMoveDuration(animateDuration);
        baseItemAnimator.setChangeDuration(animateDuration);
        return baseItemAnimator;
    }

    public int getStartSize() {
        return allItems.size() + mAdapter.headerSize();
    }

    public int getCount() {
        return allItems == null ? 0 : allItems.size();
    }

    /**
     * 请求失败时把错误暴露给用户，而不是吞掉：
     * 列表为空 → 错误信息直接显示在页面中央（替代"这里什么都没有呢"），点击空状态区域可重试；
     * 列表已有内容 → Toast 提示，不遮挡已有内容。
     */
    protected void showError(Throwable e) {
        String message = RefreshStateKt.getHumanReadableMessage(e, mContext);
        if (getCount() == 0) {
            if (noDataText != null) {
                noDataText.setText(R.string.list_load_failed_tap_retry);
            }
            if (noDataErrorDetail != null) {
                noDataErrorDetail.setText(message);
                noDataErrorDetail.setVisibility(View.VISIBLE);
            }
            mRecyclerView.setVisibility(View.INVISIBLE);
            setEmptyStateVisible(true);
        } else {
            Common.showToast(message);
        }
    }

    /**
     * 请求成功但确实没有数据：显示"这里什么都没有呢"，并清掉上一次可能残留的错误信息
     */
    protected void showEmptyState() {
        if (noDataText != null) {
            noDataText.setText(R.string.string_243);
        }
        if (noDataErrorDetail != null) {
            noDataErrorDetail.setVisibility(View.GONE);
        }
        mRecyclerView.setVisibility(View.INVISIBLE);
        setEmptyStateVisible(true);
    }

    /**
     * 空态/错误态显隐统一收口:fragment_base_list 把空态包进了 NestedScrollView
     * (@id/emptyScroller),让空态下的拖动也能驱动宿主 CoordinatorLayout/AppBar 联动
     * (否则 UserActivityV3 这类折叠头页面在空态下头部"凝固"拖不动),两层必须同步显隐。
     * 包装层隐藏用 GONE:fragment_base_list 被 ~50 个列表页共用,INVISIBLE 会让这层
     * 满屏 NestedScrollView 白白参与每次 measure/layout。
     * 其他布局没有这层包装(emptyScroller 为 null)时退化为只切 no_data_rela,行为不变。
     */
    protected void setEmptyStateVisible(boolean visible) {
        if (emptyRela != null) {
            emptyRela.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
        if (emptyScroller != null) {
            emptyScroller.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}

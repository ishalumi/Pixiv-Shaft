package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smart.refresh.header.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.BookedTagAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBookedTagBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListTag;
import ceui.lisa.models.TagsBean;
import ceui.lisa.repo.BookedTagRepo;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;

public class FragmentBookedTag extends NetListFragment<FragmentBookedTagBinding,
        ListTag, TagsBean> {

    private String starType = "";
    private int type = 0;

    // 全量镜像（虚拟项 + 所有已加载的 API 标签）；过滤的真源
    private final List<TagsBean> mFullList = new ArrayList<>();

    private EditText mSearchInput;
    private ImageView mSearchClear;
    private ProgressBar mPreloadProgress;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingFilter;

    private String mQuery = "";
    private boolean mPreloading = false;
    private boolean mFilterMode = false;

    /**
     * @param starType public/private 公开收藏或者私人收藏
     * @return FragmentBookedTag
     */
    public static FragmentBookedTag newInstance(String starType) {
        Bundle args = new Bundle();
        args.putString(Params.STAR_TYPE, starType);
        FragmentBookedTag fragment = new FragmentBookedTag();
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * @param type 0 插画 1 小说
     * @param starType public/private 公开收藏或者私人收藏
     * @return FragmentBookedTag
     */
    public static FragmentBookedTag newInstance(int type, String starType) {
        Bundle args = new Bundle();
        args.putInt(Params.DATA_TYPE, type);
        args.putString(Params.STAR_TYPE, starType);
        FragmentBookedTag fragment = new FragmentBookedTag();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        starType = bundle.getString(Params.STAR_TYPE);
        type = bundle.getInt(Params.DATA_TYPE, 0);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_booked_tag;
    }

    @Override
    public RemoteRepo<ListTag> repository() {
        return new BookedTagRepo(type, starType);
    }

    @Override
    public BaseAdapter<TagsBean, RecyBookTagBinding> adapter() {
        return new BookedTagAdapter(allItems, mContext, false).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(type == 1 ? Params.FILTER_NOVEL : Params.FILTER_ILLUST);
                intent.putExtra(Params.CONTENT, allItems.get(position).getName());
                intent.putExtra(Params.STAR_TYPE, starType);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
                mActivity.finish();
            }
        });
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_244);
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
    }

    @Override
    public void initView() {
        super.initView();
        mSearchInput = rootView.findViewById(R.id.searchInput);
        mSearchClear = rootView.findViewById(R.id.searchClear);
        mPreloadProgress = rootView.findViewById(R.id.searchPreloadProgress);

        mSearchClear.setOnClickListener(v -> mSearchInput.setText(""));
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim();
                mSearchClear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                mQuery = q;
                if (mPendingFilter != null) mUiHandler.removeCallbacks(mPendingFilter);
                mPendingFilter = FragmentBookedTag.this::onQuerySettled;
                // 清空走立即生效（用户明确意图，没必要 debounce）
                mUiHandler.postDelayed(mPendingFilter, q.isEmpty() ? 0L : 200L);
            }
        });
    }

    private void onQuerySettled() {
        if (mQuery.isEmpty()) {
            mFilterMode = false;
            applyFilter();
            if (!mPreloading && mRefreshLayout != null) {
                mRefreshLayout.setEnableRefresh(true);
                mRefreshLayout.setEnableLoadMore(true);
            }
        } else {
            mFilterMode = true;
            if (mRefreshLayout != null) {
                mRefreshLayout.setEnableRefresh(false);
                mRefreshLayout.setEnableLoadMore(false);
            }
            if (!mPreloading && mRemoteRepo != null
                    && !TextUtils.isEmpty(mRemoteRepo.getNextUrl())) {
                startPreloadAll();
            }
            applyFilter();
        }
    }

    @Override
    public void onFirstLoaded(List<TagsBean> tagsBeans) {
        super.onFirstLoaded(tagsBeans);
        //全部
        TagsBean all = new TagsBean();
        all.setCount(-1);
        all.setName("");
        allItems.add(0, all);

        //未分类
        TagsBean unSeparated = new TagsBean();
        unSeparated.setCount(-1);
        unSeparated.setName("未分類");
        allItems.add(0, unSeparated);

        // 重建镜像；下拉刷新会落到这条路径，顺手清掉搜索状态
        mFullList.clear();
        mFullList.addAll(allItems);
        mPreloading = false;
        mFilterMode = false;
        if (mPreloadProgress != null) mPreloadProgress.setVisibility(View.GONE);
        if (mSearchInput != null && mSearchInput.getText().length() > 0) {
            mSearchInput.setText("");
        }
        mQuery = "";
    }

    @Override
    public void onNextLoaded(List<TagsBean> items) {
        super.onNextLoaded(items);
        if (items != null) mFullList.addAll(items);
    }

    private void startPreloadAll() {
        mPreloading = true;
        if (mPreloadProgress != null) mPreloadProgress.setVisibility(View.VISIBLE);
        preloadOne();
    }

    private void preloadOne() {
        if (!isAdded()) {
            mPreloading = false;
            return;
        }
        if (mRemoteRepo == null || TextUtils.isEmpty(mRemoteRepo.getNextUrl())) {
            finishPreload();
            return;
        }
        isLoading = true;
        mRemoteRepo.getNextData(new NullCtrl<ListTag>() {
            @Override
            public void success(ListTag response) {
                if (!isAdded()) return;
                List<TagsBean> respList = response.getList();
                if (respList != null && !respList.isEmpty()) {
                    mFullList.addAll(respList);
                }
                mRemoteRepo.setNextUrl(response.getNextUrl());
                if (mAdapter != null) mAdapter.setNextUrl(response.getNextUrl());
                applyFilter();
            }
            @Override
            public void must(boolean isSuccess) {
                isLoading = false;
                if (!isAdded()) {
                    mPreloading = false;
                    return;
                }
                // 出错就停在这一页（nextUrl 没更新，递归会死循环）；已加载的部分继续可搜
                if (!isSuccess) {
                    finishPreload();
                    return;
                }
                if (mRemoteRepo != null && !TextUtils.isEmpty(mRemoteRepo.getNextUrl())) {
                    preloadOne();
                } else {
                    finishPreload();
                }
            }
        });
    }

    private void finishPreload() {
        mPreloading = false;
        if (mPreloadProgress != null) mPreloadProgress.setVisibility(View.GONE);
        // 预加载之后 nextUrl 为空时，把 footer 换成 FalsifyFooter，避免用户清空搜索再上拉
        // 时被框架弹一个误导性的"没有更多了"toast。
        if (mRefreshLayout != null && mRemoteRepo != null
                && TextUtils.isEmpty(mRemoteRepo.getNextUrl())) {
            mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        }
        if (!mFilterMode && mRefreshLayout != null) {
            mRefreshLayout.setEnableRefresh(true);
            mRefreshLayout.setEnableLoadMore(true);
        }
    }

    private void applyFilter() {
        if (mAdapter == null || allItems == null) return;
        String q = mQuery == null ? "" : mQuery.toLowerCase(Locale.getDefault());
        allItems.clear();
        if (q.isEmpty()) {
            allItems.addAll(mFullList);
        } else {
            for (TagsBean tag : mFullList) {
                // 虚拟项（"全部" / "未分類"，count == -1）在搜索时隐藏
                if (tag.getCount() == -1) continue;
                String name = tag.getName() == null ? ""
                        : tag.getName().toLowerCase(Locale.getDefault());
                String tname = tag.getTranslated_name() == null ? ""
                        : tag.getTranslated_name().toLowerCase(Locale.getDefault());
                if (name.contains(q) || tname.contains(q)) {
                    allItems.add(tag);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
        if (emptyRela != null) {
            if (allItems.isEmpty() && !q.isEmpty()) {
                emptyRela.setVisibility(View.VISIBLE);
            } else if (!allItems.isEmpty()) {
                emptyRela.setVisibility(View.INVISIBLE);
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (mPendingFilter != null) mUiHandler.removeCallbacks(mPendingFilter);
        super.onDestroyView();
    }
}

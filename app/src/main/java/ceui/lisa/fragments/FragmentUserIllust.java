package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.feature.FeatureEntity;
import ceui.lisa.helper.UserIllustJumpHelper;
import ceui.lisa.model.ListIllust;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.repo.UserIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.pixiv.db.queue.WorkType;
import ceui.pixiv.ui.bulk.BulkActions;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * 某人創作的插畫
 */
public class FragmentUserIllust extends NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean> {

    private int userID;
    private boolean showToolbar = false;
    private int initialOffset = 0;
    private String targetDate;
    private FragmentUserIllustViewModel vm;

    public static FragmentUserIllust newInstance(int userID, boolean paramShowToolbar) {
        return newInstance(userID, paramShowToolbar, 0, null);
    }

    public static FragmentUserIllust newInstance(int userID, boolean paramShowToolbar, int initialOffset) {
        return newInstance(userID, paramShowToolbar, initialOffset, null);
    }

    public static FragmentUserIllust newInstance(int userID, boolean paramShowToolbar, int initialOffset, String targetDate) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        args.putInt(Params.INITIAL_OFFSET, initialOffset);
        if (targetDate != null) args.putString(Params.TARGET_DATE, targetDate);
        FragmentUserIllust fragment = new FragmentUserIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        showToolbar = bundle.getBoolean(Params.FLAG);
        initialOffset = bundle.getInt(Params.INITIAL_OFFSET, 0);
        targetDate = bundle.getString(Params.TARGET_DATE);
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> response) {
        super.onFirstLoaded(response);
        scrollToTargetDate(response);
    }

    private void scrollToTargetDate(List<IllustsBean> items) {
        if (TextUtils.isEmpty(targetDate) || items == null || items.isEmpty()) return;
        int hit = -1;
        for (int i = 0; i < items.size(); i++) {
            String cd = items.get(i).getCreate_date();
            if (cd == null || cd.length() < 10) continue;
            // ISO 字符串前 10 位即 yyyy-MM-dd，可按字典序比较
            if (cd.substring(0, 10).compareTo(targetDate) <= 0) {
                hit = i;
                break;
            }
        }
        if (hit < 0) hit = items.size() - 1;
        final int adapterPos = hit + mAdapter.headerSize();
        if (mRecyclerView != null) {
            mRecyclerView.postDelayed(() -> {
                scrollToAdapterPos(adapterPos);
                highlightItemAt(adapterPos, 5);
            }, 200L);
        }
        targetDate = null; // 一次性消费，避免刷新/loadMore 时再次滚
    }

    private void scrollToAdapterPos(int pos) {
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (lm instanceof StaggeredGridLayoutManager) {
            // StaggeredGridLayoutManager.scrollToPosition 会错乱 span 分配导致 decoration 边距错位
            StaggeredGridLayoutManager sglm = (StaggeredGridLayoutManager) lm;
            sglm.scrollToPositionWithOffset(pos, 0);
            mRecyclerView.post(sglm::invalidateSpanAssignments);
        } else {
            mRecyclerView.scrollToPosition(pos);
        }
    }

    private void highlightItemAt(int adapterPos, int triesLeft) {
        if (mRecyclerView == null) return;
        RecyclerView.ViewHolder vh = mRecyclerView.findViewHolderForAdapterPosition(adapterPos);
        if (vh == null) {
            if (triesLeft > 0) {
                mRecyclerView.postDelayed(() -> highlightItemAt(adapterPos, triesLeft - 1), 100L);
            }
            return;
        }
        final View v = vh.itemView;
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(220L)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(220L).start())
                .start();
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new UserIllustRepo(userID, initialOffset);
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public void initView() {
        super.initView();
        baseBind.toolbar.inflateMenu(R.menu.local_save);
        // 「下载全部」单独一个 menu —— 不挂到 local_save 上,免得其他共用 local_save 的页面
        // (FragmentLikeIllust / FragmentRelatedIllust / FragmentMangaSeries 等) 跟着多出一个
        // 当前页面没意义的菜单项。和 FragmentCollection 的「下载全部作品」走同一条 BulkActions 通道。
        baseBind.toolbar.inflateMenu(R.menu.user_illust_actions);

        // 数量没拿到之前先藏起来,免得点了弹个「数量加载中」吓人;VM 里有就直接显出来,
        // 没有就 fetch 一次写进 VM —— 旋转 / 重建不重复拉。
        final MenuItem downloadAllItem = baseBind.toolbar.getMenu().findItem(R.id.action_download_all);
        if (downloadAllItem != null) downloadAllItem.setVisible(false);
        vm = new ViewModelProvider(this).get(FragmentUserIllustViewModel.class);
        vm.getTotalIllusts().observe(getViewLifecycleOwner(), total -> {
            if (downloadAllItem != null && total != null && total > 0) {
                downloadAllItem.setVisible(true);
            }
        });
        if (vm.getTotalIllusts().getValue() == null) {
            Retro.getAppApi().getUserDetail(userID)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<UserDetailResponse>() {
                        @Override
                        public void success(UserDetailResponse resp) {
                            if (resp.getProfile() == null) return;
                            vm.getTotalIllusts().setValue(resp.getProfile().getTotal_illusts());
                        }
                    });
        }

        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_download_all) {
                    Integer total = vm.getTotalIllusts().getValue();
                    if (total == null || total <= 0) return true; // 按钮该是藏着的,兜底
                    String authorName = "user";
                    if (allItems != null && !allItems.isEmpty()
                            && allItems.get(0).getUser() != null
                            && allItems.get(0).getUser().getName() != null) {
                        authorName = allItems.get(0).getUser().getName();
                    }
                    final String finalAuthorName = authorName;
                    final int finalTotal = total;
                    new QMUIDialog.MessageDialogBuilder(mContext)
                            .setTitle(R.string.bulk_user_menu_download_all_illust)
                            .setMessage(getString(
                                    R.string.bulk_user_download_all_illust_confirm,
                                    finalAuthorName, finalTotal))
                            .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
                            .addAction(0, getString(R.string.cancel),
                                    QMUIDialogAction.ACTION_PROP_NEUTRAL,
                                    (d, idx) -> d.dismiss())
                            .addAction(0, getString(android.R.string.ok),
                                    (d, idx) -> {
                                        d.dismiss();
                                        BulkActions.startAuthorWorksBulkDownload(
                                                requireActivity(), userID,
                                                WorkType.ILLUST, finalAuthorName);
                                    })
                            .show();
                    return true;
                }
                if (item.getItemId() == R.id.action_bookmark) {
                    FeatureEntity entity = new FeatureEntity();
                    entity.setUuid(userID + "插画作品");
                    entity.setShowToolbar(showToolbar);
                    entity.setDataType("插画作品");
                    entity.setIllustJson(Common.cutToJson(allItems));
                    entity.setUserID(userID);
                    entity.setDateTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).downloadDao().insertFeature(entity);
                    Common.showToast("已收藏到精华");
                    return true;
                }
                if (item.getItemId() == R.id.action_jump) {
                    UserIllustJumpHelper.showJumpDialog(
                            mActivity, userID, UserIllustJumpHelper.Kind.ILLUST,
                            (offset, pickedDate) -> {
                                if (isAdded() && !isStateSaved()) {
                                    getParentFragmentManager().beginTransaction()
                                            .replace(getId(), newInstance(userID, showToolbar, offset, pickedDate))
                                            .commit();
                                }
                            });
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        if (showToolbar) {
            return getString(R.string.string_246);
        } else {
            return super.getToolbarTitle();
        }
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}

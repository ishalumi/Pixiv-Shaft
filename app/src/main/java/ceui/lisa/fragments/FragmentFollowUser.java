package ceui.lisa.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.repo.FollowUserRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentFollowUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    /** Pixiv /v1/user/following 每页固定 30 条。 */
    public static final int PAGE_SIZE = 30;

    private int userID;
    private String starType;
    private boolean showToolbar = false;

    public static FragmentFollowUser newInstance(int userID, String starType, boolean pShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putString(Params.STAR_TYPE, starType);
        args.putBoolean(Params.FLAG, pShowToolbar);
        FragmentFollowUser fragment = new FragmentFollowUser();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        starType = bundle.getString(Params.STAR_TYPE);
        showToolbar = bundle.getBoolean(Params.FLAG);
    }

    @Override
    public RemoteRepo<ListUser> repository() {
        return new FollowUserRepo(userID, starType);
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_232);
    }

    /**
     * 独立 toolbar 场景（"正在关注"入口）也挂跳页菜单，
     * FragmentCollection 容器场景的菜单由容器自己挂。
     */
    @Override
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        if (showToolbar) {
            toolbar.getMenu().clear();
            toolbar.inflateMenu(R.menu.follow_user_jump);
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_jump_page) {
                    showJumpPageDialog(mActivity, this);
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * 让 toolbar 上的"跳页"动作能驱动这个 fragment 跳到任意 offset。
     * page 从 1 开始；offset = (page-1)*30 直接交给 Pixiv API。
     */
    public void jumpToPage(int page) {
        if (mModel == null || mRefreshLayout == null) return;
        BaseRepo base = mModel.getBaseRepo();
        if (!(base instanceof FollowUserRepo)) return;
        int safePage = Math.max(1, page);
        FollowUserRepo repo = (FollowUserRepo) base;
        repo.setStartOffset((safePage - 1) * PAGE_SIZE);
        // 清掉旧的 next_url，否则下拉加载更多会沿用上次的 offset 链
        repo.setNextUrl("");
        // autoRefresh -> OnRefreshListener -> clear() + fresh() -> initApi() with new offset
        mRefreshLayout.autoRefresh();
    }

    /**
     * 跳页流程：先拉 target.userID 的 user_detail 拿 total_follow_users，
     * 用它换算 totalPages 作为输入上限，再弹输入框。
     *
     * 取数说明：Pixiv user_detail 只回单个 total_follow_users，自己看自己时通常就是公开关注数；
     * 对私人关注 tab 它不一定准，但 Pixiv 也没暴露私人计数，把同一个值作为上限的"软约束"够用：
     * 越界会被预校验拦下、不会真发空请求。
     *
     * 抽成 static 是为了 FragmentCollection 容器场景复用同一份 UX。
     */
    public static void showJumpPageDialog(@NonNull Activity activity,
                                          @NonNull FragmentFollowUser target) {
        if (activity.isFinishing()) return;
        final QMUITipDialog loading = new QMUITipDialog.Builder(activity)
                .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
                .setTipWord(activity.getString(R.string.user_jump_loading))
                .create();
        loading.show();

        Retro.getAppApi().getUserDetail(target.userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserDetailResponse>() {
                    @Override
                    public void success(UserDetailResponse resp) {
                        if (isAlive(activity) && loading.isShowing()) loading.dismiss();
                        if (!isAlive(activity)) return;
                        int total = (resp.getProfile() == null) ? 0
                                : resp.getProfile().getTotal_follow_users();
                        if (total <= 0) {
                            Common.showToast(activity.getString(
                                    R.string.follow_user_jump_no_users));
                            return;
                        }
                        int totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
                        showPagePicker(activity, target, totalPages);
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        if (!isSuccess && loading.isShowing() && isAlive(activity)) {
                            loading.dismiss();
                        }
                    }
                });
    }

    private static void showPagePicker(@NonNull Activity activity,
                                       @NonNull FragmentFollowUser target,
                                       int totalPages) {
        if (!isAlive(activity)) return;
        final QMUIDialog.EditTextDialogBuilder builder =
                new QMUIDialog.EditTextDialogBuilder(activity);
        builder.setTitle(R.string.user_jump_page_dialog_title)
                .setPlaceholder(activity.getString(R.string.user_jump_page_hint, totalPages))
                .setInputType(InputType.TYPE_CLASS_NUMBER)
                .addAction(R.string.string_142,
                        (QMUIDialogAction.ActionListener) (dialog, index) -> dialog.dismiss())
                .addAction(R.string.sure,
                        (QMUIDialogAction.ActionListener) (dialog, index) -> {
                            CharSequence raw = builder.getEditText().getText();
                            Integer page = parsePositiveInt(raw);
                            if (page == null || page > totalPages) {
                                Common.showToast(activity.getString(
                                        R.string.user_jump_page_range_error, totalPages));
                                return;
                            }
                            dialog.dismiss();
                            target.jumpToPage(page);
                        })
                .show();
    }

    private static boolean isAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static Integer parsePositiveInt(CharSequence raw) {
        if (raw == null) return null;
        try {
            int v = Integer.parseInt(raw.toString().trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
